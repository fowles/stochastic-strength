package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import org.junit.Assert.assertEquals
import org.junit.Test

class EstimatorConfigTauTest {
    private val config = EstimatorConfig()

    @Test
    fun tauForMapsEachEquipmentClass() {
        assertEquals(0.08f, config.tauFor(Equipment.BARBELL), 0f)
        assertEquals(0.20f, config.tauFor(Equipment.MACHINE), 0f)
        assertEquals(0.20f, config.tauFor(Equipment.CABLE_MACHINE), 0f)
        assertEquals(0.25f, config.tauFor(Equipment.DUMBBELL), 0f)
        assertEquals(0.25f, config.tauFor(Equipment.KETTLEBELL), 0f)
        assertEquals(0.25f, config.tauFor(Equipment.BODYWEIGHT), 0f)
        assertEquals(0.25f, config.tauFor(Equipment.BAND), 0f)
        assertEquals(0.25f, config.tauFor(null), 0f)
    }
}
