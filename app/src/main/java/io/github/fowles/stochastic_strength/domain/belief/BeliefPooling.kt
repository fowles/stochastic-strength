package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.ln

/** The blended read-time view of one exercise: what prescription and scoring consume. */
data class EffectiveBelief(val mu: Float, val sigma2: Float)

data class MusclePoolResult(
    /** Precision-weighted muscle level (ln, seed-relative); null when no exercise has a belief. */
    val levelLn: Float?,
    /** Effective belief per loaded exercise; absent when there is nothing to say (cold + no pool). */
    val effective: Map<Long, EffectiveBelief>,
)

/**
 * Read-time pooling (spec Phase 2; never mutates beliefs). Each exercise with a belief votes
 * mu_j − ln(coef_j) with precision 1/(sigma_j² + tau²); the effective belief is the precision
 * blend of the own aged belief with the leave-one-out sibling prediction
 * (ln coef_i + L₋ᵢ, var(L₋ᵢ) + tau²). Fresh tight evidence mathematically outvotes siblings;
 * stale (aged) exercises lean on them. Exercises without a belief take the full-pool prediction —
 * no separate seed-anchor constant: seeded-cold exercises sit at seed with sigmaSeed and anchor
 * the level automatically.
 */
class BeliefPooling(private val config: BeliefConfig) {
    private val fold = BeliefFold(config)

    fun effective(
        beliefs: Map<Long, Belief>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
    ): MusclePoolResult {
        val tau2 = config.tau * config.tau
        // Loaded voters, aged to now.
        data class Voter(val id: Long, val vote: Float, val weight: Float)
        val voters = muscleExerciseIds.mapNotNull { id ->
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) return@mapNotNull null
            val b = beliefs[id]?.let { fold.aged(it, now) } ?: return@mapNotNull null
            Voter(id, b.mu - ln(coef), 1f / (b.sigma2 + tau2))
        }
        val sumW = voters.sumOf { it.weight.toDouble() }.toFloat()
        val sumWV = voters.sumOf { (it.weight * it.vote).toDouble() }.toFloat()
        val levelLn = if (sumW > 0f) sumWV / sumW else null

        val effective = mutableMapOf<Long, EffectiveBelief>()
        for (id in muscleExerciseIds) {
            val coef = seedCoef[id] ?: continue
            if (coef <= 0f) continue
            val own = beliefs[id]?.let { fold.aged(it, now) }
            // Leave-one-out sums so an exercise never borrows its own evidence back.
            val voter = voters.firstOrNull { it.id == id }
            val looW = sumW - (voter?.weight ?: 0f)
            val looWV = sumWV - ((voter?.weight ?: 0f) * (voter?.vote ?: 0f))
            val sibling: EffectiveBelief? = if (looW > 0f) {
                EffectiveBelief(mu = ln(coef) + looWV / looW, sigma2 = 1f / looW + tau2)
            } else null
            effective[id] = when {
                own != null && sibling != null -> {
                    val pOwn = 1f / own.sigma2
                    val pSib = 1f / sibling.sigma2
                    EffectiveBelief(
                        mu = (pOwn * own.mu + pSib * sibling.mu) / (pOwn + pSib),
                        sigma2 = 1f / (pOwn + pSib),
                    )
                }
                own != null -> EffectiveBelief(own.mu, own.sigma2)
                sibling != null -> sibling
                else -> continue
            }
        }
        return MusclePoolResult(levelLn, effective)
    }
}
