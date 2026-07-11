package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class VarianceStudyConfigsTest {
    private val base = EstimatorConfig()

    @Test fun obsNoiseScalesAllNoiseBases() {
        val c = VarianceStudyConfigs.withObsNoise(base, 2.0)
        assertEquals(base.repNoiseBucket * 2f, c.repNoiseBucket, 1e-7f)
        assertEquals(base.repNoiseCounted * 2f, c.repNoiseCounted, 1e-7f)
        assertEquals(base.repNoiseRel * 2f, c.repNoiseRel, 1e-7f)
        assertEquals(base.obsModelSd * 2f, c.obsModelSd, 1e-7f)
    }

    @Test fun tauScalesAllThreeClasses() {
        val c = VarianceStudyConfigs.withTau(base, 0.5)
        assertEquals(base.tauBarbell * 0.5f, c.tauBarbell, 1e-7f)
        assertEquals(base.tauMachineCable * 0.5f, c.tauMachineCable, 1e-7f)
        assertEquals(base.tauOtherLoaded * 0.5f, c.tauOtherLoaded, 1e-7f)
    }

    @Test fun unitMultiplierReproducesDefault() {
        assertEquals(base, VarianceStudyConfigs.withObsNoise(base, 1.0))
        assertEquals(base, VarianceStudyConfigs.withTau(base, 1.0))
        assertEquals(base, VarianceStudyConfigs.withAnchorPrecision(base, 1.0))
        assertEquals(base, VarianceStudyConfigs.withProcNoise(base, 1.0))
    }
}
