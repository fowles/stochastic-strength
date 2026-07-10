package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.RepRangePicker
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WorkoutGenerator
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilder
import io.github.fowles.stochastic_strength.domain.policy.PooledBelief
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
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
 * Param-lock simulation for the belief estimator + prescription policy (replaces the deleted
 * ExerciseEstimatorSimulationTest). Every prescription goes through the FULL production path,
 * exactly as WorkoutRepository.buildPlanner wires it: [PolicyStateBuilder] accumulated per session,
 * [MuscleStrengthProjector] → [PooledBelief] map → [PrescriptionPolicy.prescribe]; folds run
 * through [SessionProgressionStepper] in production order (step, then policy accumulation).
 *
 * The synthetic lifter is the old test's frame: [achievableReps] is the exact inverse of the
 * load-aware 1RM formula plus additive rep noise, with cross-set fatigue of [fatiguePerSet] per
 * set. The lifter's fatigue deliberately EQUALS [EstimatorConfig.fatiguePerSet] — the estimator's
 * fatigue model matches simulated truth. Reps come from the production range
 * (RepRangePicker.pick(5, 10)).
 *
 * Error metric: the policy's prescribed weight converted back to 1RM space
 * (toOneRepMax(prescribedWeight, reps)) against the LAST set's effective 1RM,
 * truth × (1 − φ·(S−1)) — the set the policy targets at RIR 0–1.
 *
 * The tuning constants pinned here are EstimatorConfig.uncertaintyZ, overloadDelta and poolObsVar
 * (Task 9's tuning surface); everything else in EstimatorConfig is locked by unit tests.
 */
class BeliefSimulationTest {

    private val unit = WeightUnit.KG
    private val config = EstimatorConfig()
    private val updater = BeliefUpdater(config)
    private val projector = MuscleStrengthProjector(config)

    /** Simulated cross-set fatigue — EQUAL to the estimator's model by design. */
    private val fatiguePerSet = config.fatiguePerSet

    /** Realistic per-session strengthening for the behavioral steady-state validation. */
    private val behavioralGrowth = 0.002f

    /** Steady-state target basis: the last (most fatigued) set's fraction of fresh 1RM. */
    private val steadyFactor = 1f - fatiguePerSet * (PlannedExercise.DEFAULT_SETS - 1)

    private val seeds = (1L..8L).map { it * 101L }

    private fun daysMs(days: Int): Long = days.toLong() * 24L * 60L * 60L * 1000L

    private fun f(x: Float) = "%.2f".format(x)

    // ---- synthetic lifter -----------------------------------------------------------------------

    /** Exact inverse of the load-aware 1RM formula + additive rep noise. */
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

    // ---- production-path session machinery ------------------------------------------------------

    /**
     * One simulated training history driven through the production stack. Prescriptions come from
     * [policy] (buildPlanner's exact wiring); folds go through [completeSession] in production
     * order (stepper.step, then PolicyStateBuilder.onSession — mirroring ReplayEngine +
     * WorkoutRepository.replayDerivedState).
     */
    private inner class Sim(library: List<Exercise>, seedCoef: Map<Long, Float>) {
        val snapshot = ReplaySnapshot(
            exerciseMuscle = library.associate { it.id to it.primaryMuscle },
            seedCoefficients = seedCoef,
            exerciseEquipment = library.associate { it.id to it.equipment },
        )
        private val stepper = SessionProgressionStepper(config = config)
        private val builder = PolicyStateBuilder()
        var t = 0L
        private var nextSessionId = 0L

        fun seedBelief(id: Long, e1rm: Float) {
            snapshot.currentBeliefs[id] = ExerciseBelief.seed(e1rm, at = 0L, config = config)
        }

        /** The planner's prescription policy as of time [t] — WorkoutRepository.buildPlanner's wiring. */
        fun policy(): PrescriptionPolicy {
            val state = builder.build(snapshot.muscleLastObs.toMap())
            val pooled = mutableMapOf<Long, PooledBelief>()
            for ((muscle, ids) in snapshot.muscleExerciseIds) {
                val proj = projector.project(
                    beliefs = snapshot.currentBeliefs,
                    seedCoef = snapshot.seedCoefficients,
                    muscleExerciseIds = ids,
                    now = t,
                    muscleLastObs = state.muscleLastObs[muscle],
                )
                for ((id, e1rm) in proj.effectiveE1rm) pooled[id] = PooledBelief(e1rm, proj.pooledSigma[id] ?: 0f)
            }
            return PrescriptionPolicy(pooled, state, config, DefaultProgressionEngine, unit, t)
        }

        /** Performs one exercise at [w0]: DEFAULT_SETS sets, cross-set fatigue, mid-set drops on failure. */
        fun performExercise(
            exerciseId: Long,
            w0: Float,
            reps: Int,
            true1RmFresh: Float,
            gauss: java.util.Random,
        ): Pair<List<WorkoutSet>, Double?> {
            val sets = mutableListOf<WorkoutSet>()
            var w = w0
            var lastFullReps: Double? = null
            for (setNum in 1..PlannedExercise.DEFAULT_SETS) {
                val noise = gauss.nextGaussian()
                val setTrue1Rm = true1RmFresh * (1f - fatiguePerSet * (setNum - 1))
                val (fb, ar) = feedbackFor(w, reps, setTrue1Rm, noise)
                if (w >= w0 - 1e-3f) lastFullReps = achievableReps(w, setTrue1Rm, noise)
                sets.add(
                    WorkoutSet(
                        sessionId = nextSessionId, exerciseId = exerciseId, setNumber = setNum,
                        targetWeight = w, targetReps = reps, actualReps = ar, feedback = fb,
                    ),
                )
                if (fb == SetFeedback.TOO_HARD && setNum < PlannedExercise.DEFAULT_SETS) {
                    w = maxOf(
                        0.5f,
                        WeightFormatter.round(
                            DefaultProgressionEngine.scaleReps(w, from = maxOf(1, ar ?: 1), to = reps), unit,
                        ),
                    )
                }
            }
            return sets to lastFullReps
        }

        fun completeSession(sets: List<WorkoutSet>) {
            if (sets.isNotEmpty()) {
                stepper.step(sets, snapshot, t)
                builder.onSession(t, sets, snapshot)
            }
            nextSessionId++
        }
    }

    // ---- realistic multi-muscle harness ----------------------------------------------------------

    /** Reference 1RM-ish true baseline per muscle (kg), keyed on the muscle's reference lift. */
    private val trueBaselines = mapOf(
        MuscleGroup.CHEST to 80f, MuscleGroup.BACK to 85f, MuscleGroup.SHOULDERS to 50f,
        MuscleGroup.BICEPS to 35f, MuscleGroup.TRICEPS to 45f, MuscleGroup.QUADS to 130f,
        MuscleGroup.HAMSTRINGS to 120f, MuscleGroup.GLUTES to 110f, MuscleGroup.CALVES to 80f,
        MuscleGroup.CORE to 50f,
    )

    private data class RMetrics(
        val convSessions: Int,     // sessions until mean prescribed err over ever-trained <= 10%
        val trainedEndErr: Float,  // % — tail mean prescribed err over trained (neff >= 1) exercises
        val jitter: Float,         // % — tail std of prescribed/target over trainCount >= 3 exercises
        val lastSetReserve: Float, // continuous reps of reserve on tail last full-weight sets
        val failRate: Float,       // fraction of tail last full-weight sets that failed
        val firstSessionMinRatio: Float, // min prescribed / seed-implied last-set weight on session 0
    )

    private fun metricsFinite(m: RMetrics) = listOf(m.trainedEndErr, m.jitter, m.lastSetReserve, m.failRate)
        .none { it.isNaN() || it.isInfinite() }

    /**
     * Runs the real exercise library + real planner selection through [sessions]+[tail] sessions of
     * up to 5 exercises. True coefficients are the seeds perturbed by lognormal ~8% noise plus 4
     * outlier factors; the true baseline starts at [seedBaselineFactor]×trueBaseline and compounds
     * at [growthPerSession]. Prescriptions and metrics both use the production policy path.
     */
    private fun simulateRealistic(
        seedBaselineFactor: Float,
        seed: Long,
        sessions: Int,
        tail: Int,
        growthPerSession: Float = 0f,
        // Tail-session calibration hook: (own belief aged to session time with the PRE-session
        // muscle clock, the exercise's performed sets).
        calibrationSink: ((ExerciseBelief, List<WorkoutSet>) -> Unit)? = null,
    ): RMetrics {
        val rng = Random(seed)
        val gauss = java.util.Random(seed)
        val truthRng = java.util.Random(seed xor 0x9E3779B9L)

        // Real library with assigned ids, bands removed, all other equipment kept (incl. bodyweight).
        val library = ExerciseLibrary.exercises
            .mapIndexed { i, e -> e.copy(id = (i + 1).toLong()) }
            .filter { it.equipment != Equipment.BAND }
        val seedCoef = library.associate { it.id to (ExerciseCoefficients.byName[it.name] ?: 0f) }
        val loaded = library.filter { seedCoef.getValue(it.id) > 0f }
        val musclesLoaded = loaded.map { it.primaryMuscle }.toSet()

        // True coefficients: seed slightly off (lognormal ~8%), with a few big outliers.
        val outlierFactors = listOf(1.5f, 0.6f, 1.4f, 0.65f)
        val outlierIds = loaded.map { it.id }.shuffled(Random(seed * 7 + 3)).take(outlierFactors.size).toSet()
        var oi = 0
        val trueCoef = loaded.associate { ex ->
            var factor = exp(truthRng.nextGaussian() * 0.08).toFloat()
            if (ex.id in outlierIds) factor *= outlierFactors[oi++ % outlierFactors.size]
            ex.id to seedCoef.getValue(ex.id) * factor
        }
        val trueBaseline = trueBaselines.filterKeys { it in musclesLoaded }

        val sim = Sim(library, seedCoef)
        for (ex in loaded) {
            val tb = trueBaseline[ex.primaryMuscle] ?: continue
            sim.seedBelief(ex.id, seedBaselineFactor * tb * seedCoef.getValue(ex.id))
        }

        val trainCount = mutableMapOf<Long, Int>()
        var convAt = -1
        val tailRatio = loaded.associate { it.id to mutableListOf<Float>() }
        val tailTrainedErr = mutableListOf<Float>()
        val tailLastSetReserve = mutableListOf<Float>()
        val tailLastSetFail = mutableListOf<Float>()
        var guardMinRatio = Float.MAX_VALUE

        fun gMulAt(s: Int): Float = (1.0 + growthPerSession).pow(s).toFloat()

        val total = sessions + tail
        for (s in 0 until total) {
            sim.t += daysMs(3)
            val gMul = gMulAt(s)
            val reps = RepRangePicker.pick(5, 10, rng)
            val policy = sim.policy()

            // Real planner selection (bands already removed), capped at 5 exercises this workout.
            val selected = WorkoutGenerator.generate(WorkoutGenerator.Input(library, rng)).take(5)
            val sessionSets = mutableListOf<WorkoutSet>()
            for (pe in selected) {
                val ex = pe.exercise
                if (seedCoef.getValue(ex.id) <= 0f) continue // bodyweight / unloadable: no load signal
                val w0 = policy.prescribe(ex, reps) ?: continue
                if (w0 <= 0f) continue
                if (s == 0) {
                    // First-session guard input: the weight the raw seed belief implies for the
                    // last (fatigued) set, before any policy shading.
                    val seedE1rm = seedBaselineFactor * trueBaseline.getValue(ex.primaryMuscle) * seedCoef.getValue(ex.id)
                    val seedImpliedW = DefaultProgressionEngine.fromOneRepMax(seedE1rm * steadyFactor, reps)
                    if (seedImpliedW > 0f) {
                        val ratio = w0 / seedImpliedW
                        if (ratio < 0.7f) {
                            println("  [guardDebug] seed=$seed ${ex.name} w0=$w0 ref=$seedImpliedW reps=$reps seedE1rm=$seedE1rm")
                        }
                        guardMinRatio = minOf(guardMinRatio, ratio)
                    }
                }
                val true1Rm = trueBaseline.getValue(ex.primaryMuscle) * gMul * trueCoef.getValue(ex.id)
                val (sets, lastFullReps) = sim.performExercise(ex.id, w0, reps, true1Rm, gauss)
                sessionSets += sets
                if (s >= sessions) {
                    lastFullReps?.let {
                        tailLastSetReserve.add((it - reps).toFloat())
                        tailLastSetFail.add(if (it < reps) 1f else 0f)
                    }
                    calibrationSink?.let { sink ->
                        // Pre-fold: completeSession has not run, so beliefs + muscle clock are
                        // still pre-session here.
                        val b = sim.snapshot.currentBeliefs[ex.id]
                        if (b != null) {
                            sink(updater.age(b, sim.t, sim.snapshot.muscleLastObs[ex.primaryMuscle]), sets)
                        }
                    }
                }
                trainCount.merge(ex.id, 1, Int::plus)
            }
            sim.completeSession(sessionSets)

            // Prescribed error, post-fold: what the app would put on the bar right now.
            val postPolicy = sim.policy()
            fun errOf(ex: Exercise): Float? {
                val target = trueBaseline.getValue(ex.primaryMuscle) * gMul * trueCoef.getValue(ex.id) * steadyFactor
                val w = postPolicy.prescribe(ex, reps) ?: return null
                if (w <= 0f) return null
                return abs(DefaultProgressionEngine.toOneRepMax(w, reps) - target) / target
            }

            if (convAt < 0) {
                val errs = loaded.filter { (trainCount[it.id] ?: 0) >= 1 }.mapNotNull { errOf(it) }
                if (errs.isNotEmpty() && errs.average() <= 0.10) convAt = s
            }
            if (s >= sessions) {
                // "Trained" gate = well-observed: poolPrecision above cold-floor (≈ neff≥1 equivalent).
                val well = loaded.filter { ex ->
                    val b = sim.snapshot.currentBeliefs[ex.id] ?: return@filter false
                    val aged = updater.age(b, sim.t, sim.snapshot.muscleLastObs[ex.primaryMuscle])
                    projector.poolPrecision(aged, config.tauOtherLoaded) >= 15f
                }
                val wellErrs = well.mapNotNull { errOf(it) }
                if (wellErrs.isNotEmpty()) tailTrainedErr.add(wellErrs.average().toFloat() * 100f)
                for (ex in loaded) {
                    val target = trueBaseline.getValue(ex.primaryMuscle) * gMul * trueCoef.getValue(ex.id) * steadyFactor
                    val w = postPolicy.prescribe(ex, reps) ?: continue
                    if (w <= 0f) continue
                    tailRatio.getValue(ex.id).add(DefaultProgressionEngine.toOneRepMax(w, reps) / target)
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
            lastSetReserve = if (tailLastSetReserve.isEmpty()) Float.NaN else tailLastSetReserve.average().toFloat(),
            failRate = if (tailLastSetFail.isEmpty()) Float.NaN else tailLastSetFail.average().toFloat(),
            firstSessionMinRatio = guardMinRatio,
        )
    }

    // ---- match-feel pins -------------------------------------------------------------------------

    @Test
    fun matchFeel_realisticGrowthLifter() {
        val rows = seeds.map { simulateRealistic(0.8f, it, sessions = 120, tail = 30, growthPerSession = behavioralGrowth) }
        fun avg(sel: (RMetrics) -> Float) = rows.map(sel).average().toFloat()
        val conv = rows.map { it.convSessions }.average()
        val guardMin = rows.minOf { it.firstSessionMinRatio }
        println(
            "[matchFeel growth] conv=${"%.1f".format(conv)} (<=12) trainedErr=${f(avg { it.trainedEndErr })}% (<=8) " +
                "jitter=${f(avg { it.jitter })}% (<=6) reserve=${f(avg { it.lastSetReserve })} (0..2) " +
                "fail=${f(avg { it.failRate })} (<=0.40) guardMin=${f(guardMin)} (>=0.6)",
        )
        rows.forEachIndexed { i, m -> println("  seed=${seeds[i]} $m") }

        rows.forEach { assertTrue("non-finite metric: $it", metricsFinite(it)) }
        assertTrue("convergence $conv > 12-session budget", conv <= 12.0)
        assertTrue("tail trained error ${avg { it.trainedEndErr }}% > 8%", avg { it.trainedEndErr } <= 8.0f)
        assertTrue("jitter ${avg { it.jitter }}% > 6%", avg { it.jitter } <= 6.0f)
        val reserve = avg { it.lastSetReserve }
        assertTrue("last-set reserve $reserve outside [0, 2]", reserve in 0.0f..2.0f)
        assertTrue("failRate ${avg { it.failRate }} > 0.40", avg { it.failRate } <= 0.40f)
        assertTrue("first-session guard: min prescribed/seed-implied ratio $guardMin < 0.6", guardMin >= 0.6f)
    }

    @Test
    fun matchFeel_staticLifterStaysFiniteAndAccurate() {
        val rows = seeds.map { simulateRealistic(0.8f, it, sessions = 120, tail = 30) }
        val tailErr = rows.map { it.trainedEndErr }.average()
        println("[matchFeel static] trainedErr=${"%.2f".format(tailErr)}% (<=8) allFinite=${rows.all { metricsFinite(it) }}")
        rows.forEach { assertTrue("non-finite static metric: $it", metricsFinite(it)) }
        assertTrue("static tail trained error $tailErr% > 8%", tailErr <= 8.0)
    }

    // ---- QUADS scenario rig ----------------------------------------------------------------------

    private data class QuadsRig(val sim: Sim, val lifts: List<Exercise>, val truth: Map<Long, Float>)

    /** Single-muscle rig: first 3 loaded QUADS lifts, truth = seed coefficients at a 130 kg level. */
    private fun quadsRig(seedFactor: Float = 1f): QuadsRig {
        val lifts = ExerciseLibrary.exercises
            .mapIndexed { i, e -> e.copy(id = (i + 1).toLong()) }
            .filter { it.equipment != Equipment.BAND && it.primaryMuscle == MuscleGroup.QUADS }
            .filter { (ExerciseCoefficients.byName[it.name] ?: 0f) > 0f }
            .take(3)
        assertTrue("need 3 loaded QUADS lifts", lifts.size == 3)
        val seedCoef = lifts.associate { it.id to ExerciseCoefficients.byName.getValue(it.name) }
        val truth = lifts.associate { it.id to QUADS_BASELINE * seedCoef.getValue(it.id) }
        val sim = Sim(lifts, seedCoef)
        for (ex in lifts) sim.seedBelief(ex.id, seedFactor * truth.getValue(ex.id))
        return QuadsRig(sim, lifts, truth)
    }

    private data class LiftOutcome(
        val prescribed: Float,
        val sets: List<WorkoutSet>,
        val lastFullReps: Double?,
        /** Own belief aged to the session time with the PRE-session muscle clock. */
        val preAged: ExerciseBelief,
    )

    /** One QUADS session (reps = 10) through the production policy path, 3 days after the previous. */
    private fun runQuadsSession(rig: QuadsRig, gauss: java.util.Random, capacityMul: Float = 1f): Map<Long, LiftOutcome> {
        val sim = rig.sim
        sim.t += daysMs(3)
        val policy = sim.policy()
        val outcomes = mutableMapOf<Long, LiftOutcome>()
        val sessionSets = mutableListOf<WorkoutSet>()
        for (ex in rig.lifts) {
            val w0 = policy.prescribe(ex, QUADS_REPS) ?: continue
            if (w0 <= 0f) continue
            val (sets, lastFull) = sim.performExercise(ex.id, w0, QUADS_REPS, rig.truth.getValue(ex.id) * capacityMul, gauss)
            sessionSets += sets
            val preAged = updater.age(
                sim.snapshot.currentBeliefs.getValue(ex.id), sim.t, sim.snapshot.muscleLastObs[MuscleGroup.QUADS],
            )
            outcomes[ex.id] = LiftOutcome(w0, sets, lastFull, preAged)
        }
        sim.completeSession(sessionSets)
        return outcomes
    }

    /** Mean post-fold prescribed error over the rig's lifts vs [truthMul]×truth (last-set basis). */
    private fun quadsPrescribedErr(rig: QuadsRig, truthMul: Float): Float {
        val policy = rig.sim.policy()
        val errs = rig.lifts.mapNotNull { ex ->
            val w = policy.prescribe(ex, QUADS_REPS) ?: return@mapNotNull null
            val target = rig.truth.getValue(ex.id) * truthMul * steadyFactor
            abs(DefaultProgressionEngine.toOneRepMax(w, QUADS_REPS) - target) / target
        }
        return if (errs.isEmpty()) Float.NaN else errs.average().toFloat()
    }

    // ---- scenario pins ---------------------------------------------------------------------------

    @Test
    fun calibration_eightyPercentIntervalRoughlyCovers() {
        // Collected over the tail of the realistic growth run (the brief's "tail of the realistic
        // run"): the single-muscle rig cannot host this pin — its 3-day cadence makes every
        // pre-session sigma^2 identical, so the n_eff >= 1 gate and the coverage ceiling demand
        // contradictory poolObsVar. The realistic tail has the sigma^2 / staleness spread the
        // predictive interval is supposed to cover.
        data class Sample(val absDiff: Float, val sigma2: Float)
        val samples = mutableListOf<Sample>()
        for (seed in seeds) {
            simulateRealistic(0.8f, seed, sessions = 120, tail = 30, growthPerSession = behavioralGrowth) { preAged, sets ->
                val implied = impliedSessionE1rm(sets, config)
                if (implied != null) samples.add(Sample(abs(ln(implied) - preAged.mu), preAged.sigma2))
            }
        }
        // Coverage as a function of poolObsVar (gate n_eff = (1/sigma^2 - 1/sigmaSeed^2) * p, the
        // projector's formula): tuning table for the poolObsVar knob.
        val seedVar = config.sigmaSeed * config.sigmaSeed
        fun coverageAt(p: Float): Pair<Float, Int> {
            var inside = 0
            var n = 0
            for (s in samples) {
                if ((1f / s.sigma2 - 1f / seedVar) * p < 1f) continue
                n++
                if (s.absDiff <= 1.2816f * sqrt(s.sigma2 + p)) inside++
            }
            return (if (n == 0) Float.NaN else inside.toFloat() / n) to n
        }
        for (p in listOf(7e-4f, 1e-3f, 1.5e-3f, 2e-3f, 3e-3f, 5e-3f, 8e-3f)) {
            val (c, n) = coverageAt(p)
            println("  [calibDebug] p=${"%.1e".format(p)} coverage=${"%.3f".format(c)} n=$n")
        }
        val (coverage, totalN) = coverageAt(config.poolObsVar)
        println("[calibration] coverage=${"%.3f".format(coverage)} (0.60..0.95) n=$totalN of ${samples.size}")
        assertTrue("no trained calibration samples collected", totalN > 0)
        assertTrue("80% predictive-interval coverage $coverage outside [0.60, 0.95]", coverage in 0.60f..0.95f)
    }

    @Test
    fun badDay_ceilingBlocksThenRecoversWithinTwoSessions() {
        val violations = mutableListOf<String>()
        val recovery = mutableListOf<Float>()
        for (seed in seeds) {
            val rig = quadsRig()
            val gauss = java.util.Random(seed)
            repeat(15) { runQuadsSession(rig, gauss) }
            // Fluke session at 80% capacity — the drop cascade emerges from the set simulation.
            val fluke = runQuadsSession(rig, gauss, capacityMul = 0.80f)
            val failedAtFull = fluke.filterValues { it.sets.first().feedback == SetFeedback.TOO_HARD }
            assertTrue("fluke session failed no full-weight first set (seed=$seed)", failedAtFull.isNotEmpty())

            // (a) The very next prescription is below the failed weight.
            val next = runQuadsSession(rig, gauss)
            for ((id, o) in failedAtFull) {
                val w1 = next[id]?.prescribed ?: continue
                if (w1 >= o.prescribed - 1e-3f) {
                    violations += "seed=$seed ex=$id: next ${f(w1)} !< failed ${f(o.prescribed)}"
                }
            }
            // (b) After 2 FURTHER clean sessions (i.e. beyond the (a) session — three clean
            // sessions total) the prescription is back within 5% of pre-incident, ONE-SIDED:
            // the failure mode is a lasting depression, so the pin is w >= 0.95×pre. Upside is
            // deliberately not bounded here — a low-λ lift's equilibrium prescription oscillates
            // more than ±5% (the jitter pin allows 6%), so a fluke caught at the band's trough
            // legitimately recovers above it; overshoot is governed by the (a) ceiling and the
            // reserve/failRate pins. RIR_5_PLUS reports are deliberately weak lower bounds
            // (~+0.02 ln per fold), so the immediate post-incident session mostly re-establishes
            // footing; the two further sessions close the gap.
            runQuadsSession(rig, gauss)
            runQuadsSession(rig, gauss)
            val recovered = rig.sim.policy()
            for (ex in rig.lifts) {
                val wPre = fluke[ex.id]?.prescribed ?: continue
                val w3 = recovered.prescribe(ex, QUADS_REPS) ?: continue
                recovery += (w3 / wPre - 1f) * 100f
                if (w3 < wPre * 0.95f - 1e-4f) {
                    violations += "seed=$seed ${ex.name}: w3=${f(w3)} below 95% of pre=${f(wPre)}"
                }
            }
        }
        println(
            "[badDay] recoveryDelta%%: min=${f(recovery.min())} mean=${f(recovery.average().toFloat())} " +
                "max=${f(recovery.max())} violations=${violations.size}",
        )
        assertTrue("bad-day violations:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun layoff_easedReturnAndFastReconvergence() {
        val violations = mutableListOf<String>()
        var comebackFails = 0
        var comebackN = 0
        val convergedBy = mutableListOf<Int>()
        // A fail FRACTION needs more samples than the mean-based pins: 8 seeds x 3 lifts = 24
        // samples quantizes the estimate in 1/24 steps and one unlucky rep-noise draw flips the
        // pin. 16 seeds (48 samples) measures the same property with half the granularity.
        val layoffSeeds = (1L..16L).map { it * 101L }
        for (seed in layoffSeeds) {
            val rig = quadsRig()
            val gauss = java.util.Random(seed)
            var pre: Map<Long, LiftOutcome> = emptyMap()
            repeat(15) { pre = runQuadsSession(rig, gauss) }
            // 8 idle weeks: 56 days from the last session to the comeback (runQuadsSession adds 3).
            rig.sim.t += daysMs(53)
            // Truth detrains at HALF the belief drift's rate (same grace). The drift model is
            // deliberately conservative — its purpose is an eased return, so it must over-estimate
            // the true loss; a truth that loses exactly the model rate would leave only
            // z·(sigma_gap − sigma_steady) ≈ 0.6 reps of comeback margin, indistinguishable from
            // steady-state riding under rep noise. No regain modeled inside the 3-session window.
            val idleWeeksPastGrace = (56f - 14f) / 7f
            val truthMul = exp(-minOf(0.5f * config.detrainRatePerWeek * idleWeeksPastGrace, config.detrainCap))
            val comeback = runQuadsSession(rig, gauss, capacityMul = truthMul)
            for (ex in rig.lifts) {
                val wPre = pre[ex.id]?.prescribed ?: continue
                val cb = comeback[ex.id] ?: continue
                if (cb.prescribed > wPre + 1e-3f) {
                    violations += "seed=$seed ${ex.name}: comeback ${f(cb.prescribed)} > pre-gap ${f(wPre)}"
                }
                cb.lastFullReps?.let { comebackN++; if (it < QUADS_REPS) comebackFails++ }
            }
            var conv = if (quadsPrescribedErr(rig, truthMul) <= 0.10f) 1 else -1
            for (k in 2..3) {
                runQuadsSession(rig, gauss, capacityMul = truthMul)
                if (conv < 0 && quadsPrescribedErr(rig, truthMul) <= 0.10f) conv = k
            }
            if (conv < 0) {
                violations += "seed=$seed: err ${f(quadsPrescribedErr(rig, truthMul) * 100)}% not <=10% within 3 post-gap sessions"
            } else {
                convergedBy += conv
            }
        }
        val failFrac = if (comebackN == 0) Float.NaN else comebackFails.toFloat() / comebackN
        println("[layoff] comebackFailFrac=${f(failFrac)} (<=0.25) convergedBy=$convergedBy violations=${violations.size}")
        assertTrue("layoff violations:\n${violations.joinToString("\n")}", violations.isEmpty())
        assertTrue("comeback last-set fail fraction $failFrac > 0.25", comebackN > 0 && failFrac <= 0.25f)
    }

    @Test
    fun censoredResponsiveness_underestimatedLifterConvergesInFourSessions() {
        val violations = mutableListOf<String>()
        val convergedAt = mutableListOf<Int>()
        for (seed in seeds) {
            val rig = quadsRig(seedFactor = 0.70f)
            val gauss = java.util.Random(seed)
            var conv = -1
            for (k in 1..4) {
                runQuadsSession(rig, gauss)
                if (conv < 0 && quadsPrescribedErr(rig, 1f) <= 0.10f) conv = k
            }
            if (conv < 0) {
                violations += "seed=$seed: err ${f(quadsPrescribedErr(rig, 1f) * 100)}% after 4 sessions"
            } else {
                convergedAt += conv
            }
        }
        println("[censored] convergedAt=$convergedAt violations=${violations.size}")
        assertTrue("censored-responsiveness violations:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private companion object {
        const val QUADS_REPS = 10
        const val QUADS_BASELINE = 130f
    }
}
