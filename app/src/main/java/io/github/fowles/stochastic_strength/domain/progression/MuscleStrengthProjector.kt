package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.exp
import kotlin.math.ln

data class MuscleProjection(
    /** Muscle level L: confidence-weighted geomean of E_j / seedCoef_j over confident exercises. */
    val level: Float,
    /** Shrunk prescription target per exercise (own estimate blended toward the sibling prediction). */
    val effectiveE1rm: Map<Long, Float>,
    /** Display/prescription coefficient: effectiveE1rm[i] / level (so level * coef == effectiveE1rm). */
    val derivedCoef: Map<Long, Float>,
)

/**
 * Read-time pooling. Computes a muscle level from confidently-trained exercises, predicts each
 * exercise from that level via its seed coefficient, and shrinks each exercise's own estimate
 * toward its prediction by confidence. Pure; cross-informing happens here and never mutates the
 * stored per-exercise estimates.
 */
class MuscleStrengthProjector(private val config: EstimatorConfig = EstimatorConfig()) {

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
        if (loaded.isEmpty()) return MuscleProjection(level = 0f, effectiveE1rm = emptyMap(), derivedCoef = emptyMap())

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
        return MuscleProjection(level = level, effectiveE1rm = effective, derivedCoef = coefs)
    }

    private fun Float.pow(x: Float): Float = Math.pow(this.toDouble(), x.toDouble()).toFloat()
}
