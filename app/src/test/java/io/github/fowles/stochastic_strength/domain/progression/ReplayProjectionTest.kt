package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class ReplayProjectionTest {
    @Test
    fun projectionPreservesPrescriptionIdentity() {
        // level * derivedCoef == effectiveE1rm for every exercise -> display projection is internally consistent.
        val estimates = mapOf(
            10L to ExerciseEstimate(ln(100f), confidence = 6f, updatedAt = 0L),
            11L to ExerciseEstimate(ln(58f), confidence = 3f, updatedAt = 0L),
            12L to ExerciseEstimate(ln(40f), confidence = 0f, updatedAt = 0L),
        )
        val seed = mapOf(10L to 1.0f, 11L to 0.6f, 12L to 0.4f)
        val p = MuscleStrengthProjector().project(estimates, seed, listOf(10L, 11L, 12L), now = 0L)
        for (id in listOf(10L, 11L, 12L)) {
            assertEquals(p.effectiveE1rm.getValue(id), p.level * p.derivedCoef.getValue(id), 1e-2f)
        }
    }
}
