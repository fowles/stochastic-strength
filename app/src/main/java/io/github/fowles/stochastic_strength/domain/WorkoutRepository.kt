package io.github.fowles.stochastic_strength.domain

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.derived.DerivedStateStore
import io.github.fowles.stochastic_strength.domain.derived.MutableDerivedState
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimate
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimateUpdater
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.ln

class WorkoutRepository(
    private val db: AppDatabase,
    val derivedState: DerivedStateStore = DerivedStateStore(),
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
) {
    private val replayMutex = Mutex()

    private suspend fun excludedExerciseIds(locationId: Long?): Set<Long> =
        if (locationId != null) db.locationExcludedExerciseDao().getExcludedIds(locationId).toSet()
        else emptySet()

    private fun effectiveCoefficientSource(): UserCoefficientSource {
        val latest = derivedState.snapshot().coefficientHistoryLatestPerExercise()
            .associate { it.exerciseId to it.coefficient }
        return UserCoefficientSource(latest)
    }

    suspend fun buildPlanner(
        locationId: Long?,
        weightUnit: WeightUnit,
        strengthOverrides: Map<MuscleGroup, Float> = emptyMap(),
    ): WorkoutPlanner {
        val excluded = excludedExerciseIds(locationId)
        val available = db.exerciseDao().getActive().filter { it.id !in excluded }
        val dbStrengths = derivedState.snapshot().allMuscleGroupStrengths().associateBy { it.muscleGroup }
        val strengths = if (strengthOverrides.isEmpty()) dbStrengths else
            dbStrengths + strengthOverrides.mapValues { (muscle, baseline) ->
                MuscleGroupStrength(muscleGroup = muscle, baselineWeight = baseline)
            }
        val history = if (available.isNotEmpty())
            db.workoutSetDao().getRecentSetsForExercises(available.map { it.id }, limit = 200)
                .groupBy { it.exerciseId }
        else emptyMap()
        val recentSessions = db.workoutSessionDao().getRecentCompletedSessions(limit = 50)
        val recentSets = if (recentSessions.isNotEmpty())
            db.workoutSetDao().getSetsForSessions(recentSessions.map { it.id })
                .groupBy { it.sessionId }
        else emptyMap()
        val effectiveCoefficients = effectiveCoefficientSource()
        val exercisesById = available.associateBy { it.id }
        val pacingEstimator = ExercisePacingEstimator.build(recentSessions, recentSets, exercisesById)
        return WorkoutPlanner(
            availableExercises = available,
            strengths = strengths,
            recentHistory = history,
            weightUnit = weightUnit,
            locationId = locationId,
            coefficientSource = effectiveCoefficients,
            progressionEngine = progressionEngine,
            pacingEstimator = pacingEstimator,
        )
    }

    private suspend fun applySessionProgression(
        sessionId: Long,
        snapshot: ReplaySnapshot,
        asOf: Long,
        scratch: MutableDerivedState,
    ) {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        if (sets.isEmpty()) return

        val updater = ExerciseEstimateUpdater()
        val projector = MuscleStrengthProjector()

        // HURT first (muscle-level): for any hurt muscle, hurt every loaded exercise estimate in it.
        val hurtMuscles = sets.filter { it.feedback == SetFeedback.HURT }
            .mapNotNull { snapshot.exerciseMuscle[it.exerciseId] }.toSet()
        for (m in hurtMuscles) {
            for (id in snapshot.muscleExerciseIds[m].orEmpty()) {
                snapshot.currentEstimates[id]?.let {
                    snapshot.currentEstimates[id] = updater.hurt(it, asOf)
                }
            }
        }

        // Per-exercise fold from the session aggregate.
        val affectedMuscles = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
            val agg = SessionSignalExtractor.aggregateSession(exSets) ?: return@forEach
            val prior = snapshot.currentEstimates[id] ?: return@forEach
            snapshot.currentEstimates[id] = updater.fold(prior, agg.est1RM, agg.bracketConfidence, asOf)
            snapshot.exerciseMuscle[id]?.let { affectedMuscles.add(it) }
        }
        // Also project muscles that had HURT updates.
        affectedMuscles.addAll(hurtMuscles)

        // Write display projections for affected muscles.
        for (m in affectedMuscles) {
            val exerciseIds = snapshot.muscleExerciseIds[m] ?: continue
            val projection = projector.project(
                estimates = snapshot.currentEstimates,
                seedCoef = snapshot.seedCoefficients,
                muscleExerciseIds = exerciseIds,
                now = asOf,
            )
            writeLevelUpdate(m, projection.level, sessionId, asOf, scratch)
            writeDerivedCoefficients(
                muscleExerciseIds = exerciseIds,
                derivedCoef = projection.derivedCoef,
                snapshot = snapshot,
                asOf = asOf,
                scratch = scratch,
            )
        }
    }

    private fun writeLevelUpdate(
        muscle: MuscleGroup,
        level: Float,
        sessionId: Long,
        asOf: Long,
        scratch: MutableDerivedState,
    ) {
        if (level <= 0f) return
        val current = scratch.muscleGroupStrength(muscle)?.baselineWeight
        scratch.upsertMuscleGroupStrength(MuscleGroupStrength(muscleGroup = muscle, baselineWeight = level))
        if (current == null || current != level) {
            scratch.insertBaselineHistory(
                BaselineHistory(
                    sessionId = sessionId,
                    muscleGroup = muscle,
                    previousBaseline = current ?: 0f,
                    newBaseline = level,
                    changeReason = BaselineChangeReason.PROGRESSION,
                    feedbacks = null,
                    sessionReps = null,
                    minReductionFraction = null,
                    timestamp = asOf,
                    heuristicName = "per-exercise-estimate",
                    heuristicMetadata = null,
                )
            )
        }
    }

    private fun writeDerivedCoefficients(
        muscleExerciseIds: List<Long>,
        derivedCoef: Map<Long, Float>,
        snapshot: ReplaySnapshot,
        asOf: Long,
        scratch: MutableDerivedState,
    ) {
        val latestByExercise = scratch.coefficientHistoryLatestPerExercise().associateBy { it.exerciseId }
        for (id in muscleExerciseIds) {
            val coef = derivedCoef[id] ?: continue
            val last = snapshot.lastWrittenCoef[id]
            // Epsilon-dedupe: only write when the coefficient changed materially.
            if (last != null && abs(coef - last) / last.coerceAtLeast(1e-6f) < 1e-4f) continue
            val row = CoefficientHistory(
                exerciseId = id,
                previousCoefficient = latestByExercise[id]?.coefficient
                    ?: snapshot.seedCoefficients[id],
                coefficient = coef,
                heuristicName = "per-exercise-estimate",
                heuristicMetadata = null,
                computedAt = asOf,
            )
            scratch.insertCoefficientHistory(row)
            snapshot.lastWrittenCoef[id] = coef
        }
    }

    suspend fun applyManualBaselineOverrides(sessionId: Long, overrides: Map<MuscleGroup, Float>) {
        if (overrides.isEmpty()) return
        val session = db.workoutSessionDao().getById(sessionId)
        val asOf = session?.startTime ?: System.currentTimeMillis()
        for ((muscleGroup, newBaseline) in overrides) {
            db.baselineOverrideDao().insert(
                BaselineOverride(
                    sessionId = sessionId,
                    muscleGroup = muscleGroup,
                    baselineWeight = newBaseline,
                    asOf = asOf,
                    reason = BaselineChangeReason.OVERRIDE,
                )
            )
        }
    }

    suspend fun applyDetrainingReduction(sessionId: Long, overrides: Map<MuscleGroup, Float>) {
        if (overrides.isEmpty()) return
        val session = db.workoutSessionDao().getById(sessionId)
        val asOf = session?.startTime ?: System.currentTimeMillis()
        for ((muscleGroup, newBaseline) in overrides) {
            db.baselineOverrideDao().insert(
                BaselineOverride(
                    sessionId = sessionId,
                    muscleGroup = muscleGroup,
                    baselineWeight = newBaseline,
                    asOf = asOf,
                    reason = BaselineChangeReason.DETRAIN,
                )
            )
        }
    }

    /**
     * Replays all sessions, applying [exerciseReductions] (sessionId → per-exercise reduction
     * fractions) for any matching session. Used by the workout-end path to thread the user's
     * mid-session weight reductions into the progression calculation.
     */
    suspend fun finishSession(sessionId: Long, exerciseReductions: Map<Long, Float>) {
        replayDerivedState(mapOf(sessionId to exerciseReductions))
    }

    suspend fun replayDerivedState(
        // reductionsBySession is retained for API compatibility; mid-set drops now flow through
        // the set log as negative innovations (the reduction clamp was dropped with the PI controller).
        reductionsBySession: Map<Long, Map<Long, Float>> = emptyMap(),
    ) = replayMutex.withLock {
        derivedState.rebuild { scratch ->
            val snapshot = ReplaySnapshot.loadStaticFromDb(db)
            val config = EstimatorConfig()

            // Init from per-exercise strength overrides (sessionId = null rows).
            val initials = db.exerciseStrengthOverrideDao().getInitials()
            for (init in initials) {
                snapshot.currentEstimates[init.exerciseId] = ExerciseEstimate.seed(init.e1rm, at = init.asOf)
            }

            // Group non-initial per-exercise overrides by sessionId.
            val exerciseOverridesBySession = db.exerciseStrengthOverrideDao().getNonInitials()
                .groupBy { it.sessionId!! }

            val sessions = db.workoutSessionDao().getAll()
                .filter { it.endTime != null }
                .sortedWith(compareBy({ it.endTime!! }, { it.id }))

            for (session in sessions) {
                // Apply per-exercise override rows for this session first (manual/detrain adjustments).
                exerciseOverridesBySession[session.id]?.forEach { o ->
                    snapshot.currentEstimates[o.exerciseId] = ExerciseEstimate(
                        lnE = ln(o.e1rm),
                        confidence = config.confidentThreshold,
                        updatedAt = o.asOf,
                    )
                }

                applySessionProgression(
                    sessionId = session.id,
                    snapshot = snapshot,
                    asOf = session.endTime!!,
                    scratch = scratch,
                )
            }

            // Store the final estimate map for the live planner (Task 8 reads it).
            scratch.putExerciseEstimates(snapshot.currentEstimates.toMap())
        }
    }

    suspend fun seedInitialWeights(sex: Sex, strengthLevel: StrengthLevel, weightUnit: WeightUnit) {
        db.userProfileDao().insert(UserProfile(sex = sex, strengthLevel = strengthLevel, weightUnit = weightUnit, perExerciseSeedsBackfilled = true))
        val exercises = db.exerciseDao().getAll()
        for (ex in exercises) {
            val e1rm = StartingWeights.seedInitialE1rm(sex, strengthLevel, ex)
            if (e1rm > 0f) {
                db.exerciseStrengthOverrideDao().deleteInitialFor(ex.id)
                db.exerciseStrengthOverrideDao().insert(
                    ExerciseStrengthOverride(sessionId = null, exerciseId = ex.id, e1rm = e1rm, asOf = 0L)
                )
            }
        }
        replayDerivedState()
    }

    // Locations
    suspend fun getLocations(): List<KnownLocation> = db.knownLocationDao().getAll()

    fun observeLocations(): Flow<List<KnownLocation>> = db.knownLocationDao().observeAll()

    suspend fun updateLocation(location: KnownLocation) = db.knownLocationDao().update(location)

    suspend fun deleteLocation(locationId: Long) = db.withTransaction {
        db.locationExcludedExerciseDao().deleteAllForLocation(locationId)
        db.knownLocationDao().deleteById(locationId)
    }

    suspend fun getExcludedExerciseIds(locationId: Long): Set<Long> =
        db.locationExcludedExerciseDao().getExcludedIds(locationId).toSet()

    suspend fun excludeExercise(locationId: Long, exerciseId: Long) =
        db.locationExcludedExerciseDao().insert(LocationExcludedExercise(locationId, exerciseId))

    suspend fun setExcludedExercises(locationId: Long, exerciseIds: Set<Long>) = db.withTransaction {
        db.locationExcludedExerciseDao().deleteAllForLocation(locationId)
        db.locationExcludedExerciseDao().insertAll(exerciseIds.map { LocationExcludedExercise(locationId, it) })
    }

    // Exercise library
    fun observeAllExercises(): Flow<List<Exercise>> = db.exerciseDao().observeAll()

    suspend fun getExerciseById(exerciseId: Long): Exercise? = db.exerciseDao().getById(exerciseId)

    suspend fun updateExercise(exercise: Exercise) = db.exerciseDao().update(exercise)

    suspend fun getAllSetsForExercise(exerciseId: Long): List<WorkoutSet> =
        db.workoutSetDao().getAllForExercise(exerciseId)

    // History
    suspend fun getAllSessions(): List<WorkoutSession> = db.workoutSessionDao().getAll()

    suspend fun deleteSession(sessionId: Long) = db.withTransaction {
        db.workoutSetDao().deleteAllForSession(sessionId)
        db.workoutSessionDao().deleteById(sessionId)
    }

    suspend fun getSessionExerciseNames(sessionId: Long): List<String> {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        val orderedIds = sets.map { it.exerciseId }.distinct()
        val nameById = db.exerciseDao().getByIds(orderedIds).associate { it.id to it.name }
        return orderedIds.mapNotNull { nameById[it] }
    }

    suspend fun getMuscleGroupStrengths(): List<MuscleGroupStrength> =
        derivedState.snapshot().allMuscleGroupStrengths()

    suspend fun getRecentCoefficientChanges(limit: Int = 2): List<CoefficientRow> {
        val rows = derivedState.snapshot().coefficientHistoryMostRecent(limit)
        if (rows.isEmpty()) return emptyList()
        val exerciseIds = rows.map { it.exerciseId }.distinct()
        val exercisesById = db.exerciseDao().getByIds(exerciseIds).associateBy { it.id }
        return rows.mapNotNull { log ->
            val exercise = exercisesById[log.exerciseId] ?: return@mapNotNull null
            CoefficientRow(
                exerciseId = exercise.id,
                exerciseName = exercise.name,
                currentCoefficient = log.coefficient,
                previousCoefficient = log.previousCoefficient,
                computedAt = log.computedAt,
                heuristicName = log.heuristicName,
                heuristicMetadataPreview = log.heuristicMetadata
                    ?.replace('\n', ' ')
                    ?.take(80),
            )
        }
    }

    suspend fun getAllCoefficientRows(): List<CoefficientRow> {
        val allExercises = db.exerciseDao().getAll()
        val latestByExercise = derivedState.snapshot().coefficientHistoryLatestPerExercise()
            .associateBy { it.exerciseId }
        return allExercises
            .map { exercise ->
                val log = latestByExercise[exercise.id]
                val seed = ExerciseCoefficients.get(exercise) ?: 0f
                CoefficientRow(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    currentCoefficient = log?.coefficient ?: seed,
                    previousCoefficient = null,
                    computedAt = log?.computedAt,
                    heuristicName = log?.heuristicName,
                    heuristicMetadataPreview = null,
                )
            }
            .sortedBy { it.exerciseName }
    }

    suspend fun getBaselineEvents(muscleGroup: MuscleGroup): List<BaselineHistory> =
        derivedState.snapshot().baselineHistoryForMuscle(muscleGroup)

    suspend fun getCoefficientEvents(exerciseId: Long): List<CoefficientHistory> =
        derivedState.snapshot().coefficientHistoryForExercise(exerciseId)

    suspend fun getLatestCoefficientPerExercise(): Map<Long, Float> =
        derivedState.snapshot().coefficientHistoryLatestPerExercise()
            .associate { it.exerciseId to it.coefficient }

    fun getSeedCoefficient(exercise: Exercise): Float? =
        ExerciseCoefficients.get(exercise)

}
