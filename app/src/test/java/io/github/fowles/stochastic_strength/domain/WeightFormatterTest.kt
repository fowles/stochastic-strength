package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class WeightFormatterTest {

    private fun warmupKg(kg: Float) = WeightFormatter.roundForWarmup(kg, WeightUnit.KG)
    private fun warmupLbs(lbs: Float): Float {
        val kg = lbs / 2.20462f
        return WeightFormatter.roundForWarmup(kg, WeightUnit.LBS) * 2.20462f
    }

    // Snaps to nearest 10 kg when within 12%
    @Test fun kg_36_snapsTo40() = assertEquals(40f, warmupKg(36f))   // 11.1% from 40
    @Test fun kg_38_snapsTo40() = assertEquals(40f, warmupKg(38f))   // 5.3% from 40
    @Test fun kg_57_snapsTo60() = assertEquals(60f, warmupKg(57f))   // 5.3% from 60
    @Test fun kg_76_snapsTo80() = assertEquals(80f, warmupKg(76f))   // 5.3% from 80
    @Test fun kg_34_snapsTo30() = assertEquals(30f, warmupKg(34f))   // 11.8% from 30

    // Exact 10 kg multiples stay put
    @Test fun kg_40_stays40() = assertEquals(40f, warmupKg(40f))
    @Test fun kg_60_stays60() = assertEquals(60f, warmupKg(60f))
    @Test fun kg_80_stays80() = assertEquals(80f, warmupKg(80f))

    // >12% from nearest 10, falls back to 5 kg
    @Test fun kg_26_fallsTo25() = assertEquals(25f, warmupKg(26f))   // nearest 10 is 30 (+15.4%)
    @Test fun kg_24_fallsTo25() = assertEquals(25f, warmupKg(24f))   // nearest 10 is 20 (-16.7%)
    @Test fun kg_35_fallsTo35() = assertEquals(35f, warmupKg(35f))   // nearest 10 is 40 (+14.3%)

    // LBS: always snaps to nearest value ending in 5 (bar=45, so plate weights are 45,55,65,75...)
    @Test fun lbs_86_snapsTo85()   = assertEquals(85f,  warmupLbs(86f),  0.6f)
    @Test fun lbs_124_snapsTo125() = assertEquals(125f, warmupLbs(124f), 0.6f)
    @Test fun lbs_120_snapsTo125() = assertEquals(125f, warmupLbs(120f), 0.6f)
    @Test fun lbs_135_stays135()   = assertEquals(135f, warmupLbs(135f), 0.6f)
    @Test fun lbs_95_stays95()     = assertEquals(95f,  warmupLbs(95f),  0.6f)
    @Test fun lbs_54_snapsTo55()   = assertEquals(55f,  warmupLbs(54f),  0.6f)
}
