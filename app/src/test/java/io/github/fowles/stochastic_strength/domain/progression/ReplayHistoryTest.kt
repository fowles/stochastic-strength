package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class ReplayHistoryTest {

    private val config = EstimatorConfig()

    private fun set(sessionId: Long, exerciseId: Long) = WorkoutSet(
        sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
        targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4,
    )

    @Test
    fun replaysInEndTimeOrderAppliesOverridesAndSkipsEmptySessions() {
        val snapshot = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f),
        )
        // Sessions deliberately out of order; session 3 has an override but no sets.
        val history = ReplayHistory(
            sessions = listOf(
                WorkoutSession(id = 2, locationId = null, startTime = 0L, endTime = 2_000L, stravaActivityId = null),
                WorkoutSession(id = 1, locationId = null, startTime = 0L, endTime = 1_000L, stravaActivityId = null),
                WorkoutSession(id = 3, locationId = null, startTime = 0L, endTime = 3_000L, stravaActivityId = null),
            ),
            setsBySession = mapOf(1L to listOf(set(1, 1)), 2L to listOf(set(2, 1))),
            initialOverrides = listOf(
                ExerciseStrengthOverride(sessionId = null, exerciseId = 1L, e1rm = 100f, asOf = 0L),
            ),
            sessionOverrides = mapOf(
                3L to listOf(ExerciseStrengthOverride(sessionId = 3L, exerciseId = 1L, e1rm = 75f, asOf = 2_500L)),
            ),
        )

        val observed = mutableListOf<Long>()
        ReplayEngine().run(history, snapshot) { sessionId, _, _, _, _ -> observed.add(sessionId) }

        // Sessions with sets ran in endTime order; the empty session 3 was skipped by the observer.
        assertEquals(listOf(1L, 2L), observed)
        // But session 3's override was still applied to the belief map.
        val belief = snapshot.currentBeliefs.getValue(1L)
        assertEquals(ln(75f), belief.mu, 1e-4f)
        assertEquals(config.sigmaOverride * config.sigmaOverride, belief.sigma2, 1e-4f)
    }
}
