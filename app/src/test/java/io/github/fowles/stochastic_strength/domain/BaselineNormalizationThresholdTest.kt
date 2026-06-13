package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineNormalizationThresholdTest {

    @Test
    fun forUnit_kg_returns2() {
        assertEquals(2f, BaselineNormalizationThreshold.forUnit(WeightUnit.KG), 0f)
    }

    @Test
    fun forUnit_lb_returns5() {
        assertEquals(5f, BaselineNormalizationThreshold.forUnit(WeightUnit.LBS), 0f)
    }
}
