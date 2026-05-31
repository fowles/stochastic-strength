package io.github.fowles.stochastic_strength.domain

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
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
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WarmupSet
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import kotlinx.coroutines.flow.Flow

class WorkoutRepository(private val db: AppDatabase) {
    private suspend fun excludedExerciseIds(locationId: Long?): Set<Long> =
        if (locationId != null) db.locationExcludedExerciseDao().getExcludedIds(locationId).toSet()
        else emptySet()

    suspend fun generateWorkoutForLocation(locationId: Long?, weightUnit: WeightUnit): WorkoutPlan {
        val excluded = excludedExerciseIds(locationId)
        val allExercises = db.exerciseDao().getActive()
        val exercises = allExercises.filter { it.id !in excluded }
        val strengths = db.muscleGroupStrengthDao().getAll().associateBy { it.muscleGroup }

        val sessionReps = ProgressionEngine.REP_OPTIONS.random()
        val planned = WorkoutGenerator.generate(
            WorkoutGenerator.Input(exercises = exercises)
        ).map { pe ->
            if (pe.exercise.isTimed) pe.copy(sessionWeight = 0f, sessionReps = 60, warmupSets = emptyList())
            else {
                val weight = deriveWeight(pe.exercise, strengths, sessionReps, weightUnit)
                pe.copy(sessionWeight = weight, sessionReps = sessionReps, warmupSets = computeWarmupSets(weight, weightUnit))
            }
        }

        return WorkoutPlan(exercises = planned, locationId = locationId, sessionReps = sessionReps)
    }

    suspend fun pickAdditional(plan: WorkoutPlan, weightUnit: WeightUnit): PlannedExercise? {
        val inPlan = plan.exercises.map { it.exercise.id }.toSet()
        val excluded = excludedExerciseIds(plan.locationId) + plan.sessionRejectedIds
        val candidates = db.exerciseDao().getActive()
            .filter { it.id !in inPlan && it.id !in excluded }
        if (candidates.isEmpty()) return null
        val picked = WorkoutGenerator.pickReplacement(
            input = WorkoutGenerator.Input(exercises = candidates),
            currentExercises = plan.exercises,
        ) ?: return null
        val strengths = db.muscleGroupStrengthDao().getAll().associateBy { it.muscleGroup }
        if (picked.exercise.isTimed) return picked.copy(sessionWeight = 0f, sessionReps = 60, warmupSets = emptyList())
        val weight = deriveWeight(picked.exercise, strengths, plan.sessionReps, weightUnit)
        return picked.copy(
            sessionWeight = weight,
            sessionReps = plan.sessionReps,
            warmupSets = computeWarmupSets(weight, weightUnit),
        )
    }

    suspend fun pickReplacement(plan: WorkoutPlan, removedIndex: Int, weightUnit: WeightUnit): PlannedExercise? {
        val remaining = plan.exercises.filterIndexed { i, _ -> i != removedIndex }
        val inPlan = remaining.map { it.exercise.id }.toSet()
        val excluded = excludedExerciseIds(plan.locationId) + plan.sessionRejectedIds
        val candidates = db.exerciseDao().getActive()
            .filter { it.id !in inPlan && it.id !in excluded }
        if (candidates.isEmpty()) return null

        val replacement = WorkoutGenerator.pickReplacement(
            input = WorkoutGenerator.Input(exercises = candidates),
            currentExercises = remaining,
        ) ?: return null

        val strengths = db.muscleGroupStrengthDao().getAll().associateBy { it.muscleGroup }
        if (replacement.exercise.isTimed) return replacement.copy(sessionWeight = 0f, sessionReps = 60, warmupSets = emptyList())
        val weight = deriveWeight(replacement.exercise, strengths, plan.sessionReps, weightUnit)
        return replacement.copy(
            sessionWeight = weight,
            sessionReps = plan.sessionReps,
            warmupSets = computeWarmupSets(weight, weightUnit),
        )
    }

    suspend fun applySessionProgression(sessionId: Long) {
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

        val exercisesByMuscle = exerciseById.values
            .filter { (ExerciseCoefficients.byName[it.name] ?: 0f) > 0f }
            .groupBy { it.primaryMuscle }
        for ((muscleGroup, muscleExercises) in exercisesByMuscle) {
            val allFeedbacks = muscleExercises.flatMap { exercise ->
                sets.filter { it.exerciseId == exercise.id }.mapNotNull { it.feedback }
            }
            if (allFeedbacks.isEmpty()) continue

            val current = db.muscleGroupStrengthDao().get(muscleGroup) ?: continue
            val newBaseline = ProgressionEngine.computeNextBaseline(current.baselineWeight, allFeedbacks)
            db.muscleGroupStrengthDao().upsert(
                current.copy(baselineWeight = WeightFormatter.round(newBaseline, weightUnit))
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

    private fun deriveWeight(
        exercise: Exercise,
        strengths: Map<MuscleGroup, MuscleGroupStrength>,
        sessionReps: Int,
        weightUnit: WeightUnit,
    ): Float {
        val coeff = ExerciseCoefficients.byName[exercise.name] ?: return 0f
        if (coeff <= 0f) return 0f
        val baseline = strengths[exercise.primaryMuscle]?.baselineWeight ?: return 0f
        return WeightFormatter.round(
            ProgressionEngine.scaleWeight(baseline * coeff, fromReps = 10, toReps = sessionReps),
            weightUnit,
        )
    }

    private fun computeWarmupSets(weightKg: Float, weightUnit: WeightUnit): List<WarmupSet> {
        if (weightKg < 40f) return emptyList()
        fun w(pct: Float) = WeightFormatter.roundForWarmup(weightKg * pct, weightUnit)
        return if (weightKg < 60f) {
            listOf(WarmupSet(w(0.5f), 8), WarmupSet(w(0.75f), 5))
        } else {
            listOf(WarmupSet(w(0.4f), 8), WarmupSet(w(0.6f), 5), WarmupSet(w(0.8f), 3))
        }
    }
}
