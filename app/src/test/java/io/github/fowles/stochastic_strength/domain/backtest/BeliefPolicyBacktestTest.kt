package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefPrescriber
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Phase-2 invariant + health report on real history (constitution rules 3/4): with the policy
 * layer applied on top of the BELIEF stack's raw held-out predictions (z + overload nudge, as
 * Phase 3 will wire them), no prescription may reach a weight the user failed in that exercise's
 * most recent prior session (within the cap expiry); and every run reports the clamp-bind rate.
 * Skips when history.json is absent.
 *
 * Phase-1 rate over main's stack was 3.1% (1560 prescriptions) with exercises 21/77/30 chronic —
 * note history.json was updated since, so raw counts aren't perfectly comparable; still reported
 * side by side. If the belief stack does not visibly reduce those three exercises' bind counts,
 * flag it at review (rule 4: frequent binds = estimator bug).
 */
class BeliefPolicyBacktestTest {

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
        val bindsByExercise = mutableMapOf<Long, Int>()
        val violations = mutableListOf<String>()

        BeliefStackReplay.run(data, BeliefConfig()) { sessionId, asOf, _, effective, _ ->
            val facts = PolicyFacts.build(seen, muscleMap)
            for ((exerciseId, eff) in effective) {
                val muscle = muscleMap[exerciseId] ?: continue
                val raw = BeliefPrescriber.targetE1rm(eff)
                val reps = data.setsBySession[sessionId].orEmpty()
                    .firstOrNull { it.exerciseId == exerciseId }?.targetReps ?: 10
                val p = PrescriptionPolicy.prescribe(
                    rawE1rm = raw, sessionReps = reps, exerciseId = exerciseId, muscle = muscle,
                    facts = facts, now = asOf, weightUnit = data.weightUnit, engine = DefaultProgressionEngine,
                    overloadNudge = true,
                )
                prescriptions++
                if (p.capBound) {
                    capBinds++
                    bindsByExercise[exerciseId] = (bindsByExercise[exerciseId] ?: 0) + 1
                }
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
                        overloadNudge = true,
                    )
                    if (pf.weightKg >= f.targetWeight - 1e-3f) {
                        violations += "session $sessionId ex $exerciseId: prescribed ${pf.weightKg} kg ≥ failed ${f.targetWeight} kg (@${f.targetReps} reps)"
                    }
                }
            }
            // Update trackers AFTER checking (facts must describe only prior sessions).
            val sets = data.setsBySession[sessionId].orEmpty()
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
            appendLine("=== Phase 2 clamp-bind report (policy over BELIEF prescriptions, nudge ON) ===")
            appendLine("prescriptions checked : $prescriptions")
            appendLine("cap binds             : $capBinds (%.1f%%)".format(100.0 * capBinds / prescriptions))
            appendLine("hurt binds            : $hurtBinds")
            appendLine("per-exercise cap binds: " + bindsByExercise.entries.sortedByDescending { it.value }
                .joinToString { "ex ${it.key}=${it.value}" })
            appendLine("post-policy failure-invariant violations: ${violations.size}")
            violations.forEach { appendLine("  $it") }
        }
        println(report)
        assertTrue(report, violations.isEmpty())
    }
}
