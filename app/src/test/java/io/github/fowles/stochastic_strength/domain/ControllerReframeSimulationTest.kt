package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Broadened A/B harness comparing two ways of driving the (baseline, coefficient) factorization:
 *
 *  - "current": the production three-component stack —
 *        LastSetAutoregulationHeuristic (baseline) + EstCoefConsensusHeuristic (coefficients)
 *        + SeedNormalizer (gauge fixing).
 *  - "pi": a single CommonDiffPiController — one common/differential-mode PI loop per muscle,
 *        with NO separate normalizer (the differential mode conserves the coefficient geomean,
 *        so the gauge is pinned for free).
 *
 * Each muscle is an independent instance of the rank-1 factorization, so "several muscles" is
 * modelled as several [Profile]s with different exercise counts and seed-error structures. Every
 * config is averaged over multiple RNG seeds, run under both full and thin (subset) training, and
 * under both a static and a genuinely-strengthening lifter.
 *
 * Each profile carries a never-trained held-out exercise whose seed coefficient equals its true
 * coefficient — so its cold-start prescription error isolates baseline GAUGE drift, the thing the
 * normalizer exists to prevent.
 *
 * Exploration only (prints tables, asserts finiteness). Not a behavioral lock.
 */
class ControllerReframeSimulationTest {

    private val muscle = MuscleGroup.CHEST
    private val unit = WeightUnit.KG

    // ---- profiles (stand-ins for muscles with different seed-error structures) -------------------

    data class Profile(
        val name: String,
        val trueCoefs: Map<Long, Float>,
        val seedCoefs: Map<Long, Float>,
        val heldOutId: Long, // never trained; seed == true, so its cold-start error == pure gauge drift
    ) {
        val allIds = trueCoefs.keys.sorted()
        val trainedIds = (trueCoefs.keys - heldOutId).sorted()
    }

