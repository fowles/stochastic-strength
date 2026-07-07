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
 * exercise from that level via its seed coefficient, and shrinks each exercise's own estimate
 * toward its prediction by confidence. Pure; cross-informing happens here and never mutates the
 * stored per-exercise estimates.
 */
class MuscleStrengthProjector(private val config: EstimatorConfig = EstimatorConfig()) {
    private val updater = BeliefUpdater(config)

    /**
     * Bridge vote weight (phase 2 only): the belief's effective sample size in poolObsVar units —
     * precision above the seed floor. Seed-fresh → 0; fully trained → ≈5 (today's scale); stale
     * (σ² grown past σ_seed²) → 0, so a stale lone voter decays to the seed-anchored prior.
     */
    fun neff(aged: ExerciseBelief): Float {
        val seedVar = config.sigmaSeed * config.sigmaSeed
        return ((1f / aged.sigma2 - 1f / seedVar) * config.poolObsVar).coerceAtLeast(0f)
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
            // Evidence gate (unchanged from phase 1, in n_eff units): siblings may override only by
            // their EXCESS evidence, so same-age/staler siblings cannot lift a fresh measurement.
            val siblingExcess = loaded.sumOf { (jid, jb, _) ->
                if (jid == id) 0.0 else (neff(jb) - cSelf).coerceAtLeast(0f).toDouble()
            }.toFloat()
            val kappa = minOf(config.priorStrength, siblingExcess)
            val lnUsed = if (cSelf + kappa <= 0f) b.mu else (cSelf * b.mu + kappa * lnPred) / (cSelf + kappa)
            effective[id] = exp(lnUsed)
            coefs[id] = if (level > 0f) exp(lnUsed) / level else coef
            sigmas[id] = b.sigma
        }
        return MuscleProjection(level, effective, coefs, sigmas)
    }

    fun project(
        estimates: Map<Long, ExerciseEstimate>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
    ): MuscleProjection {
        fun conf(e: ExerciseEstimate): Float {
            val age = (now - e.updatedAt).coerceAtLeast(0L)
            return e.confidence * 0.5f.pow(age.toFloat() / config.halfLifeMs)
        }

        // Loaded exercises with a positive seed coefficient.
        val loaded: List<Triple<Long, ExerciseEstimate, Float>> = muscleExerciseIds.mapNotNull { id ->
            val e = estimates[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) null else Triple(id, e, coef)
        }
        if (loaded.isEmpty()) return MuscleProjection(level = 0f, effectiveE1rm = emptyMap(), derivedCoef = emptyMap(), pooledSigma = emptyMap())

        // Seed prior anchor: unweighted mean of seed-relative levels ln(E_j / coef_j). Equals
        // ln(baseline) for a cold muscle (untrained siblings sit at seed) and drifts only as
        // exercises are genuinely trained.
        val lnPrior = loaded.map { (_, e, coef) -> e.lnE - ln(coef) }.average().toFloat()

        // Pooled level: every exercise votes with its full decayed confidence against the
        // fixed-weight prior. No threshold — low confidence simply contributes little.
        var num = config.levelPrior * lnPrior
        var den = config.levelPrior
        for ((_, e, coef) in loaded) {
            val c = conf(e)
            num += c * (e.lnE - ln(coef))
            den += c
        }
        val lnLevel = num / den
        val level = exp(lnLevel)

        val effective = mutableMapOf<Long, Float>()
        val coefs = mutableMapOf<Long, Float>()
        for ((id, e, coef) in loaded) {
            val cSelf = conf(e)
            val lnPred = ln(coef) + lnLevel // always defined; cold muscle -> ln(coef)+ln(baseline) == seed
            // Evidence gate on the sibling prior: a sibling may override this estimate only to the extent
            // it carries MORE decayed evidence than this estimate itself. Decayed confidence bakes in
            // recency, so a same-session-or-older sibling (equal/less confidence) cannot pull a fresh,
            // confident measurement up — a just-demonstrated estimate stands on its own. A cold/stale
            // lift (cSelf ~ 0) is still pulled by its confident siblings, preserving cross-informing.
            val siblingExcess = loaded.sumOf { (jid, je, _) ->
                if (jid == id) 0.0 else (conf(je) - cSelf).coerceAtLeast(0f).toDouble()
            }.toFloat()
            val kappa = minOf(config.priorStrength, siblingExcess)
            val lnUsed = if (cSelf + kappa <= 0f) e.lnE else (cSelf * e.lnE + kappa * lnPred) / (cSelf + kappa)
            val used = exp(lnUsed)
            effective[id] = used
            coefs[id] = if (level > 0f) used / level else coef
        }
        return MuscleProjection(level = level, effectiveE1rm = effective, derivedCoef = coefs, pooledSigma = emptyMap())
    }

    private fun Float.pow(x: Float): Float = Math.pow(this.toDouble(), x.toDouble()).toFloat()
}
