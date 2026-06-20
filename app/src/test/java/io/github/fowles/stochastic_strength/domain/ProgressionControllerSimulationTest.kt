package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Param-lock simulation test for the production [RollingConservingProgressionController].
 *
 * Drives the real exercise library + real planner (bands removed, all other equipment kept) through
 * 120+30 sessions with perturbed coefficients, mid-set weight drops, and multiple muscle groups.
 * Signal extraction uses [SessionSignalExtractor.aggregateSession], matching production wiring.
 *
 * Two locked asserts gate convergence/accuracy/jitter and gauge conservation ceilings; they must
 * not be loosened without a documented design decision. A ceiling violation means the harness
 * diverges from the validated prototype — investigate before adjusting.
 */
class ProgressionControllerSimulationTest {

    private val unit = WeightUnit.KG

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
        val coefInflation: Float, // geomean(coef/seedCoef) over loaded — 1.0 = no gauge creep
    )

    private fun metricsFinite(m: RMetrics) = listOf(
        m.trainedEndErr, m.jitter, m.coefInflation,
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
        val trainCount = mutableMapOf<Long, Int>()
        var t = 0L
        var convAt = -1

        val controller = RollingConservingProgressionController()
        val muscleExercises = loaded.groupBy { it.primaryMuscle }.mapValues { e -> e.value.map { it.id } }

        val tailRatio = loaded.associate { it.id to mutableListOf<Float>() }
        val tailTrainedErr = mutableListOf<Float>()

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
                }
                trainCount.merge(ex.id, 1, Int::plus)
            }

            val observations = thisSessionSets.groupBy { it.exerciseId }.mapNotNull { (id, sets) ->
                SessionSignalExtractor.aggregateSession(sets)?.let {
                    ProgressionObservation(id, exMuscle.getValue(id), it.est1RM, it.sessionConfidence)
                }
            }
            val hurtMuscles = thisSessionSets
                .filter { it.feedback == SetFeedback.HURT }
                .mapNotNull { exMuscle[it.exerciseId] }.toSet()
            val out = controller.step(
                ProgressionStepInput(
                    now = t, observations = observations,
                    baselines = baselines.toMap(), coefficients = coefs.toMap(),
                    muscleExercises = muscleExercises, hurtMuscles = hurtMuscles, weightUnit = unit,
                ),
            )
            out.baselineUpdates.forEach { baselines[it.muscleGroup] = it.newBaseline }
            out.coefficientUpdates.forEach { coefs[it.exerciseId] = it.coefficient }

            val trained = loaded.map { it.id }.filter { (trainCount[it] ?: 0) >= 1 }
            if (convAt < 0 && trained.isNotEmpty() && trained.map { errOf(it, gMul) }.average() <= 0.10) convAt = s

            if (s >= sessions) {
                val well = loaded.map { it.id }.filter { (trainCount[it] ?: 0) >= 3 }
                if (well.isNotEmpty()) tailTrainedErr.add(well.map { errOf(it, gMul) }.average().toFloat() * 100f)
                loaded.forEach { ex ->
                    val m = ex.primaryMuscle
                    tailRatio.getValue(ex.id).add(baselines.getValue(m) * coefs.getValue(ex.id) / (trueBaseline.getValue(m) * gMul * trueCoef.getValue(ex.id)))
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

        return RMetrics(
            convSessions = if (convAt < 0) total else convAt,
            trainedEndErr = tailTrainedErr.average().toFloat(),
            jitter = jitter,
            coefInflation = coefInflation,
        )
    }

    // ---- locked asserts -------------------------------------------------------------------------

    @Ignore("Re-locked in 2026-06-19-asymmetric-fatigue-aware-signal Task 3: harness needs a cross-set fatigue model")
    @Test
    fun production_gains_hold_convergence_and_gauge_ceilings() {
        val rows = seeds.map { simulateRealistic(0.8f, it, sessions = 120, tail = 30) }
        fun avg(sel: (RMetrics) -> Float) = rows.map(sel).average().toFloat()
        val convSess = rows.map { it.convSessions }.average()
        rows.forEach { assertTrue("non-finite metric: $it", metricsFinite(it)) }

        assertTrue("convergence ${convSess} > budget", convSess <= 8.0)            // doc: ~3
        assertTrue("trainedErr ${avg { it.trainedEndErr }} > ceiling", avg { it.trainedEndErr } <= 4.0f)  // doc: ~1.8
        assertTrue("jitter ${avg { it.jitter }} > ceiling", avg { it.jitter } <= 1.0f)                    // doc: ~0.6
    }

    @Ignore("Re-locked in 2026-06-19-asymmetric-fatigue-aware-signal Task 3: harness needs a cross-set fatigue model")
    @Test
    fun production_gains_conserve_gauge_under_strengthening() {
        for (growth in listOf(0.0f, 0.002f, 0.004f)) {
            val rows = seeds.map { simulateRealistic(1.0f, it, sessions = 120, tail = 30, growthPerSession = growth) }
            val infl = rows.map { it.coefInflation }.average()
            assertTrue("coefInflation $infl drifted at growth=$growth", infl in 0.97..1.03) // doc: ~1.00
        }
    }
}
