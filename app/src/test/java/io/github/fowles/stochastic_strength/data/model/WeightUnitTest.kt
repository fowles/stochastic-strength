package io.github.fowles.stochastic_strength.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WeightUnitTest {

    @Test fun kg_fromKg_isIdentity() =
        assertEquals(42f, WeightUnit.KG.fromKg(42f), 0.0001f)

    @Test fun kg_toKg_isIdentity() =
        assertEquals(42f, WeightUnit.KG.toKg(42f), 0.0001f)

    @Test fun lbs_fromKg_convertsCorrectly() =
        assertEquals(100f * 2.20462f, WeightUnit.LBS.fromKg(100f), 0.001f)

    @Test fun lbs_toKg_convertsCorrectly() =
        assertEquals(100f / 2.20462f, WeightUnit.LBS.toKg(100f), 0.001f)

    @Test fun lbs_roundTrip() {
        val kg = 82.5f
        assertEquals(kg, WeightUnit.LBS.toKg(WeightUnit.LBS.fromKg(kg)), 0.0001f)
    }
}
