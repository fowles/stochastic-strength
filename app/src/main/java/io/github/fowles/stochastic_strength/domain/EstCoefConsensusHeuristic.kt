package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.ln

class EstCoefConsensusHeuristic(
    private val tauHalfMs: Long = 14L * 24 * 60 * 60 * 1000,
    private val minEvidenceWeight: Float = 1.5f,
    private val minOutlierSessions: Int = 2,
    private val tauConsensusThreshold: Float = ln(1.05f),
    private val tauOutlierThreshold: Float = LN_110,
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
            val muscle = input.exerciseMuscle[exerciseId] ?: continue
            val baseline = input.baselines[sessionId to muscle] ?: continue
            if (baseline <= 0f) continue
            val sessionTime = input.sessionTimes[sessionId] ?: continue
            val agg = aggregateSession(bucketSets) ?: continue
            perExerciseSignals.getOrPut(exerciseId) { mutableListOf() }
                .add(SessionSignal(
                    sessionId = sessionId,
                    sessionTime = sessionTime,
                    estCoef = agg.est1RM / baseline,
                    sessionConfidence = agg.sessionConfidence,
                    hasDefinite = agg.hasDefinite,
                ))
        }

        val h1Proposals = perExerciseSignals.mapNotNull { (id, signals) ->
            computeH1(signals)?.let { id to it }
        }.toMap()
        if (h1Proposals.isEmpty()) return emptyList()

        val survivors = applyH2(h1Proposals, input.currentCoefficients, input.exerciseMuscle)

        return survivors.mapNotNull { (id, emit) ->
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
        val estCoef: Float,
        val sessionConfidence: Float,
        val hasDefinite: Boolean,
    )

    data class H1Proposal(
        val proposal: Float,
        val totalWeight: Float,
        val proposalConfidence: Float,
        val hasDefinite: Boolean,
        val sessionCount: Int,
    )

    internal fun computeH1(signals: List<SessionSignal>): H1Proposal? {
        if (signals.isEmpty()) return null
        val nowT = signals.maxOf { it.sessionTime }
        val ln2OverHalf = ln(2.0) / tauHalfMs
        val weighted = signals.map { s ->
            val recency = kotlin.math.exp(-(nowT - s.sessionTime).coerceAtLeast(0L) * ln2OverHalf).toFloat()
            Triple(s, recency, recency * s.sessionConfidence)
        }
        val totalWeight = weighted.sumOf { it.third.toDouble() }.toFloat()
        val hasDefinite = signals.any { it.hasDefinite }
        if (totalWeight < minEvidenceWeight && !hasDefinite) return null

        val median = weightedMedian(weighted.map { it.first.estCoef to it.third })
        val recencySum = weighted.sumOf { it.second.toDouble() }.toFloat()
        val confSum = weighted.sumOf { (it.second * it.first.sessionConfidence).toDouble() }.toFloat()
        val proposalConfidence = if (recencySum > 0f) confSum / recencySum else 0f

        return H1Proposal(
            proposal = median,
            totalWeight = totalWeight,
            proposalConfidence = proposalConfidence,
            hasDefinite = hasDefinite,
            sessionCount = signals.size,
        )
    }

    data class EmitProposal(
        val proposal: Float,
        val confidence: Float,
        val metadata: String?,
    )

    internal fun applyH2(
        proposals: Map<Long, H1Proposal>,
        currentCoefficients: Map<Long, Float>,
        exerciseMuscle: Map<Long, io.github.fowles.stochastic_strength.data.model.MuscleGroup> = emptyMap(),
    ): Map<Long, EmitProposal> {
        val out = mutableMapOf<Long, EmitProposal>()
        // If exerciseMuscle is empty (test convenience), treat all as one synthetic group.
        val groups = if (exerciseMuscle.isEmpty()) {
            mapOf("ALL" to proposals.keys.toList())
        } else {
            proposals.keys.groupBy { exerciseMuscle[it]?.name ?: "UNKNOWN" }
        }

        for ((muscleName, exerciseIds) in groups) {
            val entries = exerciseIds.map { id ->
                val p = proposals.getValue(id)
                val cur = currentCoefficients[id] ?: 0f
                if (cur <= 0f) return@map null
                Triple(id, p, ln((p.proposal / cur).toDouble()).toFloat())
            }.filterNotNull()

            val n = entries.size
            when {
                n == 0 -> { /* nothing */ }
                n == 1 -> {
                    val (id, p, _) = entries.single()
                    out[id] = EmitProposal(p.proposal, p.proposalConfidence, null)
                }
                else -> {
                    val mean = entries.sumOf { it.third.toDouble() }.toFloat() / n
                    val sameSign = entries.all { it.third >= 0f } || entries.all { it.third <= 0f }
                    if (sameSign && kotlin.math.abs(mean) > tauConsensusThreshold) {
                        // suppress all
                    } else {
                        val outlierCandidates = entries.filter { kotlin.math.abs(it.third) > tauOutlierThreshold }
                        val siblings = entries - outlierCandidates.toSet()
                        val siblingsCalm = siblings.all { kotlin.math.abs(it.third) < tauConsensusThreshold }
                        if (n >= 3 && outlierCandidates.size == 1 && siblingsCalm
                            && outlierCandidates.single().second.sessionCount >= minOutlierSessions) {
                            val (id, p, _) = outlierCandidates.single()
                            out[id] = EmitProposal(p.proposal, 1.0f, "consensus_outlier:m=$muscleName,sibling_count=${n - 1}")
                        } else {
                            for ((id, p, _) in entries) {
                                val meta = "consensus_mixed:m=$muscleName,n=$n"
                                out[id] = EmitProposal(p.proposal, p.proposalConfidence, meta)
                            }
                        }
                    }
                }
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

    companion object {
        private val LN_110 = ln(1.10f)
    }
}
