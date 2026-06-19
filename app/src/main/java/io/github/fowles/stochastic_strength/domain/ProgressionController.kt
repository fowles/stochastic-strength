package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** One trained exercise's aggregated session signal (from [SessionSignalExtractor]). */
data class ProgressionObservation(
    val exerciseId: Long,
    val muscle: MuscleGroup,
    val est1RM: Float,
    val confidence: Float,
)

data class ProgressionStepInput(
    val now: Long,
    val observations: List<ProgressionObservation>,
    val baselines: Map<MuscleGroup, Float>,
    val coefficients: Map<Long, Float>,
    /** Every loaded (coefficient > 0) exercise id per muscle — the rolling pool. */
    val muscleExercises: Map<MuscleGroup, List<Long>>,
    /** Muscles with a HURT set this session — baseline backs off, overriding the PI update. */
    val hurtMuscles: Set<MuscleGroup>,
    val weightUnit: WeightUnit,
)

data class BaselineUpdate(val muscleGroup: MuscleGroup, val newBaseline: Float, val metadata: String?)
data class CoefficientUpdate(val exerciseId: Long, val coefficient: Float, val metadata: String?)
data class ProgressionStepOutput(
    val baselineUpdates: List<BaselineUpdate>,
    val coefficientUpdates: List<CoefficientUpdate>,
)

interface ProgressionController {
    val name: String
    /** Fold one session into baseline + coefficient updates, advancing internal per-exercise state. */
    fun step(input: ProgressionStepInput): ProgressionStepOutput
}

data class ProgressionControllerConfig(
    val kB: Float = 0.5f,
    val kC: Float = 0.5f,
    val emaBeta: Float = 0.5f,
    val halfLifeMs: Long = 21L * 24 * 60 * 60 * 1000,
    val maxLogStepB: Float = ln(1.15f),
    val maxLogStepC: Float = ln(1.10f),
    val hurtFactor: Float = 0.85f,
    val minRelativeChange: Float = 0.002f,
)

/**
 * Gauge-conserving rolling-window common/differential P controller, one loop per muscle. Ported
 * from the validated `RollingConservingPiController` simulation prototype.
 *
 * Per session: advance each observed exercise's recency-decayed EMA of `log(observed1RM)`. For each
 * trained muscle, pool every loaded exercise with a recent measurement (weight = recency × confidence)
 * and split the innovations `e_i = ln(emaEst_i) − ln(baseline·coef_i)`:
 *   - common mode (weighted mean) → baseline;
 *   - differential `e_i − common`, applied to ALL pooled exercises scaled by `w_i / max w`, so the
 *     log-updates sum to zero → coefficient geomean (the gauge) is conserved with no normalizer.
 * HURT overrides: the muscle's baseline backs off by [hurtFactor] and no coefficient moves are emitted.
 */
class RollingConservingProgressionController(
    private val config: ProgressionControllerConfig = ProgressionControllerConfig(),
) : ProgressionController {

    override val name: String = "rolling-conserving-pi"

    private val emaLogEst = mutableMapOf<Long, Float>()
    private val lastConf = mutableMapOf<Long, Float>()
    private val lastTime = mutableMapOf<Long, Long>()
    private val ln2 = ln(2.0)

    override fun step(input: ProgressionStepInput): ProgressionStepOutput {
        val baselineUpdates = mutableListOf<BaselineUpdate>()
        val coefficientUpdates = mutableListOf<CoefficientUpdate>()

        for (o in input.observations) {
            if (o.est1RM <= 0f) continue
            val le = ln(o.est1RM)
            emaLogEst[o.exerciseId] =
                emaLogEst[o.exerciseId]?.let { (1f - config.emaBeta) * it + config.emaBeta * le } ?: le
            lastConf[o.exerciseId] = o.confidence
            lastTime[o.exerciseId] = input.now
        }

        val trainedMuscles = input.observations.map { it.muscle }.toSet() + input.hurtMuscles
        for (m in trainedMuscles) {
            val b = input.baselines[m] ?: continue
            if (b <= 0f) continue

            if (m in input.hurtMuscles) {
                val bNew = WeightFormatter.round(b * config.hurtFactor, input.weightUnit)
                if (bNew != b && bNew > 0f) baselineUpdates.add(BaselineUpdate(m, bNew, "hurt"))
                continue
            }

            val pooled = input.muscleExercises[m].orEmpty().mapNotNull { id ->
                val le = emaLogEst[id] ?: return@mapNotNull null
                val c = input.coefficients[id] ?: return@mapNotNull null
                if (c <= 0f) return@mapNotNull null
                val age = (input.now - (lastTime[id] ?: input.now)).coerceAtLeast(0L)
                val w = exp(-age * ln2 / config.halfLifeMs).toFloat() * (lastConf[id] ?: 0f)
                if (w <= 1e-6f) return@mapNotNull null
                Triple(id, le - ln(b * c), w)
            }
            if (pooled.isEmpty()) continue

            val wsum = pooled.sumOf { it.third.toDouble() }.toFloat()
            val common = if (wsum > 0f) pooled.sumOf { (it.second * it.third).toDouble() }.toFloat() / wsum else 0f

            val dLogB = (config.kB * common).coerceIn(-config.maxLogStepB, config.maxLogStepB)
            val bNew = WeightFormatter.round(b * exp(dLogB), input.weightUnit)
            if (bNew != b && bNew > 0f) {
                baselineUpdates.add(BaselineUpdate(m, bNew, "pi:n=${pooled.size},common=${fmt(common)}"))
            }

            val maxW = pooled.maxOf { it.third }
            for ((id, e, w) in pooled) {
                val gain = w / maxW // freshest gets full K_c; staler proportionally less. Preserves sum-zero.
                val dLogC = (config.kC * gain * (e - common)).coerceIn(-config.maxLogStepC, config.maxLogStepC)
                val cOld = input.coefficients.getValue(id)
                val cNew = cOld * exp(dLogC)
                if (abs(cNew - cOld) <= config.minRelativeChange * cOld) continue
                coefficientUpdates.add(CoefficientUpdate(id, cNew, "pi:d=${fmt(e - common)},w=${fmt(gain)}"))
            }
        }
        return ProgressionStepOutput(baselineUpdates, coefficientUpdates)
    }

    private fun fmt(v: Float) = "%.4f".format(Locale.ROOT, v)
}
