package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import kotlin.math.abs

/**
 * Inverts DefaultProgressionEngine.scaleReps for the backfill.
 *
 * Given a pre-failure weight (`from`), the post-reduction weight (`to`) observed on the next
 * same-exercise set, and the session's target reps, returns the completed-rep count whose
 * scaleReps projection rounds to within 0.5 kg of `to`. Iterates candidates high-to-low so
 * rounding ties resolve toward the more conservative interpretation (higher completed reps).
 *
 * Returns null when no candidate matches — leave `actualReps` null in that case.
 */
class ActualRepsBackfill(
    private val database: AppDatabase,
    private val weightUnit: WeightUnit,
) {
    suspend fun run() {
        val sessions = database.workoutSessionDao().getAll()
        for (session in sessions) {
            val sets = database.workoutSetDao().getSetsForSession(session.id)
            val bySetKey = sets.associateBy { it.exerciseId to it.setNumber }
            for (s in sets) {
                if (s.actualReps != null) continue
                val newReps: Int? = when (s.feedback) {
                    null, SetFeedback.HURT -> null
                    SetFeedback.RIR_0_1, SetFeedback.RIR_2_4, SetFeedback.RIR_5_PLUS ->
                        s.targetReps
                    SetFeedback.TOO_HARD -> {
                        val next = bySetKey[s.exerciseId to (s.setNumber + 1)]
                        if (next != null && next.targetWeight < s.targetWeight) {
                            inferReps(s.targetWeight, next.targetWeight, s.targetReps, weightUnit)
                        } else null
                    }
                }
                if (newReps != null) {
                    database.workoutSetDao().updateActualReps(s.id, newReps)
                }
            }
        }
    }
}

internal fun inferReps(from: Float, to: Float, targetReps: Int, weightUnit: WeightUnit): Int? {
    for (candidate in (targetReps - 1) downTo 0) {
        val predicted = DefaultProgressionEngine.scaleReps(
            from,
            from = maxOf(1, candidate),
            to = targetReps,
        )
        val rounded = WeightFormatter.round(predicted, weightUnit)
        if (abs(rounded - to) <= 0.5f) return candidate
    }
    return null
}
