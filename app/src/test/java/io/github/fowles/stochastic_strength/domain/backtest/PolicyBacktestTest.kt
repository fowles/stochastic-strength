package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Phase-1 invariant + health report on real history (constitution rules 3/4): with the policy
 * layer applied on top of main's raw held-out predictions, no prescription may reach a weight
 * the user failed in that exercise's most recent prior session (within the cap expiry); and
 * every run reports the clamp-bind rate. Skips when history.json is absent.
 */
class PolicyBacktestTest {

    @Test
    fun policyNeverRePrescribesAFailedWeight_andReportsClampBindRate() {
        val data = BacktestData.loadOrNull()
        Assume.assumeTrue("backtest/history.json not present; skipping", data != null)
        data!!

        val muscleMap = data.backup.exercises.associate { it.id to it.primaryMuscle }
        val seen = mutableListOf<WorkoutSet>()
        // Most recent feedback session's failed sets per exercise, and when.
        val lastFailure = mutableMapOf<Long, Pair<List<WorkoutSet>, Long>>()

        var prescriptions = 0
        var capBinds = 0
        var hurtBinds = 0
        val violations = mutableListOf<String>()

        MainStackReplay.run(data) { sessionId, asOf, sets, predictions, _ ->
            val facts = PolicyFacts.build(seen, muscleMap)
            for ((exerciseId, raw) in predictions) {
                val muscle = muscleMap[exerciseId] ?: continue
                val reps = sets.firstOrNull { it.exerciseId == exerciseId }?.targetReps ?: 10
                val p = PrescriptionPolicy.prescribe(
                    rawE1rm = raw, sessionReps = reps, exerciseId = exerciseId, muscle = muscle,
                    facts = facts, now = asOf, weightUnit = data.weightUnit, engine = DefaultProgressionEngine,
                )
                prescriptions++
                if (p.capBound) capBinds++
                if (p.hurtMultiplier < 1f) hurtBinds++

                // Invariant: strictly below every counted failure of the most recent prior
                // session (evaluated at that failed set's rep target, where strictness is
                // mathematically guaranteed by the a+½ cap).
                val (failedSets, at) = lastFailure[exerciseId] ?: continue
                if (asOf - at > PrescriptionPolicy.CAP_EXPIRY_MS) continue
                for (f in failedSets) {
                    val a = f.actualReps ?: continue
                    if (a >= f.targetReps) continue
                    val pf = PrescriptionPolicy.prescribe(
                        rawE1rm = raw, sessionReps = f.targetReps, exerciseId = exerciseId, muscle = muscle,
                        facts = facts, now = asOf, weightUnit = data.weightUnit, engine = DefaultProgressionEngine,
                    )
                    if (pf.weightKg >= f.targetWeight - 1e-3f) {
                        violations += "session $sessionId ex $exerciseId: prescribed ${pf.weightKg} kg ≥ failed ${f.targetWeight} kg (@${f.targetReps} reps)"
                    }
                }
            }
            // Update trackers AFTER checking (facts must describe only prior sessions).
            sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
                val scoreable = exSets.filter { it.feedback != null && it.feedback != SetFeedback.HURT }
                if (scoreable.isNotEmpty()) {
                    lastFailure[id] = scoreable.filter { it.feedback == SetFeedback.TOO_HARD } to asOf
                }
            }
            seen += sets
        }

        assertTrue(prescriptions > 0)
        val report = buildString {
            appendLine("=== Phase 1 clamp-bind report (policy over main's raw predictions) ===")
            appendLine("prescriptions checked : $prescriptions")
            appendLine("cap binds             : $capBinds (%.1f%%)".format(100.0 * capBinds / prescriptions))
            appendLine("hurt binds            : $hurtBinds")
            appendLine("post-policy failure-invariant violations: ${violations.size}")
            violations.forEach { appendLine("  $it") }
        }
        println(report)
        assertTrue(report, violations.isEmpty())
    }
}
