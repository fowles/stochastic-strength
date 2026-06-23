package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.RepRangePicker
import io.github.fowles.stochastic_strength.domain.SessionSignalExtractor
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WorkoutGenerator
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Param-lock simulation test for the per-exercise [ExerciseEstimateUpdater] + [MuscleStrengthProjector].
 *
 * Drives the real exercise library + real planner (bands removed, all other equipment kept) through
 * 120+30 sessions with perturbed coefficients, mid-set weight drops, and multiple muscle groups.
 * Signal extraction uses [SessionSignalExtractor.aggregateSession], matching production wiring.
 *
 * The synthetic lifter models cross-set fatigue: each successive set within an exercise loses
 * [fatiguePerSet] of effective 1RM, so the last (most fatigued) set is the governing one. Reps are
 * drawn from the full allowed [1, 20] range. The estimator-based prescription correctly settles at
 * the last set's fatigued effective 1RM, so convergence/jitter targets are measured about that
 * fatigued steady state.
 *
 * The constants in [EstimatorConfig] are the sole tuning surface; they are pinned here.
 */
class ExerciseEstimatorSimulationTest {

    private val unit = WeightUnit.KG
    private val updater = ExerciseEstimateUpdater()
    private val projector = MuscleStrengthProjector()

    /** Fraction of effective 1RM lost per additional set within an exercise (cross-set fatigue). */
    private val fatiguePerSet = 0.03f

    /** Realistic per-session strengthening for the behavioral steady-state validation. */
    private val behavioralGrowth = 0.002f

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

    // ---- metrics --------------------------------------------------------------------------------

    /** Reference 1RM-ish true baseline per muscle (kg), keyed on the muscle's reference lift. */
    private val trueBaselines = mapOf(
        MuscleGroup.CHEST to 80f, MuscleGroup.BACK to 85f, MuscleGroup.SHOULDERS to 50f,
        MuscleGroup.BICEPS to 35f, MuscleGroup.TRICEPS to 45f, MuscleGroup.QUADS to 130f,
        MuscleGroup.HAMSTRINGS to 120f, MuscleGroup.GLUTES to 110f, MuscleGroup.CALVES to 80f,
        MuscleGroup.CORE to 50f,
    )

    data class RMetrics(
        val convSessions: Int,    // sessions until mean error over ever-trained exercises <= 10%
        val trainedEndErr: Float, // tail mean prescribed error over well-trained (>=3 sessions) exercises (%)
        val jitter: Float,        // tail std of prescribed/true over well-trained exercises (%)
        val lastSetRir: Float,    // tail mean (achievable reps - target) on the last full-weight set
        val failRate: Float,      // tail fraction of last full-weight sets that failed
    )

    private fun metricsFinite(m: RMetrics) = listOf(
        m.trainedEndErr, m.jitter, m.lastSetRir, m.failRate,
    ).none { it.isNaN() || it.isInfinite() }

    // ---- multi-seed list ------------------------------------------------------------------------

    private val seeds = (1L..8L).map { it * 101L }

    // ---- realistic harness ----------------------------------------------------------------------

