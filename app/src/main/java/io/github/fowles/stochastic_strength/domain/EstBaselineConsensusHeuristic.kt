package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit

class EstBaselineConsensusHeuristic(
    private val alpha: Float = 0.3f,
    private val stepUpMaxLog: Float = kotlin.math.ln(1.025f),
    private val stepDownMaxLog: Float = kotlin.math.ln(1.10f),
    private val hurtFactor: Float = 0.85f,
    private val safetyWindowMs: Long = 14L * 24 * 60 * 60 * 1000,
    private val safetyOscillateFlips: Int = 2,
    private val safetyConsistentLength: Int = 3,
    private val unit: WeightUnit = WeightUnit.KG,
) : BaselineHeuristic {

    override val name: String = "est-baseline-consensus"

    @Suppress("unused")
    private val coefHeuristic = EstCoefConsensusHeuristic()

    override fun compute(input: BaselineComputationInput): List<BaselineProposal> {
        val setsByMuscle = input.sets.groupBy { input.exerciseMuscle[it.exerciseId] }
        val out = mutableListOf<BaselineProposal>()
        for ((muscle, muscleSets) in setsByMuscle) {
            if (muscle == null) continue
            val bOld = input.currentBaselines[muscle] ?: continue
            if (bOld <= 0f) continue

            if (muscleSets.any { it.feedback == SetFeedback.HURT }) {
                val bNew = WeightFormatter.round(bOld * hurtFactor, unit)
                if (bNew != bOld) {
                    out.add(BaselineProposal(muscle, bNew, "hurt"))
                }
                continue
            }
        }
        return out
    }
}
