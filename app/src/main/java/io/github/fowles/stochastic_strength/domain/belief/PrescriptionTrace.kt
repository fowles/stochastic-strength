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
import kotlin.math.sqrt

/** One plain-English gym sentence, plus the label naming the pipeline stage it explains. */
data class TraceLine(val label: String, val detail: String)

/** The full "why this weight" explanation for one exercise's prescription (spec Phase 3). */
data class PrescriptionTrace(val lines: List<TraceLine>, val finalWeightKg: Float)

/**
 * Explains a prescription line by line, one plain gym sentence per pipeline stage, each citing the
 * data behind it. NEVER re-implements pipeline math: the pooling lines read
 * [BeliefPooling.effective]'s breakdown (own/sibling/blend share), and the policy lines read what
 * [PrescriptionPolicy.prescribe] reports it did (hurt multiplier, nudge, cap weight) — one source
 * of truth end to end.
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
        /** Inferred detraining factor the planner applies to the target (1f = no layoff backoff). */
        retention: Float = 1f,
        config: BeliefConfig = BeliefConfig(),
        engine: ProgressionEngine = DefaultProgressionEngine,
    ): PrescriptionTrace? {
        val pool = BeliefPooling(config).effective(beliefs, seedCoef, muscleExerciseIds, now)
        val effective = pool.effective[exerciseId] ?: return null
        val own = effective.own
        val sibling = effective.sibling

        val ownLine = if (own != null) {
            // The aged belief's updatedAt is `now`; the fold date the user cares about is the
            // stored belief's.
            val foldedAt = beliefs[exerciseId]?.updatedAt ?: now
            TraceLine(
                "Own belief",
                "~${WeightFormatter.format(own.e1rm, weightUnit)} (±${"%.0f".format(sigmaPercent(own.sigma2))}%), " +
                    "last updated ${dateFormat.format(Date(foldedAt))}",
            )
        } else {
            TraceLine("Own belief", "none — cold exercise, leaning on siblings")
        }

        val siblingLine = if (sibling != null) {
            TraceLine(
                "Sibling pull",
                "siblings imply ~${WeightFormatter.format(exp(sibling.mu), weightUnit)}; " +
                    "blended at ${"%.0f".format(effective.siblingShare * 100f)}%",
            )
        } else {
            TraceLine("Sibling pull", "no siblings with evidence")
        }

        val effectiveLine = TraceLine(
            "Effective belief",
            "~${WeightFormatter.format(exp(effective.mu), weightUnit)} (±${"%.0f".format(sigmaPercent(effective.sigma2))}%)",
        )

        val successTarget = BeliefPrescriber.targetE1rm(effective)
        val riskLine = TraceLine(
            "Success target",
            "aiming for a weight you'll make ~${(BeliefPrescriber.targetSuccessChance * 100).toInt()}% of the time: " +
                "~${WeightFormatter.format(successTarget, weightUnit)}",
        )

        // Detraining backoff: the planner eases the comeback target down by `retention` after a
        // layoff, then the set log self-corrects the belief. Mirror it here so the trace's final
        // weight matches what the workout screen actually prescribes.
        val rawE1rm = successTarget * retention
        val detrainLine = if (retention < 1f) {
            TraceLine(
                "Detraining backoff",
                "×${"%.2f".format(retention)} after a layoff → ~${WeightFormatter.format(rawE1rm, weightUnit)}",
            )
        } else null

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

        val hurtEvents = facts.hurtEventsByMuscle[muscle].orEmpty()
        val hurtLine = if (prescription.hurtMultiplier >= 1f) {
            TraceLine("HURT backoff", "none")
        } else {
            TraceLine(
                "HURT backoff",
                "×${"%.2f".format(prescription.hurtMultiplier)} after ${hurtEvents.size} recent HURT set(s)",
            )
        }

        val nudgeLine = if (prescription.nudgeKg > 0f) {
            TraceLine("Overload nudge", "last session all easy → +one increment")
        } else {
            TraceLine("Overload nudge", "not applied")
        }

        val capLine = when {
            prescription.capWeightKg == null -> TraceLine("Capacity cap", "no cap")
            prescription.capBound -> {
                val cited = capSessionSets.joinToString("; ") { citeSet(it) }
                val wanted = WeightFormatter.format(prescription.uncappedWeightKg, weightUnit)
                val capped = WeightFormatter.format(prescription.weightKg, weightUnit)
                TraceLine(
                    "Capacity cap",
                    "capped at $capped (wanted $wanted)" + if (cited.isNotEmpty()) ": $cited" else "",
                )
            }
            else -> TraceLine(
                "Capacity cap",
                "cap ~${WeightFormatter.format(prescription.capWeightKg, weightUnit)}, not binding",
            )
        }

        val roundingLine = TraceLine("Rounding", "final: ${WeightFormatter.format(prescription.weightKg, weightUnit)}")

        return PrescriptionTrace(
            lines = listOfNotNull(
                ownLine, siblingLine, effectiveLine, riskLine, detrainLine, hurtLine, nudgeLine, capLine, roundingLine,
            ),
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
