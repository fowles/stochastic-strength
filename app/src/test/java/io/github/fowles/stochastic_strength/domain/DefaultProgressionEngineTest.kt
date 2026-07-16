package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProgressionEngineTest {

    @Test
    fun fractional_reps_match_int_at_whole_numbers() {
        assertEquals(
            DefaultProgressionEngine.rawToOneRepMax(100f, 5),
            DefaultProgressionEngine.rawToOneRepMax(100f, 5f),
            1e-3f,
        )
    }

    @Test
    fun fractional_reps_interpolate_monotonically() {
        val a = DefaultProgressionEngine.rawToOneRepMax(100f, 5f)
        val mid = DefaultProgressionEngine.rawToOneRepMax(100f, 5.5f)
        val b = DefaultProgressionEngine.rawToOneRepMax(100f, 6f)
        assertTrue("expected $a < $mid < $b", a < mid && mid < b)
    }

    @Test
    fun fractional_reps_at_or_below_one_return_weight() {
        assertEquals(100f, DefaultProgressionEngine.rawToOneRepMax(100f, 0.5f), 1e-6f)
        assertEquals(100f, DefaultProgressionEngine.rawToOneRepMax(100f, 1f), 1e-6f)
    }

    @Test
    fun rawConversionsAreExposedOnTheInterface() {
        val engine: ProgressionEngine = DefaultProgressionEngine
        // Un-rounded round trip: raw inverse of raw forward recovers the weight (no 0.5 kg grid).
        val oneRm = engine.rawToOneRepMax(101.3f, 5f)
        assertEquals(101.3f, engine.rawFromOneRepMax(oneRm, 5), 0.05f)
        // Fractional reps are meaningful (used by the interval bounds table).
        assertTrue(engine.rawToOneRepMax(100f, 5.5f) > engine.rawToOneRepMax(100f, 5f))
    }
}
