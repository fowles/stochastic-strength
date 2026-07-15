package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.belief.BeliefSessionStep
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
}
