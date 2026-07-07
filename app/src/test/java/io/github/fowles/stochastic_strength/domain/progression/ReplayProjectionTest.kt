package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class ReplayProjectionTest {
    @Test
    fun projectionPreservesPrescriptionIdentity() {
        // level * derivedCoef == effectiveE1rm for every exercise -> display projection is internally consistent.
        val config = EstimatorConfig()
        val beliefs = mapOf(
            10L to ExerciseBelief(ln(100f), config.sigmaMin * config.sigmaMin, updatedAt = 0L),
            11L to ExerciseBelief(ln(58f), 0.05f * 0.05f, updatedAt = 0L),
            12L to ExerciseBelief.seed(40f, at = 0L, config = config),
        )
        val seed = mapOf(10L to 1.0f, 11L to 0.6f, 12L to 0.4f)
        val p = MuscleStrengthProjector().project(beliefs, seed, listOf(10L, 11L, 12L), now = 0L)
        for (id in listOf(10L, 11L, 12L)) {
            assertEquals(p.effectiveE1rm.getValue(id), p.level * p.derivedCoef.getValue(id), 1e-2f)
        }
    }
}
