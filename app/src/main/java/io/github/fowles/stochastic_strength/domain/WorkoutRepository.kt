package io.github.fowles.stochastic_strength.domain

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.derived.DerivedStateStore
import io.github.fowles.stochastic_strength.domain.derived.MutableDerivedState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkoutRepository(
    private val db: AppDatabase,
    val derivedState: DerivedStateStore = DerivedStateStore(),
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val progressionControllerFactory: () -> ProgressionController,
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
        controller: ProgressionController,
        scratch: MutableDerivedState,
    ) {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        if (sets.isEmpty()) return

        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val exerciseById = db.exerciseDao().getByIds(exerciseIds).associateBy { it.id }
        val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
        val sessionReps = sets.firstOrNull { exerciseById[it.exerciseId]?.isTimed != true }?.targetReps ?: 5
        val exerciseMuscle = snapshot.exerciseMuscle

        val observations = sets.groupBy { it.exerciseId }.mapNotNull { (id, exSets) ->
            val muscle = exerciseMuscle[id] ?: return@mapNotNull null
            if ((snapshot.currentCoefficients[id] ?: 0f) <= 0f) return@mapNotNull null
            SessionSignalExtractor.aggregateSession(exSets)?.let {
                ProgressionObservation(id, muscle, it.est1RM, it.sessionConfidence)
            }
        }
        val hurtMuscles = sets.filter { it.feedback == io.github.fowles.stochastic_strength.data.model.SetFeedback.HURT }
            .mapNotNull { exerciseMuscle[it.exerciseId] }.toSet()
        val muscleExercises = snapshot.currentCoefficients.filterValues { it > 0f }.keys
            .mapNotNull { id -> exerciseMuscle[id]?.let { it to id } }
            .groupBy({ it.first }, { it.second })

        val output = controller.step(
            ProgressionStepInput(
                now = asOf,
                observations = observations,
                baselines = snapshot.currentBaselines.toMap(),
                coefficients = snapshot.currentCoefficients.toMap(),
                muscleExercises = muscleExercises,
                hurtMuscles = hurtMuscles,
                weightUnit = weightUnit,
            ),
        )

        val setsByMuscle = sets.groupBy { exerciseMuscle[it.exerciseId] }
        for (update in output.baselineUpdates) {
            writeBaselineUpdate(update, sessionId, snapshot, sessionReps, setsByMuscle, asOf, controller.name, scratch)
        }
        writeCoefficientUpdates(output.coefficientUpdates, snapshot, asOf, controller.name, scratch)
    }

    private fun writeBaselineUpdate(
        update: BaselineUpdate,
        sessionId: Long,
        snapshot: ReplaySnapshot,
        sessionReps: Int,
        setsByMuscle: Map<MuscleGroup?, List<WorkoutSet>>,
        asOf: Long,
        heuristicName: String,
        scratch: MutableDerivedState,
    ) {
        val current = snapshot.currentBaselines[update.muscleGroup] ?: return
        val rounded = update.newBaseline
        if (rounded <= 0f || rounded == current) return
        scratch.upsertMuscleGroupStrength(
            MuscleGroupStrength(muscleGroup = update.muscleGroup, baselineWeight = rounded),
        )
        snapshot.progressionBaselines[sessionId to update.muscleGroup] = current
        snapshot.currentBaselines[update.muscleGroup] = rounded
        val muscleFeedbacks = setsByMuscle[update.muscleGroup].orEmpty().mapNotNull { it.feedback }
        val historyRow = BaselineHistory(
            sessionId = sessionId,
            muscleGroup = update.muscleGroup,
            previousBaseline = current,
            newBaseline = rounded,
            changeReason = BaselineChangeReason.PROGRESSION,
            feedbacks = muscleFeedbacks.joinToString(",") { it.name }.ifEmpty { null },
            sessionReps = sessionReps,
            minReductionFraction = null,
            timestamp = asOf,
            heuristicName = heuristicName,
            heuristicMetadata = update.metadata,
        )
        scratch.insertBaselineHistory(historyRow)
        snapshot.baselineHistoryByMuscle.getOrPut(update.muscleGroup) { mutableListOf() }.add(historyRow)
    }

    private fun writeCoefficientUpdates(
        updates: List<CoefficientUpdate>,
        snapshot: ReplaySnapshot,
        asOf: Long,
        heuristicName: String,
        scratch: MutableDerivedState,
    ) {
        if (updates.isEmpty()) return
        val latestByExercise = scratch.coefficientHistoryLatestPerExercise().associateBy { it.exerciseId }
        for (update in updates) {
            val row = CoefficientHistory(
                exerciseId = update.exerciseId,
                previousCoefficient = latestByExercise[update.exerciseId]?.coefficient
                    ?: snapshot.seedCoefficients[update.exerciseId],
                coefficient = update.coefficient,
                heuristicName = heuristicName,
                heuristicMetadata = update.metadata,
                computedAt = asOf,
            )
            scratch.insertCoefficientHistory(row)
            snapshot.currentCoefficients[update.exerciseId] = update.coefficient
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
            val controller = progressionControllerFactory()
            val initials = db.baselineOverrideDao().getInitials()
            val overridesBySession = db.baselineOverrideDao().getNonInitials()
                .groupBy { it.sessionId!! }

            for (init in initials) {
                snapshot.currentBaselines[init.muscleGroup] = init.baselineWeight
                scratch.upsertMuscleGroupStrength(
                    MuscleGroupStrength(muscleGroup = init.muscleGroup, baselineWeight = init.baselineWeight)
                )
                val row = BaselineHistory(
                    sessionId = null,
                    muscleGroup = init.muscleGroup,
                    previousBaseline = 0f,
                    newBaseline = init.baselineWeight,
                    changeReason = BaselineChangeReason.INITIAL,
                    timestamp = init.asOf,
                )
                scratch.insertBaselineHistory(row)
                snapshot.baselineHistoryByMuscle.getOrPut(init.muscleGroup) { mutableListOf() }.add(row)
            }

            val sessions = db.workoutSessionDao().getAll()
                .filter { it.endTime != null }
                .sortedWith(compareBy({ it.endTime!! }, { it.id }))

            for (session in sessions) {
                overridesBySession[session.id]?.forEach { o ->
                    val prev = snapshot.currentBaselines[o.muscleGroup] ?: 0f
                    snapshot.currentBaselines[o.muscleGroup] = o.baselineWeight
                    scratch.upsertMuscleGroupStrength(
                        MuscleGroupStrength(muscleGroup = o.muscleGroup, baselineWeight = o.baselineWeight)
                    )
                    val row = BaselineHistory(
                        sessionId = session.id,
                        muscleGroup = o.muscleGroup,
                        previousBaseline = prev,
                        newBaseline = o.baselineWeight,
                        changeReason = BaselineChangeReason.OVERRIDE,
                        timestamp = o.asOf,
                    )
                    scratch.insertBaselineHistory(row)
                    snapshot.baselineHistoryByMuscle.getOrPut(o.muscleGroup) { mutableListOf() }.add(row)
                }
                applySessionProgression(
                    session.id,
                    snapshot,
                    asOf = session.endTime!!,
                    controller = controller,
                    scratch = scratch,
                )
            }
        }
    }

    suspend fun seedInitialWeights(sex: Sex, strengthLevel: StrengthLevel, weightUnit: WeightUnit) {
        db.userProfileDao().insert(UserProfile(sex = sex, strengthLevel = strengthLevel, weightUnit = weightUnit))
        for (muscle in MuscleGroup.entries) {
            val baseline = StartingWeights.baseline(sex, strengthLevel, muscle)
            if (baseline > 0f) {
                db.baselineOverrideDao().deleteInitialFor(muscle)
                db.baselineOverrideDao().insert(
                    BaselineOverride(
                        sessionId = null,
                        muscleGroup = muscle,
                        baselineWeight = baseline,
                        asOf = 0L,
                    )
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
