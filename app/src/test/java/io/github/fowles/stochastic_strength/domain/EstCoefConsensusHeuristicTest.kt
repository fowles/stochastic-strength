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

    // Synthetic sessions to drive computeEstimate directly.
    private fun sessionSignal(
        sessionId: Long,
        sessionTime: Long,
        est1RM: Float,
        sessionConfidence: Float,
    ) = EstCoefConsensusHeuristic.SessionSignal(
        sessionId = sessionId,
        sessionTime = sessionTime,
        est1RM = est1RM,
        sessionConfidence = sessionConfidence,
    )

    @Test
    fun computeEstimate_empty_returnsNull() {
        val h = EstCoefConsensusHeuristic()
        assertNull(h.computeEstimate(emptyList()))
    }

    @Test
    fun computeEstimate_weightedMedianIgnoresSingleOutlier() {
        // Three near-100 + one freak — median picks the cluster.
        val h = EstCoefConsensusHeuristic()
        val signals = listOf(
            sessionSignal(1L, 1000L, 100.0f, 0.7f),
            sessionSignal(2L, 1000L, 100.0f, 0.7f),
            sessionSignal(3L, 1000L, 105.0f, 0.7f),
            sessionSignal(4L, 1000L, 180.0f, 0.4f), // freak, low confidence
        )
        val est = h.computeEstimate(signals)!!
        assertTrue("median should sit in the 100-105 cluster, got ${est.est1RM}",
            est.est1RM in 100.0f..105.0f)
    }

    private fun estimate(
        est1RM: Float,
        weight: Float = 3f,
        confidence: Float = 0.8f,
    ) = EstCoefConsensusHeuristic.ExerciseEstimate(
        est1RM = est1RM,
        weight = weight,
        confidence = confidence,
    )

    @Test
    fun damp_proposalEqualsCurrent_emitsNothing() {
        val h = EstCoefConsensusHeuristic()
        val result = h.damp(
            exerciseId = 1L,
            emit = EstCoefConsensusHeuristic.EmitProposal(1.00f, 0.8f, null),
            currentCoef = 1.00f,
        )
        assertNull(result)
    }

    @Test
    fun damp_smallChangeProportionalToConfidenceAndDistance() {
        val h = EstCoefConsensusHeuristic()
        // log(1.10/1.00) = 0.0953. α=0.2 × conf 0.5 × 0.0953 = 0.00953. Under cap (0.0488).
        val result = h.damp(
            exerciseId = 1L,
            emit = EstCoefConsensusHeuristic.EmitProposal(1.10f, 0.5f, "m"),
            currentCoef = 1.00f,
        )!!
        val expected = 1.00f * kotlin.math.exp(0.2f * 0.5f * kotlin.math.ln(1.10f))
        assertEquals(expected, result.coefficient, 0.001f)
        assertEquals(1L, result.exerciseId)
        assertEquals("m", result.metadata)
    }

    @Test
    fun damp_lowCoefficientWithMeaningfulRelativeMove_isEmitted() {
        // At a low coefficient (0.15), a 10% gap at full confidence produces an
        // absolute change of ~0.003 — below an absolute 0.005 floor, but well above
        // a sensible relative floor (0.5% of 0.15 = 0.00075). We expect the change
        // to be emitted so low-coefficient exercises don't get anchored.
        val h = EstCoefConsensusHeuristic()
        val result = h.damp(
            exerciseId = 1L,
            emit = EstCoefConsensusHeuristic.EmitProposal(0.165f, 1.0f, null),
            currentCoef = 0.15f,
        )
        val expected = 0.15f * kotlin.math.exp(0.2f * 1.0f * kotlin.math.ln(0.165f / 0.15f))
        assertEquals(expected, result!!.coefficient, 0.0001f)
    }

    @Test
    fun damp_largeChangeIsClampedToMaxLogStep() {
        val h = EstCoefConsensusHeuristic()
        // Confidence 1.0 + huge gap → log step clamped to ln(1.05).
        val result = h.damp(
            exerciseId = 1L,
            emit = EstCoefConsensusHeuristic.EmitProposal(2.00f, 1.0f, null),
            currentCoef = 1.00f,
        )!!
        val expected = 1.00f * 1.05f
        assertEquals(expected, result.coefficient, 0.001f)
    }

    @Test
    fun `compute uses max sessionTime from input as now, not wall clock`() {
        val newT = 1_700_000_000_000L
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        // Three exercises, one session each at newT; coefficients disagree so a proposal must emit.
        fun s(sessionId: Long, exerciseId: Long) = WorkoutSet(
            id = sessionId, sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = newT,
        )
        val sets = listOf(s(1, 101), s(2, 102), s(3, 103))
        val input = CoefficientComputationInput(
            sets = sets,
            sessionTimes = mapOf(1L to newT, 2L to newT, 3L to newT),
            exerciseMuscle = mapOf(101L to muscle, 102L to muscle, 103L to muscle),
            baselines = emptyMap(),
            currentCoefficients = mapOf(101L to 1.0f, 102L to 1.0f, 103L to 1.2f),
        )
        val results = EstCoefConsensusHeuristic().compute(input)
        assertTrue("expected at least one result; was empty (heuristic likely using wall clock)",
            results.isNotEmpty())
    }

    @Test
    fun compute_skipsBodyweightExercisesWithZeroCoefficient() {
        val nowT = 100_000_000_000L
        val sets = listOf(WorkoutSet(
            sessionId = 1L, exerciseId = 99L, setNumber = 1,
            targetWeight = 0f, targetReps = 10, feedback = SetFeedback.RIR_2_4,
        ))
        val input = CoefficientComputationInput(
            sets = sets,
            sessionTimes = mapOf(1L to nowT),
            exerciseMuscle = mapOf(99L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST),
            baselines = mapOf((1L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST) to 80f),
            currentCoefficients = mapOf(99L to 0f),
        )
        val h = EstCoefConsensusHeuristic()
        assertTrue(h.compute(input).isEmpty())
    }
}
