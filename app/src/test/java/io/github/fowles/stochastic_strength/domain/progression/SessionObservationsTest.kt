package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionObservationsTest {
    private fun set(n: Int, fb: SetFeedback?, w: Float = 60f, reps: Int = 10, actual: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = n, targetWeight = w, targetReps = reps, actualReps = actual, feedback = fb)

    @Test
    fun onTargetSessionImpliesRoughlyTheTargetCapacity() {
        val implied = impliedSessionE1rm(listOf(set(1, SetFeedback.RIR_0_1), set(2, SetFeedback.RIR_0_1), set(3, SetFeedback.RIR_0_1)))!!
        val target = DefaultProgressionEngine.rawToOneRepMax(60f, 10)
        // RIR_0_1 = [target, target+2) on a fresh basis: implied sits at/above target, within ~12%.
        assertTrue("implied $implied vs target $target", implied >= target * 0.98f && implied <= target * 1.15f)
    }

    @Test
    fun failuresDragTheImpliedCapacityDown() {
        val clean = impliedSessionE1rm(listOf(set(1, SetFeedback.RIR_0_1)))!!
        val failed = impliedSessionE1rm(listOf(set(1, SetFeedback.TOO_HARD, actual = 5)))!!
        assertTrue(failed < clean)
    }

    @Test
    fun sessionsWithoutLoadSignalYieldNoDot() {
        assertNull(impliedSessionE1rm(listOf(set(1, SetFeedback.HURT))))
        assertNull(impliedSessionE1rm(listOf(set(1, null))))
        assertNull(impliedSessionE1rm(emptyList()))
        assertNull(impliedSessionE1rm(listOf(set(1, SetFeedback.RIR_0_1, w = 0f))))
    }

    @Test
    fun dotIsIndependentOfPriorHistory() {
        // Broad-prior fold: two identical set lists give identical dots regardless of call order.
        val sets = listOf(set(1, SetFeedback.RIR_2_4), set(2, SetFeedback.TOO_HARD, actual = 8))
        assertEquals(impliedSessionE1rm(sets)!!, impliedSessionE1rm(sets)!!, 1e-6f)
    }
}
