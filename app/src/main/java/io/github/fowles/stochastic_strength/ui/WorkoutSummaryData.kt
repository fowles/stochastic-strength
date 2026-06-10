package io.github.fowles.stochastic_strength.ui

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

data class SummarySet(val setNumber: Int, val targetWeight: Float, val targetReps: Int, val feedback: SetFeedback?, val isTimed: Boolean = false)

fun WorkoutSet.toSummarySet(isTimed: Boolean) = SummarySet(setNumber, targetWeight, targetReps, feedback, isTimed)

data class SummaryExercise(val name: String, val exerciseId: Long, val sets: List<SummarySet>)

data class WorkoutSummaryData(
    val startTime: Long,
    val durationSeconds: Long,
    val exercises: List<SummaryExercise>,
    val weightUnit: WeightUnit,
)

suspend fun loadWorkoutSummary(db: AppDatabase, sessionId: Long): WorkoutSummaryData {
    val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
    val session = db.workoutSessionDao().getById(sessionId)
    val sets = db.workoutSetDao().getSetsForSession(sessionId)
    val exerciseIds = sets.map { it.exerciseId }.distinct()
    val exerciseById = exerciseIds
        .mapNotNull { id -> db.exerciseDao().getById(id)?.let { id to it } }
        .toMap()
    val setsByExercise = sets.groupBy { it.exerciseId }
    val exercises = exerciseIds.map { id ->
        val exercise = exerciseById[id]
        SummaryExercise(
            name = exercise?.name ?: "Unknown",
            exerciseId = id,
            sets = (setsByExercise[id] ?: emptyList()).sortedBy { it.setNumber }
                .map { it.toSummarySet(exercise?.isTimed ?: false) },
        )
    }
    val duration = if (session != null && session.endTime != null) {
        (session.endTime - session.startTime) / 1000
    } else 0L
    return WorkoutSummaryData(
        startTime = session?.startTime ?: 0L,
        durationSeconds = duration,
        exercises = exercises,
        weightUnit = weightUnit,
    )
}
