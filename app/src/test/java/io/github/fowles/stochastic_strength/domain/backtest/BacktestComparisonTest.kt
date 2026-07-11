package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real-history regression gate (spec §9): replay the exported history through the current stack
 * and assert per-session prescriptions stay within a pinned band of a frozen reference, and never
 * go NaN. Skipped when the local fixture files are absent.
 *
 * REFERENCE RE-BASELINED 2026-07-09 to the phase-2 (belief-swap) output. Phase 2 is a deliberate
 * SYSTEMIC reprice, not an incremental change: vs the old phase-0 baseline it moved the median
 * prescription ~15% down (mean 18.7%, tail to 100%). That whole distribution was attributed and is
 * intended — the downward bulk is the newly activated z-shading (~−9.5% at cold σ_seed) + last-set
 * fatigue discount (−6.1%); the upward spikes are the belief estimator tracking one-sided strong
 * performances (RIR_5_PLUS) harder than the old EMA; and the 50–100% tail is grid quantization on
 * sub-8 kg lifts (4.5↔9.1 kg is one grid step). No NaN, no anomalies. Comparing phase-2 output to a
 * phase-0 baseline can only ever re-measure that intended reprice, so the baseline was re-frozen
 * here (user-approved deviation from the plan's "never regenerate" constraint) to restore a tight,
 * meaningful gate for phases 3–4. Phase 0 remains regenerable from commit 92205abd if ever needed.
 */
class BacktestComparisonTest {

    // RE-BASELINED 2026-07-10 (phase 3, reliability-weighted pooling): reference == current phase-3
    // output, so the observed worst delta is 0 again. The τ-pooling swap re-priced systemically UP
    // (median |Δ| 14%, mean 17%, worst 50%, 0 rows >50%, no NaN/anomalies) vs the phase-2 baseline —
    // attributed to real precision-weighted borrowing replacing the old near-zero kappa cap
    // (≈0.032 under the phase-2 formula); worst deltas are light-lift grid quantization (one 5-lb step).
    // BSS safety gate (ProdBssPrescriptionTest, 20 lb) holds. User approved the re-baseline after
    // attribution (same call as phase-2). BAND = 0.05 nominal headroom; phase 4 re-attributes vs this.
    private val BAND = 0.05f

    @Test
    fun policyPrescriptionsStayWithinBandOfFrozenBaselineAndNeverGoNaN() {
        val data = BacktestHarness.load()
        assumeTrue("no local backtest history; skipping", data != null)
        val baseline = BacktestHarness.readBaseline()
        assumeTrue("baseline not frozen; run BacktestBaselineGeneratorTest first", baseline != null)

        val current = BacktestHarness.replayPolicyPrescriptions(data!!)
        assertTrue("no prescriptions produced", current.isNotEmpty())
        current.forEach { assertFalse("NaN weight at $it", it.weightKg.isNaN()) }

        val baseByKey = baseline!!.associateBy { it.sessionId to it.exerciseId }
        var worst = 0f
        var worstDesc = ""
        val report = StringBuilder("session exercise old new delta\n")
        for (row in current) {
            val old = baseByKey[row.sessionId to row.exerciseId] ?: continue
            if (old.weightKg <= 0f) continue
            val rel = abs(row.weightKg - old.weightKg) / old.weightKg
            if (rel > 0.02f) {
                report.appendLine("${row.sessionId} ${row.exerciseId} ${old.weightKg} ${row.weightKg} ${(rel * 100).roundToInt()}%")
            }
            if (rel > worst) {
                worst = rel
                worstDesc = "session=${row.sessionId} exercise=${row.exerciseId} old=${old.weightKg} new=${row.weightKg}"
            }
        }
        println(report)
        println("worst relative delta: ${(worst * 100).roundToInt()}% ($worstDesc)")
        assertTrue("worst delta $worst ($worstDesc) exceeds band $BAND", worst <= BAND)
    }

    @Test fun fittedThetaIsInBoundsAndScoresAtLeastDefaults() {
        val data = BacktestHarness.load()
        assumeTrue("no local backtest history; skipping", data != null)
        val result = BacktestHarness.fitConfigFor(data!!)
        // In-bounds: every fitted parameter within ÷4..×4 of its default (spec §8).
        val d = EstimatorConfig(); val c = result.config
        fun within(f: Float, def: Float) = f in def * 0.25f..def * 4f
        assertTrue(within(c.fatiguePerSet, d.fatiguePerSet))
        assertTrue(within(c.processNoisePerDay, d.processNoisePerDay))
        assertTrue(within(c.detrainRatePerWeek, d.detrainRatePerWeek))
        assertTrue(within(c.tauBarbell, d.tauBarbell))
        assertTrue(within(c.repNoiseBucket, d.repNoiseBucket))
        // MAP: fitted never scores below defaults (else fallback fires).
        assertTrue(result.score >= result.defaultScore)
    }

    @Test fun fittedPrescriptionsWithinBandOfBaseline() {
        val data = BacktestHarness.load()
        assumeTrue("no local backtest history; skipping", data != null)
        val baseline = BacktestHarness.readBaseline()
        assumeTrue("baseline not frozen; run BacktestBaselineGeneratorTest first", baseline != null)
        val fitted = BacktestHarness.replayPolicyPrescriptions(data!!, BacktestHarness.fitConfigFor(data).config)
        val byKey = baseline!!.associateBy { it.sessionId to it.exerciseId }
        // Two valid configs can land on ADJACENT grid points for a lift sitting on a rounding boundary
        // (e.g. 5→10 lb on a light accessory) — quantization noise, not a systemic reprice. Skip rows
        // whose absolute change is within one grid increment; gate the systemic relative reprice on the
        // rest. (The near-no-op 4-param fit on real history moves nothing beyond a few such single-step
        // flips; this keeps the gate a real trip-wire for a FUTURE systemic reprice.)
        val gridStep = WeightFormatter.minIncrement(data.weightUnit)
        var maxRel = 0f
        var worstDesc = ""
        for (r in fitted) {
            val b = byKey[r.sessionId to r.exerciseId] ?: continue
            if (b.weightKg <= 0f) continue
            if (abs(r.weightKg - b.weightKg) <= gridStep + 1e-3f) continue
            val rel = abs(r.weightKg - b.weightKg) / b.weightKg
            if (rel > maxRel) { maxRel = rel; worstDesc = "session=${r.sessionId} exercise=${r.exerciseId} old=${b.weightKg} new=${r.weightKg}" }
        }
        println("BACKTEST fitted-vs-baseline (excl. single grid step) maxRel=${(maxRel * 100).roundToInt()}% ($worstDesc)")
        assertTrue("fitted systemic drift $maxRel ($worstDesc) > band $BAND — inspect before re-baselining", maxRel <= BAND)
    }
}
