package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.exp
import kotlin.math.ln

data class MuscleProjection(
    /** Muscle level L: n_eff-weighted geomean of aged E_j / seedCoef_j against the seed-anchored prior. */
    val level: Float,
    /** Shrunk pooled mean per exercise (own aged belief blended toward the sibling prediction). */
    val effectiveE1rm: Map<Long, Float>,
    /** Display/prescription coefficient: effectiveE1rm[i] / level. */
    val derivedCoef: Map<Long, Float>,
    /** Own aged belief std per exercise — the z-shading input until phase-3 pooling. */
    val pooledSigma: Map<Long, Float>,
)

/**
 * Read-time pooling. Computes a muscle level from confidently-trained exercises, predicts each
 * exercise from that level via its seed coefficient, and shrinks each exercise's own belief
 * toward its prediction by n_eff. Pure; cross-informing happens here and never mutates the
 * stored per-exercise beliefs.
 */
class MuscleStrengthProjector(private val config: EstimatorConfig = EstimatorConfig()) {
    private val updater = BeliefUpdater(config)

    /**
     * Bridge vote weight (phase 2): the belief's effective sample size in poolObsVar units — precision
     * above the seed floor, computed from the ADAPTATION-IMMUNE evidenceVar (not the live sigma2, which
     * adaptive attention inflates to move the mean). Seed-fresh → 0; well-observed → ≈2–5; stale
     * (evidenceVar grown past σ_seed²) → 0, so a stale lone voter decays to the seed-anchored prior.
     */
    fun neff(aged: ExerciseBelief): Float {
        val seedVar = config.sigmaSeed * config.sigmaSeed
        return ((1f / aged.evidenceVar - 1f / seedVar) * config.poolObsVar).coerceAtLeast(0f)
    }

    fun project(
        beliefs: Map<Long, ExerciseBelief>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
        muscleLastObs: Long? = null,
    ): MuscleProjection {
        val loaded = muscleExerciseIds.mapNotNull { id ->
            val b = beliefs[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) null else Triple(id, updater.age(b, now, muscleLastObs), coef)
        }
        if (loaded.isEmpty()) return MuscleProjection(0f, emptyMap(), emptyMap(), emptyMap())

        val lnPrior = loaded.map { (_, b, coef) -> b.mu - ln(coef) }.average().toFloat()
        var num = config.levelPrior * lnPrior
        var den = config.levelPrior
        for ((_, b, coef) in loaded) {
            val c = neff(b)
            num += c * (b.mu - ln(coef))
            den += c
        }
        val lnLevel = num / den
        val level = exp(lnLevel)

        val effective = mutableMapOf<Long, Float>()
        val coefs = mutableMapOf<Long, Float>()
        val sigmas = mutableMapOf<Long, Float>()
        for ((id, b, coef) in loaded) {
            val cSelf = neff(b)
            val lnPred = ln(coef) + lnLevel
            // Evidence gate (phase-1 shape, n_eff units): siblings may override only by their
            // EXCESS evidence, so same-age/staler siblings cannot lift a fresh measurement.
            val siblingExcess = loaded.sumOf { (jid, jb, _) ->
                if (jid == id) 0.0 else (neff(jb) - cSelf).coerceAtLeast(0f).toDouble()
            }.toFloat()
            // The prediction's evidence is capped at what a τ-noised transfer earns (poolObsVar/τ²
            // ≈ 0.03): a trained own belief (n_eff ≥ ~0.5) is barely moved by the level, while a
            // cold one (cSelf = 0) still adopts the prediction fully. This is spec §3's shrink with
            // σ²_ℓLOO ≈ 0 and one uniform τ class; phase 3 installs the real thing.
            val kappa = minOf(config.poolObsVar / (config.tauBridge * config.tauBridge), siblingExcess)
            val lnUsed = if (cSelf + kappa <= 0f) b.mu else (cSelf * b.mu + kappa * lnPred) / (cSelf + kappa)
            effective[id] = exp(lnUsed)
            coefs[id] = if (level > 0f) exp(lnUsed) / level else coef
            sigmas[id] = b.sigma
        }
        return MuscleProjection(level, effective, coefs, sigmas)
    }
}
