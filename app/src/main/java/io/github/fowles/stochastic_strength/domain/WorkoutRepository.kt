package io.github.fowles.stochastic_strength.domain

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeLog
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
        return WorkoutPlanner(
            availableExercises = available,
            strengths = strengths,
            recentHistory = history,
            weightUnit = weightUnit,
            locationId = locationId,
            coefficientSource = coefficientSource,
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

        val exercisesByMuscle = exerciseById.values
            .filter { (coefficientSource.get(it) ?: 0f) > 0f }
            .groupBy { it.primaryMuscle }
        for ((muscleGroup, muscleExercises) in exercisesByMuscle) {
            val allFeedbacks = muscleExercises.flatMap { exercise ->
                sets.filter { it.exerciseId == exercise.id }.mapNotNull { it.feedback }
            }
            if (allFeedbacks.isEmpty()) continue

            val current = db.muscleGroupStrengthDao().get(muscleGroup) ?: continue
            val minReduction = muscleExercises.mapNotNull { exerciseReductions[it.id] }.maxOrNull() ?: 0f
            val newBaseline = DefaultProgressionEngine.computeNextBaseline(current.baselineWeight, allFeedbacks, minReduction, sessionReps)
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
