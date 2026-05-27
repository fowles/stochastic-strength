package io.github.fowles.stochastic_strength

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.random.Random

object DebugSeeder {
    private val feedbackDistribution = listOf(
        SetFeedback.RIR_0_1, SetFeedback.RIR_0_1, SetFeedback.RIR_0_1,
        SetFeedback.RIR_2_4, SetFeedback.RIR_2_4,
        SetFeedback.RIR_5_PLUS,
        SetFeedback.TOO_HARD,
    )

    private val repTargets = listOf(5, 8, 10)

    // Rough starting weights by equipment (kg), scales with progression
    private val baseWeightByEquipment = mapOf(
        "BARBELL" to 60f,
        "DUMBBELL" to 16f,
        "CABLE_MACHINE" to 30f,
        "MACHINE" to 40f,
        "BODYWEIGHT" to 0f,
        "KETTLEBELL" to 16f,
        "RESISTANCE_BAND" to 10f,
        "SMITH_MACHINE" to 50f,
    )

    suspend fun seedIfEmpty(db: AppDatabase) {
        if (db.workoutSessionDao().getAll().isNotEmpty()) return

        val exercises = db.exerciseDao().getActive()
        if (exercises.isEmpty()) return

        val rng = Random(seed = 42)
        val now = System.currentTimeMillis()
        val msPerDay = 86_400_000L

        // ~3 sessions/week for 12 weeks
        val sessionDaysAgo = buildList {
            var day = 84
            while (day > 0) {
                add(day)
                day -= rng.nextInt(2, 4)
            }
        }

        // Group exercises by primary muscle so sessions have variety
        val byMuscle = exercises.groupBy { it.primaryMuscle }
        val muscleGroups = byMuscle.keys.toList()

        for ((sessionIndex, daysAgo) in sessionDaysAgo.withIndex()) {
            val progressionFactor = 1f + (sessionIndex.toFloat() / sessionDaysAgo.size) * 0.15f

            val startMs = now - daysAgo * msPerDay + rng.nextLong(6 * 3_600_000L, 20 * 3_600_000L)
            val sessionId = db.workoutSessionDao().insert(
                WorkoutSession(startTime = startMs, endTime = startMs + rng.nextLong(45 * 60_000L, 75 * 60_000L))
            )

            // Pick 5–6 muscle groups, then one exercise each
            val shuffledMuscles = muscleGroups.shuffled(rng).take(rng.nextInt(5, 7))
            val sessionExercises = shuffledMuscles.mapNotNull { muscle ->
                byMuscle[muscle]?.filter { !it.hurtFlag }?.randomOrNull(rng)
            }

            val targetReps = repTargets.random(rng)

            for (exercise in sessionExercises) {
                val equipmentKey = exercise.equipment.name
                val baseWeight = baseWeightByEquipment[equipmentKey] ?: 20f
                val weight = (baseWeight * progressionFactor).let {
                    // Round to nearest 2.5
                    (it / 2.5f).toInt() * 2.5f
                }

                var setTime = startMs
                for (setNumber in 1..3) {
                    setTime += rng.nextLong(3 * 60_000L, 7 * 60_000L)
                    db.workoutSetDao().insert(
                        WorkoutSet(
                            sessionId = sessionId,
                            exerciseId = exercise.id,
                            setNumber = setNumber,
                            targetWeight = weight,
                            targetReps = targetReps,
                            feedback = feedbackDistribution.random(rng),
                            completedAt = setTime,
                        )
                    )
                }
            }
        }
    }
}
