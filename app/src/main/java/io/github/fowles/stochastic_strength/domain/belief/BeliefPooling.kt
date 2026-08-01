package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.ln

/**
 * The blended read-time view of one exercise: what prescription and scoring consume, plus the
 * components the blend was made from (so the trace / cross-tuning / charts report the pooling
 * that actually ran instead of re-deriving it).
 */
data class EffectiveBelief(
    val mu: Float,
    val sigma2: Float,
    /** The aged own belief that entered the blend; null for a cold exercise. */
    val own: Belief? = null,
    /** The leave-one-out sibling prediction (ln coef + L₋ᵢ, var(L₋ᵢ) + τ²); null with no other voters. */
    val sibling: EffectiveBelief? = null,
    /** Sibling share of the precision blend, pSib/(pOwn+pSib); 1 when cold, 0 with no siblings. */
    val siblingShare: Float = 0f,
    /** This exercise's own pooling precision w_i = 1/(agedσ² + τ²); 0 when it isn't a voter. */
    val voterWeight: Float = 0f,
)

data class MusclePoolResult(
    /** Precision-weighted muscle level (ln, seed-relative); null when no exercise has a belief. */
    val levelLn: Float?,
    /** Effective belief per loaded exercise; absent when there is nothing to say (cold + no pool). */
    val effective: Map<Long, EffectiveBelief>,
    /** Σw over all voters — [EffectiveBelief.voterWeight]/this is an exercise's precision share. */
    val totalVoterWeight: Float = 0f,
)

/**
 * Read-time pooling (spec Phase 2; never mutates beliefs). Each exercise with a belief votes
 * mu_j − ln(coef_j) with precision 1/(sigma_j² + crossLiftIndependenceEstimate²); the effective belief is the precision
 * blend of the own aged belief with the leave-one-out sibling prediction
 * (ln coef_i + L₋ᵢ, var(L₋ᵢ) + crossLiftIndependenceEstimate²). Fresh tight evidence mathematically outvotes siblings;
 * stale (aged) exercises lean on them. Exercises without a belief take the full-pool prediction —
 * no separate seed-anchor constant: seeded-cold exercises sit at seed with sigmaSeed and anchor
 * the level automatically.
 */
class BeliefPooling(private val config: BeliefConfig) {
    private val fold = BeliefFold(config)

    private data class Voter(val vote: Float, val weight: Float)

    fun effective(
        beliefs: Map<Long, Belief>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
    ): MusclePoolResult {
        val independenceVar = config.crossLiftIndependenceEstimate * config.crossLiftIndependenceEstimate
        // Loaded voters, aged to now. Insertion-ordered maps keep the summation order identical
        // to the muscleExerciseIds order (the backtest gate is pinned bit-identical).
        val aged = mutableMapOf<Long, Belief>()
        val voters = mutableMapOf<Long, Voter>()
        for (id in muscleExerciseIds) {
            val coef = seedCoef[id] ?: continue
            if (coef <= 0f) continue
            val b = beliefs[id]?.let { fold.aged(it, now) } ?: continue
            aged[id] = b
            voters[id] = Voter(vote = b.mu - ln(coef), weight = 1f / (b.sigma2 + independenceVar))
        }
        val sumW = voters.values.sumOf { it.weight.toDouble() }.toFloat()
        val sumWV = voters.values.sumOf { (it.weight * it.vote).toDouble() }.toFloat()
        val levelLn = if (sumW > 0f) sumWV / sumW else null

        val effective = mutableMapOf<Long, EffectiveBelief>()
        for (id in muscleExerciseIds) {
            val coef = seedCoef[id] ?: continue
            if (coef <= 0f) continue
            val own = aged[id]
            // Leave-one-out sums so an exercise never borrows its own evidence back.
            val voter = voters[id]
            val looW = sumW - (voter?.weight ?: 0f)
            val looWV = sumWV - ((voter?.weight ?: 0f) * (voter?.vote ?: 0f))
            val sibling: EffectiveBelief? = if (looW > 0f) {
                EffectiveBelief(mu = ln(coef) + looWV / looW, sigma2 = 1f / looW + independenceVar)
            } else null
            effective[id] = when {
                own != null && sibling != null -> {
                    val pOwn = 1f / own.sigma2
                    val pSib = 1f / sibling.sigma2
                    EffectiveBelief(
                        mu = (pOwn * own.mu + pSib * sibling.mu) / (pOwn + pSib),
                        sigma2 = 1f / (pOwn + pSib),
                        own = own,
                        sibling = sibling,
                        siblingShare = pSib / (pOwn + pSib),
                        voterWeight = voter?.weight ?: 0f,
                    )
                }
                own != null -> EffectiveBelief(
                    own.mu, own.sigma2,
                    own = own, siblingShare = 0f, voterWeight = voter?.weight ?: 0f,
                )
                sibling != null -> sibling.copy(sibling = sibling, siblingShare = 1f)
                else -> continue
            }
        }
        return MusclePoolResult(levelLn, effective, totalVoterWeight = sumW)
    }
}
