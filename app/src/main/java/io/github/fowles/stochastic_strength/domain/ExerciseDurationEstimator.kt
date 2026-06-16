package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.roundToInt

class ExerciseDurationEstimator(
    private val secondsByExerciseId: Map<Long, Int>,
) {
    fun secondsFor(exerciseId: Long): Int? = secondsByExerciseId[exerciseId]

    companion object {
        const val MAX_APPEARANCES = 10
        const val MIN_SECONDS = 60
        const val MAX_SECONDS = 1200

        val EMPTY = ExerciseDurationEstimator(emptyMap())

        fun build(
            sessionsNewestFirst: List<WorkoutSession>,
            setsBySessionId: Map<Long, List<WorkoutSet>>,
        ): ExerciseDurationEstimator {
            val samplesByExercise = mutableMapOf<Long, MutableList<Int>>()

            for (session in sessionsNewestFirst) {
                val sessionSets = setsBySessionId[session.id] ?: continue
                if (sessionSets.isEmpty()) continue

                val byExercise = sessionSets.groupBy { it.exerciseId }
                for ((exerciseId, exerciseSets) in byExercise) {
                    val existing = samplesByExercise[exerciseId]
                    if (existing != null && existing.size >= MAX_APPEARANCES) continue

                    val sample = measureAppearance(
                        exerciseSets = exerciseSets,
                        sessionSets = sessionSets,
                        sessionStartTime = session.startTime,
                    ) ?: continue

                    samplesByExercise.getOrPut(exerciseId) { mutableListOf() }.add(sample)
                }
            }

            val means = samplesByExercise.mapValues { (_, samples) ->
                samples.average().roundToInt()
            }
            return ExerciseDurationEstimator(means)
        }

        private fun measureAppearance(
            exerciseSets: List<WorkoutSet>,
            sessionSets: List<WorkoutSet>,
            sessionStartTime: Long,
        ): Int? {
            if (exerciseSets.any { it.feedback == SetFeedback.HURT }) return null

            val completedTimes = exerciseSets.map { it.completedAt ?: return null }

            val firstCompleted = completedTimes.min()
            val lastCompleted = completedTimes.max()

            val predecessorEnd = sessionSets
                .mapNotNull { it.completedAt }
                .filter { it < firstCompleted }
                .maxOrNull()
                ?: sessionStartTime

            val durationSec = ((lastCompleted - predecessorEnd) / 1000L).toInt()
            return if (durationSec in MIN_SECONDS..MAX_SECONDS) durationSec else null
        }
    }
}
