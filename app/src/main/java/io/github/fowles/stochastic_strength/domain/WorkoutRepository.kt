package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import kotlin.math.roundToInt

class WorkoutRepository(private val db: AppDatabase) {
    suspend fun generateWorkoutForLocation(locationId: Long?): WorkoutPlan {
        val availableEquipment = if (locationId != null) {
            db.locationEquipmentDao().getEquipmentForLocation(locationId).toSet() + Equipment.BODYWEIGHT
        } else {
            Equipment.entries.toSet()
        }

        val allExercises = db.exerciseDao().getActive()
        val exercises = allExercises.filter { it.equipment in availableEquipment }
        var statesMap = db.exerciseStateDao().getAll().associateBy { it.exerciseId }

        // If no weights are seeded but a profile exists, seed them now.
        // This handles the race where seedInitialWeights ran before exercises were inserted.
        if (statesMap.values.none { it.currentWeight > 0f }) {
            val profile = db.userProfileDao().getProfile()
            if (profile != null) {
                seedWeightsFromProfile(profile.sex, profile.strengthLevel, allExercises)
                statesMap = db.exerciseStateDao().getAll().associateBy { it.exerciseId }
            }
        }

        val planned = WorkoutGenerator.generate(
            WorkoutGenerator.Input(exercises = exercises, states = statesMap)
        ).map { pe ->
            if (pe.state.currentWeight > 0f) pe
            else {
                val est = WeightEstimator.estimate(pe.exercise, allExercises, statesMap)
                pe.copy(state = pe.state.copy(currentWeight = est))
            }
        }

        return WorkoutPlan(exercises = planned, locationId = locationId)
    }

    suspend fun pickReplacement(plan: WorkoutPlan, removedIndex: Int): PlannedExercise? {
        val remaining = plan.exercises.filterIndexed { i, _ -> i != removedIndex }
        val excludedIds = remaining.map { it.exercise.id }.toSet()

        val availableEquipment = if (plan.locationId != null) {
            db.locationEquipmentDao().getEquipmentForLocation(plan.locationId).toSet() + Equipment.BODYWEIGHT
        } else {
            Equipment.entries.toSet()
        }

        val allExercises = db.exerciseDao().getActive()
        val candidates = allExercises.filter { it.equipment in availableEquipment && it.id !in excludedIds }
        if (candidates.isEmpty()) return null

        val statesMap = db.exerciseStateDao().getAll().associateBy { it.exerciseId }
        val replacement = WorkoutGenerator.pickReplacement(
            input = WorkoutGenerator.Input(exercises = candidates, states = statesMap),
            currentExercises = remaining,
        ) ?: return null

        return if (replacement.state.currentWeight > 0f) replacement
        else {
            val est = WeightEstimator.estimate(replacement.exercise, allExercises, statesMap)
            replacement.copy(state = replacement.state.copy(currentWeight = est))
        }
    }

    suspend fun applySessionProgression(sessionId: Long) {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        val exerciseIds = sets.map { it.exerciseId }.distinct()

        for (exerciseId in exerciseIds) {
            val feedbacks = sets
                .filter { it.exerciseId == exerciseId }
                .mapNotNull { it.feedback }
            if (feedbacks.isEmpty()) continue

            val currentState = db.exerciseStateDao().getState(exerciseId)
                ?: ExerciseState(exerciseId = exerciseId)

            db.exerciseStateDao().upsert(
                ProgressionEngine.computeNextState(currentState, feedbacks)
            )

            if (SetFeedback.HURT in feedbacks) {
                db.exerciseDao().getById(exerciseId)?.let { exercise ->
                    db.exerciseDao().update(exercise.copy(hurtFlag = true))
                }
            }
        }
    }

    suspend fun seedInitialWeights(sex: Sex, strengthLevel: StrengthLevel) {
        db.userProfileDao().insert(UserProfile(sex = sex, strengthLevel = strengthLevel))
        seedWeightsFromProfile(sex, strengthLevel, db.exerciseDao().getActive())
    }

    private suspend fun seedWeightsFromProfile(sex: Sex, strengthLevel: StrengthLevel, exercises: List<Exercise>) {
        for (exercise in exercises) {
            val baseline = StartingWeights.baseline(sex, strengthLevel, exercise.primaryMuscle)
            val coeff = ExerciseCoefficients.byName[exercise.name] ?: 0f
            if (coeff <= 0f || baseline <= 0f) continue
            val weight = roundToPlate(baseline * coeff)
            db.exerciseStateDao().upsert(ExerciseState(exerciseId = exercise.id, currentWeight = weight))
        }
    }

    private fun roundToPlate(weight: Float): Float =
        (weight / 2.5f).roundToInt() * 2.5f
}
