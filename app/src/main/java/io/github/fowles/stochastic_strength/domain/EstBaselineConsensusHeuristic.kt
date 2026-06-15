package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import java.util.Locale

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
                if (bNew != bOld) out.add(BaselineProposal(muscle, bNew, "hurt"))
                continue
            }

            val perSet = muscleSets.mapNotNull { wsSet ->
                val sig = coefHeuristic.setSignal(wsSet) ?: return@mapNotNull null
                val coef = input.currentCoefficients[wsSet.exerciseId] ?: return@mapNotNull null
                if (coef <= 0f) return@mapNotNull null
                PerSet(sig, sig.est1RM / coef)
            }
            if (perSet.isEmpty()) continue

            val agg = aggregateImplied(perSet) ?: continue
            val bTarget = agg.value
            val rawLog = alpha * agg.confidence *
                kotlin.math.ln((bTarget / bOld).toDouble()).toFloat()
            val safety = classifySafety(input.recentHistory[muscle], input.asOf)
            val upCap = when (safety) {
                Safety.OSCILLATING -> stepUpMaxLog * 0.5f
                Safety.CONSISTENT_UP -> stepUpMaxLog * 2.0f
                else -> stepUpMaxLog
            }
            val downCap = stepDownMaxLog
            val clamped = rawLog.coerceIn(-downCap, upCap)
            val bRaw = bOld * kotlin.math.exp(clamped.toDouble()).toFloat()
            var bNew = WeightFormatter.round(bRaw, unit)

            val effectiveCap = if (rawLog >= 0f) upCap else downCap
            val capBound = kotlin.math.abs(rawLog) > effectiveCap
            if (capBound && bNew == bOld) {
                val step = WeightFormatter.minIncrement(unit)
                bNew = if (rawLog > 0f) bOld + step else bOld - step
            }

            val minRed = input.minReductionFractions[muscle] ?: 0f
            if (minRed > 0f) {
                val cap = WeightFormatter.round(bOld * (1f - minRed), unit)
                if (bNew > cap) bNew = cap
            }

            if (bNew == bOld) continue
            val safetyLabel = when (safety) {
                Safety.DEFAULT -> "default"
                Safety.OSCILLATING -> "oscillating"
                Safety.CONSISTENT_UP -> "consistent_up"
                Safety.MIXED -> "mixed"
            }
            out.add(BaselineProposal(
                muscle,
                bNew,
                "target=${"%.2f".format(Locale.ROOT, bTarget)},conf=${"%.2f".format(Locale.ROOT, agg.confidence)},safety=$safetyLabel",
            ))
        }
        return out
    }

    private enum class Safety { DEFAULT, OSCILLATING, CONSISTENT_UP, MIXED }

    private fun classifySafety(
        history: List<io.github.fowles.stochastic_strength.data.model.BaselineHistory>?,
        asOf: Long,
    ): Safety {
        if (history == null) return Safety.DEFAULT
        val window = history.filter {
            it.timestamp >= asOf - safetyWindowMs &&
                it.changeReason != io.github.fowles.stochastic_strength.data.model.BaselineChangeReason.INITIAL
        }
        val signs = window.mapNotNull {
            val d = it.newBaseline - it.previousBaseline
            when {
                d > 0f -> +1
                d < 0f -> -1
                else -> null
            }
        }
        if (signs.isEmpty()) return Safety.DEFAULT
        var flips = 0
        for (i in 1 until signs.size) if (signs[i] != signs[i - 1]) flips++
        val oscillating = flips >= safetyOscillateFlips
        val consistentUp = signs.size >= safetyConsistentLength &&
            signs.takeLast(safetyConsistentLength).all { it > 0 }
        return when {
            oscillating && consistentUp -> Safety.MIXED
            oscillating -> Safety.OSCILLATING
            consistentUp -> Safety.CONSISTENT_UP
            else -> Safety.DEFAULT
        }
    }

    private data class PerSet(val signal: EstCoefConsensusHeuristic.SetSignal, val implied: Float)
    private data class Aggregate(val value: Float, val confidence: Float)

    private fun aggregateImplied(perSet: List<PerSet>): Aggregate? {
        if (perSet.isEmpty()) return null
        val nonUpper = perSet.filter { !it.signal.isUpperBound }
        val included = if (nonUpper.isEmpty()) {
            perSet
        } else {
            val nonUpperTotalConf = nonUpper.sumOf { it.signal.confidence.toDouble() }.toFloat()
            if (nonUpperTotalConf <= 0f) return null
            val nonUpperMean = nonUpper.sumOf { (it.implied * it.signal.confidence).toDouble() }
                .toFloat() / nonUpperTotalConf
            perSet.filter { p -> !p.signal.isUpperBound || nonUpperMean > p.implied }
        }
        if (included.isEmpty()) return null
        val totalConf = included.sumOf { it.signal.confidence.toDouble() }.toFloat()
        if (totalConf <= 0f) return null
        val weightedValue = included.sumOf { (it.implied * it.signal.confidence).toDouble() }
            .toFloat() / totalConf
        val avgConf = totalConf / included.size
        return Aggregate(weightedValue, avgConf)
    }
}
