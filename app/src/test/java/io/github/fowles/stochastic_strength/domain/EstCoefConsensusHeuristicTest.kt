package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EstCoefConsensusHeuristicTest {

    private val heuristic = EstCoefConsensusHeuristic()

    private fun set(
        targetWeight: Float = 80f,
        targetReps: Int = 5,
        actualReps: Int? = null,
        feedback: SetFeedback? = null,
    ) = WorkoutSet(
        sessionId = 1L,
        exerciseId = 1L,
        setNumber = 1,
        targetWeight = targetWeight,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
    )

    @Test
    fun setSignal_returnsNullForNullFeedback() {
        assertNull(heuristic.setSignal(set(feedback = null)))
    }

    @Test
    fun setSignal_returnsNullForHurt() {
        assertNull(heuristic.setSignal(set(feedback = SetFeedback.HURT)))
    }

    @Test
    fun setSignal_rir5Plus_isWeakSignal() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS))!!
        // toOneRepMax(80, 5 + 7 = 12)
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 12)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.4f, s.confidence, 0.001f)
        assertFalse(s.isUpperBound)
        assertFalse(s.isDefinite)
    }

    @Test
    fun setSignal_rir2_4_isMidConfidence() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 8)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.7f, s.confidence, 0.001f)
    }

    @Test
    fun setSignal_rir0_1_isHighConfidence() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, feedback = SetFeedback.RIR_0_1))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 9)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.85f, s.confidence, 0.001f)
    }

    @Test
    fun setSignal_tooHardWithActualReps_isDefinite() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = 3, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 3)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.95f, s.confidence, 0.001f)
        assertTrue(s.isDefinite)
        assertFalse(s.isUpperBound)
    }

    @Test
    fun setSignal_tooHardWithoutActualReps_isUpperBound() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = null, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 7)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.5f, s.confidence, 0.001f)
        assertTrue(s.isUpperBound)
        assertFalse(s.isDefinite)
    }

    @Test
    fun setSignal_tooHardWithoutActualReps_targetReps1_clampsTo1() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 1, actualReps = null, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 1)
        assertEquals(expected, s.est1RM, 0.001f)
    }

    @Test
    fun aggregateSession_returnsNullForAllNullSets() {
        val agg = heuristic.aggregateSession(listOf(
            set(feedback = null),
            set(feedback = SetFeedback.HURT),
        ))
        assertNull(agg)
    }

    @Test
    fun aggregateSession_twoRir2_4Sets_returnsWeightedMean() {
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 8)
        assertEquals(expected, agg.est1RM, 0.001f)
        assertEquals(0.7f, agg.sessionConfidence, 0.001f)
        assertFalse(agg.hasDefinite)
    }

    @Test
    fun aggregateSession_includesReducedWeightSets() {
        // Original RIR_2_4 at 80 followed by reduced-weight RIR_0_1 at 70 (post-failure backoff).
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(targetWeight = 70f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val a = DefaultProgressionEngine.toOneRepMax(80f, 8)
        val b = DefaultProgressionEngine.toOneRepMax(70f, 6)
        val expectedEst1RM = (a * 0.7f + b * 0.85f) / (0.7f + 0.85f)
        assertEquals(expectedEst1RM, agg.est1RM, 0.001f)
    }

    @Test
    fun aggregateSession_definiteFlagSetWhenAnySetIsTooHardWithActualReps() {
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(targetWeight = 75f, targetReps = 5, actualReps = 3, feedback = SetFeedback.TOO_HARD),
        )
        val agg = heuristic.aggregateSession(sets)!!
        assertTrue(agg.hasDefinite)
    }

    @Test
    fun aggregateSession_upperBoundOmittedWhenOtherPointsLower() {
        // The RIR_5_PLUS estimate at (60, 12) is much less than the upper bound at (100, 4) — upper bound
        // would dominate if included. Spec says omit it when other-feedback est_1RM is below the bound.
        val sets = listOf(
            set(targetWeight = 60f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
            set(targetWeight = 100f, targetReps = 5, actualReps = null, feedback = SetFeedback.TOO_HARD),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val rirEst = DefaultProgressionEngine.toOneRepMax(60f, 12)
        assertEquals(rirEst, agg.est1RM, 0.001f)
    }

    @Test
    fun aggregateSession_upperBoundIncludedWhenOtherPointsAgreeAbove() {
        // Other-feedback est_1RM exceeds upper bound — bound is in agreement (below or equal) → included.
        val sets = listOf(
            set(targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
            set(targetWeight = 100f, targetReps = 5, actualReps = null, feedback = SetFeedback.TOO_HARD),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val rirEst = DefaultProgressionEngine.toOneRepMax(100f, 12)
        val upperBound = DefaultProgressionEngine.toOneRepMax(100f, 4)
        // When upper bound is *below* the other-feedback estimate, it's a ceiling that pulls the agg down.
        // Confidence-weighted mean of (rirEst @ 0.4, upperBound @ 0.5).
        val expected = (rirEst * 0.4f + upperBound * 0.5f) / (0.4f + 0.5f)
        assertEquals(expected, agg.est1RM, 0.001f)
    }

    // Synthetic sessions to drive computeH1 directly.
    private fun sessionSignal(
        sessionId: Long,
        sessionTime: Long,
        estCoef: Float,
        sessionConfidence: Float,
        hasDefinite: Boolean = false,
    ) = EstCoefConsensusHeuristic.SessionSignal(
        sessionId = sessionId,
        sessionTime = sessionTime,
        estCoef = estCoef,
        sessionConfidence = sessionConfidence,
        hasDefinite = hasDefinite,
    )

    @Test
    fun computeH1_empty_returnsNull() {
        val h = EstCoefConsensusHeuristic(now = { 1000L })
        assertNull(h.computeH1(emptyList()))
    }

    @Test
    fun computeH1_belowMinEvidenceAndNoDefinite_returnsNull() {
        // One RIR_2_4-like session, recency ~1.0, confidence 0.7 -> weight 0.7 < min_evidence_weight = 1.5.
        val h = EstCoefConsensusHeuristic(now = { 1000L })
        val signals = listOf(sessionSignal(1L, 1000L, 1.25f, 0.7f, hasDefinite = false))
        assertNull(h.computeH1(signals))
    }

    @Test
    fun computeH1_singleDefinitePointBypassesMinEvidence() {
        val h = EstCoefConsensusHeuristic(now = { 1000L })
        val signals = listOf(sessionSignal(1L, 1000L, 1.25f, 0.95f, hasDefinite = true))
        val proposal = h.computeH1(signals)!!
        assertEquals(1.25f, proposal.proposal, 0.001f)
        assertEquals(1, proposal.sessionCount)
        assertTrue(proposal.hasDefinite)
    }

    @Test
    fun computeH1_weightedMedianIgnoresSingleOutlier() {
        // Three near-1.0 + one freak — median picks the cluster.
        val h = EstCoefConsensusHeuristic(now = { 1000L })
        val signals = listOf(
            sessionSignal(1L, 1000L, 1.00f, 0.7f),
            sessionSignal(2L, 1000L, 1.00f, 0.7f),
            sessionSignal(3L, 1000L, 1.05f, 0.7f),
            sessionSignal(4L, 1000L, 1.80f, 0.4f), // freak, low confidence
        )
        val proposal = h.computeH1(signals)!!
        assertTrue("median should sit in the 1.0–1.05 cluster, got ${proposal.proposal}",
            proposal.proposal in 1.00f..1.05f)
        assertEquals(4, proposal.sessionCount)
    }

    @Test
    fun computeH1_recencyDecayMakesRecentLowConfWeighComparableToOldHighConf() {
        // tauHalf = 14d = 14*24*60*60*1000 ms. Two sessions:
        // Recent low-confidence (0.4) at full recency, old high-confidence (0.85) at 28d (recency = 0.25).
        val tauHalfMs = 14L * 24 * 60 * 60 * 1000
        val nowT = 100_000_000L
        val h = EstCoefConsensusHeuristic(now = { nowT })
        val signals = listOf(
            sessionSignal(1L, nowT, 1.10f, 0.4f),          // weight ≈ 1.0 × 0.4 = 0.40
            sessionSignal(2L, nowT - 2 * tauHalfMs, 1.30f, 0.85f), // weight ≈ 0.25 × 0.85 = 0.2125
        )
        val proposal = h.computeH1(signals)
        // total_weight ≈ 0.61 < 1.5 and no definite point → expect null
        assertNull(proposal)
    }
}
