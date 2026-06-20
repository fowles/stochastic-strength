package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * Last-set RIR autoregulation controller. Per muscle, the most recent session's last
 * working set (at full, un-reduced weight) for each exercise maps to a target percentage;
 * the contributing percentages are averaged, floored to whole weight increments, then the
 * existing mid-session reduction clamp is applied. Replaces the implied-1RM estimator to
 * remove its high-rep fatigue downward bias. See
 * docs/superpowers/specs/2026-06-17-last-set-baseline-controller-design.md
 */
class LastSetAutoregulationHeuristic(
    private val bigUpPct: Float = 0.15f,
    private val moderateUpPct: Float = 0.10f,
    private val tinyUpPct: Float = 0.05f,
    private val smallDownPct: Float = 0.05f,
    private val hurtFactor: Float = 0.85f,
    private val nearMissReps: Int = 1,
) : BaselineHeuristic {

    override val name: String = "last-set-autoregulation"

    override fun compute(input: BaselineComputationInput): List<BaselineProposal> {
        val out = mutableListOf<BaselineProposal>()
        val setsByMuscle = input.sets.groupBy { input.exerciseMuscle[it.exerciseId] }
        val increment = WeightFormatter.minIncrement(input.weightUnit)
        for ((muscle, muscleSets) in setsByMuscle) {
            if (muscle == null) continue
            val bOld = input.currentBaselines[muscle] ?: continue
            if (bOld <= 0f) continue

            // Pain overrides everything.
            if (muscleSets.any { it.feedback == SetFeedback.HURT }) {
                val bNew = WeightFormatter.round(bOld * hurtFactor, input.weightUnit)
                if (bNew != bOld) out.add(BaselineProposal(muscle, bNew, "hurt"))
                continue
            }

            val pcts = muscleSets.groupBy { it.exerciseId }
                .values
                .mapNotNull { exerciseTargetPct(it) }
            val avgPct = if (pcts.isEmpty()) 0f else pcts.sum() / pcts.size

            // Floor the raw move to whole increments, toward zero, sign preserved.
            val rawMove = bOld * avgPct
            val steps = (kotlin.math.abs(rawMove) / increment).toInt()
            val flooredMove = if (rawMove >= 0f) steps * increment else -steps * increment
            var bNew = bOld + flooredMove

            // Reduction clamp: authoritative downward gate for mid-session drops.
            val minRed = input.minReductionFractions[muscle] ?: 0f
            if (minRed > 0f) {
                val cap = WeightFormatter.round(bOld * (1f - minRed), input.weightUnit)
                if (bNew > cap) bNew = cap
            }

            bNew = WeightFormatter.round(bNew, input.weightUnit)
            if (bNew == bOld) continue

            val meta = if (pcts.isEmpty()) "clamp" else "n=${pcts.size},avgPct=${"%.3f".format(java.util.Locale.ROOT, avgPct)}"
            out.add(BaselineProposal(muscle, bNew, meta))
        }
        return out
    }

    /**
     * Signed target fraction for the exercise's governing set, or null if the exercise
     * contributes no signal (reduced mid-session, no working sets, or no usable feedback).
     * HURT returns null here; it is handled at the muscle level in compute().
     */
    internal fun exerciseTargetPct(exerciseSets: List<WorkoutSet>): Float? {
        val bySetNumber = exerciseSets.sortedBy { it.setNumber } // All persisted sets are working sets — warmups advance UI state only and are never inserted.
        if (bySetNumber.isEmpty()) return null
        val fullWeight = bySetNumber.first().targetWeight
        val eps = 0.001f
        val reduced = bySetNumber.any { it.targetWeight < fullWeight - eps }
        if (reduced) return null // down-story handled by the reduction clamp
        val governing = bySetNumber.lastOrNull { it.targetWeight >= fullWeight - eps } ?: return null
        return when (governing.feedback) {
            null -> null
            SetFeedback.HURT -> null
            SetFeedback.RIR_5_PLUS -> bigUpPct
            SetFeedback.RIR_2_4 -> moderateUpPct
            SetFeedback.RIR_0_1 -> tinyUpPct
            SetFeedback.TOO_HARD -> {
                val reps = governing.actualReps
                when {
                    reps == null -> 0f
                    reps >= governing.targetReps - nearMissReps -> 0f
                    else -> -smallDownPct
                }
            }
        }
    }
}
