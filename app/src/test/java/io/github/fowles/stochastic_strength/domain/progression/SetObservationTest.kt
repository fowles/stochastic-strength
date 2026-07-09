package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

class SetObservationTest {
    private val config = EstimatorConfig()
    private fun set(feedback: SetFeedback?, weight: Float = 60f, reps: Int = 10, actual: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = weight, targetReps = reps, actualReps = actual, feedback = feedback)

    private fun capLn(w: Float, reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))

    @Test
    fun rirBucketsMapToTheSpecIntervals() {
        val r01 = SetObservation.from(set(SetFeedback.RIR_0_1), fatigueRank = 1, config = config)!!
        assertEquals(capLn(60f, 10f), r01.lowerLn!!, 1e-5f)
        assertEquals(capLn(60f, 12f), r01.upperLn!!, 1e-5f)
        assertNull(r01.gaussianLn)

        val r24 = SetObservation.from(set(SetFeedback.RIR_2_4), 1, config)!!
        assertEquals(capLn(60f, 12f), r24.lowerLn!!, 1e-5f)
        assertEquals(capLn(60f, 15f), r24.upperLn!!, 1e-5f)

        val r5 = SetObservation.from(set(SetFeedback.RIR_5_PLUS), 1, config)!!
        assertEquals(capLn(60f, 15f), r5.lowerLn!!, 1e-5f)
        assertNull(r5.upperLn)
    }

    @Test
    fun countedFailureIsATightGaussianAtHalfRep() {
        val obs = SetObservation.from(set(SetFeedback.TOO_HARD, actual = 6), 1, config)!!
        assertEquals(capLn(60f, 6.5f), obs.gaussianLn!!, 1e-5f)
        assertNull(obs.lowerLn); assertNull(obs.upperLn)
        val repSd = SetObservation.repSlope(60f, 10) *
            sqrt(config.repNoiseCounted * config.repNoiseCounted + (config.repNoiseRel * 10) * (config.repNoiseRel * 10))
        val expected = sqrt(repSd * repSd + config.obsModelSd * config.obsModelSd)
        assertEquals(expected, obs.noiseSd, 1e-6f)
    }

    @Test
    fun uncountedFailureIsOneSidedFromAbove() {
        val obs = SetObservation.from(set(SetFeedback.TOO_HARD), 1, config)!!
        assertNull(obs.lowerLn); assertNull(obs.gaussianLn)
        assertEquals(capLn(60f, 10f), obs.upperLn!!, 1e-5f)
    }

    @Test
    fun fatigueRankShiftsObservationsUpToTheFreshBasis() {
        val fresh = SetObservation.from(set(SetFeedback.RIR_0_1), fatigueRank = 1, config = config)!!
        val third = SetObservation.from(set(SetFeedback.RIR_0_1), fatigueRank = 3, config = config)!!
        val shift = -ln(1f - config.fatiguePerSet * 2f)
        assertEquals(fresh.lowerLn!! + shift, third.lowerLn!!, 1e-5f)
        assertEquals(fresh.upperLn!! + shift, third.upperLn!!, 1e-5f)
        assertTrue(shift > 0f)
    }

    @Test
    fun hurtMissingFeedbackAndZeroWeightCarryNoObservation() {
        assertNull(SetObservation.from(set(SetFeedback.HURT), 1, config))
        assertNull(SetObservation.from(set(null), 1, config))
        assertNull(SetObservation.from(set(SetFeedback.RIR_0_1, weight = 0f), 1, config))
    }

    @Test
    fun noiseIsLargerAtLightAbsoluteLoads() {
        // λ = ∂ln f/∂ρ is steeper for light weights: accessory-lift noisiness emerges (spec §2).
        assertTrue(SetObservation.repSlope(20f, 10) > SetObservation.repSlope(100f, 10))
        assertTrue(SetObservation.repSlope(60f, 10) > 0f)
    }
}
