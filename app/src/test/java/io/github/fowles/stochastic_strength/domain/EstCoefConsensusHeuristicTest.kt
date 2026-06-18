package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun setSignal_tooHardWithActualReps_isHighConfidenceMeasured() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = 3, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 3)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.95f, s.confidence, 0.001f)
        assertFalse(s.isUpperBound)
    }

    @Test
    fun setSignal_tooHardWithoutActualReps_isUpperBound() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = null, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 7)
        assertEquals(expected, s.est1RM, 0.001f)
        assertEquals(0.5f, s.confidence, 0.001f)
        assertTrue(s.isUpperBound)
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

    @Test
    fun computeEstimate_singleSession_returnsEstimateWithNoGate() {
        // Under the old design a single 0.7-confidence session (weight 0.7 < 1.5)
        // returned null. There is no gate now — one session is usable.
        val h = EstCoefConsensusHeuristic()
        val est = h.computeEstimate(listOf(sessionSignal(1L, 1000L, 120.0f, 0.7f)))!!
        assertEquals(120.0f, est.est1RM, 0.001f)
        assertEquals(0.7f, est.confidence, 0.001f)
        assertTrue("weight should be positive", est.weight > 0f)
    }

    @Test
    fun applyPeerConsensus_proposalIsEstimateOverPeerMedianImpliedBaseline() {
        val h = EstCoefConsensusHeuristic()
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        // Three exercises, all E=100. Exercise 1 has coef 0.8.
        // For exercise 1: peers 2,3 both have coef 1.0 -> implied baseline 100 each.
        //   interpolated median(100, 100) = 100; proposal = 100/100 = 1.0.
        // For exercises 2,3: one peer is ex1 (coef 0.8 -> baseline 125), other is the peer (coef 1.0 -> baseline 100).
        //   interpolated median(100, 125) with equal weights = 112.5; proposal = 100/112.5 ≈ 0.8889.
        val estimates = mapOf(
            1L to estimate(est1RM = 100f),
            2L to estimate(est1RM = 100f),
            3L to estimate(est1RM = 100f),
        )
        val result = h.applyPeerConsensus(
            estimates,
            currentCoefficients = mapOf(1L to 0.8f, 2L to 1.0f, 3L to 1.0f),
            exerciseMuscle = mapOf(1L to muscle, 2L to muscle, 3L to muscle),
        )
        assertEquals(1.0f, result.getValue(1L).proposal, 0.001f)
        assertTrue(result.getValue(1L).metadata?.startsWith("peer_consensus") == true)
        assertEquals(100f / 112.5f, result.getValue(2L).proposal, 0.001f)
        assertEquals(100f / 112.5f, result.getValue(3L).proposal, 0.001f)
    }

    @Test
    fun applyPeerConsensus_fewerThanTwoPeers_emitsNothing() {
        val h = EstCoefConsensusHeuristic()
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        // Two exercises in the muscle: each has exactly one peer (< minPeers = 2).
        val estimates = mapOf(
            1L to estimate(est1RM = 100f),
            2L to estimate(est1RM = 120f),
        )
        val result = h.applyPeerConsensus(
            estimates,
            currentCoefficients = mapOf(1L to 1.0f, 2L to 1.0f),
            exerciseMuscle = mapOf(1L to muscle, 2L to muscle),
        )
        assertTrue("a 2-exercise muscle has <2 peers per exercise", result.isEmpty())
    }

    @Test
    fun compute_twoWrongCoefficientsBothMoveTowardTruth() {
        // Five CHEST exercises, identical sessions (same E). True coefficient is 1.0 for all.
        // Exercises 1 and 2 start wrong (0.8 and 1.25); 3,4,5 are correct (1.0).
        // Each exercise's peer median ignores one polluted peer, so both wrong ones
        // are pulled toward 1.0 while the correct ones do not move.
        val nowT = 100_000_000_000L
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        fun s(exerciseId: Long) = WorkoutSet(
            id = exerciseId, sessionId = exerciseId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = nowT,
        )
        val ids = listOf(1L, 2L, 3L, 4L, 5L)
        val input = CoefficientComputationInput(
            sets = ids.map { s(it) },
            sessionTimes = ids.associateWith { nowT },
            exerciseMuscle = ids.associateWith { muscle },
            baselines = emptyMap(),
            currentCoefficients = mapOf(1L to 0.8f, 2L to 1.25f, 3L to 1.0f, 4L to 1.0f, 5L to 1.0f),
        )
        val results = EstCoefConsensusHeuristic().compute(input).associateBy { it.exerciseId }

        // Exercise 1 (too low) moves up toward 1.0; exercise 2 (too high) moves down toward 1.0.
        val one = results.getValue(1L).coefficient
        val two = results.getValue(2L).coefficient
        assertTrue("ex1 should rise meaningfully from 0.8 toward 1.0, got $one", one in 0.81f..0.99f)
        assertTrue("ex2 should fall meaningfully from 1.25 toward 1.0, got $two", two in 1.01f..1.24f)
        // The three correct exercises sit at peer consensus and do not move.
        assertFalse(results.containsKey(3L))
        assertFalse(results.containsKey(4L))
        assertFalse(results.containsKey(5L))
    }

    @Test
    fun compute_systemicDriftProducesNoCoefficientMovement() {
        // Three CHEST exercises, all coef 1.0, all performing identically. Because every
        // implied baseline matches, each proposal equals the current coefficient -> no move.
        // This holds regardless of the absolute weight (i.e. a uniform strength shift is invisible).
        val nowT = 100_000_000_000L
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        fun run(weight: Float): List<CoefficientResult> {
            fun s(exerciseId: Long) = WorkoutSet(
                id = exerciseId, sessionId = exerciseId, exerciseId = exerciseId, setNumber = 1,
                targetWeight = weight, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = nowT,
            )
            val ids = listOf(1L, 2L, 3L)
            return EstCoefConsensusHeuristic().compute(
                CoefficientComputationInput(
                    sets = ids.map { s(it) },
                    sessionTimes = ids.associateWith { nowT },
                    exerciseMuscle = ids.associateWith { muscle },
                    baselines = emptyMap(),
                    currentCoefficients = ids.associateWith { 1.0f },
                )
            )
        }
        assertTrue("no movement at 80kg", run(80f).isEmpty())
        assertTrue("no movement at 120kg (uniform drift invisible)", run(120f).isEmpty())
    }

    @Test
    fun compute_atPeerConsensusEquilibrium_emitsNothing() {
        // Coefficients already reflect each exercise's relative strength: exercise 2 is
        // genuinely twice as strong as 1 and 3, and its session weight reflects that.
        // At equilibrium the pass proposes no change (so it cannot chase renormalization).
        val nowT = 100_000_000_000L
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        fun s(exerciseId: Long, weight: Float) = WorkoutSet(
            id = exerciseId, sessionId = exerciseId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = weight, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = nowT,
        )
        // Same feedback at proportional weights => E_2 = 2 * E_1 = 2 * E_3.
        // Implied baselines: E_1/1.0, E_2/2.0, E_3/1.0 all equal => every proposal == current.
        val sets = listOf(s(1L, 50f), s(2L, 100f), s(3L, 50f))
        val input = CoefficientComputationInput(
            sets = sets,
            sessionTimes = mapOf(1L to nowT, 2L to nowT, 3L to nowT),
            exerciseMuscle = mapOf(1L to muscle, 2L to muscle, 3L to muscle),
            baselines = emptyMap(),
            currentCoefficients = mapOf(1L to 1.0f, 2L to 2.0f, 3L to 1.0f),
        )
        // toOneRepMax is not exactly linear, so E_2 may differ slightly from 2*E_1.
        // Use minRelativeChange=0.0 to force the proposal to always be emitted so the
        // 1% bound is always exercised (List.all{} is vacuously true on empty).
        val current = mapOf(1L to 1.0f, 2L to 2.0f, 3L to 1.0f)
        val results = EstCoefConsensusHeuristic(minRelativeChange = 0.0f).compute(input)
        assertFalse("near-equilibrium still emits a proposal (toOneRepMax nonlinearity)", results.isEmpty())
        assertTrue(
            "every coefficient stays within 1% of its current value at equilibrium",
            results.all {
                kotlin.math.abs(it.coefficient - current.getValue(it.exerciseId)) < 0.01f * current.getValue(it.exerciseId)
            },
        )
    }

    @Test
    fun interpolatedWeightedMedian_singleValue_returnsThatValue() {
        assertEquals(42f, heuristic.interpolatedWeightedMedian(listOf(42f to 1f)), 0.0001f)
    }

    @Test
    fun interpolatedWeightedMedian_twoEqualWeights_returnsMidpoint() {
        // equal weights -> midpoint blend, not a hard pick of either
        assertEquals(110f, heuristic.interpolatedWeightedMedian(listOf(100f to 1f, 120f to 1f)), 0.0001f)
    }

    @Test
    fun interpolatedWeightedMedian_twoUnequalWeights_leansTowardHeavier() {
        // weights 0.3 (@100) and 0.5 (@130): total 0.8, target 0.4
        // midpoints p0=0.15, p1=0.30+0.25=0.55; t=(0.4-0.15)/(0.55-0.15)=0.625
        // value = 100 + 0.625*(130-100) = 118.75
        assertEquals(118.75f, heuristic.interpolatedWeightedMedian(listOf(100f to 0.3f, 130f to 0.5f)), 0.001f)
    }

    @Test
    fun interpolatedWeightedMedian_allEqualValues_returnsThatValue() {
        assertEquals(50f, heuristic.interpolatedWeightedMedian(listOf(50f to 1f, 50f to 2f, 50f to 0.5f)), 0.0001f)
    }

    @Test
    fun interpolatedWeightedMedian_isScaleEquivariant() {
        val pts = listOf(100f to 0.3f, 130f to 0.5f, 90f to 0.2f)
        val base = heuristic.interpolatedWeightedMedian(pts)
        val scaled = heuristic.interpolatedWeightedMedian(pts.map { (v, w) -> (v * 3f) to w })
        assertEquals(base * 3f, scaled, 0.001f)
    }

    @Test
    fun peerReference_twoPeers_usesInterpolatedBlendNotSelection() {
        val h = EstCoefConsensusHeuristic(minPeers = 2, minRelativeChange = 0.0f)
        // Muscle CHEST, exercises 1 (target), 2 and 3 (peers). One session each.
        // Peers imply different baselines via different weights/strengths so the
        // data-point median would hard-pick one; the interpolated median blends.
        val sets = listOf(
            // target exercise 1
            WorkoutSet(sessionId = 1L, exerciseId = 1L, setNumber = 1,
                targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
            // peer 2 — heavier evidence (measured failure => high confidence/weight)
            WorkoutSet(sessionId = 2L, exerciseId = 2L, setNumber = 1,
                targetWeight = 120f, targetReps = 5, actualReps = 5, feedback = SetFeedback.TOO_HARD),
            // peer 3 — lighter evidence (RIR_5_PLUS => low confidence/weight)
            WorkoutSet(sessionId = 3L, exerciseId = 3L, setNumber = 1,
                targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
        )
        val input = CoefficientComputationInput(
            sets = sets,
            sessionTimes = mapOf(1L to 0L, 2L to 0L, 3L to 0L),
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST, 3L to MuscleGroup.CHEST),
            baselines = emptyMap(),
            currentCoefficients = mapOf(1L to 1.0f, 2L to 1.0f, 3L to 1.0f),
        )
        val result = h.compute(input).firstOrNull { it.exerciseId == 1L }
        assertNotNull(result)
        // Reference is interpolatedWeightedMedian over peers 2 and 3 (both implied
        // baselines E_j/c_j with c_j = 1). Compute it directly and confirm the
        // proposal matches E_1 / thatReference (after damp from current 1.0).
        val e2 = DefaultProgressionEngine.toOneRepMax(120f, 5)   // peer 2 measured failure
        val w2 = 0.95f
        val e3 = DefaultProgressionEngine.toOneRepMax(80f, 5 + 7) // peer 3 RIR_5_PLUS
        val w3 = 0.4f
        val reference = h.interpolatedWeightedMedian(listOf(e2 to w2, e3 to w3))
        val e1 = DefaultProgressionEngine.toOneRepMax(100f, 5 + 1) // target RIR_0_1
        val proposal = e1 / reference
        // damp from current 1.0: step = alpha*conf*ln(proposal); conf = target session conf (0.85)
        val step = (0.2f * 0.85f * kotlin.math.ln(proposal.toDouble())).toFloat()
            .coerceIn(-kotlin.math.ln(1.05f), kotlin.math.ln(1.05f))
        val expected = 1.0f * kotlin.math.exp(step.toDouble()).toFloat()
        assertEquals(expected, result!!.coefficient, 0.0005f)
    }

    @Test
    fun peerSupportAttenuation_thinPeers_dampensMoveRelativeToNoAttenuation() {
        // Same scenario; target coefficient is wrong (0.7) so there is a move to make.
        fun run(attenuation: Float?): Float {
            val h = EstCoefConsensusHeuristic(minPeers = 2, minRelativeChange = 0.0f,
                peerSupportFullWeight = attenuation)
            val sets = listOf(
                WorkoutSet(sessionId = 1L, exerciseId = 1L, setNumber = 1,
                    targetWeight = 70f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
                WorkoutSet(sessionId = 2L, exerciseId = 2L, setNumber = 1,
                    targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
                WorkoutSet(sessionId = 3L, exerciseId = 3L, setNumber = 1,
                    targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
            )
            val input = CoefficientComputationInput(
                sets = sets,
                sessionTimes = mapOf(1L to 0L, 2L to 0L, 3L to 0L),
                exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST, 3L to MuscleGroup.CHEST),
                baselines = emptyMap(),
                currentCoefficients = mapOf(1L to 0.7f, 2L to 1.0f, 3L to 1.0f),
            )
            val r = input.let { h.compute(it) }.first { it.exerciseId == 1L }
            return kotlin.math.abs(r.coefficient - 0.7f)
        }
        val moveNoAtten = run(null)
        val moveAtten = run(100f) // threshold far above the thin peer weight (~0.8) -> heavy attenuation
        assertTrue("attenuated move should be smaller", moveAtten < moveNoAtten)
        assertTrue("attenuated move should be > 0", moveAtten > 0f)
    }
}
