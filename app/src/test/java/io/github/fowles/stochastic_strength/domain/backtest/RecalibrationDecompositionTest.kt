package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Single-parameter CV decomposition (Phase-5 follow-up, 2026-07-11). The recalibration report fits
 * the four params jointly, so it cannot say how much of the held-out CV gain each one carries. This
 * evaluates FIXED default candidates (only some params moved off default, to the proposed values
 * from the CV-optimal ×16 run) and reports each one's out-of-sample held-out-tail score delta vs the
 * all-default baseline. Main effects (one param at a time) + the entangled procNoise/τ pair + the
 * all-three joint + leave-one-out. Evidence, not a gate. No-ops without the personal fixture.
 */
class RecalibrationDecompositionTest {

    // Proposed multipliers from the CV-optimal cap ×16 run (2026-07-11).
    private val propDrift = 1.0        // no signal — held at default in every candidate
    private val propFatigue = 0.149
    private val propProcNoise = 16.0
    private val propTau = 2.616

    @Test
    fun singleParamCvDecomposition_onRealHistory() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!

        val user = RecalibrationHarness.UserHistory(data.history) { data.newSnapshot() }

        // name -> (fatigueMult, procNoiseMult, tauMult); drift stays at default (no signal).
        val candidates = linkedMapOf(
            "default"            to Triple(1.0,         1.0,           1.0),
            "fatigue-only"       to Triple(propFatigue, 1.0,           1.0),
            "procNoise-only-x4"  to Triple(1.0,         4.0,           1.0),
            "procNoise-only-x8"  to Triple(1.0,         8.0,           1.0),
            "procNoise-only"     to Triple(1.0,         propProcNoise, 1.0),
            "tau-only"           to Triple(1.0,         1.0,           propTau),
            "procNoise+tau"      to Triple(1.0,         propProcNoise, propTau),
            "all-three"          to Triple(propFatigue, propProcNoise, propTau),
            "all-minus-fatigue"  to Triple(1.0,         propProcNoise, propTau),
            "all-minus-procNoise" to Triple(propFatigue, 1.0,          propTau),
            "all-minus-tau"      to Triple(propFatigue, propProcNoise, 1.0),
        )

        val scores = candidates.mapValues { (_, m) ->
            val cfg = RecalibrationHarness.configWithMultipliers(
                drift = propDrift, fatigue = m.first, procNoise = m.second, tau = m.third,
            )
            RecalibrationHarness.heldOutTailScore(user, cfg)
        }
        val base = scores.getValue("default")

        val sb = StringBuilder()
        sb.appendLine("Phase-5 single-param CV decomposition (held-out tail, sessions 9..N)")
        sb.appendLine("baseline (all default) score = ${"%.3f".format(base)}")
        sb.appendLine("candidate              heldOut       delta-vs-default")
        for ((name, s) in scores) {
            sb.appendLine("%-21s %-13s %+.3f".format(name, "%.3f".format(s), s - base))
        }
        val text = sb.toString()
        println(text)
        File("build/recalibration-decomposition.txt").apply { parentFile?.mkdirs() }.writeText(text)

        // Sanity: the all-three candidate should beat the all-default baseline out-of-sample.
        assertTrue(scores.getValue("all-three") > base)
    }
}
