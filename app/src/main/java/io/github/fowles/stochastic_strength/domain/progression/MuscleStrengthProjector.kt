package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import kotlin.math.exp
import kotlin.math.ln

data class MuscleProjection(
    /** Muscle level L: precision-weighted geomean of aged E_j / seedCoef_j against a seed-anchored prior. */
    val level: Float,
    /** Shrunk pooled mean per exercise (own aged belief blended toward the sibling LOO prediction). */
    val effectiveE1rm: Map<Long, Float>,
    /** Display/prescription coefficient: effectiveE1rm[i] / level. */
    val derivedCoef: Map<Long, Float>,
    /** Own live aged belief std per exercise (un-shrunk) — the z-shading input for the policy. */
    val pooledSigma: Map<Long, Float>,
)

/**
 * Read-time pooling. Computes a muscle level from the precision-weighted votes of all loaded
 * exercises, then shrinks each exercise's own belief toward the leave-one-out sibling prediction
 * in proportion to its own vs. prediction precision. Pure; cross-informing happens here and never
 * mutates the stored per-exercise beliefs.
 */
class MuscleStrengthProjector(private val config: EstimatorConfig = EstimatorConfig()) {
    private val updater = BeliefUpdater(config)

    /**
     * Pooling precision of an aged belief given its transfer tightness τ: 1/(evidenceVar + τ²).
     * Reads the ADAPTATION-IMMUNE evidenceVar (not live sigma2), so a surprise-inflated σ is not
     * misread as "uninformed" and dragged back by confident siblings (the prod-BSS regression).
     */
    fun poolPrecision(aged: ExerciseBelief, tau: Float): Float = 1f / (aged.evidenceVar + tau * tau)

    private data class Loaded(
        val id: Long, val belief: ExerciseBelief, val coef: Float,
        val tau: Float, val opinion: Float, val votePrec: Float,
    )

    fun project(
        beliefs: Map<Long, ExerciseBelief>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
        muscleLastObs: Long? = null,
        equipment: Map<Long, Equipment> = emptyMap(),
    ): MuscleProjection {
        val loaded = muscleExerciseIds.mapNotNull { id ->
            val b0 = beliefs[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) return@mapNotNull null
            val aged = updater.age(b0, now, muscleLastObs)
            val tau = config.tauFor(equipment[id])
            Loaded(id, aged, coef, tau, aged.mu - ln(coef), poolPrecision(aged, tau))
        }
        if (loaded.isEmpty()) return MuscleProjection(0f, emptyMap(), emptyMap(), emptyMap())

        // Bayesian posterior on the muscle level ℓ over an included set. Prior mean = unweighted mean
        // of the included opinions (seed level for a cold muscle); fixed prior precision λ₀. Returns
        // (lnLevel, σ_ℓ²). Excluding an id gives that exercise's leave-one-out prediction.
        fun posterior(exclude: Long?): Pair<Float, Float> {
            val incl = loaded.filter { it.id != exclude }
            val prior = if (incl.isEmpty())
                loaded.first { it.id == exclude }.opinion   // lone exercise: LOO prior = its own opinion ⇒ pred == own μ
            else incl.map { it.opinion }.average().toFloat()
            var p = config.levelAnchorPrecision
            var num = config.levelAnchorPrecision * prior
            for (l in incl) { p += l.votePrec; num += l.votePrec * l.opinion }
            return (num / p) to (1f / p)
        }

        val (lnLevel, _) = posterior(null)
        val level = exp(lnLevel)

        val effective = mutableMapOf<Long, Float>()
        val coefs = mutableMapOf<Long, Float>()
        val sigmas = mutableMapOf<Long, Float>()
        for (l in loaded) {
            val (lnLevelLoo, sigmaL2Loo) = posterior(l.id)
            val lnPred = ln(l.coef) + lnLevelLoo
            val predPrec = 1f / (sigmaL2Loo + l.tau * l.tau)
            val ownPrec = 1f / l.belief.evidenceVar          // borrow weight from the immune track
            val lnUsed = (ownPrec * l.belief.mu + predPrec * lnPred) / (ownPrec + predPrec)
            effective[l.id] = exp(lnUsed)
            coefs[l.id] = if (level > 0f) exp(lnUsed) / level else l.coef
            sigmas[l.id] = l.belief.sigma                    // own live aged σ, un-shrunk (spec §3 divergence)
        }
        return MuscleProjection(level, effective, coefs, sigmas)
    }
}
