package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WarmupSet
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan

class WorkoutRepository(private val db: AppDatabase) {
    private suspend fun availableEquipment(locationId: Long?): Set<Equipment> =
        if (locationId != null) {
            db.locationEquipmentDao().getEquipmentForLocation(locationId).toSet() + Equipment.BODYWEIGHT
        } else {
            Equipment.entries.toSet()
        }

    suspend fun generateWorkoutForLocation(locationId: Long?, weightUnit: WeightUnit): WorkoutPlan {
        val availableEquipment = availableEquipment(locationId)

        val allExercises = db.exerciseDao().getActive()
        val exercises = allExercises.filter { it.equipment in availableEquipment }
        val strengths = db.muscleGroupStrengthDao().getAll().associateBy { it.muscleGroup }

        val sessionReps = ProgressionEngine.REP_OPTIONS.random()
        val planned = WorkoutGenerator.generate(
            WorkoutGenerator.Input(exercises = exercises)
        ).map { pe ->
            val weight = deriveWeight(pe.exercise, strengths, sessionReps, weightUnit)
            pe.copy(
                sessionWeight = weight,
                sessionReps = sessionReps,
                warmupSets = computeWarmupSets(weight, weightUnit),
            )
        }

        return WorkoutPlan(exercises = planned, locationId = locationId, sessionReps = sessionReps)
    }

    suspend fun pickAdditional(plan: WorkoutPlan, weightUnit: WeightUnit): PlannedExercise? {
        val excludedIds = plan.exercises.map { it.exercise.id }.toSet()
        val availableEquipment = availableEquipment(plan.locationId)
        val candidates = db.exerciseDao().getActive()
            .filter { it.equipment in availableEquipment && it.id !in excludedIds }
        if (candidates.isEmpty()) return null
        val picked = WorkoutGenerator.pickReplacement(
            input = WorkoutGenerator.Input(exercises = candidates),
            currentExercises = plan.exercises,
        ) ?: return null
        val strengths = db.muscleGroupStrengthDao().getAll().associateBy { it.muscleGroup }
        val weight = deriveWeight(picked.exercise, strengths, plan.sessionReps, weightUnit)
        return picked.copy(
            sessionWeight = weight,
            sessionReps = plan.sessionReps,
            warmupSets = computeWarmupSets(weight, weightUnit),
        )
    }

    suspend fun pickReplacement(plan: WorkoutPlan, removedIndex: Int, weightUnit: WeightUnit): PlannedExercise? {
        val remaining = plan.exercises.filterIndexed { i, _ -> i != removedIndex }
        val excludedIds = remaining.map { it.exercise.id }.toSet()
        val candidates = db.exerciseDao().getActive()
            .filter { it.equipment in availableEquipment(plan.locationId) && it.id !in excludedIds }
        if (candidates.isEmpty()) return null

        val replacement = WorkoutGenerator.pickReplacement(
            input = WorkoutGenerator.Input(exercises = candidates),
            currentExercises = remaining,
        ) ?: return null

        val strengths = db.muscleGroupStrengthDao().getAll().associateBy { it.muscleGroup }
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

        val exercisesByMuscle = exerciseById.values.groupBy { it.primaryMuscle }
        for ((muscleGroup, muscleExercises) in exercisesByMuscle) {
            val allFeedbacks = muscleExercises.flatMap { exercise ->
                sets.filter { it.exerciseId == exercise.id }.mapNotNull { it.feedback }
            }
            if (allFeedbacks.isEmpty()) continue

            val current = db.muscleGroupStrengthDao().get(muscleGroup) ?: continue
            val aggregated = ProgressionEngine.aggregateMuscleGroupFeedback(allFeedbacks)
            val newBaseline = ProgressionEngine.applyBaselineFeedback(current.baselineWeight, aggregated)
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
        fun w(pct: Float) = WeightFormatter.round(weightKg * pct, weightUnit)
        return if (weightKg < 60f) {
            listOf(WarmupSet(w(0.5f), 8), WarmupSet(w(0.75f), 5))
        } else {
            listOf(WarmupSet(w(0.4f), 8), WarmupSet(w(0.6f), 5), WarmupSet(w(0.8f), 3))
        }
    }
}
