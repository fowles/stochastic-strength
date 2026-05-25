package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import io.github.fowles.stochastic_strength.location.LocationService

class WorkoutRepository(
    private val db: AppDatabase,
    private val locationService: LocationService,
) {
    suspend fun generateWorkout(): WorkoutPlan {
        val locationId = locationService.findMatchingLocation(db)

        val availableEquipment = if (locationId != null) {
            db.locationEquipmentDao().getEquipmentForLocation(locationId).toSet()
        } else {
            Equipment.entries.toSet()
        }

        val exercises = db.exerciseDao().getActive()
            .filter { it.equipment in availableEquipment }

        val statesMap = db.exerciseStateDao().getAll().associateBy { it.exerciseId }

        val planned = WorkoutGenerator.generate(
            WorkoutGenerator.Input(exercises = exercises, states = statesMap)
        )

        return WorkoutPlan(exercises = planned, locationId = locationId)
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
}
