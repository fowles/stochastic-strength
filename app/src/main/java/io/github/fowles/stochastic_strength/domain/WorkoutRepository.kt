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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkoutRepository(
    private val db: AppDatabase,
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val heuristics: List<CoefficientHeuristic> = listOf(),
    private val normalizers: List<BaselineNormalizer> = listOf(),
) {
    // Task 11: mutex to prevent concurrent replay runs
    private val replayMutex = Mutex()

    private suspend fun excludedExerciseIds(locationId: Long?): Set<Long> =
        if (locationId != null) db.locationExcludedExerciseDao().getExcludedIds(locationId).toSet()
        else emptySet()

    private suspend fun effectiveCoefficientSource(): UserCoefficientSource {
        val latest = db.coefficientHistoryDao().getLatestPerExercise()
            .associate { it.exerciseId to it.coefficient }
        return UserCoefficientSource(latest, coefficientSource)
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
        val effectiveCoefficients = effectiveCoefficientSource()
        return WorkoutPlanner(
            availableExercises = available,
            strengths = strengths,
            recentHistory = history,
            weightUnit = weightUnit,
            locationId = locationId,
            coefficientSource = effectiveCoefficients,
            progressionEngine = progressionEngine,
        )
    }

    // Task 14: Refactored applySessionProgression — reads current baseline from snapshot, not DB.
    // Calls recomputeCoefficients(snapshot, asOf) and applyBaselineNormalization(snapshot, asOf, sessionId).
    // Does not mutate hurtFlag (moved to live recording, Phase 5 Task 18).
    suspend fun applySessionProgression(
        sessionId: Long,
        snapshot: ReplaySnapshot,
        asOf: Long,
        exerciseReductions: Map<Long, Float> = emptyMap(),
    ) {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        if (sets.isEmpty()) return

        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val exerciseById = db.exerciseDao().getByIds(exerciseIds).associateBy { it.id }

        // (hurtFlag side effect REMOVED — moved to live recording in Phase 5, Task 18.)

        val sessionReps = sets.firstOrNull { exerciseById[it.exerciseId]?.isTimed != true }?.targetReps ?: 5

        val effectiveReductions = exerciseReductions.takeIf { it.isNotEmpty() }
            ?: pendingReductions?.let { (id, r) -> if (id == sessionId) r else null }
            ?: emptyMap()

        val exercisesByMuscle = exerciseById.values
            .filter { (snapshot.currentCoefficients[it.id] ?: 0f) > 0f }
            .groupBy { it.primaryMuscle }
        val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
        for ((muscleGroup, muscleExercises) in exercisesByMuscle) {
            val allFeedbacks = muscleExercises.flatMap { exercise ->
                sets.filter { it.exerciseId == exercise.id }.mapNotNull { it.feedback }
            }
            if (allFeedbacks.isEmpty()) continue

            val current = snapshot.currentBaselines[muscleGroup] ?: continue
            val minReduction = muscleExercises.mapNotNull { effectiveReductions[it.id] }.maxOrNull() ?: 0f
            val newBaseline = progressionEngine.computeNextBaseline(current, allFeedbacks, minReduction, sessionReps)
            val roundedNewBaseline = WeightFormatter.round(newBaseline, weightUnit)
            db.muscleGroupStrengthDao().upsert(
                MuscleGroupStrength(muscleGroup = muscleGroup, baselineWeight = roundedNewBaseline)
            )
            snapshot.progressionBaselines[sessionId to muscleGroup] = current
            snapshot.currentBaselines[muscleGroup] = roundedNewBaseline
            db.baselineHistoryDao().insert(
                BaselineHistory(
                    sessionId = sessionId,
                    muscleGroup = muscleGroup,
                    previousBaseline = current,
                    newBaseline = roundedNewBaseline,
                    changeReason = BaselineChangeReason.PROGRESSION,
                    feedbacks = allFeedbacks.joinToString(",") { it.name },
                    sessionReps = sessionReps,
                    minReductionFraction = if (minReduction > 0f) minReduction else null,
                    timestamp = asOf,
                )
            )
        }
        recomputeCoefficients(snapshot, asOf)
        applyBaselineNormalization(snapshot, asOf, sessionId)
    }

    private suspend fun sessionTriggerTime(sessionId: Long, sets: List<WorkoutSet>): Long {
        sets.mapNotNull { it.completedAt }.maxOrNull()?.let { return it }
        val session = db.workoutSessionDao().getById(sessionId)
        return session?.endTime ?: session?.startTime ?: System.currentTimeMillis()
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

    // Task 12: Refactored recomputeCoefficients — snapshot-aware, no internal transaction.
    // Caller (replay) holds the wrapping transaction.
    internal suspend fun recomputeCoefficients(snapshot: ReplaySnapshot, asOf: Long) {
        if (heuristics.isEmpty()) return
        val input = snapshot.filteredCoefficientInput(asOf)
        val candidatesByExercise = mutableMapOf<Long, MutableList<Pair<String, CoefficientResult>>>()
        for (heuristic in heuristics) {
            for (result in heuristic.compute(input)) {
                candidatesByExercise.getOrPut(result.exerciseId) { mutableListOf() }
                    .add(heuristic.name to result)
            }
        }
        val latestByExercise = db.coefficientHistoryDao().getLatestPerExercise()
            .associateBy { it.exerciseId }
        for ((exerciseId, candidates) in candidatesByExercise) {
            val (winnerName, winner) = mergeHeuristicResults(candidates) ?: continue
            db.coefficientHistoryDao().insert(
                CoefficientHistory(
                    exerciseId = exerciseId,
                    previousCoefficient = latestByExercise[exerciseId]?.coefficient
                        ?: snapshot.seedCoefficients[exerciseId],
                    coefficient = winner.coefficient,
                    heuristicName = winnerName,
                    heuristicMetadata = winner.metadata,
                    computedAt = asOf,
                )
            )
            snapshot.currentCoefficients[exerciseId] = winner.coefficient
        }
    }

    // Task 13: Refactored applyBaselineNormalization — snapshot-aware, no internal transaction.
    // Caller (replay) holds the wrapping transaction.
    internal suspend fun applyBaselineNormalization(
        snapshot: ReplaySnapshot,
        asOf: Long,
        sessionId: Long,
    ) {
        if (normalizers.isEmpty()) return
        val input = snapshot.filteredNormalizationInput(asOf)
        val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
        val threshold = BaselineNormalizationThreshold.forUnit(weightUnit)

        val proposals = normalizers.flatMap { it.compute(input) }
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

            db.muscleGroupStrengthDao().upsert(
                MuscleGroupStrength(muscleGroup = proposal.muscleGroup, baselineWeight = newBaseline)
            )
            snapshot.currentBaselines[proposal.muscleGroup] = newBaseline
            db.baselineHistoryDao().insert(
                BaselineHistory(
                    sessionId = sessionId,
                    muscleGroup = proposal.muscleGroup,
                    previousBaseline = oldBaseline,
                    newBaseline = newBaseline,
                    changeReason = BaselineChangeReason.NORMALIZATION,
                    timestamp = asOf,
                )
            )

            val inGroup = input.exercises.filter {
                it.exercise.primaryMuscle == proposal.muscleGroup && it.currentCoefficient > 0f
            }
            for (snap in inGroup) {
                val newCoef = snap.currentCoefficient * mEffective
                db.coefficientHistoryDao().insert(
                    CoefficientHistory(
                        exerciseId = snap.exercise.id,
                        previousCoefficient = latestCoefByExercise[snap.exercise.id]?.coefficient
                            ?: snap.currentCoefficient,
                        coefficient = newCoef,
                        heuristicName = "baseline_normalization",
                        heuristicMetadata = proposal.metadata,
                        computedAt = asOf,
                    )
                )
                snapshot.currentCoefficients[snap.exercise.id] = newCoef
            }
        }
    }

    // Task 22: single-use stash so finishSession can route reductions to applySessionProgression
    // without changing the replay loop's call-site signature.
    private var pendingReductions: Pair<Long, Map<Long, Float>>? = null

    /**
     * Stashes [exerciseReductions] for the upcoming session, then triggers a full replay so that
     * [applySessionProgression] picks them up when it reaches [sessionId].
     */
    suspend fun finishSession(sessionId: Long, exerciseReductions: Map<Long, Float>) {
        pendingReductions = sessionId to exerciseReductions
        try {
            replayDerivedState()
        } finally {
            pendingReductions = null
        }
    }

    // Task 15: Top-level wipe-and-replay. Acquires replayMutex and wraps everything in one transaction.
    suspend fun replayDerivedState() = replayMutex.withLock {
        db.withTransaction {
            db.baselineHistoryDao().deleteAll()
            db.coefficientHistoryDao().deleteAll()
            db.muscleGroupStrengthDao().deleteAll()

            val snapshot = ReplaySnapshot.loadStaticFromDb(db, coefficientSource)
            val initials = db.baselineOverrideDao().getInitials()
            val overridesBySession = db.baselineOverrideDao().getNonInitials()
                .groupBy { it.sessionId!! }

            for (init in initials) {
                snapshot.currentBaselines[init.muscleGroup] = init.baselineWeight
                db.muscleGroupStrengthDao().upsert(
                    MuscleGroupStrength(muscleGroup = init.muscleGroup, baselineWeight = init.baselineWeight)
                )
                db.baselineHistoryDao().insert(
                    BaselineHistory(
                        sessionId = null,
                        muscleGroup = init.muscleGroup,
                        previousBaseline = 0f,
                        newBaseline = init.baselineWeight,
                        changeReason = BaselineChangeReason.INITIAL,
                        timestamp = init.asOf,
                    )
                )
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
                    db.baselineHistoryDao().insert(
                        BaselineHistory(
                            sessionId = session.id,
                            muscleGroup = o.muscleGroup,
                            previousBaseline = prev,
                            newBaseline = o.baselineWeight,
                            changeReason = BaselineChangeReason.OVERRIDE,
                            timestamp = o.asOf,
                        )
                    )
                }
                applySessionProgression(session.id, snapshot, asOf = session.endTime!!)
            }
        }
    }

    private fun mergeHeuristicResults(
        candidates: List<Pair<String, CoefficientResult>>,
    ): Pair<String, CoefficientResult>? = candidates.firstOrNull()

    suspend fun seedInitialWeights(sex: Sex, strengthLevel: StrengthLevel, weightUnit: WeightUnit) {
        db.userProfileDao().insert(UserProfile(sex = sex, strengthLevel = strengthLevel, weightUnit = weightUnit))
        val strengths = MuscleGroup.entries.mapNotNull { muscle ->
            val baseline = StartingWeights.baseline(sex, strengthLevel, muscle)
            if (baseline > 0f) MuscleGroupStrength(muscleGroup = muscle, baselineWeight = baseline) else null
        }
        db.muscleGroupStrengthDao().upsertAll(strengths)
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
                val seed = coefficientSource.get(exercise) ?: 0f
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
        coefficientSource.get(exercise)

}
