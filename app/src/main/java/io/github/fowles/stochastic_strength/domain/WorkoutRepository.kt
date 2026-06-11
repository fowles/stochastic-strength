package io.github.fowles.stochastic_strength.domain

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeLog
import io.github.fowles.stochastic_strength.data.model.CoefficientChangeLog
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlinx.coroutines.flow.Flow

class WorkoutRepository(
    private val db: AppDatabase,
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val heuristics: List<CoefficientHeuristic> = listOf(),
) {
    private suspend fun excludedExerciseIds(locationId: Long?): Set<Long> =
        if (locationId != null) db.locationExcludedExerciseDao().getExcludedIds(locationId).toSet()
        else emptySet()

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
        val latestCoefficients = db.coefficientChangeLogDao().getLatestPerExercise()
            .associate { it.exerciseId to it.coefficient }
        val effectiveCoefficients = UserCoefficientSource(latestCoefficients, coefficientSource)
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

    suspend fun applySessionProgression(sessionId: Long, exerciseReductions: Map<Long, Float> = emptyMap()) {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val profile = db.userProfileDao().getProfile()
        val weightUnit = profile?.weightUnit ?: WeightUnit.KG

        val exerciseById = exerciseIds
            .mapNotNull { id -> db.exerciseDao().getById(id)?.let { id to it } }
            .toMap()

        for (exerciseId in exerciseIds) {
            val feedbacks = sets
                .filter { it.exerciseId == exerciseId }
                .mapNotNull { it.feedback }
            if (SetFeedback.HURT in feedbacks) {
                exerciseById[exerciseId]?.let { exercise ->
                    db.exerciseDao().update(exercise.copy(hurtFlag = true))
                }
            }
        }

        val sessionReps = sets.firstOrNull { exerciseById[it.exerciseId]?.isTimed != true }?.targetReps ?: 5

        val latestCoefficients = db.coefficientChangeLogDao().getLatestPerExercise()
            .associate { it.exerciseId to it.coefficient }
        val effectiveCoefficients = UserCoefficientSource(latestCoefficients, coefficientSource)
        val exercisesByMuscle = exerciseById.values
            .filter { (effectiveCoefficients.get(it) ?: 0f) > 0f }
            .groupBy { it.primaryMuscle }
        for ((muscleGroup, muscleExercises) in exercisesByMuscle) {
            val allFeedbacks = muscleExercises.flatMap { exercise ->
                sets.filter { it.exerciseId == exercise.id }.mapNotNull { it.feedback }
            }
            if (allFeedbacks.isEmpty()) continue

            val current = db.muscleGroupStrengthDao().get(muscleGroup) ?: continue
            val minReduction = muscleExercises.mapNotNull { exerciseReductions[it.id] }.maxOrNull() ?: 0f
            val newBaseline = progressionEngine.computeNextBaseline(current.baselineWeight, allFeedbacks, minReduction, sessionReps)
            val roundedNewBaseline = WeightFormatter.round(newBaseline, weightUnit)
            db.muscleGroupStrengthDao().upsert(current.copy(baselineWeight = roundedNewBaseline))
            db.baselineChangeLogDao().insert(
                BaselineChangeLog(
                    sessionId = sessionId,
                    muscleGroup = muscleGroup,
                    previousBaseline = current.baselineWeight,
                    newBaseline = roundedNewBaseline,
                    changeReason = BaselineChangeReason.PROGRESSION,
                    feedbacks = allFeedbacks.joinToString(",") { it.name },
                    sessionReps = sessionReps,
                    minReductionFraction = if (minReduction > 0f) minReduction else null,
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
        recomputeCoefficients()
    }

    suspend fun applyManualBaselineOverrides(sessionId: Long, overrides: Map<MuscleGroup, Float>) {
        for ((muscleGroup, newBaseline) in overrides) {
            val previous = db.muscleGroupStrengthDao().get(muscleGroup)?.baselineWeight ?: 0f
            db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(muscleGroup = muscleGroup, baselineWeight = newBaseline))
            db.baselineChangeLogDao().insert(
                BaselineChangeLog(
                    sessionId = sessionId,
                    muscleGroup = muscleGroup,
                    previousBaseline = previous,
                    newBaseline = newBaseline,
                    changeReason = BaselineChangeReason.MANUAL_OVERRIDE,
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
    }

    internal suspend fun buildCoefficientInput(): CoefficientComputationInput {
        // Use all exercises (including disliked) for history — we want full training data.
        // Use only active exercises for currentCoefficients — disliked exercises are excluded from planning.
        val allExercises = db.exerciseDao().getAll()
        val activeExercises = db.exerciseDao().getActive()
        val exerciseMuscle = allExercises.associate { it.id to it.primaryMuscle }
        val sessionTimeById = db.workoutSessionDao().getAll().associate { it.id to it.startTime }
        val progressionLogs = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.PROGRESSION }
            .associateBy { it.sessionId to it.muscleGroup }
        val snapshots = db.workoutSetDao().getAll()
            .groupBy { it.sessionId to it.exerciseId }
            .mapNotNull { (key, sets) ->
                val (sessionId, exerciseId) = key
                val muscle = exerciseMuscle[exerciseId] ?: return@mapNotNull null
                val logEntry = progressionLogs[sessionId to muscle] ?: return@mapNotNull null
                val sessionTime = sessionTimeById[sessionId] ?: return@mapNotNull null
                val targetReps = sets.firstOrNull()?.targetReps ?: return@mapNotNull null
                ExerciseSessionSnapshot(
                    exerciseId = exerciseId,
                    sessionId = sessionId,
                    sessionTime = sessionTime,
                    targetReps = targetReps,
                    muscleBaseline = logEntry.previousBaseline,
                    sets = sets.sortedBy { it.setNumber }
                        .map { SetSnapshot(it.targetWeight, it.feedback) },
                )
            }
            .sortedBy { it.sessionTime }
        val latestUserCoefficients = db.coefficientChangeLogDao().getLatestPerExercise()
            .associate { it.exerciseId to it.coefficient }
        val currentCoefficients = activeExercises.associate { exercise ->
            exercise.id to (latestUserCoefficients[exercise.id]
                ?: coefficientSource.get(exercise)
                ?: 0f)
        }
        return CoefficientComputationInput(
            history = snapshots,
            currentCoefficients = currentCoefficients,
        )
    }

    suspend fun recomputeCoefficients() {
        if (heuristics.isEmpty()) return
        val input = buildCoefficientInput()
        val candidatesByExercise = mutableMapOf<Long, MutableList<Pair<String, CoefficientResult>>>()
        for (heuristic in heuristics) {
            for (result in heuristic.compute(input)) {
                candidatesByExercise.getOrPut(result.exerciseId) { mutableListOf() }
                    .add(heuristic.name to result)
            }
        }
        val now = System.currentTimeMillis()
        db.withTransaction {
            val latestByExercise = db.coefficientChangeLogDao().getLatestPerExercise()
                .associateBy { it.exerciseId }
            for ((exerciseId, candidates) in candidatesByExercise) {
                val (winnerName, winner) = mergeHeuristicResults(candidates) ?: continue
                db.coefficientChangeLogDao().insert(
                    CoefficientChangeLog(
                        exerciseId = exerciseId,
                        previousCoefficient = latestByExercise[exerciseId]?.coefficient,
                        coefficient = winner.coefficient,
                        heuristicName = winnerName,
                        heuristicMetadata = winner.metadata,
                        computedAt = now,
                    )
                )
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
        return sets.map { it.exerciseId }.distinct()
            .mapNotNull { db.exerciseDao().getById(it)?.name }
    }

    suspend fun getMuscleGroupStrengths(): List<MuscleGroupStrength> =
        db.muscleGroupStrengthDao().getAll()

}
