package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class MuscleStrengthProjectorTest {

    private val projector = MuscleStrengthProjector()

    private fun est(e1rm: Float, conf: Float) = ExerciseEstimate(lnE = ln(e1rm), confidence = conf, updatedAt = 0L)

    @Test
    fun confidentExerciseUsesItsOwnEstimate() {
        val estimates = mapOf(1L to est(100f, conf = 6f), 2L to est(60f, conf = 6f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val p = projector.project(estimates, seed, muscleExerciseIds = listOf(1L, 2L), now = 0L)
        assertEquals("confident exercise prescribes its own estimate", 100f, p.effectiveE1rm.getValue(1L), 1f)
        assertEquals(60f, p.effectiveE1rm.getValue(2L), 1f)
    }

    @Test
    fun coldExerciseBorrowsFromConfidentSiblings() {
        // Exercise 2 is cold (conf 0); its sibling 1 is well trained at 100 with seed ratio 0.6.
        val estimates = mapOf(1L to est(100f, conf = 6f), 2L to est(40f, conf = 0f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val p = projector.project(estimates, seed, muscleExerciseIds = listOf(1L, 2L), now = 0L)
        // Sibling-implied target = L * seed_2 = (100/1.0) * 0.6 = 60, not the stale seed of 40.
        assertEquals("cold exercise pulled toward sibling prediction", 60f, p.effectiveE1rm.getValue(2L), 3f)
    }

    @Test
    fun levelTimesDerivedCoefReproducesEffectiveE1rm() {
        val estimates = mapOf(1L to est(100f, conf = 6f), 2L to est(55f, conf = 2f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val p = projector.project(estimates, seed, muscleExerciseIds = listOf(1L, 2L), now = 0L)
        for (id in listOf(1L, 2L)) {
            assertEquals(p.effectiveE1rm.getValue(id), p.level * p.derivedCoef.getValue(id), 1e-2f)
        }
    }

    @Test
    fun noConfidentSiblingsFallsBackToOwnSeedEstimate() {
        // Everything cold -> each exercise just uses its own seed estimate.
        val estimates = mapOf(1L to est(100f, conf = 0f), 2L to est(60f, conf = 0f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val p = projector.project(estimates, seed, muscleExerciseIds = listOf(1L, 2L), now = 0L)
        assertEquals(100f, p.effectiveE1rm.getValue(1L), 1e-2f)
        assertEquals(60f, p.effectiveE1rm.getValue(2L), 1e-2f)
        assertTrue("level is positive", p.level > 0f)
    }
}
