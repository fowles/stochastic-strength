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
    private val heuristic: CoefficientHeuristic? = null,
    private val normalizer: BaselineNormalizer? = null,
    private val baselineHeuristic: BaselineHeuristic,
) {
    private val replayMutex = Mutex()

    private suspend fun excludedExerciseIds(locationId: Long?): Set<Long> =
        if (locationId != null) db.locationExcludedExerciseDao().getExcludedIds(locationId).toSet()
        else emptySet()

    private suspend fun effectiveCoefficientSource(): UserCoefficientSource {
        val latest = db.coefficientHistoryDao().getLatestPerExercise()
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
        val dbStrengths = db.muscleGroupStrengthDao().getAll().associateBy { it.muscleGroup }
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
        val durationEstimator = ExerciseDurationEstimator.build(recentSessions, recentSets)
        val effectiveCoefficients = effectiveCoefficientSource()
        return WorkoutPlanner(
            availableExercises = available,
            strengths = strengths,
            recentHistory = history,
            weightUnit = weightUnit,
            locationId = locationId,
            coefficientSource = effectiveCoefficients,
            progressionEngine = progressionEngine,
            durationEstimator = durationEstimator,
        )
    }

    private suspend fun applySessionProgression(
        sessionId: Long,
        snapshot: ReplaySnapshot,
        asOf: Long,
        exerciseReductions: Map<Long, Float>,
        scratch: MutableDerivedState,
    ) {
        val input = buildBaselineComputationInput(sessionId, snapshot, asOf, exerciseReductions)
            ?: return
        val setsByMuscle = input.sets.groupBy { input.exerciseMuscle[it.exerciseId] }
        for (proposal in baselineHeuristic.compute(input)) {
            applyBaselineProposal(
                proposal = proposal,
                sessionId = sessionId,
                snapshot = snapshot,
                weightUnit = input.weightUnit,
                sessionReps = input.sessionReps,
                minReductionsByMuscle = input.minReductionFractions,
                setsByMuscle = setsByMuscle,
                asOf = asOf,
                scratch = scratch,
            )
        }
        recomputeCoefficients(snapshot, asOf, scratch)
        applyBaselineNormalization(snapshot, asOf, sessionId, scratch)
    }

    private suspend fun buildBaselineComputationInput(
        sessionId: Long,
        snapshot: ReplaySnapshot,
        asOf: Long,
        exerciseReductions: Map<Long, Float>,
    ): BaselineComputationInput? {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        if (sets.isEmpty()) return null

        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val exerciseById = db.exerciseDao().getByIds(exerciseIds).associateBy { it.id }
        val sessionReps = sets.firstOrNull { exerciseById[it.exerciseId]?.isTimed != true }?.targetReps ?: 5
        val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG

        val minReductionsByMuscle: Map<MuscleGroup, Float> =
            exerciseById.values.groupBy { it.primaryMuscle }
                .mapValues { (_, exs) ->
                    exs.mapNotNull { exerciseReductions[it.id] }.maxOrNull() ?: 0f
                }
                .filterValues { it > 0f }

        return BaselineComputationInput(
            sets = sets,
            exerciseMuscle = exerciseById.mapValues { it.value.primaryMuscle },
            currentCoefficients = snapshot.currentCoefficients.toMap(),
            currentBaselines = snapshot.currentBaselines.toMap(),
            recentHistory = snapshot.baselineHistoryByMuscle.mapValues { it.value.toList() },
            sessionReps = sessionReps,
            minReductionFractions = minReductionsByMuscle,
            asOf = asOf,
            weightUnit = weightUnit,
        )
    }

    private suspend fun applyBaselineProposal(
        proposal: BaselineProposal,
        sessionId: Long,
        snapshot: ReplaySnapshot,
        weightUnit: WeightUnit,
        sessionReps: Int,
        minReductionsByMuscle: Map<MuscleGroup, Float>,
        setsByMuscle: Map<MuscleGroup?, List<WorkoutSet>>,
        asOf: Long,
        scratch: MutableDerivedState,
    ) {
        val current = snapshot.currentBaselines[proposal.muscleGroup] ?: return
        val rounded = WeightFormatter.round(proposal.newBaseline, weightUnit)
        val strength = MuscleGroupStrength(muscleGroup = proposal.muscleGroup, baselineWeight = rounded)
        db.muscleGroupStrengthDao().upsert(strength)
        scratch.upsertMuscleGroupStrength(strength)
        snapshot.progressionBaselines[sessionId to proposal.muscleGroup] = current
        snapshot.currentBaselines[proposal.muscleGroup] = rounded
        val muscleFeedbacks = setsByMuscle[proposal.muscleGroup].orEmpty()
            .mapNotNull { it.feedback }
        val historyRow = BaselineHistory(
            sessionId = sessionId,
            muscleGroup = proposal.muscleGroup,
            previousBaseline = current,
            newBaseline = rounded,
            changeReason = BaselineChangeReason.PROGRESSION,
            feedbacks = muscleFeedbacks.joinToString(",") { it.name }.ifEmpty { null },
            sessionReps = sessionReps,
            minReductionFraction = minReductionsByMuscle[proposal.muscleGroup],
            timestamp = asOf,
            heuristicName = baselineHeuristic.name,
            heuristicMetadata = proposal.metadata,
        )
        db.baselineHistoryDao().insert(historyRow)
        scratch.insertBaselineHistory(historyRow)
        snapshot.baselineHistoryByMuscle.getOrPut(proposal.muscleGroup) { mutableListOf() }
            .add(historyRow)
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

    private suspend fun recomputeCoefficients(
        snapshot: ReplaySnapshot,
        asOf: Long,
        scratch: MutableDerivedState,
    ) {
        val heuristic = heuristic ?: return
        val results = heuristic.compute(snapshot.filteredCoefficientInput(asOf))
        if (results.isEmpty()) return
        val latestByExercise = db.coefficientHistoryDao().getLatestPerExercise()
            .associateBy { it.exerciseId }
        for (result in results) {
            val row = CoefficientHistory(
                exerciseId = result.exerciseId,
                previousCoefficient = latestByExercise[result.exerciseId]?.coefficient
                    ?: snapshot.seedCoefficients[result.exerciseId],
                coefficient = result.coefficient,
                heuristicName = heuristic.name,
                heuristicMetadata = result.metadata,
                computedAt = asOf,
            )
            db.coefficientHistoryDao().insert(row)
            scratch.insertCoefficientHistory(row)
            snapshot.currentCoefficients[result.exerciseId] = result.coefficient
        }
    }

    private suspend fun applyBaselineNormalization(
        snapshot: ReplaySnapshot,
        asOf: Long,
        sessionId: Long,
        scratch: MutableDerivedState,
    ) {
        val normalizer = normalizer ?: return
        val input = snapshot.filteredNormalizationInput(asOf)
        val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
        val threshold = BaselineNormalizationThreshold.forUnit(weightUnit)

        val proposals = normalizer.compute(input)
        if (proposals.isEmpty()) return

        val latestCoefByExercise = db.coefficientHistoryDao().getLatestPerExercise()
            .associateBy { it.exerciseId }
        for (proposal in proposals) {
            val oldBaseline = snapshot.currentBaselines[proposal.muscleGroup] ?: continue
            if (oldBaseline <= 0f || proposal.scale <= 0f) continue
            val rawNew = oldBaseline / proposal.scale
            val newBaseline = WeightFormatter.round(rawNew, weightUnit)
            if (kotlin.math.abs(newBaseline - oldBaseline) < threshold) continue
            if (newBaseline <= 0f) continue
            val mEffective = oldBaseline / newBaseline

            val newStrength = MuscleGroupStrength(muscleGroup = proposal.muscleGroup, baselineWeight = newBaseline)
            db.muscleGroupStrengthDao().upsert(newStrength)
            scratch.upsertMuscleGroupStrength(newStrength)
            snapshot.currentBaselines[proposal.muscleGroup] = newBaseline
            val row = BaselineHistory(
                sessionId = sessionId,
                muscleGroup = proposal.muscleGroup,
                previousBaseline = oldBaseline,
                newBaseline = newBaseline,
                changeReason = BaselineChangeReason.NORMALIZATION,
                timestamp = asOf,
            )
            db.baselineHistoryDao().insert(row)
            scratch.insertBaselineHistory(row)
            snapshot.baselineHistoryByMuscle.getOrPut(proposal.muscleGroup) { mutableListOf() }.add(row)

            val inGroup = input.exercises.filter {
                it.exercise.primaryMuscle == proposal.muscleGroup && it.currentCoefficient > 0f
            }
            for (snap in inGroup) {
                val newCoef = snap.currentCoefficient * mEffective
                val coefRow = CoefficientHistory(
                    exerciseId = snap.exercise.id,
                    previousCoefficient = latestCoefByExercise[snap.exercise.id]?.coefficient
                        ?: snap.currentCoefficient,
                    coefficient = newCoef,
                    heuristicName = "baseline_normalization",
                    heuristicMetadata = proposal.metadata,
                    computedAt = asOf,
                )
                db.coefficientHistoryDao().insert(coefRow)
                scratch.insertCoefficientHistory(coefRow)
                snapshot.currentCoefficients[snap.exercise.id] = newCoef
            }
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
        reductionsBySession: Map<Long, Map<Long, Float>> = emptyMap(),
    ) = replayMutex.withLock {
        db.withTransaction {
            db.baselineHistoryDao().deleteAll()
            db.coefficientHistoryDao().deleteAll()
            db.muscleGroupStrengthDao().deleteAll()

            derivedState.rebuild { scratch ->
                val snapshot = ReplaySnapshot.loadStaticFromDb(db)
                val initials = db.baselineOverrideDao().getInitials()
                val overridesBySession = db.baselineOverrideDao().getNonInitials()
                    .groupBy { it.sessionId!! }

                for (init in initials) {
                    snapshot.currentBaselines[init.muscleGroup] = init.baselineWeight
                    db.muscleGroupStrengthDao().upsert(
                        MuscleGroupStrength(muscleGroup = init.muscleGroup, baselineWeight = init.baselineWeight)
                    )
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
                    db.baselineHistoryDao().insert(row)
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
                        db.muscleGroupStrengthDao().upsert(
                            MuscleGroupStrength(muscleGroup = o.muscleGroup, baselineWeight = o.baselineWeight)
                        )
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
                        db.baselineHistoryDao().insert(row)
                        scratch.insertBaselineHistory(row)
                        snapshot.baselineHistoryByMuscle.getOrPut(o.muscleGroup) { mutableListOf() }.add(row)
                    }
                    applySessionProgression(
                        session.id,
                        snapshot,
                        asOf = session.endTime!!,
                        exerciseReductions = reductionsBySession[session.id] ?: emptyMap(),
                        scratch = scratch,
                    )
                }
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
        db.muscleGroupStrengthDao().getAll()

    suspend fun getRecentCoefficientChanges(limit: Int = 2): List<CoefficientRow> {
        val rows = db.coefficientHistoryDao().getMostRecent(limit)
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
        val latestByExercise = db.coefficientHistoryDao().getLatestPerExercise()
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
        db.baselineHistoryDao().getForMuscle(muscleGroup)

    suspend fun getCoefficientEvents(exerciseId: Long): List<CoefficientHistory> =
        db.coefficientHistoryDao().getForExercise(exerciseId)

    fun getSeedCoefficient(exercise: Exercise): Float? =
        ExerciseCoefficients.get(exercise)

}
