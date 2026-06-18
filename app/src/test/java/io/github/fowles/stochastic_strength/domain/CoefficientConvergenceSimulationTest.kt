package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Deterministic simulation harness for choosing coefficient-estimator parameters.
 * Not a behavioral assertion test (see Task 5 for the locked-value asserts) — it
 * drives the heuristic over synthetic sessions and prints sweep tables.
 */
class CoefficientConvergenceSimulationTest {

    private val muscle = MuscleGroup.CHEST
    private val baseline = 100f

    /** Reps achievable at [weight] given a true one-rep-max [true1RM], plus noise. */
    private fun achievableReps(weight: Float, true1RM: Float, noise: Double): Double {
        val denom = -2.55 + 4.58 * ln(weight.toDouble())
        val ratio = true1RM / weight - 1.0
        val raw = if (ratio <= 0.0 || denom <= 0.0) 1.0 else 1.0 + (ratio * denom).pow(1.0 / 0.85)
        return (raw + noise).coerceAtLeast(1.0)
    }

    private fun feedbackFor(weight: Float, repTarget: Int, true1RM: Float, noise: Double):
        Pair<SetFeedback, Int?> {
        val reps = achievableReps(weight, true1RM, noise)
        val rir = floor(reps).toInt() - repTarget
        return when {
            rir >= 5 -> SetFeedback.RIR_5_PLUS to null
            rir in 2..4 -> SetFeedback.RIR_2_4 to null
            rir in 0..1 -> SetFeedback.RIR_0_1 to null
            else -> SetFeedback.TOO_HARD to floor(reps).toInt().coerceAtLeast(1)
        }
    }

    data class SimResult(
        val worstConvSessions: Int,   // worst exercise's sessions-to-within-10%
        val avgJitterPct: Float,      // mean steady-state jitter across exercises (% of true)
        val maxStepPct: Float,        // largest single-session coefficient move (%)
        val avgEndErrorPct: Float,    // mean |c - c*| / c* at the end (%)
    )

    /**
     * @param trainPerSession null => train all exercises every session; else a random
     *   subset of that size (forces thin peer sets).
     */
    private fun simulate(
        heuristic: EstCoefConsensusHeuristic,
        trueCoefs: Map<Long, Float>,
        seedCoefs: Map<Long, Float>,
        convergenceSessions: Int,
        jitterTailSessions: Int,
        trainPerSession: Int?,
        sessionIntervalMs: Long,
        repNoiseStd: Double,
        seed: Long,
    ): SimResult {
        val rng = Random(seed)
        val gaussRng = java.util.Random(seed)
        val ids = trueCoefs.keys.sorted()
        val current = seedCoefs.toMutableMap()
        val allSets = mutableListOf<WorkoutSet>()
        val sessionTimes = mutableMapOf<Long, Long>()
        val exMuscle = ids.associateWith { muscle }
        val convAt = ids.associateWith { -1 }.toMutableMap()
        val tail = ids.associateWith { mutableListOf<Float>() }
        var maxStepPct = 0f
        var t = 0L

        val totalSessions = convergenceSessions + jitterTailSessions
        for (s in 0 until totalSessions) {
            t += sessionIntervalMs
            val sessionId = s.toLong()
            sessionTimes[sessionId] = t
            val inTail = s >= convergenceSessions
            val noiseStd = if (inTail) repNoiseStd else 0.0
            val repTarget = listOf(5, 8, 10).random(rng)

            val trained = if (trainPerSession == null) ids
            else ids.shuffled(rng).take(trainPerSession)

            for (id in trained) {
                val target1RM = baseline * (current[id] ?: 0f)
                val weight = DefaultProgressionEngine.fromOneRepMax(target1RM, repTarget)
                val true1RM = baseline * trueCoefs.getValue(id)
                val noise = if (noiseStd > 0.0) gaussRng.nextGaussian() * noiseStd else 0.0
                val (fb, ar) = feedbackFor(weight, repTarget, true1RM, noise)
                allSets.add(
                    WorkoutSet(
                        sessionId = sessionId, exerciseId = id, setNumber = 1,
                        targetWeight = weight, targetReps = repTarget,
                        actualReps = ar, feedback = fb,
                    )
                )
            }

            val input = CoefficientComputationInput(
                sets = allSets.toList(),
                sessionTimes = sessionTimes.toMap(),
                exerciseMuscle = exMuscle,
                baselines = emptyMap(),
                currentCoefficients = current.toMap(),
            )
            for (r in heuristic.compute(input)) {
                val old = current.getValue(r.exerciseId)
                if (old > 0f) {
                    val stepPct = abs(r.coefficient / old - 1f) * 100f
                    if (stepPct > maxStepPct) maxStepPct = stepPct
                }
                current[r.exerciseId] = r.coefficient
            }

            for (id in ids) {
                val cur = current.getValue(id)
                val tru = trueCoefs.getValue(id)
                if (convAt.getValue(id) < 0 && abs(cur - tru) / tru <= 0.10f) convAt[id] = s
                if (inTail) tail.getValue(id).add(cur)
            }
        }

        val worstConv = ids.maxOf { val c = convAt.getValue(it); if (c < 0) totalSessions else c }
        val jitter = ids.map { id ->
            val xs = tail.getValue(id)
            if (xs.size < 2) 0f else {
                val mean = xs.average().toFloat()
                val variance = xs.map { (it - mean) * (it - mean) }.average().toFloat()
                kotlin.math.sqrt(variance) / trueCoefs.getValue(id) * 100f
            }
        }.average().toFloat()
        val endErr = ids.map { abs(current.getValue(it) - trueCoefs.getValue(it)) / trueCoefs.getValue(it) }
            .average().toFloat() * 100f
        return SimResult(worstConv, jitter, maxStepPct, endErr)
    }

