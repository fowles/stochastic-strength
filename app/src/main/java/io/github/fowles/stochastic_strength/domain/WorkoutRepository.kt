package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan

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
        if (statesMap.values.none { it.currentWeight > 0f }) {
            val profile = db.userProfileDao().getProfile()
            if (profile != null) {
                seedWeightsFromProfile(profile.sex, profile.strengthLevel, allExercises, profile.weightUnit)
                statesMap = db.exerciseStateDao().getAll().associateBy { it.exerciseId }
            }
        }

        val sessionReps = ProgressionEngine.REP_OPTIONS.random()
        val planned = WorkoutGenerator.generate(
            WorkoutGenerator.Input(exercises = exercises, states = statesMap)
        ).map { pe ->
            val baseWeight = if (pe.state.currentWeight > 0f) {
                ProgressionEngine.scaleWeight(pe.state.currentWeight, pe.state.currentReps, sessionReps)
            } else {
                val est = WeightEstimator.estimate(pe.exercise, allExercises, statesMap)
                ProgressionEngine.scaleWeight(est, 10, sessionReps)
            }
            pe.copy(state = pe.state.copy(currentWeight = baseWeight, currentReps = sessionReps))
        }

        return WorkoutPlan(exercises = planned, locationId = locationId, sessionReps = sessionReps)
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

        val sessionReps = plan.sessionReps
        val baseWeight = if (replacement.state.currentWeight > 0f) {
            ProgressionEngine.scaleWeight(replacement.state.currentWeight, replacement.state.currentReps, sessionReps)
        } else {
            val est = WeightEstimator.estimate(replacement.exercise, allExercises, statesMap)
            ProgressionEngine.scaleWeight(est, 10, sessionReps)
        }
        return replacement.copy(state = replacement.state.copy(currentWeight = baseWeight, currentReps = sessionReps))
    }

    suspend fun applySessionProgression(sessionId: Long) {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val profile = db.userProfileDao().getProfile()
        val weightUnit = profile?.weightUnit ?: WeightUnit.KG

        for (exerciseId in exerciseIds) {
            val exerciseSets = sets.filter { it.exerciseId == exerciseId }
            val feedbacks = exerciseSets.mapNotNull { it.feedback }
            if (feedbacks.isEmpty()) continue

            val currentState = db.exerciseStateDao().getState(exerciseId)
                ?: ExerciseState(exerciseId = exerciseId)

            // Use the actual session weight/reps as the progression basis so stored state
            // always reflects what was done, regardless of which rep count was selected.
            val sessionReps = exerciseSets.first().targetReps
            val sessionWeight = exerciseSets.first().targetWeight
            val sessionState = currentState.copy(currentWeight = sessionWeight, currentReps = sessionReps)

            val nextState = ProgressionEngine.computeNextState(sessionState, feedbacks)
            val roundedWeight = WeightFormatter.round(nextState.currentWeight, weightUnit)
            db.exerciseStateDao().upsert(nextState.copy(currentWeight = roundedWeight))

            if (SetFeedback.HURT in feedbacks) {
                db.exerciseDao().getById(exerciseId)?.let { exercise ->
                    db.exerciseDao().update(exercise.copy(hurtFlag = true))
                }
            }
        }
    }

    suspend fun seedInitialWeights(sex: Sex, strengthLevel: StrengthLevel, weightUnit: WeightUnit) {
        db.userProfileDao().insert(UserProfile(sex = sex, strengthLevel = strengthLevel, weightUnit = weightUnit))
        seedWeightsFromProfile(sex, strengthLevel, db.exerciseDao().getActive(), weightUnit)
    }

    private suspend fun seedWeightsFromProfile(
        sex: Sex,
        strengthLevel: StrengthLevel,
        exercises: List<Exercise>,
        weightUnit: WeightUnit,
    ) {
        for (exercise in exercises) {
            val baseline = StartingWeights.baseline(sex, strengthLevel, exercise.primaryMuscle)
            val coeff = ExerciseCoefficients.byName[exercise.name] ?: 0f
            if (coeff <= 0f || baseline <= 0f) continue
            // Seeding is a form of recording data, so we round to the user's unit
            val weight = WeightFormatter.round(baseline * coeff, weightUnit)
            db.exerciseStateDao().upsert(ExerciseState(exerciseId = exercise.id, currentWeight = weight))
        }
    }
}
