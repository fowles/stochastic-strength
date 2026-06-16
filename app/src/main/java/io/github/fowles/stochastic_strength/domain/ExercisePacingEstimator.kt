package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

class ExercisePacingEstimator(
    private val secondsPerRepByExerciseId: Map<Long, Float>,
) {
    fun secondsPerRep(exerciseId: Long): Float? = secondsPerRepByExerciseId[exerciseId]

    companion object {
        const val MAX_APPEARANCES = 10
        const val MIN_SECONDS_PER_REP = 1.0f
        const val MAX_SECONDS_PER_REP = 30.0f

        val EMPTY = ExercisePacingEstimator(emptyMap())

        fun build(
            sessionsNewestFirst: List<WorkoutSession>,
            setsBySessionId: Map<Long, List<WorkoutSet>>,
            exercisesById: Map<Long, Exercise>,
        ): ExercisePacingEstimator {
            val appearancesByExercise = mutableMapOf<Long, MutableList<Float>>()

            for (session in sessionsNewestFirst) {
                val sessionSets = setsBySessionId[session.id] ?: continue
                if (sessionSets.isEmpty()) continue

                val byExercise = sessionSets.groupBy { it.exerciseId }
                for ((exerciseId, exerciseSets) in byExercise) {
                    val existing = appearancesByExercise[exerciseId]
                    if (existing != null && existing.size >= MAX_APPEARANCES) continue

                    val exercise = exercisesById[exerciseId] ?: continue
                    val sides = if (exercise.isUnilateral) 2 else 1

                    val appearanceAvg = appearanceAverage(
                        exerciseSets = exerciseSets,
                        sides = sides,
                    ) ?: continue

                    appearancesByExercise.getOrPut(exerciseId) { mutableListOf() }.add(appearanceAvg)
                }
            }

            val perExercise = appearancesByExercise.mapValues { (_, samples) ->
                samples.average().toFloat()
            }
            return ExercisePacingEstimator(perExercise)
        }

        private fun appearanceAverage(exerciseSets: List<WorkoutSet>, sides: Int): Float? {
            val sorted = exerciseSets.sortedBy { it.setNumber }
            val samples = mutableListOf<Float>()
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val curr = sorted[i]
                if (prev.feedback == SetFeedback.HURT || curr.feedback == SetFeedback.HURT) continue
                val prevAt = prev.completedAt ?: continue
                val currAt = curr.completedAt ?: continue
                val workTimeSec = (currAt - prevAt) / 1000.0 - DurationCalculator.REST_SECONDS
                val reps = curr.actualReps ?: curr.targetReps
                if (reps <= 0) continue
                val perRep = (workTimeSec / (reps * sides)).toFloat()
                if (perRep !in MIN_SECONDS_PER_REP..MAX_SECONDS_PER_REP) continue
                samples.add(perRep)
            }
            if (samples.isEmpty()) return null
            return samples.average().toFloat()
        }
    }
}
