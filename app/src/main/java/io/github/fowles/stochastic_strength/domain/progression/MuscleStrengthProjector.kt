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

        // Muscle level L = conf-weighted geomean of E_j / seedCoef_j over confident loaded exercises.
        val votes = muscleExerciseIds.mapNotNull { id ->
            val e = estimates[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            val c = conf(e)
            if (coef <= 0f || c < config.confidentThreshold) return@mapNotNull null
            Pair(e.lnE - ln(coef), c) // ln(E_j / coef_j), weight c
        }
        val lnLevel: Float? =
            if (votes.isEmpty()) null
            else votes.sumOf { (it.first * it.second).toDouble() }.toFloat() /
                votes.sumOf { it.second.toDouble() }.toFloat()
        val level = lnLevel?.let { exp(it) } ?: fallbackLevel(estimates, seedCoef, muscleExerciseIds)

        val effective = mutableMapOf<Long, Float>()
        val coefs = mutableMapOf<Long, Float>()
        for (id in muscleExerciseIds) {
            val e = estimates[id] ?: continue
            val coef = seedCoef[id] ?: continue
            if (coef <= 0f) continue
            val cSelf = conf(e)
            val lnPred = if (lnLevel != null) ln(coef) + lnLevel else e.lnE // cold muscle -> own seed
            val lnUsed = (cSelf * e.lnE + config.priorStrength * lnPred) / (cSelf + config.priorStrength)
            val used = exp(lnUsed)
            effective[id] = used
            coefs[id] = if (level > 0f) used / level else coef
        }
        return MuscleProjection(level = level, effectiveE1rm = effective, derivedCoef = coefs)
    }

    /** When no exercise is confident, pick a representative level so display has a value. */
    private fun fallbackLevel(
        estimates: Map<Long, ExerciseEstimate>,
        seedCoef: Map<Long, Float>,
        ids: List<Long>,
    ): Float {
        val lvls = ids.mapNotNull { id ->
            val e = estimates[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) null else e.lnE - ln(coef)
        }
        return if (lvls.isEmpty()) 0f else exp(lvls.average().toFloat())
    }

    private fun Float.pow(x: Float): Float = Math.pow(this.toDouble(), x.toDouble()).toFloat()
}
