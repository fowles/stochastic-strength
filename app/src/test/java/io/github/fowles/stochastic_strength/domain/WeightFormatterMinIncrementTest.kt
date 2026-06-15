package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class WeightFormatterMinIncrementTest {
    @Test
    fun minIncrement_kg_isHalfBarSmallestPlate() {
        assertEquals(2.5f, WeightFormatter.minIncrement(WeightUnit.KG), 0.0001f)
    }

    @Test
    fun minIncrement_lbs_is5lbInKg() {
        // 5 lb in kg = 5 / 2.20462
        assertEquals(5f / 2.20462f, WeightFormatter.minIncrement(WeightUnit.LBS), 0.0001f)
    }
}
