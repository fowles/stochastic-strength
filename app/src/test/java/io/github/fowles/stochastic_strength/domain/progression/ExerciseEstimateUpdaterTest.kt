package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class ExerciseEstimateUpdaterTest {

    private val updater = ExerciseEstimateUpdater()

    @Test
    fun seedHasZeroConfidenceAndExactE1rm() {
        val e = ExerciseEstimate.seed(100f, at = 0L)
        assertEquals(100f, e.e1rm, 1e-3f)
        assertEquals(0f, e.confidence, 0f)
    }

    @Test
    fun upSignalMovesEstimateGentlyTowardObservation() {
        val prior = ExerciseEstimate(lnE = ln(100f), confidence = 2f, updatedAt = 0L)
        // Observation above current estimate -> gentle up move.
        val next = updater.fold(prior, obsE1rm = 120f, bracketConfidence = 0f, now = 0L)
        assertTrue("should move up", next.e1rm > 100f)
        assertTrue("up move is gentle (well below the observation)", next.e1rm < 110f)
        assertTrue("confidence grows", next.confidence > prior.confidence)
    }

    @Test
    fun downSignalMovesEstimateFastTowardObservation() {
        val prior = ExerciseEstimate(lnE = ln(100f), confidence = 2f, updatedAt = 0L)
        // Observation below current estimate -> fast down move (asymmetric W).
        val next = updater.fold(prior, obsE1rm = 90f, bracketConfidence = 0f, now = 0L)
        assertTrue("down move tracks fast (past the midpoint)", next.e1rm < 95f)
    }

    @Test
    fun bracketConfidenceSnapsDownEvenHarder() {
        val prior = ExerciseEstimate(lnE = ln(100f), confidence = 5f, updatedAt = 0L)
        val soft = updater.fold(prior, obsE1rm = 90f, bracketConfidence = 0f, now = 0L)
        val snap = updater.fold(prior, obsE1rm = 90f, bracketConfidence = 1f, now = 0L)
        assertTrue("bracket snaps down further than a plain down signal", snap.e1rm < soft.e1rm)
    }

    @Test
    fun confidenceDecaysWithStaleness() {
        val prior = ExerciseEstimate(lnE = ln(100f), confidence = 4f, updatedAt = 0L)
        val halfLife = EstimatorConfig().halfLifeMs
        val decayed = updater.decayedConfidence(prior, now = halfLife)
        assertEquals(2f, decayed, 1e-2f)
    }

    @Test
    fun confidenceIsCappedSoLongTrainedExercisesStayAdaptive() {
        var e = ExerciseEstimate.seed(100f, at = 0L)
        repeat(50) { e = updater.fold(e, obsE1rm = 100f, bracketConfidence = 0f, now = 0L) }
        assertTrue("confidence capped", e.confidence <= EstimatorConfig().confidenceCap + 1e-3f)
    }
}
