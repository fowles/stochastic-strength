package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.ln

class EstCoefConsensusHeuristic(
    private val tauHalfMs: Long = 14L * 24 * 60 * 60 * 1000,
    private val minPeers: Int = 2,
    private val peerWeightEpsilon: Float = 1e-4f,
    private val alpha: Float = 0.2f,
    private val maxLogStep: Float = ln(1.05f),
    private val minRelativeChange: Float = 0.005f,
) : CoefficientHeuristic {

    override val name: String = "est-coef-consensus"

    data class SetSignal(
        val est1RM: Float,
        val confidence: Float,
        val isUpperBound: Boolean,
        val isDefinite: Boolean,
    )

    override fun compute(input: CoefficientComputationInput): List<CoefficientResult> {
        val buckets = input.sets.groupBy { it.sessionId to it.exerciseId }
        val perExerciseSignals = mutableMapOf<Long, MutableList<SessionSignal>>()

        for ((key, bucketSets) in buckets) {
            val (sessionId, exerciseId) = key
            val current = input.currentCoefficients[exerciseId] ?: 0f
            if (current <= 0f) continue
            if (input.exerciseMuscle[exerciseId] == null) continue
            val sessionTime = input.sessionTimes[sessionId] ?: continue
            val agg = aggregateSession(bucketSets) ?: continue
            perExerciseSignals.getOrPut(exerciseId) { mutableListOf() }
                .add(
                    SessionSignal(
                        sessionId = sessionId,
                        sessionTime = sessionTime,
                        est1RM = agg.est1RM,
                        sessionConfidence = agg.sessionConfidence,
                    )
                )
        }

        val estimates = perExerciseSignals.mapNotNull { (id, signals) ->
            computeEstimate(signals)?.let { id to it }
        }.toMap()
        if (estimates.isEmpty()) return emptyList()

        val emits = applyPeerConsensus(estimates, input.currentCoefficients, input.exerciseMuscle)

        return emits.mapNotNull { (id, emit) ->
            val cur = input.currentCoefficients[id] ?: return@mapNotNull null
            damp(id, emit, cur)
        }
    }

    data class SessionAggregate(
        val est1RM: Float,
        val sessionConfidence: Float,
        val hasDefinite: Boolean,
    )

    internal fun aggregateSession(sets: List<WorkoutSet>): SessionAggregate? {
        val signals = sets.mapNotNull { setSignal(it) }
        if (signals.isEmpty()) return null

        val nonUpperBound = signals.filter { !it.isUpperBound }
        val included = if (nonUpperBound.isEmpty()) {
            signals
        } else {
            val nonBoundMean = nonUpperBound.sumOf { (it.est1RM * it.confidence).toDouble() }
                .toFloat() / nonUpperBound.sumOf { it.confidence.toDouble() }.toFloat()
            signals.filter { sig ->
                if (!sig.isUpperBound) true
                else nonBoundMean > sig.est1RM
            }
        }
        if (included.isEmpty()) return null

        val totalConf = included.sumOf { it.confidence.toDouble() }.toFloat()
        val weighted1RM = included.sumOf { (it.est1RM * it.confidence).toDouble() }.toFloat() / totalConf
        val avgConf = totalConf / included.size
        return SessionAggregate(
            est1RM = weighted1RM,
            sessionConfidence = avgConf,
            hasDefinite = signals.any { it.isDefinite },
        )
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
                if (reps != null) {
                    SetSignal(
                        est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, reps),
                        confidence = 0.95f,
                        isUpperBound = false,
                        isDefinite = true,
                    )
                } else {
                    SetSignal(
                        est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, maxOf(1, set.targetReps - 1)),
                        confidence = 0.5f,
                        isUpperBound = true,
                        isDefinite = false,
                    )
                }
            }
        }
    }

    data class SessionSignal(
        val sessionId: Long,
        val sessionTime: Long,
        val est1RM: Float,
        val sessionConfidence: Float,
    )

    data class ExerciseEstimate(
        val est1RM: Float,
        val weight: Float,
        val confidence: Float,
    )

    internal fun computeEstimate(signals: List<SessionSignal>): ExerciseEstimate? {
        if (signals.isEmpty()) return null
        val nowT = signals.maxOf { it.sessionTime }
        val ln2OverHalf = ln(2.0) / tauHalfMs
        val weighted = signals.map { s ->
            val recency = kotlin.math.exp(-(nowT - s.sessionTime).coerceAtLeast(0L) * ln2OverHalf).toFloat()
            Triple(s, recency, recency * s.sessionConfidence)
        }
        val totalWeight = weighted.sumOf { it.third.toDouble() }.toFloat()
        val median = weightedMedian(weighted.map { it.first.est1RM to it.third })
        val recencySum = weighted.sumOf { it.second.toDouble() }.toFloat()
        val confSum = weighted.sumOf { (it.second * it.first.sessionConfidence).toDouble() }.toFloat()
        val confidence = if (recencySum > 0f) confSum / recencySum else 0f
        return ExerciseEstimate(est1RM = median, weight = totalWeight, confidence = confidence)
    }

    data class EmitProposal(
        val proposal: Float,
        val confidence: Float,
        val metadata: String?,
    )

    private data class Peer(val id: Long, val impliedBaseline: Float, val weight: Float)

    internal fun applyPeerConsensus(
        estimates: Map<Long, ExerciseEstimate>,
        currentCoefficients: Map<Long, Float>,
        exerciseMuscle: Map<Long, MuscleGroup>,
    ): Map<Long, EmitProposal> {
        val out = mutableMapOf<Long, EmitProposal>()
        val groups = estimates.keys.groupBy { exerciseMuscle[it] }
        for ((muscle, idsInMuscle) in groups) {
            if (muscle == null) continue
            val peers = idsInMuscle.mapNotNull { id ->
                val est = estimates.getValue(id)
                val c = currentCoefficients[id] ?: 0f
                if (c <= 0f) return@mapNotNull null
                Peer(id, est.est1RM / c, est.weight)
            }
            for (id in idsInMuscle) {
                val est = estimates.getValue(id)
                val c = currentCoefficients[id] ?: 0f
                if (c <= 0f) continue
                val others = peers.filter { it.id != id && it.weight > peerWeightEpsilon }
                if (others.size < minPeers) continue
                val reference = weightedMedian(others.map { it.impliedBaseline to it.weight })
                if (reference <= 0f) continue
                val proposal = est.est1RM / reference
                out[id] = EmitProposal(proposal, est.confidence, "peer_consensus:peers=${others.size}")
            }
        }
        return out
    }

    internal fun damp(exerciseId: Long, emit: EmitProposal, currentCoef: Float): CoefficientResult? {
        if (currentCoef <= 0f) return null
        val raw = alpha * emit.confidence * ln((emit.proposal / currentCoef).toDouble()).toFloat()
        val step = raw.coerceIn(-maxLogStep, maxLogStep)
        val newCoef = currentCoef * kotlin.math.exp(step.toDouble()).toFloat()
        if (kotlin.math.abs(newCoef - currentCoef) < minRelativeChange * currentCoef) return null
        return CoefficientResult(exerciseId, newCoef, emit.metadata)
    }

    private fun weightedMedian(valueWeights: List<Pair<Float, Float>>): Float {
        val sorted = valueWeights.sortedBy { it.first }
        val total = sorted.sumOf { it.second.toDouble() }.toFloat()
        val half = total / 2f
        var cum = 0f
        for ((v, w) in sorted) {
            cum += w
            if (cum >= half) return v
        }
        return sorted.last().first
    }
}
