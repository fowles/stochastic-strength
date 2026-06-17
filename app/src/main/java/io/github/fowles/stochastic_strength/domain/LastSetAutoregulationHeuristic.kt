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

    override fun compute(input: BaselineComputationInput): List<BaselineProposal> = emptyList()

    /**
     * Signed target fraction for the exercise's governing set, or null if the exercise
     * contributes no signal (reduced mid-session, no working sets, or no usable feedback).
     * HURT returns null here; it is handled at the muscle level in compute().
     */
    internal fun exerciseTargetPct(exerciseSets: List<WorkoutSet>): Float? {
        val working = exerciseSets.sortedBy { it.setNumber }
        if (working.isEmpty()) return null
        val fullWeight = working.first().targetWeight
        val eps = 0.001f
        val reduced = working.any { it.targetWeight < fullWeight - eps }
        if (reduced) return null // down-story handled by the reduction clamp
        val governing = working.lastOrNull { it.targetWeight >= fullWeight - eps } ?: return null
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
