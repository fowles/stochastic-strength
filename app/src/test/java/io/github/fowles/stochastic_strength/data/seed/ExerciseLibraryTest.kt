package io.github.fowles.stochastic_strength.data.seed

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.CoefficientCompression
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseLibraryTest {
    private fun byName(name: String) =
        ExerciseLibrary.exercises.firstOrNull { it.name == name }

    @Test
    fun `T-Bar Row is asymmetric barbell`() {
        val e = byName("T-Bar Row")
        assertNotNull(e)
        assertEquals(Equipment.BARBELL, e!!.equipment)
        assertTrue(e.isAsymmetric)
    }

    @Test
    fun `Landmine Press seeded as asymmetric barbell shoulders`() {
        val e = byName("Landmine Press")
        assertNotNull(e)
        assertEquals(Equipment.BARBELL, e!!.equipment)
        assertEquals(MuscleGroup.SHOULDERS, e.primaryMuscle)
        assertTrue(e.isAsymmetric)
        assertTrue(!e.isUnilateral)
    }

    @Test
    fun `Landmine Press has a coefficient`() {
        val e = byName("Landmine Press")!!
        assertEquals(
            CoefficientCompression.compress(0.5f, CoefficientCompression.BAKED_LAMBDA),
            ExerciseCoefficients.get(e)!!,
            1e-6f,
        )
    }
}
