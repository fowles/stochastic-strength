package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** One plain-English gym sentence, plus the label naming the pipeline stage it explains. */
data class TraceLine(val label: String, val detail: String)

/** The full "why this weight" explanation for one exercise's prescription (spec Phase 3). */
data class PrescriptionTrace(val lines: List<TraceLine>, val finalWeightKg: Float)

/**
 * Explains a prescription line by line, one plain gym sentence per pipeline stage, each citing the
 * data behind it. NEVER re-implements policy/prescriber math — it calls `BeliefPrescriber.targetE1rm`
 * and `PrescriptionPolicy.prescribe` and reports what they did (one source of truth). The blend
 * weight in "Sibling pull" recomputes pOwn/pSib exactly as `BeliefPooling.effective` does, since that
 * class doesn't expose its intermediate per-voter weights.
 */
object PrescriptionTraceBuilder {
    private val dateFormat get() = SimpleDateFormat("MMM d", Locale.US)

    private fun sigmaPercent(sigma2: Float): Float = (exp(sqrt(sigma2.toDouble())).toFloat() - 1f) * 100f

    fun build(
        exerciseId: Long,
        muscle: MuscleGroup,
        beliefs: Map<Long, Belief>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        facts: PolicyFacts,
        capSessionSets: List<WorkoutSet>,
        sessionReps: Int,
        now: Long,
        weightUnit: WeightUnit,
        config: BeliefConfig = BeliefConfig(),
        engine: ProgressionEngine = DefaultProgressionEngine,
    ): PrescriptionTrace? {
        val fold = BeliefFold(config)
        val tau2 = config.tau * config.tau

        data class Voter(val id: Long, val vote: Float, val weight: Float)
        val voters = muscleExerciseIds.mapNotNull { id ->
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) return@mapNotNull null
            val b = beliefs[id]?.let { fold.aged(it, now) } ?: return@mapNotNull null
            Voter(id, b.mu - ln(coef), 1f / (b.sigma2 + tau2))
        }
        val sumW = voters.sumOf { it.weight.toDouble() }.toFloat()
        val sumWV = voters.sumOf { (it.weight * it.vote).toDouble() }.toFloat()

        val coef = seedCoef[exerciseId] ?: return null
        if (coef <= 0f) return null
        val own = beliefs[exerciseId]?.let { fold.aged(it, now) }
        val voter = voters.firstOrNull { it.id == exerciseId }
        val looW = sumW - (voter?.weight ?: 0f)
        val looWV = sumWV - ((voter?.weight ?: 0f) * (voter?.vote ?: 0f))
        val sibling: EffectiveBelief? = if (looW > 0f) {
            EffectiveBelief(mu = ln(coef) + looWV / looW, sigma2 = 1f / looW + tau2)
        } else null

        var pOwn = 0f
        var pSib = 0f
        val effective: EffectiveBelief = when {
            own != null && sibling != null -> {
                pOwn = 1f / own.sigma2
                pSib = 1f / sibling.sigma2
                EffectiveBelief(
                    mu = (pOwn * own.mu + pSib * sibling.mu) / (pOwn + pSib),
                    sigma2 = 1f / (pOwn + pSib),
                )
            }
            own != null -> EffectiveBelief(own.mu, own.sigma2)
            sibling != null -> sibling
            else -> return null
        }

        val ownLine = if (own != null) {
            TraceLine(
                "Own belief",
                "~${WeightFormatter.format(own.e1rm, weightUnit)} (±${"%.0f".format(sigmaPercent(own.sigma2))}%), " +
                    "last updated ${dateFormat.format(Date(own.updatedAt))}",
            )
        } else {
            TraceLine("Own belief", "none — cold exercise, leaning on siblings")
        }

