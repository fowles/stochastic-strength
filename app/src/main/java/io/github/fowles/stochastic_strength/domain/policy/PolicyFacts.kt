package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * The cap demonstrated by an exercise's most recent feedback session (and only that session —
 * newer sessions supersede older ones entirely). [capLn] null = that session uncapped.
 */
data class ExerciseCapFact(val capLn: Float?, val demonstratedAt: Long)

/**
 * Raw set-log facts the policy layer needs at prescription time (constitution rule 6: plain
 * restatements of the log — no estimator state, rebuilt from sets alone). Sessions are identified
 * by sessionId; session time is the max completedAt of its sets. Sets without completedAt
 * (in-progress) are ignored.
 */
data class PolicyFacts(
    val capByExercise: Map<Long, ExerciseCapFact> = emptyMap(),
    val hurtEventsByMuscle: Map<MuscleGroup, List<Long>> = emptyMap(),
) {
    companion object {
        val EMPTY = PolicyFacts()

        fun build(sets: List<WorkoutSet>, exerciseMuscle: Map<Long, MuscleGroup>): PolicyFacts {
            val completed = sets.filter { it.completedAt != null }

            val capByExercise = completed.groupBy { it.exerciseId }
                .mapNotNull { (exerciseId, exSets) ->
                    // Only sessions with scoreable (non-HURT) feedback demonstrate capacity;
                    // a HURT-only or feedback-less session never supersedes an older cap.
                    val sessions = exSets.groupBy { it.sessionId }.filterValues { s ->
                        s.any { it.feedback != null && it.feedback != SetFeedback.HURT }
                    }
                    val latest = sessions.values.maxByOrNull { s -> s.maxOf { it.completedAt!! } }
                        ?: return@mapNotNull null
                    exerciseId to ExerciseCapFact(
                        capLn = PrescriptionPolicy.capLnFor(latest),
                        demonstratedAt = latest.maxOf { it.completedAt!! },
                    )
                }
                .toMap()

            val hurtEventsByMuscle = completed
                .filter { it.feedback == SetFeedback.HURT }
                .mapNotNull { s -> exerciseMuscle[s.exerciseId]?.let { m -> Triple(m, s.sessionId, s.completedAt!!) } }
                .groupBy { it.first }
                .mapValues { (_, events) ->
                    // One backoff event per (session, muscle), like the old muscle-level HURT fold.
                    events.groupBy { it.second }.map { (_, e) -> e.maxOf { it.third } }
                }

            return PolicyFacts(capByExercise, hurtEventsByMuscle)
        }
    }
}