    // True coefficients and deliberately-wrong seeds (last one is 2x low).
    private val trueCoefs = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f, 4L to 0.4f, 5L to 0.30f)
    private val seedCoefs = mapOf(1L to 1.0f, 2L to 0.6f, 3L to 0.6f, 4L to 0.4f, 5L to 0.15f)

    private fun daysMs(days: Int): Long = days.toLong() * 24L * 60L * 60L * 1000L

    data class SweepRow(
        val scenario: String,
        val alpha: Float,
        val tauD: Int,
        val minRel: Float,
        val minPeers: Int,
        val atten: Float?,
        val result: SimResult,
    )

    private fun SimResult.metricsFinite(): Boolean =
        !avgJitterPct.isNaN() && !avgJitterPct.isInfinite() &&
            !maxStepPct.isNaN() && !maxStepPct.isInfinite() &&
            !avgEndErrorPct.isNaN() && !avgEndErrorPct.isInfinite()

    /** Damper sweep over alpha x tau x minRelChange (full peers). Writes the report and returns rows. */
    fun damperSweep(): List<SweepRow> {
        val rows = mutableListOf<SweepRow>()
        val sb = StringBuilder()
        sb.appendLine("# Coefficient damper sweep (full peers, seed err incl. 2x and 33% low)\n")
        sb.appendLine("| alpha | tau_d | minRelChg | worstConvSess | avgJitter% | maxStep% | endErr% |")
        sb.appendLine("|------:|------:|----------:|--------------:|-----------:|---------:|--------:|")
        for (alpha in listOf(0.2f, 0.3f, 0.4f)) {
            for (tauD in listOf(14, 21, 28)) {
                for (minRel in listOf(0.002f, 0.005f)) {
                    val h = EstCoefConsensusHeuristic(
                        alpha = alpha, tauHalfMs = daysMs(tauD),
                        minRelativeChange = minRel, minPeers = 3,
                    )
                    val r = simulate(
                        heuristic = h, trueCoefs = trueCoefs, seedCoefs = seedCoefs,
                        convergenceSessions = 60, jitterTailSessions = 40,
                        trainPerSession = null, sessionIntervalMs = daysMs(3),
                        repNoiseStd = 1.0, seed = 42L,
                    )
                    rows.add(SweepRow("damper", alpha, tauD, minRel, 3, null, r))
                    sb.appendLine("| $alpha | $tauD | $minRel | ${r.worstConvSessions} | " +
                        "%.2f | %.2f | %.2f |".format(r.avgJitterPct, r.maxStepPct, r.avgEndErrorPct))
                }
            }
        }
        writeReport(sb.toString(), append = false)
        println(sb.toString())
        return rows
    }

    /** Thin-peer robustness sweep over minPeers x attenuation (train 2/5). Writes report, returns rows. */
    fun thinPeerSweep(): List<SweepRow> {
        val rows = mutableListOf<SweepRow>()
        val sb = StringBuilder()
        sb.appendLine("\n# Thin-peer robustness sweep (train 2/5 per session)\n")
        sb.appendLine("| minPeers | attenFullW | worstConvSess | avgJitter% | maxStep% | endErr% |")
        sb.appendLine("|---------:|-----------:|--------------:|-----------:|---------:|--------:|")
        for (minPeers in listOf(2, 3)) {
            for (atten in listOf<Float?>(null, 2.0f)) {
                val h = EstCoefConsensusHeuristic(
                    alpha = 0.3f, tauHalfMs = daysMs(21),
                    minRelativeChange = 0.003f, minPeers = minPeers,
                    peerSupportFullWeight = atten,
                )
                val r = simulate(
                    heuristic = h, trueCoefs = trueCoefs, seedCoefs = seedCoefs,
                    convergenceSessions = 120, jitterTailSessions = 80,
                    trainPerSession = 2, sessionIntervalMs = daysMs(3),
                    repNoiseStd = 1.0, seed = 7L,
                )
                rows.add(SweepRow("thin", 0.3f, 21, 0.003f, minPeers, atten, r))
                sb.appendLine("| $minPeers | ${atten ?: "off"} | ${r.worstConvSessions} | " +
                    "%.2f | %.2f | %.2f |".format(r.avgJitterPct, r.maxStepPct, r.avgEndErrorPct))
            }
        }
        writeReport(sb.toString(), append = true)
        println(sb.toString())
        return rows
    }

    @Test
    fun damperSweep_producesFiniteMetrics() {
        val rows = damperSweep()
        assertTrue("damper sweep produced no rows", rows.isNotEmpty())
        rows.forEach {
            assertTrue("non-finite metric in $it", it.result.metricsFinite())
            assertTrue("conv beyond horizon in $it", it.result.worstConvSessions in 0..100)
        }
        // Task 5 adds the locked chosen-row bound assertions here.
    }

    @Test
    fun thinPeerSweep_producesFiniteMetrics() {
        val rows = thinPeerSweep()
        assertTrue("thin-peer sweep produced no rows", rows.isNotEmpty())
        rows.forEach {
            assertTrue("non-finite metric in $it", it.result.metricsFinite())
            assertTrue("conv beyond horizon in $it", it.result.worstConvSessions in 0..200)
        }
        // Task 5 adds the locked chosen-row bound assertions here.
    }

    /** Cap exploration: alpha x maxLogStep (full peers; tau=21d, minRel=0.002, minPeers=3). */
    fun capExplorationSweep(): List<SweepRow> {
        val rows = mutableListOf<SweepRow>()
        val sb = StringBuilder()
        sb.appendLine("\n# Cap exploration (full peers; tau=21d, minRel=0.002, minPeers=3)\n")
        sb.appendLine("| alpha | cap | worstConvSess | avgJitter% | maxStep% | endErr% |")
        sb.appendLine("|------:|----:|--------------:|-----------:|---------:|--------:|")
        for (alpha in listOf(0.3f, 0.4f, 0.5f, 0.6f)) {
            for (capPct in listOf(5, 10)) {
                val cap = kotlin.math.ln(1f + capPct / 100f)
                val h = EstCoefConsensusHeuristic(
                    alpha = alpha, tauHalfMs = daysMs(21),
                    minRelativeChange = 0.002f, minPeers = 3,
                    maxLogStep = cap,
                )
                val r = simulate(
                    heuristic = h, trueCoefs = trueCoefs, seedCoefs = seedCoefs,
                    convergenceSessions = 60, jitterTailSessions = 40,
                    trainPerSession = null, sessionIntervalMs = daysMs(3),
                    repNoiseStd = 1.0, seed = 42L,
                )
                rows.add(SweepRow("cap", alpha, 21, 0.002f, 3, null, r))
                sb.appendLine("| $alpha | $capPct% | ${r.worstConvSessions} | " +
                    "%.2f | %.2f | %.2f |".format(r.avgJitterPct, r.maxStepPct, r.avgEndErrorPct))
            }
        }
        writeReport(sb.toString(), append = true)
        println(sb.toString())
        return rows
    }

    @Test
    fun capExploration_producesFiniteMetrics() {
        val rows = capExplorationSweep()
        assertTrue("cap exploration produced no rows", rows.isNotEmpty())
        rows.forEach {
            assertTrue("non-finite metric in $it", it.result.metricsFinite())
            assertTrue("conv beyond horizon in $it", it.result.worstConvSessions in 0..100)
        }
    }

    private fun writeReport(text: String, append: Boolean) {
        val f = File("build/reports/coefficient-sweep.md")
        f.parentFile?.mkdirs()
        if (append) f.appendText(text) else f.writeText(text)
        println("Sweep written to: ${f.absolutePath}")
    }
}
