package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        val baseline = 101f // 101 * 0.85 = 85.85 -> off the 2.5 kg grid (old code rounded to 85.0)
        val coefs = mapOf(1L to 1.0f)
        val in0 = input(1000, listOf(obs(1, baseline * 1.5f)), baseline, coefs)
            .copy(hurtMuscles = setOf(m))
        val out = controller().step(in0)
        val nb = out.baselineUpdates.single().newBaseline
        assertEquals("hurt backs off by exactly hurtFactor", baseline * 0.85f, nb, 1e-3f)
        assertNotEquals("hurt back-off must NOT snap to grid", WeightFormatter.round(nb, unit), nb)
        assertTrue("hurt suppresses coefficient moves", out.coefficientUpdates.isEmpty())
    }

    @Test
    fun progression_storesUnroundedBaseline() {
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f)
        // Single exercise reading 10% above prescription => common = ln(1.10), differential = 0.
        val o = listOf(obs(1, baseline * 1.0f * 1.10f))
        val out = controller().step(input(1000, o, baseline, coefs))
        val nb = out.baselineUpdates.single().newBaseline
        val expectedRaw = baseline * exp(0.5f * ln(1.10f)) // kB * common, unclamped
        assertEquals("baseline stored at full precision", expectedRaw, nb, 1e-3f)
        assertNotEquals(
            "must NOT snap to the weight grid",
            WeightFormatter.round(expectedRaw, unit),
            nb,
        )
    }

    @Test
    fun singleExerciseSession_conservesGauge_untouchedWhenNoRecentMeasurement() {
        val c = controller()
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 0.5f, 3L to 0.8f)
        // muscleExercises includes id3, which is NEVER observed (no recent measurement).
        fun inp(now: Long, observations: List<ProgressionObservation>) = ProgressionStepInput(
            now = now, observations = observations,
            baselines = mapOf(m to baseline), coefficients = coefs,
            muscleExercises = mapOf(m to listOf(1L, 2L, 3L)),
            hurtMuscles = emptySet(), weightUnit = unit,
        )
        // Session A: id1 and id2 measured on-target, establishing the pool. id3 never seen.
        c.step(inp(1000, listOf(obs(1, baseline * 1.0f), obs(2, baseline * 0.5f))))
        // Session B: only id1 trained, reads 10% high.
        val out = c.step(inp(2000, listOf(obs(1, baseline * 1.0f * 1.10f))))
        val byId = out.coefficientUpdates.associateBy { it.exerciseId }

        // Trained exercise corrects upward.
        assertTrue("id1 corrects up", byId.getValue(1).coefficient > 1.0f)
        // Pooled-but-untrained id2 moves the opposite way to conserve the gauge.
        assertTrue("id2 (pooled) moves down to conserve gauge", byId.getValue(2).coefficient < 0.5f)
        // id3 has no recent measurement -> not in the pool -> untouched.
        assertTrue("id3 (no measurement) untouched", 3L !in byId)

        // The differential conserves the geomean over the exercises that moved.
        val ratio = exp(
            (ln((byId.getValue(1).coefficient / 1.0f).toDouble()) +
                ln((byId.getValue(2).coefficient / 0.5f).toDouble())) / 2.0,
        )
        assertEquals("geomean conserved over the moved pair", 1.0, ratio, 1e-3)
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

    @Test
    fun bracketSnap_movesCoefFartherThanClampedPath_inOneSession() {
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 1.0f)
        val lowEst = baseline * 1.0f * 0.45f // id1 reads ~45% of prescription (a hard drop-cascade)

        fun run(bracket: Float): Float {
            val o = listOf(
                ProgressionObservation(1, m, lowEst, 0.95f, bracketConfidence = bracket),
                obs(2, baseline), // peer on-target
            )
            return controller().step(input(1000, o, baseline, coefs))
                .coefficientUpdates.single { it.exerciseId == 1L }.coefficient
        }

        val plainC1 = run(0f)
        val snapC1 = run(0.95f)

        assertTrue("no-bracket path is limited by the ~10% clamp", plainC1 > 0.88f)
        assertTrue("bracket snaps well past the 10% clamp", snapC1 < 0.80f)
        assertTrue("snap moves strictly further down than the clamped path", snapC1 < plainC1)
    }
}