        val siblingLine = if (sibling != null) {
            val blendPercent = if (own != null) (pSib / (pOwn + pSib)) * 100f else 100f
            TraceLine(
                "Sibling pull",
                "siblings imply ~${WeightFormatter.format(exp(sibling.mu), weightUnit)}; " +
                    "blended at ${"%.0f".format(blendPercent)}%",
            )
        } else {
            TraceLine("Sibling pull", "no siblings with evidence")
        }

        val effectiveLine = TraceLine(
            "Effective belief",
            "~${WeightFormatter.format(exp(effective.mu), weightUnit)} (±${"%.0f".format(sigmaPercent(effective.sigma2))}%)",
        )

        val rawE1rm = BeliefPrescriber.targetE1rm(effective)
        val riskLine = TraceLine(
            "Risk percentile",
            "prescribing at the 30th percentile: ~${WeightFormatter.format(rawE1rm, weightUnit)}",
        )

        val hurtEvents = facts.hurtEventsByMuscle[muscle].orEmpty()
        val hurtMultiplier = PrescriptionPolicy.hurtMultiplier(hurtEvents, now)
        val hurtLine = if (hurtEvents.isEmpty()) {
            TraceLine("HURT backoff", "none")
        } else {
            TraceLine("HURT backoff", "×${"%.2f".format(hurtMultiplier)} after ${hurtEvents.size} recent HURT set(s)")
        }

        val capFact = facts.capByExercise[exerciseId]
        val withinWindow = capFact != null && now - capFact.demonstratedAt <= PrescriptionPolicy.CAP_EXPIRY_MS
        val nudgeLine = if (withinWindow && capFact?.allEasy == true) {
            TraceLine("Overload nudge", "last session all easy → +one increment")
        } else {
            TraceLine("Overload nudge", "not applied")
        }

        val prescription = PrescriptionPolicy.prescribe(
            rawE1rm = rawE1rm,
            sessionReps = sessionReps,
            exerciseId = exerciseId,
            muscle = muscle,
            facts = facts,
            now = now,
            weightUnit = weightUnit,
            engine = engine,
        )

        val capLine = when {
            capFact == null -> TraceLine("Capacity cap", "no cap")
            !withinWindow -> TraceLine("Capacity cap", "no cap")
            prescription.capBound -> {
                val cited = capSessionSets.joinToString("; ") { citeSet(it) }
                val wanted = WeightFormatter.format(prescription.uncappedWeightKg, weightUnit)
                val capped = WeightFormatter.format(prescription.weightKg, weightUnit)
                TraceLine(
                    "Capacity cap",
                    "capped at $capped (wanted $wanted)" + if (cited.isNotEmpty()) ": $cited" else "",
                )
            }
            else -> {
                val capWeight = WeightFormatter.format(prescription.uncappedWeightKg, weightUnit)
                TraceLine("Capacity cap", "cap ~$capWeight, not binding")
            }
        }

        val roundingLine = TraceLine("Rounding", "final: ${WeightFormatter.format(prescription.weightKg, weightUnit)}")

        return PrescriptionTrace(
            lines = listOf(ownLine, siblingLine, effectiveLine, riskLine, hurtLine, nudgeLine, capLine, roundingLine),
            finalWeightKg = prescription.weightKg,
        )
    }

    private fun citeSet(set: WorkoutSet): String {
        val weight = "%.0f".format(set.targetWeight)
        return when (set.feedback) {
            SetFeedback.TOO_HARD -> "$weight kg × ${set.targetReps} → failed at ${set.actualReps ?: 0}"
            SetFeedback.RIR_2_4 -> "$weight kg × ${set.targetReps} → RIR 2–4"
            SetFeedback.RIR_5_PLUS -> "$weight kg × ${set.targetReps} → RIR 5+"
            SetFeedback.RIR_0_1 -> "$weight kg × ${set.targetReps} → RIR 0–1"
            SetFeedback.HURT -> "$weight kg × ${set.targetReps} → hurt"
            null -> "$weight kg × ${set.targetReps}"
        }
    }
}
