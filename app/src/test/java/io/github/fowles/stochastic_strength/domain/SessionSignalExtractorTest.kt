package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSignalExtractorTest {

    private fun set(weight: Float, reps: Int, fb: SetFeedback?, actual: Int? = null, setNumber: Int = 1) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = setNumber,
            targetWeight = weight, targetReps = reps, actualReps = actual, feedback = fb)

    private fun oneRm(weight: Float, repsF: Float) = DefaultProgressionEngine.rawToOneRepMax(weight, repsF)

    // ---- setSignal -------------------------------------------------------------------------------

    @Test
    fun set_signal_maps_each_bucket() {
        assertEquals(0.5f, SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_0_1))!!.repDeviation, 1e-6f)
        assertEquals(3f, SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_2_4))!!.repDeviation, 1e-6f)
        assertEquals(6f, SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_5_PLUS))!!.repDeviation, 1e-6f)
        assertFalse(SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_0_1))!!.isFailure)
    }

    @Test
    fun too_hard_with_reps_is_negative_failure() {
        val s = SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.TOO_HARD, actual = 2))!!
        assertEquals(-3f, s.repDeviation, 1e-6f) // 2 - 5
        assertTrue(s.isFailure)
        assertEquals(0.95f, s.confidence, 1e-6f)
    }

    @Test
    fun too_hard_without_reps_assumes_half_target_shortfall() {
        val s = SessionSignalExtractor.setSignal(set(100f, 10, SetFeedback.TOO_HARD, actual = null))!!
        assertEquals(-5f, s.repDeviation, 1e-6f) // -(10 / 2)
        assertTrue(s.isFailure)
    }

    @Test
    fun hurt_yields_no_set_signal() {
        assertNull(SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.HURT)))
    }

    // ---- softening -------------------------------------------------------------------------------

    @Test
    fun softening_spans_full_rep_range_monotonically() {
        assertEquals(0.10f, SessionSignalExtractor.softening(1), 1e-4f)
        assertEquals(0.80f, SessionSignalExtractor.softening(20), 1e-4f)
        assertTrue(SessionSignalExtractor.softening(5) < SessionSignalExtractor.softening(10))
        assertTrue(SessionSignalExtractor.softening(10) < SessionSignalExtractor.softening(15))
        // out-of-range clamps
        assertEquals(0.10f, SessionSignalExtractor.softening(0), 1e-4f)
        assertEquals(0.80f, SessionSignalExtractor.softening(25), 1e-4f)
    }

    // ---- aggregateSession --------------------------------------------------------------------------

    @Test
    fun rir01_only_nudges_up_gently() {
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 3),
            ),
        )!!
        // offset = +0.5; above target (up), but below the old +1.
        assertTrue(agg.est1RM > oneRm(100f, 5f))
        assertTrue(agg.est1RM < oneRm(100f, 6f))
        assertEquals(oneRm(100f, 5.5f), agg.est1RM, 1e-2f)
    }

    @Test
    fun easy_early_sets_do_not_dominate_the_last_set() {
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_5_PLUS, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_2_4, setNumber = 2),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 3),
            ),
        )!!
        // position-weighting pulls the offset well below the plain mean (3.17) toward the last set.
        assertTrue(agg.est1RM < oneRm(100f, 8f)) // offset < 3
    }

    @Test
    fun a_failure_can_never_grow_the_session() {
        // two very easy sets then a small final miss: softened, but capped at no-growth.
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_5_PLUS, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_5_PLUS, setNumber = 2),
                set(100f, 5, SetFeedback.TOO_HARD, actual = 4, setNumber = 3),
            ),
        )!!
        assertTrue(agg.est1RM <= oneRm(100f, 5f) + 1e-2f)
    }

    @Test
    fun a_big_failure_dominates_downward() {
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
                set(100f, 5, SetFeedback.TOO_HARD, actual = 2, setNumber = 3),
            ),
        )!!
        assertTrue(agg.est1RM < oneRm(100f, 5f) * 0.99f)
    }

    @Test
    fun high_reps_more_forgiving_of_final_miss_than_low_reps() {
        fun scenario(reps: Int): Float {
            val agg = SessionSignalExtractor.aggregateSession(
                listOf(
                    set(100f, reps, SetFeedback.RIR_2_4, setNumber = 1),
                    set(100f, reps, SetFeedback.RIR_2_4, setNumber = 2),
                    set(100f, reps, SetFeedback.TOO_HARD, actual = reps - 2, setNumber = 3),
                ),
            )!!
            return agg.est1RM / oneRm(100f, reps.toFloat()) // ratio vs on-target
        }
        // high-rep ratio is closer to (or at) 1.0 — less downward — than low-rep.
        assertTrue(scenario(20) > scenario(5))
    }

    @Test
    fun reduced_weight_sets_are_ignored() {
        // last set dropped to a lighter weight after a miss; only the full-weight sets count.
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
                set(80f, 5, SetFeedback.RIR_5_PLUS, setNumber = 3), // dropped weight, easy — ignored
            ),
        )!!
        // est1RM derives from w0 = 100 and the RIR_0_1 offset, not the easy 80kg set.
        assertEquals(oneRm(100f, 5.5f), agg.est1RM, 1e-2f)
    }

    @Test
    fun only_hurt_sets_yield_null() {
        assertNull(SessionSignalExtractor.aggregateSession(listOf(set(100f, 5, SetFeedback.HURT))))
    }

    // ---- bracket path ------------------------------------------------------------------------------

    @Test
    fun drop_cascade_anchors_on_heaviest_completed_set_not_top_weight() {
        // 55 fail(2) -> 35 fail(2) -> 20 completed RIR_0_1. Capacity ~ the 20 set, not 55.
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(55f, 10, SetFeedback.TOO_HARD, actual = 2, setNumber = 1),
                set(35f, 10, SetFeedback.TOO_HARD, actual = 2, setNumber = 2),
                set(20f, 10, SetFeedback.RIR_0_1, setNumber = 3),
            ),
        )!!
        // est1RM = heaviest completed (20 @ 10 + 0.5 reserve), capped by the 35 fail ceiling (not binding here).
        assertEquals(oneRm(20f, 10.5f), agg.est1RM, 1e-2f)
        // Far below what the old top-weight path would have produced.
        assertTrue(agg.est1RM < oneRm(55f, 2f))
        assertEquals(0.95f, agg.bracketConfidence, 1e-6f)
        assertEquals(0.95f, agg.sessionConfidence, 1e-6f)
    }

    @Test
    fun all_failed_cascade_estimates_from_lightest_failed_set() {
        // Even the lightest weight failed -> strong downward estimate from that set's achieved reps.
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(55f, 10, SetFeedback.TOO_HARD, actual = 2, setNumber = 1),
                set(35f, 10, SetFeedback.TOO_HARD, actual = 3, setNumber = 2),
                set(20f, 10, SetFeedback.TOO_HARD, actual = 4, setNumber = 3),
            ),
        )!!
        assertEquals(oneRm(20f, 4f), agg.est1RM, 1e-2f)
        assertEquals(0.95f, agg.bracketConfidence, 1e-6f)
    }

    @Test
    fun top_failure_without_a_drop_keeps_old_path_and_zero_bracket_confidence() {
        // All sets at the same weight, last fails: existing same-weight behavior, NOT a bracket.
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
                set(100f, 5, SetFeedback.TOO_HARD, actual = 2, setNumber = 3),
            ),
        )!!
        assertEquals(0f, agg.bracketConfidence, 1e-6f)
        assertTrue(agg.est1RM < oneRm(100f, 5f) * 0.99f) // unchanged downward behavior
    }

    @Test
    fun voluntary_deload_without_failure_is_not_a_bracket() {
        // Existing reduced_weight_sets_are_ignored scenario must keep zero bracket confidence.
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
                set(80f, 5, SetFeedback.RIR_5_PLUS, setNumber = 3),
            ),
        )!!
        assertEquals(0f, agg.bracketConfidence, 1e-6f)
        assertEquals(oneRm(100f, 5.5f), agg.est1RM, 1e-2f)
    }
}
