package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class ProgressionControllerTest {

    private val m = MuscleGroup.CHEST
    private val unit = WeightUnit.KG

    /** No min-change suppression / clamp interference for the pure-math properties. */
    private fun controller() = RollingConservingProgressionController(
        ProgressionControllerConfig(minRelativeChange = 0f),
    )

    private fun obs(id: Long, est1RM: Float, conf: Float = 0.85f) =
        ProgressionObservation(id, m, est1RM, conf)

    private fun input(
        now: Long, observations: List<ProgressionObservation>,
        baseline: Float, coefs: Map<Long, Float>,
    ) = ProgressionStepInput(
        now = now, observations = observations,
        baselines = mapOf(m to baseline), coefficients = coefs,
        muscleExercises = mapOf(m to coefs.keys.toList()),
        hurtMuscles = emptySet(), weightUnit = unit,
    )

    @Test
    fun allEasy_raisesBaseline_leavesCoefsFlat() {
        // Two exercises, identical innovation => common = that innovation, differential = 0.
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 0.5f)
        // est1RM 10% above prescription for both.
        val o = listOf(
            obs(1, baseline * 1.0f * 1.10f),
            obs(2, baseline * 0.5f * 1.10f),
        )
        val out = controller().step(input(1000, o, baseline, coefs))
        assertTrue("baseline should rise", out.baselineUpdates.single().newBaseline > baseline)
        assertTrue("coefficients should not move", out.coefficientUpdates.isEmpty())
    }

    @Test
    fun easyVsHard_sameAverage_baselineFlat_coefsDiverge() {
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 1.0f)
        // id1 reads 10% high, id2 reads 10% low => common ~ 0.
        val o = listOf(
            obs(1, baseline * 1.0f * 1.10f),
            obs(2, baseline * 1.0f * (1f / 1.10f)),
        )
        val out = controller().step(input(1000, o, baseline, coefs))
        assertTrue("baseline should be ~flat", out.baselineUpdates.isEmpty())
        val byId = out.coefficientUpdates.associateBy { it.exerciseId }
        assertTrue("id1 coef up", byId.getValue(1).coefficient > 1.0f)
        assertTrue("id2 coef down", byId.getValue(2).coefficient < 1.0f)
    }

    @Test
    fun differential_conservesGeomean_acrossSequence() {
        val c = controller()
        var coefs = mapOf(1L to 1.0f, 2L to 0.5f, 3L to 0.8f)
        val baseline = 100f
        val ids = coefs.keys
        var t = 0L
        repeat(5) { i ->
            t += 1000
            val o = ids.map { id ->
                // arbitrary, differing innovations each session
                obs(id, baseline * coefs.getValue(id) * (1f + 0.1f * ((id + i) % 3 - 1)))
            }
            val out = c.step(input(t, o, baseline, coefs))
            coefs = coefs.toMutableMap().apply {
                out.coefficientUpdates.forEach { this[it.exerciseId] = it.coefficient }
            }
        }
        val seed = mapOf(1L to 1.0f, 2L to 0.5f, 3L to 0.8f)
        val geomeanRatio = exp(ids.map { ln((coefs.getValue(it) / seed.getValue(it)).toDouble()) }.average())
        assertEquals("coefficient geomean must be conserved", 1.0, geomeanRatio, 1e-3)
    }

    @Test
    fun hurt_backsOffBaseline_andSkipsCoefficients() {
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f)
        val in0 = input(1000, listOf(obs(1, baseline * 1.5f)), baseline, coefs)
            .copy(hurtMuscles = setOf(m))
        val out = controller().step(in0)
        assertEquals(WeightFormatter.round(baseline * 0.85f, unit), out.baselineUpdates.single().newBaseline, 1e-3f)
        assertTrue("hurt suppresses coefficient moves", out.coefficientUpdates.isEmpty())
    }

    @Test
    fun singleExerciseSession_poolsRecentWindow_untrainedUntouched() {
        val c = controller()
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 0.5f)
        // Session A: both measured, establishing the pool.
        c.step(input(1000, listOf(obs(1, baseline * 1.0f), obs(2, baseline * 0.5f)), baseline, coefs))
        // Session B: only id1 trained, reads high. Pool still includes id2 (recent).
        val out = c.step(input(2000, listOf(obs(1, baseline * 1.0f * 1.10f)), baseline, coefs))
        val touched = out.coefficientUpdates.map { it.exerciseId }.toSet()
        assertTrue("trained exercise corrects", 1L in touched)
        // id2 carries near-zero recency-weighted differential vs id1; its move is negligible.
        val id2 = out.coefficientUpdates.firstOrNull { it.exerciseId == 2L }
        assertTrue("untrained barely moves", id2 == null || kotlin.math.abs(id2.coefficient - 0.5f) < 0.5f * 0.01f)
    }

    @Test
    fun midSetDrop_negativeInnovation_movesDownNeverUp() {
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 1.0f)
        // id1 failed (observed 1RM below prescription); id2 on-target.
        val o = listOf(obs(1, baseline * 1.0f * 0.85f, conf = 0.95f), obs(2, baseline * 1.0f))
        val out = controller().step(input(1000, o, baseline, coefs))
        val byId = out.coefficientUpdates.associateBy { it.exerciseId }
        assertTrue("failed exercise coef moves down", byId.getValue(1).coefficient < 1.0f)
        out.baselineUpdates.forEach {
            assertTrue("baseline never rises on a net-negative session", it.newBaseline <= baseline)
        }
    }
}
