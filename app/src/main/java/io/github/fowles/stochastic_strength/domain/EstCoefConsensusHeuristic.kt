package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.ln

class EstCoefConsensusHeuristic(
    private val now: () -> Long = System::currentTimeMillis,
    private val tauHalfMs: Long = 14L * 24 * 60 * 60 * 1000,
    private val minEvidenceWeight: Float = 1.5f,
    private val minOutlierSessions: Int = 2,
    private val tauConsensusThreshold: Float = LN_105,
    private val tauOutlierThreshold: Float = LN_110,
    private val alpha: Float = 0.2f,
    private val maxLogStep: Float = LN_105,
    private val minChangeThreshold: Float = 0.005f,
) : CoefficientHeuristic {

    override val name: String = "est-coef-consensus"

    data class SetSignal(
        val est1RM: Float,
        val confidence: Float,
        val isUpperBound: Boolean,
        val isDefinite: Boolean,
    )

    override fun compute(input: CoefficientComputationInput): List<CoefficientResult> {
        // filled in by later tasks
        return emptyList()
    }

    internal fun setSignal(set: WorkoutSet): SetSignal? {
        val feedback = set.feedback ?: return null
        return when (feedback) {
            SetFeedback.HURT -> null
            SetFeedback.RIR_5_PLUS -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 7),
                confidence = 0.4f, isUpperBound = false, isDefinite = false,
            )
            SetFeedback.RIR_2_4 -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 3),
                confidence = 0.7f, isUpperBound = false, isDefinite = false,
            )
            SetFeedback.RIR_0_1 -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 1),
                confidence = 0.85f, isUpperBound = false, isDefinite = false,
            )
            SetFeedback.TOO_HARD -> {
                val reps = set.actualReps
                if (reps != null) SetSignal(
                    est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, reps),
                    confidence = 0.95f, isUpperBound = false, isDefinite = true,
                ) else SetSignal(
                    est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, maxOf(1, set.targetReps - 1)),
                    confidence = 0.5f, isUpperBound = true, isDefinite = false,
                )
            }
        }
    }

    companion object {
        private val LN_105 = ln(1.05f)
        private val LN_110 = ln(1.10f)
    }
}
