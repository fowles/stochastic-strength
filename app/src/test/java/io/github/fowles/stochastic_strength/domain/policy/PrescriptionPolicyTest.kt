package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class PrescriptionPolicyTest {

    private fun set(feedback: SetFeedback?, w: Float = 100f, r: Int = 10, a: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = w, targetReps = r, actualReps = a, feedback = feedback)

    private fun lnRm(w: Float, reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))

    // --- capLnFor ---

    @Test
    fun failedSessionCapsAtTheFailuresImpliedOneRepMax() {
        // Counted failure: implied 1RM = 1RM(w, a + 0.5) (spec phase-0 table midpoint).
        val cap = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 35f, a = 2)))
        assertEquals(lnRm(35f, 2.5f), cap!!, 1e-6f)
    }

    @Test
    fun failedSessionTakesTheMinOverFailedSetsAndIgnoresSuccesses() {
        val cap = PrescriptionPolicy.capLnFor(
            listOf(
                set(SetFeedback.TOO_HARD, w = 24.9f, a = 2),
                set(SetFeedback.TOO_HARD, w = 15.9f, a = 2),
                set(SetFeedback.RIR_0_1, w = 9.1f),  // success does not lift a failed session's cap
            )
        )
        assertEquals(lnRm(15.9f, 2.5f), cap!!, 1e-6f)
    }

    @Test
    fun uncountedFailureCapsAtTargetRepsBound() {
        val cap = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 35f, a = null)))
        assertEquals(lnRm(35f, 10f), cap!!, 1e-6f)
    }

    @Test
    fun cleanSessionCapsAtMaxDemonstratedUpperBound() {
        // RIR_0_1 at (w, r) → upper 1RM(w, r+2); RIR_2_4 → 1RM(w, r+5). Max wins.
        val cap = PrescriptionPolicy.capLnFor(
            listOf(set(SetFeedback.RIR_0_1, w = 20f), set(SetFeedback.RIR_2_4, w = 18f))
        )
        assertEquals(maxOf(lnRm(20f, 12f), lnRm(18f, 15f)), cap!!, 1e-6f)
    }

    @Test
    fun anyRir5PlusSetUncapsACleanSession() {
        assertNull(PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.RIR_5_PLUS), set(SetFeedback.RIR_0_1))))
    }

    @Test
    fun hurtOnlyOrFeedbacklessSessionHasNoCap() {
        assertNull(PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.HURT), set(null))))
        assertNull(PrescriptionPolicy.capLnFor(emptyList()))
    }

    // --- hurtMultiplier ---

    @Test
    fun freshHurtBacksOffByDepthAndFadesWithHalfLife() {
        val now = 0L
        assertEquals(0.85f, PrescriptionPolicy.hurtMultiplier(listOf(now), now), 1e-4f)
        val after14d = 14L * 24 * 60 * 60 * 1000
        assertEquals(0.925f, PrescriptionPolicy.hurtMultiplier(listOf(0L), after14d), 1e-4f)
    }

    @Test
    fun noHurtEventsMeansNoBackoff() {
        assertEquals(1f, PrescriptionPolicy.hurtMultiplier(emptyList(), 0L), 0f)
    }

    @Test
    fun stackedHurtsFloorAtSixtyPercent() {
        val m = PrescriptionPolicy.hurtMultiplier(List(7) { 0L }, 0L)  // 0.85^7 ≈ 0.32 → floored
        assertEquals(0.6f, m, 1e-6f)
        assertTrue(PrescriptionPolicy.hurtMultiplier(List(2) { 0L }, 0L) > 0.6f)
    }
}
