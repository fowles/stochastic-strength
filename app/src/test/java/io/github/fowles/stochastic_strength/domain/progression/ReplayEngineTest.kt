package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.belief.BeliefSessionStep
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayEngineTest {

    @Test
    fun observerSurfaceHasTheAgreedShape() {
        // Compile-only guard that the observer surface exists with the agreed five-arg shape.
        // (Full DB-backed replay parity is covered by the instrumented ReplayDerivedStateTest.)
        var captured = -1
        val observer = ReplayEngine.SessionObserver { _, _, sets, _, beliefResult ->
            captured = sets.size + beliefResult.steps.size
        }
        observer.onSession(
            sessionId = 1L,
            asOf = 0L,
            sets = emptyList(),
            snapshot = ReplaySnapshot(emptyMap(), emptyMap()),
            beliefResult = BeliefSessionStep.Result(emptyMap(), emptyList()),
        )
        assertEquals(0, captured)
    }

    @Test
    fun runCoreInvokesBeforeSessionPreFold() {
        // beforeSession must fire once per session, BEFORE the fold mutates beliefs.
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f),
        )
        val session = WorkoutSession(
            id = 1L, startTime = 0L, endTime = 1_000L,
        )
        val sets = listOf(
            WorkoutSet(
                sessionId = 1L, exerciseId = 1L, setNumber = 1,
                targetWeight = 100f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4,
                completedAt = 1_000L,
            ),
        )
        var beforeCount = 0
        var beliefsPresentAtBefore = true
        kotlinx.coroutines.runBlocking {
            ReplayEngine().runCore(
                snapshot = snap,
                initialSeeds = emptyList(),
                sessionSeeds = emptyMap(),
                sessions = listOf(session),
                setsForSession = { sets },
                observer = { _, _, _, _, _ -> },
                beforeSession = { beliefs, _ ->
                    beforeCount++
                    // No initial override, so pre-fold this exercise has no belief yet.
                    beliefsPresentAtBefore = beliefs.containsKey(1L)
                },
            )
        }
        assertEquals(1, beforeCount)
        assertEquals(false, beliefsPresentAtBefore)
    }
}
