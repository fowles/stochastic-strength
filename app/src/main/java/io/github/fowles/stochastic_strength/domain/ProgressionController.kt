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
    /** >0 only for a demonstrated drop-cascade; scales the differential step (snap). */
    val bracketConfidence: Float = 0f,
)

data class ProgressionStepInput(
    val now: Long,
    val observations: List<ProgressionObservation>,
    val baselines: Map<MuscleGroup, Float>,
    val coefficients: Map<Long, Float>,
    /** Every loaded (coefficient > 0) exercise id per muscle — the rolling pool. */
    val muscleExercises: Map<MuscleGroup, List<Long>>,
    /** Seed (default) coefficient per loaded exercise — reference for the geomean reclaimer. */
    val seedCoefficients: Map<Long, Float> = emptyMap(),
    /** Muscles with a HURT set this session — baseline backs off, overriding the PI update. */
    val hurtMuscles: Set<MuscleGroup>,
    val weightUnit: WeightUnit,
)

/**
 * A controller's proposed update for one muscle's baseline.
 *
 * [newBaseline] is stored at full precision (raw kg); the controller does NOT round it.
 * Grid rounding (kg/lb increment) happens only at weight selection in WorkoutPlanner, so
 * sub-grid progression accumulates instead of being lost to a rounding deadband.
 */
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
    /** Differential gain at full bracket confidence (snap). Interpolated from [kC]. */
    val kCSnap: Float = 1.0f,
    /** Differential log-step clamp at full bracket confidence (snap). Interpolated from [maxLogStepC]. */
    val maxLogStepCSnap: Float = ln(2f),
    /** Baseline log-step clamp at full common-mode confidence (unanimous high-confidence drop). */
    val maxLogStepBSnap: Float = ln(2f),
    /** Huber threshold (log space) above which a pool member is treated as an outlier. */
    val huberDelta: Float = ln(1.15f),
    /** Reweighting iterations for the robust common mode and the reclaimer. */
    val robustIterations: Int = 3,
    /** Fraction of collective coefficient drift re-based into the baseline per session (0..1). */
    val reclaimRate: Float = 0.1f,
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

        val bracketConfById = input.observations.associate { it.exerciseId to it.bracketConfidence }

        for (o in input.observations) {
            if (o.est1RM <= 0f) continue
            val le = ln(o.est1RM)
            val s = o.bracketConfidence.coerceIn(0f, 1f)
            val betaEff = config.emaBeta + (1f - config.emaBeta) * s
            emaLogEst[o.exerciseId] =
                emaLogEst[o.exerciseId]?.let { (1f - betaEff) * it + betaEff * le } ?: le
            lastConf[o.exerciseId] = o.confidence
            lastTime[o.exerciseId] = input.now
        }

        val observedMuscles = input.observations.map { it.muscle }.toSet()
        // Also include any muscle that has at least one exercise with a seed coefficient — the
        // reclaimer must run for those even when they weren't trained this session.
        val reclaimMuscles = if (input.seedCoefficients.isEmpty()) emptySet() else
            input.muscleExercises.filterValues { ids -> ids.any { id -> input.seedCoefficients.containsKey(id) } }.keys
        val trainedMuscles = observedMuscles + input.hurtMuscles + reclaimMuscles
        for (m in trainedMuscles) {
            val b = input.baselines[m] ?: continue
            if (b <= 0f) continue

            if (m in input.hurtMuscles) {
                val bNew = b * config.hurtFactor
                if (bNew != b && bNew > 0f) baselineUpdates.add(BaselineUpdate(m, bNew, "hurt"))
                continue
            }

            // Pool is only computed for muscles observed this session; for reclaim-only muscles the
            // PI block is skipped (pooled stays empty) to avoid spurious updates from stale EMA state.
            val pooled = if (m !in observedMuscles) emptyList() else
                input.muscleExercises[m].orEmpty().mapNotNull { id ->
                    val le = emaLogEst[id] ?: return@mapNotNull null
                    val c = input.coefficients[id] ?: return@mapNotNull null
                    if (c <= 0f) return@mapNotNull null
                    val age = (input.now - (lastTime[id] ?: input.now)).coerceAtLeast(0L)
                    val w = exp(-age * ln2 / config.halfLifeMs).toFloat() * (lastConf[id] ?: 0f)
                    if (w <= 1e-6f) return@mapNotNull null
                    Triple(id, le - ln(b * c), w)
                }

            // Working copies; the reclaimer below re-bases these as a product-preserving gauge shift.
            var bWork = b
            val cWork = input.muscleExercises[m].orEmpty()
                .mapNotNull { id -> input.coefficients[id]?.let { id to it } }
                .filter { it.second > 0f }
                .toMap().toMutableMap()

            if (pooled.isNotEmpty()) {
                val wsum = pooled.sumOf { it.third.toDouble() }.toFloat()
                val common = RobustCenter.of(
                    pooled.map { it.second }, pooled.map { it.third }, config.huberDelta, config.robustIterations,
                )
                val massConf =
                    if (wsum > 0f) {
                        pooled.sumOf { (bracketConfById[it.first] ?: 0f).toDouble() * it.third }.toFloat() / wsum
                    } else {
                        0f
                    }
                val maxStepB = config.maxLogStepB + (config.maxLogStepBSnap - config.maxLogStepB) * massConf
                val dLogB = (config.kB * common).coerceIn(-maxStepB, maxStepB)
                bWork = b * exp(dLogB)

                val maxW = pooled.maxOf { it.third }
                for ((id, e, w) in pooled) {
                    val gain = w / maxW
                    val snapScale = (bracketConfById[id] ?: 0f).coerceIn(0f, 1f)
                    val kCeff = config.kC + (config.kCSnap - config.kC) * snapScale
                    val maxStep = config.maxLogStepC + (config.maxLogStepCSnap - config.maxLogStepC) * snapScale
                    val dLogC = (kCeff * gain * (e - common)).coerceIn(-maxStep, maxStep)
                    cWork[id]?.let { cWork[id] = it * exp(dLogC) }
                }
            }

            // Cross-session reclaim: move collective coef/seed drift into the baseline (products preserved).
            val offsets = cWork.mapNotNull { (id, cur) ->
                val seed = input.seedCoefficients[id] ?: return@mapNotNull null
                if (seed > 0f && cur > 0f) ln(cur / seed) else null
            }
            if (offsets.isNotEmpty()) {
                val center = RobustCenter.of(offsets, List(offsets.size) { 1f }, config.huberDelta, config.robustIterations)
                val shift = config.reclaimRate * center
                if (abs(shift) > config.minRelativeChange) {
                    bWork *= exp(shift)
                    for (id in cWork.keys.toList()) cWork[id] = cWork.getValue(id) * exp(-shift)
                }
            }

            if (bWork != b && bWork > 0f) {
                val tag = if (pooled.isEmpty()) "reclaim" else "pi:n=${pooled.size}"
                baselineUpdates.add(BaselineUpdate(m, bWork, tag))
            }
            for ((id, cNew) in cWork) {
                val cOld = input.coefficients.getValue(id)
                if (abs(cNew - cOld) <= config.minRelativeChange * cOld) continue
                coefficientUpdates.add(CoefficientUpdate(id, cNew, "pi:c=${fmt(cNew)}"))
            }
        }
        return ProgressionStepOutput(baselineUpdates, coefficientUpdates)
    }

    private fun fmt(v: Float) = "%.4f".format(Locale.ROOT, v)
}