    /** Mixed errors: id5 2x low, id2 25% low, rest accurate. (the original scenario) */
    private val mixed = Profile(
        name = "mixed",
        trueCoefs = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f, 4L to 0.4f, 5L to 0.30f, 6L to 0.50f),
        seedCoefs = mapOf(1L to 1.0f, 2L to 0.6f, 3L to 0.6f, 4L to 0.4f, 5L to 0.15f, 6L to 0.50f),
        heldOutId = 6L,
    )

    /** Systematic bias: every trained seed is 1.25x its true value (shape correct, scale wrong).
     *  Pure common-mode gauge stress — the case the normalizer was built for. */
    private val systematic = Profile(
        name = "systematic",
        trueCoefs = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f, 4L to 0.4f, 5L to 0.30f, 6L to 0.50f),
        seedCoefs = mapOf(1L to 1.25f, 2L to 1.0f, 3L to 0.75f, 4L to 0.5f, 5L to 0.375f, 6L to 0.50f),
        heldOutId = 6L,
    )

    /** One badly-wrong seed (id3 2x low) amid otherwise-accurate seeds. */
    private val outlier = Profile(
        name = "outlier",
        trueCoefs = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f, 4L to 0.4f, 5L to 0.30f, 6L to 0.50f),
        seedCoefs = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.3f, 4L to 0.4f, 5L to 0.30f, 6L to 0.50f),
        heldOutId = 6L,
    )

    /** Systematic bias that ALSO includes the held-out (every seed 1.25x true, consistently).
     *  Realistic uniform bias — the gauge is internally consistent, so PI should serve it perfectly. */
    private val systematicConsistent = Profile(
        name = "systematic+",
        trueCoefs = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f, 4L to 0.4f, 5L to 0.30f, 6L to 0.50f),
        seedCoefs = mapOf(1L to 1.25f, 2L to 1.0f, 3L to 0.75f, 4L to 0.5f, 5L to 0.375f, 6L to 0.625f),
        heldOutId = 6L,
    )

    /** Small muscle: 3 trained exercises (peer sets at the minPeers=2 limit). */
    private val small = Profile(
        name = "small",
        trueCoefs = mapOf(1L to 1.0f, 2L to 0.7f, 3L to 0.5f, 4L to 0.60f),
        seedCoefs = mapOf(1L to 1.0f, 2L to 0.5f, 3L to 0.5f, 4L to 0.60f),
        heldOutId = 4L,
    )

    private fun daysMs(days: Int): Long = days.toLong() * 24L * 60L * 60L * 1000L

    // ---- synthetic lifter -----------------------------------------------------------------------

    private fun achievableReps(weight: Float, true1RM: Float, noise: Double): Double {
        val denom = -2.55 + 4.58 * ln(weight.toDouble())
        val ratio = true1RM / weight - 1.0
        val raw = if (ratio <= 0.0 || denom <= 0.0) 1.0 else 1.0 + (ratio * denom).pow(1.0 / 0.85)
        return (raw + noise).coerceAtLeast(1.0)
    }

    private fun feedbackFor(weight: Float, repTarget: Int, true1RM: Float, noise: Double): Pair<SetFeedback, Int?> {
        val reps = achievableReps(weight, true1RM, noise)
        val rir = floor(reps).toInt() - repTarget
        return when {
            rir >= 5 -> SetFeedback.RIR_5_PLUS to null
            rir in 2..4 -> SetFeedback.RIR_2_4 to null
            rir in 0..1 -> SetFeedback.RIR_0_1 to null
            else -> SetFeedback.TOO_HARD to floor(reps).toInt().coerceAtLeast(1)
        }
    }

    // ---- prototype: common / differential-mode PI controller ------------------------------------

    /**
     * One PI loop per muscle. Per session, for each trained exercise it forms the log innovation
     * e_i = ln(observed1RM_i / (baseline * coef_i)), low-pass filters it (EMA), then splits:
     *   common mode  ē  = confidence-weighted mean → drives the BASELINE (with an integral term)
     *   differential d_i = (e_i - ē)               → drives that exercise's COEFFICIENT
     * The differential update is mean-zero, so Σ log(coef) over the trained subset is conserved
     * every session → the coefficient geomean (hence the gauge) is pinned with no normalizer.
     */
    /**
     * @param seedAnchorGain optional soft gauge anchor. 0 = none (pure geomean-conserving PI).
     *   When >0, after the PI step each trained coefficient is nudged toward its SEED VALUE and the
     *   baseline absorbs the common part (a gauge move that keeps the average prescription invariant).
     *   This is the only anchor that can move the absolute scale, because plain PI already conserves
     *   geomean(coef)=geomean(seed) — a geomean-only anchor would be inert.
     */
    class CommonDiffPiController(
        private val unit: WeightUnit,
        private val kB: Float,
        private val kC: Float,
        private val kBi: Float,
        private val integralLeak: Float,
        private val integralClamp: Float,
        private val emaBeta: Float,
        private val maxLogStepB: Float,
        private val maxLogStepC: Float,
        private val seedCoefs: Map<Long, Float> = emptyMap(),
        private val seedAnchorGain: Float = 0f,
    ) {
        data class Obs(val exerciseId: Long, val est1RM: Float, val confidence: Float)

        private val emaE = mutableMapOf<Long, Float>()
        private var iBaseline = 0f

        /** Mutates [coefs] in place; returns the new baseline. */
        fun step(baseline: Float, coefs: MutableMap<Long, Float>, obs: List<Obs>): Float {
            if (baseline <= 0f) return baseline
            val filt = obs.mapNotNull { o ->
                val c = coefs[o.exerciseId] ?: return@mapNotNull null
                if (c <= 0f || o.est1RM <= 0f) return@mapNotNull null
                val e = ln(o.est1RM / (baseline * c))
                val prev = emaE[o.exerciseId] ?: e
                val f = (1f - emaBeta) * prev + emaBeta * e
                emaE[o.exerciseId] = f
                Triple(o.exerciseId, f, o.confidence)
            }
            if (filt.isEmpty()) return baseline
            val wsum = filt.sumOf { it.third.toDouble() }.toFloat()
            val common = if (wsum > 0f) filt.sumOf { (it.second * it.third).toDouble() }.toFloat() / wsum else 0f

            iBaseline = (iBaseline * integralLeak + kBi * common).coerceIn(-integralClamp, integralClamp)
            val dLogB = (kB * common + iBaseline).coerceIn(-maxLogStepB, maxLogStepB)
            var newBaseline = baseline * exp(dLogB)

            for ((id, f, _) in filt) {
                val d = f - common
                val dLogC = (kC * d).coerceIn(-maxLogStepC, maxLogStepC)
                coefs[id] = coefs.getValue(id) * exp(dLogC)
            }

            if (seedAnchorGain > 0f) {
                val ids = emaE.keys.filter { seedCoefs.containsKey(it) && (coefs[it] ?: 0f) > 0f }
                if (ids.isNotEmpty()) {
                    val deltas = ids.associateWith { ln(seedCoefs.getValue(it) / coefs.getValue(it)) }
                    val meanDelta = deltas.values.average().toFloat()
                    for (id in ids) coefs[id] = coefs.getValue(id) * exp(seedAnchorGain * deltas.getValue(id))
                    newBaseline *= exp(-seedAnchorGain * meanDelta)
                }
            }
            return WeightFormatter.round(newBaseline, unit)
        }
    }

    // ---- joint simulation -----------------------------------------------------------------------

    private class State(var baseline: Float, val coefs: MutableMap<Long, Float>)

    data class Metrics(
        val convSessions: Int,       // sessions until all trained prescriptions within 10% of true
        val endPrescribedErr: Float, // mean trained |prescribed - true| / true (%), tail mean
        val tailJitter: Float,       // mean trained std(prescribed/true) over tail (%)
        val coldStartErr: Float,     // held-out (never-trained) prescription error (%), tail mean — GAUGE drift
        val maxStepB: Float,         // largest single-session baseline move (%)
        val maxStepC: Float,         // largest single-session coefficient move (%)
    )

    private fun metricsFinite(m: Metrics) = listOf(
        m.endPrescribedErr, m.tailJitter, m.coldStartErr, m.maxStepB, m.maxStepC,
    ).none { it.isNaN() || it.isInfinite() }

    private fun simulate(
        profile: Profile,
        trainPerSession: Int?, // null => train all
        seedBaseline: Float,
        trueBaselineAt: (Int) -> Float,
        sessions: Int,
        tailSessions: Int,
        repNoiseStd: Double,
        seed: Long,
        stackStep: (State, Long, Map<Long, Long>, List<WorkoutSet>, List<WorkoutSet>) -> Unit,
    ): Metrics {
        val rng = Random(seed)
        val gauss = java.util.Random(seed)
        val state = State(seedBaseline, profile.seedCoefs.toMutableMap())
        val allSets = mutableListOf<WorkoutSet>()
        val sessionTimes = mutableMapOf<Long, Long>()
        var t = 0L
        var convAt = -1
        var maxStepB = 0f
        var maxStepC = 0f
        val tailPrescribed = profile.trainedIds.associateWith { mutableListOf<Float>() }
        val tailColdStart = mutableListOf<Float>()
        val tailPrescribedErr = mutableListOf<Float>()

        val total = sessions + tailSessions
        for (s in 0 until total) {
            t += daysMs(3)
            val sessionId = s.toLong()
            sessionTimes[sessionId] = t
            val trueB = trueBaselineAt(s)
            val repTarget = listOf(5, 8, 10).random(rng)

            val trained = if (trainPerSession == null) profile.trainedIds
            else profile.trainedIds.shuffled(rng).take(trainPerSession)

            val thisSessionSets = trained.map { id ->
                val weight = DefaultProgressionEngine.fromOneRepMax(state.baseline * state.coefs.getValue(id), repTarget)
                val true1RM = trueB * profile.trueCoefs.getValue(id)
                val noise = gauss.nextGaussian() * repNoiseStd
                val (fb, ar) = feedbackFor(weight, repTarget, true1RM, noise)
                WorkoutSet(
                    sessionId = sessionId, exerciseId = id, setNumber = 1,
                    targetWeight = weight, targetReps = repTarget, actualReps = ar, feedback = fb,
                )
            }
            allSets.addAll(thisSessionSets)

            val bBefore = state.baseline
            val coefBefore = state.coefs.toMap()
            stackStep(state, sessionId, sessionTimes.toMap(), allSets.toList(), thisSessionSets)

            if (bBefore > 0f) maxStepB = maxOf(maxStepB, abs(state.baseline / bBefore - 1f) * 100f)
            for (id in trained) {
                val old = coefBefore.getValue(id)
                if (old > 0f) maxStepC = maxOf(maxStepC, abs(state.coefs.getValue(id) / old - 1f) * 100f)
            }

            val worstErr = profile.trainedIds.maxOf { id ->
                val prescribed = state.baseline * state.coefs.getValue(id)
                abs(prescribed - trueB * profile.trueCoefs.getValue(id)) / (trueB * profile.trueCoefs.getValue(id))
            }
            if (convAt < 0 && worstErr <= 0.10f) convAt = s

            if (s >= sessions) {
                for (id in profile.trainedIds) {
                    tailPrescribed.getValue(id).add(state.baseline * state.coefs.getValue(id) / (trueB * profile.trueCoefs.getValue(id)))
                }
                val ho = profile.heldOutId
                tailColdStart.add(
                    abs(state.baseline * profile.seedCoefs.getValue(ho) - trueB * profile.trueCoefs.getValue(ho)) /
                        (trueB * profile.trueCoefs.getValue(ho)) * 100f,
                )
                tailPrescribedErr.add(
                    profile.trainedIds.map { id ->
                        abs(state.baseline * state.coefs.getValue(id) - trueB * profile.trueCoefs.getValue(id)) /
                            (trueB * profile.trueCoefs.getValue(id))
                    }.average().toFloat() * 100f,
                )
            }
        }

        val jitter = profile.trainedIds.map { id ->
            val xs = tailPrescribed.getValue(id)
            if (xs.size < 2) 0f else {
                val mean = xs.average().toFloat()
                kotlin.math.sqrt(xs.map { (it - mean) * (it - mean) }.average().toFloat()) * 100f
            }
        }.average().toFloat()

        return Metrics(
            convSessions = if (convAt < 0) total else convAt,
            endPrescribedErr = tailPrescribedErr.average().toFloat(),
            tailJitter = jitter,
            coldStartErr = tailColdStart.average().toFloat(),
            maxStepB = maxStepB,
            maxStepC = maxStepC,
        )
    }

    // ---- stack wiring ---------------------------------------------------------------------------

    private val baselineHeuristic = LastSetAutoregulationHeuristic()
    private fun coefHeuristic() = EstCoefConsensusHeuristic(
        alpha = 0.6f, tauHalfMs = daysMs(21), minRelativeChange = 0.002f,
        minPeers = 2, maxLogStep = ln(1.10f),
    )
    private val normalizer = SeedNormalizer()
    private val signalExtractor = EstCoefConsensusHeuristic()

    private fun currentStackStep(profile: Profile): (State, Long, Map<Long, Long>, List<WorkoutSet>, List<WorkoutSet>) -> Unit {
        val coef = coefHeuristic()
        val exMuscle = profile.allIds.associateWith { muscle }
        val exerciseById = profile.allIds.associateWith {
            Exercise(id = it, name = "ex$it", primaryMuscle = muscle, equipment = Equipment.BARBELL)
        }
        val threshold = BaselineNormalizationThreshold.forUnit(unit)
        return { state, _, sessionTimes, allSets, thisSessionSets ->
            // 1. baseline controller (this session only, as the repo does)
            val bInput = BaselineComputationInput(
                sets = thisSessionSets,
                exerciseMuscle = exMuscle,
                currentCoefficients = state.coefs.toMap(),
                currentBaselines = mapOf(muscle to state.baseline),
                recentHistory = emptyMap(),
                sessionReps = thisSessionSets.first().targetReps,
                minReductionFractions = emptyMap(),
                asOf = sessionTimes.values.max(),
                weightUnit = unit,
            )
            baselineHeuristic.compute(bInput).forEach { state.baseline = it.newBaseline }

            // 2. coefficient estimator (full history)
            val cInput = CoefficientComputationInput(
                sets = allSets, sessionTimes = sessionTimes, exerciseMuscle = exMuscle,
                baselines = emptyMap(), currentCoefficients = state.coefs.toMap(),
            )
            coef.compute(cInput).forEach { state.coefs[it.exerciseId] = it.coefficient }

            // 3. gauge normalizer
            val nInput = BaselineNormalizationInput(
                sets = allSets,
                exercises = profile.allIds.map {
                    ExerciseCoefficientSnapshot(exerciseById.getValue(it), profile.seedCoefs.getValue(it), state.coefs.getValue(it))
                },
                baselines = mapOf(muscle to state.baseline),
            )
            normalizer.compute(nInput).forEach { p ->
                if (p.scale <= 0f) return@forEach
                val newB = WeightFormatter.round(state.baseline / p.scale, unit)
                if (newB <= 0f || abs(newB - state.baseline) < threshold) return@forEach
                val mEff = state.baseline / newB
                state.baseline = newB
                for (id in profile.trainedIds) if (state.coefs.getValue(id) > 0f) state.coefs[id] = state.coefs.getValue(id) * mEff
            }
        }
    }

    private fun piStackStep(pi: CommonDiffPiController): (State, Long, Map<Long, Long>, List<WorkoutSet>, List<WorkoutSet>) -> Unit =
        { state, _, _, _, thisSessionSets ->
            val obs = thisSessionSets.groupBy { it.exerciseId }.mapNotNull { (id, sets) ->
                signalExtractor.aggregateSession(sets)?.let {
                    CommonDiffPiController.Obs(id, it.est1RM, it.sessionConfidence)
                }
            }
            state.baseline = pi.step(state.baseline, state.coefs, obs)
        }

    private fun newPi(
        kB: Float = 0.5f,
        kC: Float = 0.5f,
        withIntegral: Boolean = false,
        seedCoefs: Map<Long, Float> = emptyMap(),
        seedAnchorGain: Float = 0f,
    ) = CommonDiffPiController(
        unit = unit, kB = kB, kC = kC,
        kBi = if (withIntegral) 0.10f else 0f,
        integralLeak = 0.85f, integralClamp = ln(1.06f),
        emaBeta = 0.5f, maxLogStepB = ln(1.15f), maxLogStepC = ln(1.10f),
        seedCoefs = seedCoefs, seedAnchorGain = seedAnchorGain,
    )

    // ---- multi-seed averaging -------------------------------------------------------------------

    private val seeds = (1L..8L).map { it * 101L }

    private fun mean(rows: List<Metrics>) = Metrics(
        convSessions = rows.map { it.convSessions }.average().toInt(),
        endPrescribedErr = rows.map { it.endPrescribedErr }.average().toFloat(),
        tailJitter = rows.map { it.tailJitter }.average().toFloat(),
        coldStartErr = rows.map { it.coldStartErr }.average().toFloat(),
        maxStepB = rows.map { it.maxStepB }.average().toFloat(),
        maxStepC = rows.map { it.maxStepC }.average().toFloat(),
    )

    private fun std(rows: List<Metrics>, sel: (Metrics) -> Float): Float {
        val xs = rows.map { sel(it) }
        val m = xs.average()
        return kotlin.math.sqrt(xs.map { (it - m) * (it - m) }.average()).toFloat()
    }

    private data class Stack(val label: String, val build: (Profile) -> (State, Long, Map<Long, Long>, List<WorkoutSet>, List<WorkoutSet>) -> Unit)

    private val stacks = listOf(
        Stack("current") { currentStackStep(it) },
        Stack("pi (P)") { piStackStep(newPi()) },
        Stack("pi (anchor)") { p -> piStackStep(newPi(seedCoefs = p.seedCoefs, seedAnchorGain = 0.10f)) },
    )

    private fun runAvg(
        profile: Profile,
        stack: Stack,
        trainPerSession: Int?,
        seedBaseline: Float,
        trueBaselineAt: (Int) -> Float,
        sessions: Int,
        tailSessions: Int,
    ): List<Metrics> = seeds.map { seed ->
        simulate(
            profile = profile, trainPerSession = trainPerSession, seedBaseline = seedBaseline,
            trueBaselineAt = trueBaselineAt, sessions = sessions, tailSessions = tailSessions,
            repNoiseStd = 1.0, seed = seed, stackStep = stack.build(profile),
        )
    }

    // ---- the broadened A/B ----------------------------------------------------------------------

    @Test
    fun reframe_abComparison_broadened() {
        val sb = StringBuilder()
        sb.appendLine("# Controller reframe A/B (broadened): current 3-component stack vs common/diff PI\n")
        sb.appendLine("Each cell is the mean over ${seeds.size} RNG seeds. Metrics: convSess=sessions to all-trained-within-10%;")
        sb.appendLine("endErr%=tail mean trained prescribed-weight error; jitter%=tail std of prescribed/true;")
        sb.appendLine("coldStart%=held-out (never-trained) prescription error = GAUGE drift (±std);")
        sb.appendLine("stepB%/stepC%=largest single-session baseline / coefficient move.\n")

        val allMetrics = mutableListOf<Metrics>()

        fun section(title: String, profiles: List<Profile>, trainPerSession: Int?, trueBaselineAt: (Int) -> Float, seedBaseline: (Profile) -> Float, sessions: Int, tail: Int) {
            sb.appendLine("## $title\n")
            sb.appendLine("| profile | stack | convSess | endErr% | jitter% | coldStart% | stepB% | stepC% |")
            sb.appendLine("|---------|-------|---------:|--------:|--------:|-----------:|-------:|-------:|")
            for (profile in profiles) {
                for (stack in stacks) {
                    val rows = runAvg(profile, stack, trainPerSession, seedBaseline(profile), trueBaselineAt, sessions, tail)
                    rows.forEach { assertTrue("non-finite metric in ${profile.name}/${stack.label}: $it", metricsFinite(it)) }
                    allMetrics.addAll(rows)
                    val m = mean(rows)
                    sb.appendLine(
                        "| ${profile.name} | ${stack.label} | ${m.convSessions} | %.2f | %.2f | %.2f±%.1f | %.2f | %.2f |".format(
                            m.endPrescribedErr, m.tailJitter, m.coldStartErr, std(rows) { it.coldStartErr }, m.maxStepB, m.maxStepC,
                        ),
                    )
                }
            }
            sb.appendLine()
        }

        val allProfiles = listOf(mixed, systematic, systematicConsistent, outlier, small)

        // 1. Static lifter, full training. Baseline seeded 20% low to force both loops to climb.
        section(
            "Static lifter, full training (baseline seeded 20% low)",
            allProfiles, trainPerSession = null, trueBaselineAt = { 100f }, seedBaseline = { 80f },
            sessions = 100, tail = 30,
        )

        // 2. Thin-peer: only 2 exercises train per session. Tests graceful degradation without minPeers guards.
        section(
            "Thin training (2 exercises/session), static",
            allProfiles, trainPerSession = 2, trueBaselineAt = { 100f }, seedBaseline = { 80f },
            sessions = 200, tail = 40,
        )

        // 3. Rising lifter: true baseline climbs 100 -> ~130 over the run. Tests tracking / integral.
        section(
            "Rising lifter (baseline 100->130), full training",
            allProfiles, trainPerSession = null, trueBaselineAt = { 100f + 30f * (it / 130f) }, seedBaseline = { 100f },
            sessions = 100, tail = 30,
        )

        val report = sb.toString()
        val f = File("build/reports/controller-reframe-ab.md")
        f.parentFile?.mkdirs()
        f.writeText(report)
        println(report)
        println("Report written to: ${f.absolutePath}")
    }

    // =============================================================================================
    // Realistic variant: real exercise library + real planner (bands removed, all else enabled),
    // multiple muscle groups, perturbed coefficients with outliers, and mid-set weight drops.
    // =============================================================================================

    /** Multi-muscle version of the common/diff P controller (one split per muscle, no normalizer). */
    class MultiMusclePiController(
        private val unit: WeightUnit,
        private val kB: Float = 0.5f,
        private val kC: Float = 0.5f,
        private val emaBeta: Float = 0.5f,
        private val maxLogStepB: Float = ln(1.15f),
        private val maxLogStepC: Float = ln(1.10f),
    ) {
        data class Obs(val exerciseId: Long, val muscle: MuscleGroup, val est1RM: Float, val confidence: Float)

        private val emaE = mutableMapOf<Long, Float>()

        fun step(baselines: MutableMap<MuscleGroup, Float>, coefs: MutableMap<Long, Float>, obs: List<Obs>) {
            for ((m, ms) in obs.groupBy { it.muscle }) {
                val b = baselines[m] ?: continue
                if (b <= 0f) continue
                val filt = ms.mapNotNull { o ->
                    val c = coefs[o.exerciseId] ?: return@mapNotNull null
                    if (c <= 0f || o.est1RM <= 0f) return@mapNotNull null
                    val e = ln(o.est1RM / (b * c))
                    val prev = emaE[o.exerciseId] ?: e
                    val f = (1f - emaBeta) * prev + emaBeta * e
                    emaE[o.exerciseId] = f
                    Triple(o.exerciseId, f, o.confidence)
                }
                if (filt.isEmpty()) continue
                val wsum = filt.sumOf { it.third.toDouble() }.toFloat()
                val common = if (wsum > 0f) filt.sumOf { (it.second * it.third).toDouble() }.toFloat() / wsum else 0f
                baselines[m] = WeightFormatter.round(b * exp((kB * common).coerceIn(-maxLogStepB, maxLogStepB)), unit)
                for ((id, f, _) in filt) {
                    coefs[id] = coefs.getValue(id) * exp((kC * (f - common)).coerceIn(-maxLogStepC, maxLogStepC))
                }
            }
        }
    }

    /**
     * Rolling-window variant: the common/differential split is taken over every loaded exercise in
     * the muscle that has a recent (recency-weighted) measurement, not just this session's. Per
     * exercise it keeps a recency-decayed EMA of log(est1RM). On a muscle trained this session the
     * baseline moves from the pooled common mode; coefficients move only for exercises trained this
     * session, but against that pooled reference — so a lone-exercise session still yields a real
     * differential. Recovers the cross-session pooling that EstCoefConsensus does.
     */
    class RollingWindowPiController(
        private val unit: WeightUnit,
        private val kB: Float = 0.5f,
        private val kC: Float = 0.5f,
        private val emaBeta: Float = 0.5f,
        private val halfLifeMs: Long = 21L * 24 * 60 * 60 * 1000,
        private val maxLogStepB: Float = ln(1.15f),
        private val maxLogStepC: Float = ln(1.10f),
    ) {
        data class Obs(val exerciseId: Long, val muscle: MuscleGroup, val est1RM: Float, val confidence: Float)

        private val emaLogEst = mutableMapOf<Long, Float>()
        private val lastConf = mutableMapOf<Long, Float>()
        private val lastTime = mutableMapOf<Long, Long>()
        private val ln2 = ln(2.0)

        fun step(
            now: Long,
            baselines: MutableMap<MuscleGroup, Float>,
            coefs: MutableMap<Long, Float>,
            obs: List<Obs>,
            muscleExercises: Map<MuscleGroup, List<Long>>,
        ) {
            val trainedNow = obs.map { it.exerciseId }.toSet()
            for (o in obs) {
                if (o.est1RM <= 0f) continue
                val le = ln(o.est1RM)
                emaLogEst[o.exerciseId] = emaLogEst[o.exerciseId]?.let { (1f - emaBeta) * it + emaBeta * le } ?: le
                lastConf[o.exerciseId] = o.confidence
                lastTime[o.exerciseId] = now
            }
            for (m in obs.map { it.muscle }.toSet()) {
                val b = baselines[m] ?: continue
                if (b <= 0f) continue
                val pooled = muscleExercises[m].orEmpty().mapNotNull { id ->
                    val le = emaLogEst[id] ?: return@mapNotNull null
                    val c = coefs[id] ?: return@mapNotNull null
                    if (c <= 0f) return@mapNotNull null
                    val age = (now - (lastTime[id] ?: now)).coerceAtLeast(0L)
                    val w = exp(-age * ln2 / halfLifeMs).toFloat() * (lastConf[id] ?: 0f)
                    if (w <= 1e-6f) return@mapNotNull null
                    Triple(id, le - ln(b * c), w)
                }
                if (pooled.isEmpty()) continue
                val wsum = pooled.sumOf { it.third.toDouble() }.toFloat()
                val common = if (wsum > 0f) pooled.sumOf { (it.second * it.third).toDouble() }.toFloat() / wsum else 0f
                baselines[m] = WeightFormatter.round(b * exp((kB * common).coerceIn(-maxLogStepB, maxLogStepB)), unit)
                for ((id, e, _) in pooled) {
                    if (id !in trainedNow) continue
                    coefs[id] = coefs.getValue(id) * exp((kC * (e - common)).coerceIn(-maxLogStepC, maxLogStepC))
                }
            }
        }
    }

    /**
     * Gauge-conserving pooled variant. Same rolling pool as RollingWindowPiController, but the
     * differential is applied to ALL pooled exercises (not just this session's), each scaled by its
     * own recency×confidence weight. Because `Σ w_i·(e_i − common_w) = 0` by the definition of the
     * weighted mean, the (pre-clamp) coefficient log-updates sum to zero → geomean conserved → no
     * gauge creep, while the baseline still moves on the pooled (smoothed) common mode.
     */
    class RollingConservingPiController(
        private val unit: WeightUnit,
        private val kB: Float = 0.5f,
        private val kC: Float = 0.5f,
        private val emaBeta: Float = 0.5f,
        private val halfLifeMs: Long = 21L * 24 * 60 * 60 * 1000,
        private val maxLogStepB: Float = ln(1.15f),
        private val maxLogStepC: Float = ln(1.10f),
    ) {
        data class Obs(val exerciseId: Long, val muscle: MuscleGroup, val est1RM: Float, val confidence: Float)

        private val emaLogEst = mutableMapOf<Long, Float>()
        private val lastConf = mutableMapOf<Long, Float>()
        private val lastTime = mutableMapOf<Long, Long>()
        private val ln2 = ln(2.0)

        fun step(
            now: Long,
            baselines: MutableMap<MuscleGroup, Float>,
            coefs: MutableMap<Long, Float>,
            obs: List<Obs>,
            muscleExercises: Map<MuscleGroup, List<Long>>,
        ) {
            for (o in obs) {
                if (o.est1RM <= 0f) continue
                val le = ln(o.est1RM)
                emaLogEst[o.exerciseId] = emaLogEst[o.exerciseId]?.let { (1f - emaBeta) * it + emaBeta * le } ?: le
                lastConf[o.exerciseId] = o.confidence
                lastTime[o.exerciseId] = now
            }
            for (m in obs.map { it.muscle }.toSet()) {
                val b = baselines[m] ?: continue
                if (b <= 0f) continue
                val pooled = muscleExercises[m].orEmpty().mapNotNull { id ->
                    val le = emaLogEst[id] ?: return@mapNotNull null
                    val c = coefs[id] ?: return@mapNotNull null
                    if (c <= 0f) return@mapNotNull null
                    val age = (now - (lastTime[id] ?: now)).coerceAtLeast(0L)
                    val w = exp(-age * ln2 / halfLifeMs).toFloat() * (lastConf[id] ?: 0f)
                    if (w <= 1e-6f) return@mapNotNull null
                    Triple(id, le - ln(b * c), w)
                }
                if (pooled.isEmpty()) continue
                val wsum = pooled.sumOf { it.third.toDouble() }.toFloat()
                val common = if (wsum > 0f) pooled.sumOf { (it.second * it.third).toDouble() }.toFloat() / wsum else 0f
                baselines[m] = WeightFormatter.round(b * exp((kB * common).coerceIn(-maxLogStepB, maxLogStepB)), unit)
                val maxW = pooled.maxOf { it.third }
                for ((id, e, w) in pooled) {
                    val gain = w / maxW // freshest gets full K_c; staler proportionally less. Preserves sum-zero.
                    coefs[id] = coefs.getValue(id) * exp((kC * gain * (e - common)).coerceIn(-maxLogStepC, maxLogStepC))
                }
            }
        }
    }

    /** Reference 1RM-ish true baseline per muscle (kg), keyed on the muscle's reference lift. */
    private val trueBaselines = mapOf(
        MuscleGroup.CHEST to 80f, MuscleGroup.BACK to 85f, MuscleGroup.SHOULDERS to 50f,
        MuscleGroup.BICEPS to 35f, MuscleGroup.TRICEPS to 45f, MuscleGroup.QUADS to 130f,
        MuscleGroup.HAMSTRINGS to 120f, MuscleGroup.GLUTES to 110f, MuscleGroup.CALVES to 80f,
        MuscleGroup.CORE to 50f,
    )

    private enum class RStack { CURRENT, PI, PI_ROLLING, PI_ROLLING_CONS }

    data class RMetrics(
        val convSessions: Int,    // sessions until mean error over ever-trained exercises <= 10%
        val trainedEndErr: Float, // tail mean prescribed error over well-trained (>=3 sessions) exercises (%)
        val allEndErr: Float,     // tail mean prescribed error over ALL loaded library exercises (%) — gauge-sensitive
        val jitter: Float,        // tail std of prescribed/true over well-trained exercises (%)
        val pctTrained: Float,    // fraction of loaded exercises trained >= 3 times
        val avgReductions: Float, // mean mid-set weight-drop events per session
        val coefInflation: Float, // geomean(coef/seedCoef) over loaded — 1.0 = no gauge creep; >1 = coefficients ratcheting up
        val baselineGaugeErr: Float, // mean over muscles |baseline/trueBaseline - 1| (%) — cold-start error for a seed-accurate new exercise
    )

    private fun simulateRealistic(
        seedBaselineFactor: Float,
        stack: RStack,
        seed: Long,
        sessions: Int,
        tail: Int,
        growthPerSession: Float = 0f, // true baseline compounds at this rate/session (strengthening lifter)
    ): RMetrics {
        val rng = Random(seed)
        val gauss = java.util.Random(seed)
        val truthRng = java.util.Random(seed xor 0x9E3779B9L)
        val repNoiseStd = 1.0

        // Real library with assigned ids, bands removed, all other equipment kept (incl. bodyweight).
        val library = ExerciseLibrary.exercises
            .mapIndexed { i, e -> e.copy(id = (i + 1).toLong()) }
            .filter { it.equipment != Equipment.BAND }
        val seedCoef = library.associate { it.id to (ExerciseCoefficients.byName[it.name] ?: 0f) }
        val loaded = library.filter { seedCoef.getValue(it.id) > 0f }
        val exMuscle = library.associate { it.id to it.primaryMuscle }
        val exerciseById = library.associateBy { it.id }
        val musclesLoaded = loaded.map { it.primaryMuscle }.toSet()

        // True coefficients: seed slightly off (lognormal ~8%), with a few big outliers.
        val outlierFactors = listOf(1.5f, 0.6f, 1.4f, 0.65f)
        val outlierIds = loaded.map { it.id }.shuffled(Random(seed * 7 + 3)).take(outlierFactors.size).toSet()
        var oi = 0
        val trueCoef = loaded.associate { ex ->
            var f = exp(truthRng.nextGaussian() * 0.08).toFloat()
            if (ex.id in outlierIds) f *= outlierFactors[oi++ % outlierFactors.size]
            ex.id to seedCoef.getValue(ex.id) * f
        }
        val trueBaseline = trueBaselines.filterKeys { it in musclesLoaded }

        val baselines = trueBaseline.mapValues { it.value * seedBaselineFactor }.toMutableMap()
        val coefs = seedCoef.toMutableMap()
        val allSets = mutableListOf<WorkoutSet>()
        val sessionTimes = mutableMapOf<Long, Long>()
        val trainCount = mutableMapOf<Long, Int>()
        var t = 0L
        var convAt = -1
        var reductionEvents = 0

        val coef = coefHeuristic()
        val pi = MultiMusclePiController(unit)
        val rolling = RollingWindowPiController(unit)
        val conserving = RollingConservingPiController(unit)
        val muscleExercises = loaded.groupBy { it.primaryMuscle }.mapValues { e -> e.value.map { it.id } }
        val threshold = BaselineNormalizationThreshold.forUnit(unit)

        val tailRatio = loaded.associate { it.id to mutableListOf<Float>() }
        val tailTrainedErr = mutableListOf<Float>()
        val tailAllErr = mutableListOf<Float>()

        fun gMulAt(s: Int): Float = Math.pow(1.0 + growthPerSession, s.toDouble()).toFloat()
        fun errOf(id: Long, gMul: Float): Float {
            val m = exMuscle.getValue(id)
            val true1RM = trueBaseline.getValue(m) * gMul * trueCoef.getValue(id)
            return abs(baselines.getValue(m) * coefs.getValue(id) - true1RM) / true1RM
        }

        val total = sessions + tail
        for (s in 0 until total) {
            t += daysMs(3)
            val sid = s.toLong()
            sessionTimes[sid] = t
            val gMul = gMulAt(s)
            val reps = listOf(5, 8, 10).random(rng)

            // Real planner selection (bands already removed), capped at 5 exercises this workout.
            val selected = WorkoutGenerator.generate(WorkoutGenerator.Input(library, rng)).take(5)

            val thisSessionSets = mutableListOf<WorkoutSet>()
            val reductions = mutableMapOf<Long, Float>()
            for (pe in selected) {
                val ex = pe.exercise
                val c = coefs[ex.id] ?: 0f
                if (c <= 0f) continue // bodyweight / unloadable: in the workout but no load signal
                val m = ex.primaryMuscle
                val b = baselines[m] ?: continue
                val w0 = WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(b * c, reps), unit)
                if (w0 <= 0f) continue
                val true1RM = trueBaseline.getValue(m) * gMul * trueCoef.getValue(ex.id)
                var w = w0
                for (setNum in 1..PlannedExercise.DEFAULT_SETS) {
                    val noise = gauss.nextGaussian() * repNoiseStd
                    val (fb, ar) = feedbackFor(w, reps, true1RM, noise)
                    thisSessionSets.add(
                        WorkoutSet(
                            sessionId = sid, exerciseId = ex.id, setNumber = setNum,
                            targetWeight = w, targetReps = reps, actualReps = ar, feedback = fb,
                        ),
                    )
                    // Mid-set drop: a failed set reduces the weight for the remaining sets.
                    if (fb == SetFeedback.TOO_HARD && setNum < PlannedExercise.DEFAULT_SETS) {
                        w = maxOf(0.5f, WeightFormatter.round(
                            DefaultProgressionEngine.scaleReps(w, from = maxOf(1, ar ?: 1), to = reps), unit,
                        ))
                    }
                }
                if (w < w0) {
                    reductions[ex.id] = (w0 - w) / w0
                    reductionEvents++
                }
                trainCount.merge(ex.id, 1, Int::plus)
            }
            allSets.addAll(thisSessionSets)

            when (stack) {
                RStack.CURRENT -> {
                    val minRed = reductions.entries.groupBy { exMuscle.getValue(it.key) }
                        .mapValues { e -> e.value.maxOf { it.value } }.filterValues { it > 0f }
                    val bInput = BaselineComputationInput(
                        sets = thisSessionSets, exerciseMuscle = exMuscle,
                        currentCoefficients = coefs.toMap(), currentBaselines = baselines.toMap(),
                        recentHistory = emptyMap(), sessionReps = reps,
                        minReductionFractions = minRed, asOf = t, weightUnit = unit,
                    )
                    baselineHeuristic.compute(bInput).forEach { baselines[it.muscleGroup] = it.newBaseline }
                    val cInput = CoefficientComputationInput(
                        sets = allSets, sessionTimes = sessionTimes, exerciseMuscle = exMuscle,
                        baselines = emptyMap(), currentCoefficients = coefs.toMap(),
                    )
                    coef.compute(cInput).forEach { coefs[it.exerciseId] = it.coefficient }
                    val nInput = BaselineNormalizationInput(
                        sets = allSets,
                        exercises = loaded.map {
                            ExerciseCoefficientSnapshot(exerciseById.getValue(it.id), seedCoef.getValue(it.id), coefs.getValue(it.id))
                        },
                        baselines = baselines.toMap(),
                    )
                    normalizer.compute(nInput).forEach { p ->
                        val old = baselines[p.muscleGroup] ?: return@forEach
                        if (p.scale <= 0f) return@forEach
                        val newB = WeightFormatter.round(old / p.scale, unit)
                        if (newB <= 0f || abs(newB - old) < threshold) return@forEach
                        val mEff = old / newB
                        baselines[p.muscleGroup] = newB
                        loaded.filter { it.primaryMuscle == p.muscleGroup }.forEach {
                            if (coefs.getValue(it.id) > 0f) coefs[it.id] = coefs.getValue(it.id) * mEff
                        }
                    }
                }
                RStack.PI -> {
                    val obs = thisSessionSets.groupBy { it.exerciseId }.mapNotNull { (id, sets) ->
                        signalExtractor.aggregateSession(sets)?.let {
                            MultiMusclePiController.Obs(id, exMuscle.getValue(id), it.est1RM, it.sessionConfidence)
                        }
                    }
                    pi.step(baselines, coefs, obs)
                }
                RStack.PI_ROLLING -> {
                    val obs = thisSessionSets.groupBy { it.exerciseId }.mapNotNull { (id, sets) ->
                        signalExtractor.aggregateSession(sets)?.let {
                            RollingWindowPiController.Obs(id, exMuscle.getValue(id), it.est1RM, it.sessionConfidence)
                        }
                    }
                    rolling.step(t, baselines, coefs, obs, muscleExercises)
                }
                RStack.PI_ROLLING_CONS -> {
                    val obs = thisSessionSets.groupBy { it.exerciseId }.mapNotNull { (id, sets) ->
                        signalExtractor.aggregateSession(sets)?.let {
                            RollingConservingPiController.Obs(id, exMuscle.getValue(id), it.est1RM, it.sessionConfidence)
                        }
                    }
                    conserving.step(t, baselines, coefs, obs, muscleExercises)
                }
            }

            val trained = loaded.map { it.id }.filter { (trainCount[it] ?: 0) >= 1 }
            if (convAt < 0 && trained.isNotEmpty() && trained.map { errOf(it, gMul) }.average() <= 0.10) convAt = s

            if (s >= sessions) {
                val well = loaded.map { it.id }.filter { (trainCount[it] ?: 0) >= 3 }
                if (well.isNotEmpty()) tailTrainedErr.add(well.map { errOf(it, gMul) }.average().toFloat() * 100f)
                tailAllErr.add(loaded.map { errOf(it.id, gMul) }.average().toFloat() * 100f)
                loaded.forEach { ex ->
                    val m = ex.primaryMuscle
                    tailRatio.getValue(ex.id).add(baselines.getValue(m) * coefs.getValue(ex.id) / (trueBaseline.getValue(m) * trueCoef.getValue(ex.id)))
                }
            }
        }

        val wellFinal = loaded.map { it.id }.filter { (trainCount[it] ?: 0) >= 3 }
        val jitter = wellFinal.map { id ->
            val xs = tailRatio.getValue(id)
            if (xs.size < 2) 0f else {
                val mean = xs.average().toFloat()
                kotlin.math.sqrt(xs.map { (it - mean) * (it - mean) }.average().toFloat()) * 100f
            }
        }.let { if (it.isEmpty()) 0f else it.average().toFloat() }

        val coefInflation = exp(loaded.map { ln(coefs.getValue(it.id) / seedCoef.getValue(it.id)).toDouble() }.average()).toFloat()
        val gMulFinal = gMulAt(total - 1)
        val baselineGaugeErr = musclesLoaded.map {
            abs(baselines.getValue(it) / (trueBaseline.getValue(it) * gMulFinal) - 1f)
        }.average().toFloat() * 100f

        return RMetrics(
            convSessions = if (convAt < 0) total else convAt,
            trainedEndErr = tailTrainedErr.average().toFloat(),
            allEndErr = tailAllErr.average().toFloat(),
            jitter = jitter,
            pctTrained = wellFinal.size.toFloat() / loaded.size * 100f,
            avgReductions = reductionEvents.toFloat() / total,
            coefInflation = coefInflation,
            baselineGaugeErr = baselineGaugeErr,
        )
    }

    @Test
    fun reframe_realisticVariant() {
        val sb = StringBuilder()
        sb.appendLine("# Controller reframe — realistic variant (real library + planner, bands removed)\n")
        sb.appendLine("5 exercises/workout from the real planner; ${trueBaselines.size} muscle groups; coefficients = seed perturbed")
        sb.appendLine("~8% lognormal + 4 outliers; mid-set weight drops modelled. Mean over ${seeds.size} seeds, 120+30 sessions.\n")
        sb.appendLine("Metrics: convSess=sessions to mean error over trained exercises <=10%; trainedErr%=tail error over")
        sb.appendLine("exercises trained >=3 sessions; allErr%=tail error over ALL loaded exercises (incl. rarely-trained, gauge-sensitive);")
        sb.appendLine("jitter%=tail std of prescribed/true; %trained=loaded exercises trained >=3x; drops/sess=mid-set weight-drop events.\n")
        sb.appendLine("| seed baseline | stack | convSess | trainedErr% | allErr% | jitter% | %trained | drops/sess |")
        sb.appendLine("|---------------|-------|---------:|------------:|--------:|--------:|---------:|-----------:|")

        fun row(variant: String, factor: Float, stack: RStack) {
            val rows = seeds.map { simulateRealistic(factor, stack, it, sessions = 120, tail = 30) }
            fun avg(sel: (RMetrics) -> Float) = rows.map(sel).average().toFloat()
            val convSess = rows.map { it.convSessions }.average().toInt()
            sb.appendLine(
                "| $variant | ${stack.name.lowercase()} | $convSess | %.2f | %.2f | %.2f | %.0f%% | %.1f |".format(
                    avg { it.trainedEndErr }, avg { it.allEndErr }, avg { it.jitter },
                    avg { it.pctTrained }, avg { it.avgReductions },
                ),
            )
        }

        row("above (1.2x)", 1.2f, RStack.CURRENT)
        row("above (1.2x)", 1.2f, RStack.PI)
        row("above (1.2x)", 1.2f, RStack.PI_ROLLING)
        row("above (1.2x)", 1.2f, RStack.PI_ROLLING_CONS)
        row("below (0.8x)", 0.8f, RStack.CURRENT)
        row("below (0.8x)", 0.8f, RStack.PI)
        row("below (0.8x)", 0.8f, RStack.PI_ROLLING)
        row("below (0.8x)", 0.8f, RStack.PI_ROLLING_CONS)

        val report = sb.toString()
        val f = File("build/reports/controller-reframe-realistic.md")
        f.parentFile?.mkdirs()
        f.writeText(report)
        println(report)
        println("Report written to: ${f.absolutePath}")
    }

    /**
     * Strengthening-creep probe. A rising true baseline means each fresh workout slightly beats the
     * (staler) rolling pool; the rolling controller attributes that gap partly to the coefficient,
     * which could ratchet the coefficient gauge upward over time. coefInflation = geomean(coef/seed)
     * detects it (within-session PI conserves it at 1.0 exactly; it is the control). baselineGaugeErr
     * is the cold-start error a seed-accurate new exercise would see if the baseline under-tracks.
     */
    @Test
    fun reframe_strengtheningCreep() {
        val sb = StringBuilder()
        sb.appendLine("# Controller reframe — strengthening creep probe\n")
        sb.appendLine("Start at true baseline (factor 1.0); true baseline compounds at the growth rate. Mean over ${seeds.size} seeds, 120+30 sessions.")
        sb.appendLine("coefInflation = geomean(coef/seedCoef) over loaded exercises: 1.00 = no gauge creep, >1 = coefficients ratcheting up.")
        sb.appendLine("baselineGaugeErr% = mean |baseline/trueBaseline - 1| = cold-start error for a seed-accurate new exercise.\n")
        sb.appendLine("| growth/sess | stack | coefInflation | baselineGaugeErr% | trainedErr% | jitter% |")
        sb.appendLine("|------------:|-------|--------------:|------------------:|------------:|--------:|")

        fun row(growth: Float, stack: RStack) {
            val rows = seeds.map { simulateRealistic(1.0f, stack, it, sessions = 120, tail = 30, growthPerSession = growth) }
            fun avg(sel: (RMetrics) -> Float) = rows.map(sel).average().toFloat()
            sb.appendLine(
                "| %.1f%% | %s | %.3f | %.2f | %.2f | %.2f |".format(
                    growth * 100f, stack.name.lowercase(),
                    avg { it.coefInflation }, avg { it.baselineGaugeErr },
                    avg { it.trainedEndErr }, avg { it.jitter },
                ),
            )
        }

        for (growth in listOf(0.0f, 0.002f, 0.004f)) {
            row(growth, RStack.CURRENT)
            row(growth, RStack.PI)
            row(growth, RStack.PI_ROLLING)
            row(growth, RStack.PI_ROLLING_CONS)
            sb.appendLine("|  |  |  |  |  |  |")
        }

        val report = sb.toString()
        val f = File("build/reports/controller-reframe-creep.md")
        f.parentFile?.mkdirs()
        f.writeText(report)
        println(report)
        println("Report written to: ${f.absolutePath}")
    }
}
