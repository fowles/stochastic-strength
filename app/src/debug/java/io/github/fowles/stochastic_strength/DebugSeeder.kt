package io.github.fowles.stochastic_strength

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionController
import kotlin.random.Random

object DebugSeeder {
    private val feedbackDistribution = listOf(
        SetFeedback.RIR_0_1, SetFeedback.RIR_0_1, SetFeedback.RIR_0_1,
        SetFeedback.RIR_2_4, SetFeedback.RIR_2_4,
        SetFeedback.RIR_5_PLUS,
        SetFeedback.TOO_HARD,
    )

    suspend fun seedIfEmpty(db: AppDatabase, repository: WorkoutRepository) {
        if (db.workoutSessionDao().getAll().isNotEmpty()) return
        if (db.exerciseDao().getActive().isEmpty()) return
        // Wait until the user has onboarded — the planner relies on the baselines
        // that onboarding writes via seedInitialWeights.
        val profile = db.userProfileDao().getProfile() ?: return

        val weightUnit = profile.weightUnit
        val rng = Random(seed = 42)
        val now = System.currentTimeMillis()
        val msPerDay = 86_400_000L

        // ~3 sessions/week for 12 weeks, oldest first so progression compounds.
        val sessionDaysAgo = buildList {
            var day = 84
            while (day > 0) {
                add(day)
                day -= rng.nextInt(2, 4)
            }
        }.sortedDescending()

        for (daysAgo in sessionDaysAgo) {
            val startMs = now - daysAgo * msPerDay + rng.nextLong(6 * 3_600_000L, 20 * 3_600_000L)
            val endMs = startMs + rng.nextLong(45 * 60_000L, 75 * 60_000L)

            val planner = repository.buildPlanner(locationId = null, weightUnit = weightUnit)
            val plan = planner.generateWorkout()
            if (plan.exercises.isEmpty()) continue

            val sessionId = db.workoutSessionDao().insert(
                WorkoutSession(startTime = startMs, endTime = endMs)
            )

            var setTime = startMs
            for (planned in plan.exercises) {
                for (setNumber in 1..PlannedExercise.DEFAULT_SETS) {
                    setTime += rng.nextLong(3 * 60_000L, 7 * 60_000L)
                    val feedback = feedbackDistribution.random(rng)
                    val isLastSet = setNumber == PlannedExercise.DEFAULT_SETS
                    val actualReps: Int? = when (feedback) {
                        SetFeedback.RIR_0_1, SetFeedback.RIR_2_4, SetFeedback.RIR_5_PLUS -> planned.sessionReps
                        SetFeedback.TOO_HARD ->
                            if (isLastSet) null
                            else rng.nextInt(0, planned.sessionReps)
                        SetFeedback.HURT -> null
                    }

                    db.workoutSetDao().insert(
                        WorkoutSet(
                            sessionId = sessionId,
                            exerciseId = planned.exercise.id,
                            setNumber = setNumber,
                            targetWeight = planned.sessionWeight,
                            targetReps = planned.sessionReps,
                            actualReps = actualReps,
                            feedback = feedback,
                            completedAt = setTime,
                            durationSeconds = if (planned.exercise.isTimed) WorkoutSessionController.TIMED_SET_SECONDS else null,
                        )
                    )
                }
            }

            repository.applySessionProgression(sessionId)
        }
    }
}