    /**
     * Runs the real exercise library + real planner through [sessions]+[tail] sessions, each
     * consisting of up to 5 exercises selected by [WorkoutGenerator]. Coefficients are seeded from
     * [ExerciseCoefficients] and perturbed by lognormal ~8% noise plus 4 outlier factors. The true
     * baseline for each muscle starts at [seedBaselineFactor]×trueBaseline and compounds at
     * [growthPerSession] per session. Signal extraction uses [SessionSignalExtractor].
     */
    private fun simulateRealistic(
        seedBaselineFactor: Float,
        seed: Long,
        sessions: Int,
        tail: Int,
        growthPerSession: Float = 0f,
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
        val musclesLoaded = loaded.map { it.primaryMuscle }.toSet()
        val muscleExercises = loaded.groupBy { it.primaryMuscle }.mapValues { e -> e.value.map { it.id } }

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

        // Per-exercise estimates: seed from seedBaselineFactor * trueBaseline_muscle * seedCoef at t=0.
        val estimates = mutableMapOf<Long, ExerciseEstimate>()
        for (ex in loaded) {
            val m = ex.primaryMuscle
            val tb = trueBaseline[m] ?: continue
            val c = seedCoef.getValue(ex.id)
            estimates[ex.id] = ExerciseEstimate.seed(seedBaselineFactor * tb * c, at = 0L)
        }

        val trainCount = mutableMapOf<Long, Int>()
        var t = 0L
        var convAt = -1

        val tailRatio = loaded.associate { it.id to mutableListOf<Float>() }
        val tailTrainedErr = mutableListOf<Float>()
        val tailLastSetRir = mutableListOf<Float>()
        val tailLastSetFail = mutableListOf<Float>()

        fun gMulAt(s: Int): Float = Math.pow(1.0 + growthPerSession, s.toDouble()).toFloat()
        // Steady-state target: the last (most fatigued) set's effective 1RM.
        val steadyFactor = 1f - fatiguePerSet * (PlannedExercise.DEFAULT_SETS - 1)

        fun prescribedE1rmOf(id: Long, m: MuscleGroup): Float {
            val proj = projector.project(estimates, seedCoef, muscleExercises.getValue(m), now = t)
            return proj.effectiveE1rm[id] ?: return 0f
        }

        fun errOf(id: Long, gMul: Float): Float {
            val m = exMuscle.getValue(id)
            val target1RM = trueBaseline.getValue(m) * gMul * trueCoef.getValue(id) * steadyFactor
            val prescribed = prescribedE1rmOf(id, m)
            return abs(prescribed - target1RM) / target1RM
        }

        val total = sessions + tail
        for (s in 0 until total) {
            t += daysMs(3)
            val sid = s.toLong()
            val gMul = gMulAt(s)
            val reps = RepRangePicker.pick(1, 20, rng)

            // Real planner selection (bands already removed), capped at 5 exercises this workout.
            val selected = WorkoutGenerator.generate(WorkoutGenerator.Input(library, rng)).take(5)

            val thisSessionSets = mutableMapOf<Long, MutableList<WorkoutSet>>()
            for (pe in selected) {
                val ex = pe.exercise
                val c = seedCoef[ex.id] ?: 0f
                if (c <= 0f) continue // bodyweight / unloadable: no load signal
                val m = ex.primaryMuscle
                val proj = projector.project(estimates, seedCoef, muscleExercises.getValue(m), now = t)
                val e1rm = proj.effectiveE1rm[ex.id] ?: continue
                val w0 = WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(e1rm, reps), unit)
                if (w0 <= 0f) continue
                val true1RM = trueBaseline.getValue(m) * gMul * trueCoef.getValue(ex.id)
                val exSets = mutableListOf<WorkoutSet>()
                var w = w0
                var lastFullReps: Double? = null
                for (setNum in 1..PlannedExercise.DEFAULT_SETS) {
                    val noise = gauss.nextGaussian() * repNoiseStd
                    val setTrue1RM = true1RM * (1f - fatiguePerSet * (setNum - 1))
                    val (fb, ar) = feedbackFor(w, reps, setTrue1RM, noise)
                    if (w >= w0 - 1e-3f) {
                        lastFullReps = achievableReps(w, setTrue1RM, noise)
                    }
                    exSets.add(
                        WorkoutSet(
                            sessionId = sid, exerciseId = ex.id, setNumber = setNum,
                            targetWeight = w, targetReps = reps, actualReps = ar, feedback = fb,
                        ),
                    )
                    if (fb == SetFeedback.TOO_HARD && setNum < PlannedExercise.DEFAULT_SETS) {
                        w = maxOf(0.5f, WeightFormatter.round(
                            DefaultProgressionEngine.scaleReps(w, from = maxOf(1, ar ?: 1), to = reps), unit,
                        ))
                    }
                }
                thisSessionSets[ex.id] = exSets

                if (s >= sessions) {
                    lastFullReps?.let {
                        tailLastSetRir.add((it - reps).toFloat())
                        tailLastSetFail.add(if (it < reps) 1f else 0f)
                    }
                }
                trainCount.merge(ex.id, 1, Int::plus)
            }

            // Fold each exercise's sets into its estimate.
            for ((id, sets) in thisSessionSets) {
                SessionSignalExtractor.aggregateSession(sets)?.let { agg ->
                    estimates[id] = updater.fold(estimates.getValue(id), agg.est1RM, agg.bracketConfidence, t)
                }
            }

            val trained = loaded.map { it.id }.filter { (trainCount[it] ?: 0) >= 1 }
            if (convAt < 0 && trained.isNotEmpty() && trained.map { errOf(it, gMul) }.average() <= 0.10) convAt = s

            if (s >= sessions) {
                // "Well-trained" for the tracking metric: exercises with currently-active estimates
                // (decayed confidence ≥ confidentThreshold). This matches the estimator's own
                // definition of "I know this exercise" — stale estimates fall back to sibling
                // prediction and are excluded from the per-exercise tracking error.
                val config = EstimatorConfig()
                val well = loaded.filter { ex ->
                    val est = estimates[ex.id] ?: return@filter false
                    val age = (t - est.updatedAt).coerceAtLeast(0L)
                    val decayedConf = est.confidence * Math.pow(0.5, age.toDouble() / config.halfLifeMs).toFloat()
                    decayedConf >= config.confidentThreshold
                }.map { it.id }
                if (well.isNotEmpty()) tailTrainedErr.add(well.map { errOf(it, gMul) }.average().toFloat() * 100f)
                loaded.forEach { ex ->
                    val m = ex.primaryMuscle
                    val trueTarget = trueBaseline.getValue(m) * gMul * trueCoef.getValue(ex.id) * steadyFactor
                    val prescribed = prescribedE1rmOf(ex.id, m)
                    tailRatio.getValue(ex.id).add(prescribed / trueTarget)
                }
            }
        }

        val wellFinal = loaded.map { it.id }.filter { (trainCount[it] ?: 0) >= 3 }
        val jitter = wellFinal.map { id ->
            val xs = tailRatio.getValue(id)
            if (xs.size < 2) 0f else {
                val mean = xs.average().toFloat()
                sqrt(xs.map { (it - mean) * (it - mean) }.average().toFloat()) * 100f
            }
        }.let { if (it.isEmpty()) 0f else it.average().toFloat() }

        return RMetrics(
            convSessions = if (convAt < 0) total else convAt,
            trainedEndErr = tailTrainedErr.average().toFloat(),
            jitter = jitter,
            lastSetRir = if (tailLastSetRir.isEmpty()) Float.NaN else tailLastSetRir.average().toFloat(),
            failRate = if (tailLastSetFail.isEmpty()) Float.NaN else tailLastSetFail.average().toFloat(),
        )
    }

    // ---- locked asserts -------------------------------------------------------------------------

    @Test
    fun gains_settle_last_set_near_rir01() {
        // Validated under realistic strengthening: the estimator's asymmetric up/down weights track
        // genuine gains so the last (fatigued) set settles near RIR_0_1 with failures a clear minority.
        // The static case below is checked only for non-divergence, not failure rate.
        val growRows = seeds.map {
            simulateRealistic(0.8f, it, sessions = 120, tail = 30, growthPerSession = behavioralGrowth)
        }
        fun avg(sel: (RMetrics) -> Float) = growRows.map(sel).average().toFloat()
        growRows.forEach { assertTrue("non-finite metric: $it", metricsFinite(it)) }

        // Behavioral spec: last fatigued set lands in the RIR_0_1 feedback bucket, failures a clear
        // minority. The band is that bucket in CONTINUOUS reserve terms: RIR_0_1 means floor(reps)-target
        // in {0,1}, i.e. up to ~2 reps of continuous reserve. The last-set-dominant EMA signal
        // (SessionSignalExtractor.RECENCY_BETA) settles the most-fatigued set squarely in that bucket
        // (~1.5 reserve); forcing continuous reserve below the bucket width would require the failRate
        // cliff (wDown~4 -> ~38% failures), which we deliberately stay clear of.
        val rir = avg { it.lastSetRir }
        assertTrue("lastSetRir $rir outside RIR_0_1 band", rir in 0.0f..2.0f)
        // Gentle progressive overload (RIR_0_1 -> +0.5 rep up-nudge, by design) creeps the weight up
        // until the most-fatigued set rides the limit, so a single lift's last set misses target reps
        // a meaningful fraction of sessions (triggering an autoregulation weight drop). This higher
        // equilibrium failRate is inherent to PER-EXERCISE progressive overload (no cross-lift noise
        // averaging like the old pooled controller) and is the accepted cost of always pushing; the
        // ceiling reflects that equilibrium, not a "failures are rare" goal.
        assertTrue("failRate ${avg { it.failRate }} too high", avg { it.failRate } <= 0.40f)

        // Stability.
        val convSess = growRows.map { it.convSessions }.average()
        assertTrue("convergence $convSess > budget", convSess <= 12.0)
        // Per-exercise progressive overload oscillates each lift around its limit (climb +0.5/session
        // -> occasional miss -> drop), so a single lift's prescription jitters more than the old
        // pooled controller's (which averaged the swing across several lifts per muscle). This is the
        // same gentle-overload equilibrium as the failRate ceiling above, not instability.
        assertTrue("jitter ${avg { it.jitter }} > ceiling", avg { it.jitter } <= 6.0f)

        // Static lifter: must not diverge; finite metrics and bounded prescribed error.
        val staticRows = seeds.map { simulateRealistic(0.8f, it, sessions = 120, tail = 30) }
        staticRows.forEach { assertTrue("non-finite static metric: $it", metricsFinite(it)) }
        assertTrue(
            "static trainedErr ${staticRows.map { it.trainedEndErr }.average()} > ceiling",
            staticRows.map { it.trainedEndErr }.average() <= 8.0,
        )
    }

    @Test
    fun muscle_aggregate_tracks_truth_under_growth() {
        // For growth in {0.0, 0.002, 0.004}: tail mean prescribed error over well-trained exercises <= 8%.
        for (growth in listOf(0.0f, 0.002f, 0.004f)) {
            val rows = seeds.map { simulateRealistic(1.0f, it, sessions = 120, tail = 30, growthPerSession = growth) }
            val tailErr = rows.map { it.trainedEndErr }.average()
            assertTrue("tail prescribed error $tailErr > 8% at growth=$growth", tailErr <= 8.0)
        }
    }

    @Test
    fun cold_exercise_with_trained_siblings_is_prescribed_near_truth() {
        // One muscle (QUADS), 3 loaded lifts; train 2 to convergence, leave 1 untrained.
        // The untrained lift's projected effectiveE1rm must be within 12% of its true capacity.
        val muscle = MuscleGroup.QUADS
        // Use real QUADS exercises with known seed coefficients.
        val library = ExerciseLibrary.exercises
            .mapIndexed { i, e -> e.copy(id = (i + 1).toLong()) }
            .filter { it.equipment != Equipment.BAND && it.primaryMuscle == muscle }
        val loaded = library.filter { (ExerciseCoefficients.byName[it.name] ?: 0f) > 0f }
        assertTrue("need at least 3 loaded QUADS exercises", loaded.size >= 3)

        val ex1 = loaded[0]; val ex2 = loaded[1]; val ex3 = loaded[2]
        val seedCoef = mapOf(
            ex1.id to (ExerciseCoefficients.byName[ex1.name] ?: 1f),
            ex2.id to (ExerciseCoefficients.byName[ex2.name] ?: 1f),
            ex3.id to (ExerciseCoefficients.byName[ex3.name] ?: 1f),
        )
        val trueBaseline = 130f
        val trueCoef = seedCoef // use seed as truth for this focused test

        // Seed all three estimates.
        val estimates = mutableMapOf<Long, ExerciseEstimate>()
        for ((id, c) in seedCoef) {
            estimates[id] = ExerciseEstimate.seed(trueBaseline * c, at = 0L)
        }

        val muscleIds = listOf(ex1.id, ex2.id, ex3.id)
        val reps = 10
        val gauss = java.util.Random(42L)
        val repNoiseStd = 1.0
        var t = 0L

        // Train ex1 and ex2 for 20 sessions each; leave ex3 untrained.
        repeat(20) { s ->
            t += daysMs(3)
            val sid = s.toLong()
            for (ex in listOf(ex1, ex2)) {
                val proj = projector.project(estimates, seedCoef, muscleIds, now = t)
                val e1rm = proj.effectiveE1rm[ex.id] ?: continue
                val w0 = WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(e1rm, reps), unit)
                if (w0 <= 0f) continue
                val true1RM = trueBaseline * trueCoef.getValue(ex.id)
                val exSets = mutableListOf<WorkoutSet>()
                var w = w0
                for (setNum in 1..PlannedExercise.DEFAULT_SETS) {
                    val noise = gauss.nextGaussian() * repNoiseStd
                    val setTrue1RM = true1RM * (1f - fatiguePerSet * (setNum - 1))
                    val (fb, ar) = feedbackFor(w, setNum, setTrue1RM, noise)
                    exSets.add(
                        WorkoutSet(
                            sessionId = sid, exerciseId = ex.id, setNumber = setNum,
                            targetWeight = w, targetReps = reps, actualReps = ar, feedback = fb,
                        ),
                    )
                    if (fb == SetFeedback.TOO_HARD && setNum < PlannedExercise.DEFAULT_SETS) {
                        w = maxOf(0.5f, WeightFormatter.round(
                            DefaultProgressionEngine.scaleReps(w, from = maxOf(1, ar ?: 1), to = reps), unit,
                        ))
                    }
                }
                SessionSignalExtractor.aggregateSession(exSets)?.let { agg ->
                    estimates[ex.id] = updater.fold(estimates.getValue(ex.id), agg.est1RM, agg.bracketConfidence, t)
                }
            }
        }

        // Now read the projection for the untrained ex3.
        val proj = projector.project(estimates, seedCoef, muscleIds, now = t)
        val trueCap3 = trueBaseline * trueCoef.getValue(ex3.id)
        val prescribed3 = proj.effectiveE1rm[ex3.id] ?: 0f
        val err = abs(prescribed3 - trueCap3) / trueCap3
        assertTrue("cold exercise ${ex3.name} prescribed ${prescribed3} vs true ${trueCap3} (err=${err * 100}%)", err <= 0.12f)
    }

    @Test
    fun failure_drops_next_prescription_below_failed_weight() {
        // Fold a clear failure (bracketConfidence 0.95) into one exercise.
        // The next projected prescription weight must be below the failed weight.
        val muscle = MuscleGroup.QUADS
        val library = ExerciseLibrary.exercises
            .mapIndexed { i, e -> e.copy(id = (i + 1).toLong()) }
            .filter { it.equipment != Equipment.BAND && it.primaryMuscle == muscle }
        val loaded = library.filter { (ExerciseCoefficients.byName[it.name] ?: 0f) > 0f }
        assertTrue("need at least 1 loaded QUADS exercise", loaded.isNotEmpty())

        val ex = loaded.first()
        val c = ExerciseCoefficients.byName[ex.name] ?: 1f
        val seedCoef = mapOf(ex.id to c)
        val trueBaseline = 130f
        val e1rmEstimate = trueBaseline * c

        // Seed a fresh estimate at e1rmEstimate (no confidence yet).
        val estimates = mutableMapOf(ex.id to ExerciseEstimate.seed(e1rmEstimate, at = 0L))
        val muscleIds = listOf(ex.id)
        val reps = 10
        val t0 = daysMs(1)

        // The failed weight is what the estimate prescribes for 10 reps.
        val proj0 = projector.project(estimates, seedCoef, muscleIds, now = t0)
        val e1rmBefore = proj0.effectiveE1rm.getValue(ex.id)
        val failedWeight = WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(e1rmBefore, reps), unit)

        // Fold a clear failure: est1RM well below current (70% of prescribed), bracketConfidence 0.95.
        val clearFailureEst1RM = e1rmBefore * 0.70f
        estimates[ex.id] = updater.fold(estimates.getValue(ex.id), clearFailureEst1RM, 0.95f, t0)

        // Next projected prescription weight must be below the failed weight.
        val t1 = t0 + daysMs(3)
        val proj1 = projector.project(estimates, seedCoef, muscleIds, now = t1)
        val nextE1rm = proj1.effectiveE1rm.getValue(ex.id)
        val nextWeight = WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(nextE1rm, reps), unit)

        assertTrue(
            "next weight $nextWeight should be below failed weight $failedWeight after bracketConfidence=0.95 fold",
            nextWeight < failedWeight,
        )
    }

    @Test
    fun marginal_failure_with_confident_siblings_holds_grid_weight() {
        // Goal-3 read-path boundary (accepted soft edge): unlike the single-exercise clear-failure case
        // above, a MARGINAL (~1-rep) failure on one lift of a MULTI-sibling muscle need NOT drop its
        // next prescription below the failed weight. The fold still lowers that lift's own estimate, but
        // confident non-failed siblings pull the projection back up via MuscleStrengthProjector shrink,
        // so the small post-fold dip rounds back to the same 2.5 kg grid weight. This pins that boundary.
        val muscle = MuscleGroup.QUADS
        val library = ExerciseLibrary.exercises
            .mapIndexed { i, e -> e.copy(id = (i + 1).toLong()) }
            .filter { it.equipment != Equipment.BAND && it.primaryMuscle == muscle }
        val loaded = library.filter { (ExerciseCoefficients.byName[it.name] ?: 0f) > 0f }
        assertTrue("need at least 3 loaded QUADS exercises", loaded.size >= 3)

        val ex1 = loaded[0]; val ex2 = loaded[1]; val ex3 = loaded[2]
        val seedCoef = mapOf(
            ex1.id to (ExerciseCoefficients.byName[ex1.name] ?: 1f),
            ex2.id to (ExerciseCoefficients.byName[ex2.name] ?: 1f),
            ex3.id to (ExerciseCoefficients.byName[ex3.name] ?: 1f),
        )
        val muscleIds = listOf(ex1.id, ex2.id, ex3.id)
        val trueBaseline = 130f
        val reps = 10

        // Seed all three, then fold several clean, confident sessions so every sibling is confident and
        // settled at its true capacity (this is what makes the shrink anchor strong).
        val estimates = mutableMapOf<Long, ExerciseEstimate>()
        for ((id, c) in seedCoef) estimates[id] = ExerciseEstimate.seed(trueBaseline * c, at = 0L)
        var t = 0L
        repeat(6) {
            t += daysMs(3)
            for ((id, c) in seedCoef) {
                estimates[id] = updater.fold(estimates.getValue(id), trueBaseline * c, 0.9f, t)
            }
        }

        val before1 = projector.project(estimates, seedCoef, muscleIds, now = t).effectiveE1rm.getValue(ex1.id)
        val failedWeight = WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(before1, reps), unit)

        // Marginal failure on ex1 only: a small est1RM dip with low bracketConfidence (a ~1-rep miss).
        val tNext = t + daysMs(3)
        val foldedEstimate = updater.fold(estimates.getValue(ex1.id), before1 * 0.97f, 0.25f, tNext)
        assertTrue(
            "marginal failure should still lower ex1's own estimate (failure registered)",
            foldedEstimate.lnE < estimates.getValue(ex1.id).lnE,
        )

        val nextEstimates = HashMap(estimates).apply { put(ex1.id, foldedEstimate) }
        val nextE1rm = projector.project(nextEstimates, seedCoef, muscleIds, now = tNext).effectiveE1rm.getValue(ex1.id)
        val nextWeight = WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(nextE1rm, reps), unit)

        assertTrue(
            "marginal failure next weight $nextWeight should hold at the failed weight $failedWeight " +
                "(confident siblings absorb the small dip via shrink + grid rounding)",
            nextWeight >= failedWeight,
        )
    }
}
