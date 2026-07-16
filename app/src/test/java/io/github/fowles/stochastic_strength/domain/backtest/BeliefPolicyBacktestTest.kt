package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefPrescriber
import io.github.fowles.stochastic_strength.domain.policy.ExerciseCapFact
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import org.junit.Assert.assertEquals
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

        // PolicyFacts folded incrementally per session (a full PolicyFacts.build over the
        // cumulative log is O(sessions²)); the end-of-replay guard below asserts equivalence with
        // build() so the two can't drift.
        val capByExercise = mutableMapOf<Long, ExerciseCapFact>()
        val hurtEventsByMuscle = mutableMapOf<MuscleGroup, MutableList<Long>>()
        fun currentFacts() = PolicyFacts(
            capByExercise = capByExercise.toMap(),
            hurtEventsByMuscle = hurtEventsByMuscle.mapValues { it.value.toList() },
        )

        var prescriptions = 0
        var capBinds = 0
        var hurtBinds = 0
        val bindsByExercise = mutableMapOf<Long, Int>()
        val bindOvershoots = mutableListOf<Float>()  // kg the engine wanted above the capped weight
        val violations = mutableListOf<String>()

        BeliefStackReplay.run(data, BeliefConfig()) { sessionId, asOf, _, effective, _ ->
            val facts = currentFacts()
            for ((exerciseId, eff) in effective) {
                val muscle = muscleMap[exerciseId] ?: continue
                val raw = BeliefPrescriber.targetE1rm(eff)
                val reps = data.setsBySession[sessionId].orEmpty()
                    .firstOrNull { it.exerciseId == exerciseId }?.targetReps ?: 10
                val p = PrescriptionPolicy.prescribe(
                    rawE1rm = raw, sessionReps = reps, exerciseId = exerciseId, muscle = muscle,
                    facts = facts, now = asOf, weightUnit = data.weightUnit, engine = DefaultProgressionEngine,
                )
                prescriptions++
                if (p.capBound) {
                    capBinds++
                    bindsByExercise[exerciseId] = (bindsByExercise[exerciseId] ?: 0) + 1
                    bindOvershoots += p.uncappedWeightKg - p.weightKg
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
                    )
                    if (pf.weightKg >= f.targetWeight - 1e-3f) {
                        violations += "session $sessionId ex $exerciseId: prescribed ${pf.weightKg} kg ≥ failed ${f.targetWeight} kg (@${f.targetReps} reps)"
                    }
                }
            }
            // Update trackers AFTER checking (facts must describe only prior sessions).
            val sets = data.setsBySession[sessionId].orEmpty()
            val completed = sets.filter { it.completedAt != null }
            completed.groupBy { it.exerciseId }.forEach { (id, exSets) ->
                // Newer sessions supersede entirely; sessions arrive in replay (endTime, id)
                // order, matching PolicyFacts.build's (max completedAt, sessionId) pick.
                val feedbacks = exSets.mapNotNull { it.feedback }
                if (exSets.any { it.feedback != null && it.feedback != SetFeedback.HURT }) {
                    capByExercise[id] = ExerciseCapFact(
                        capLn = PrescriptionPolicy.capLnFor(exSets),
                        demonstratedAt = exSets.maxOf { it.completedAt!! },
                        allEasy = feedbacks.isNotEmpty() && feedbacks.all {
                            it == SetFeedback.RIR_2_4 || it == SetFeedback.RIR_5_PLUS
                        },
                    )
                }
            }
            // One HURT backoff event per (session, muscle).
            completed.filter { it.feedback == SetFeedback.HURT }
                .mapNotNull { s -> muscleMap[s.exerciseId]?.let { m -> m to s.completedAt!! } }
                .groupBy({ it.first }, { it.second })
                .forEach { (m, times) -> hurtEventsByMuscle.getOrPut(m) { mutableListOf() } += times.max() }

            sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
                val scoreable = exSets.filter { it.feedback != null && it.feedback != SetFeedback.HURT }
                if (scoreable.isNotEmpty()) {
                    lastFailure[id] = scoreable.filter { it.feedback == SetFeedback.TOO_HARD } to asOf
                }
            }
            seen += sets
        }

        // Drift guard: the incremental fold must equal a from-scratch build over the whole log.
        val rebuilt = PolicyFacts.build(seen, muscleMap)
        assertEquals(rebuilt.capByExercise, currentFacts().capByExercise)
        assertEquals(
            rebuilt.hurtEventsByMuscle.mapValues { it.value.sorted() },
            currentFacts().hurtEventsByMuscle.mapValues { it.value.sorted() },
        )

        assertTrue(prescriptions > 0)
        val report = buildString {
            appendLine("=== Phase 2 clamp-bind report (policy over BELIEF prescriptions, nudge ON) ===")
            appendLine("prescriptions checked : $prescriptions")
            appendLine("cap binds             : $capBinds (%.1f%%)".format(100.0 * capBinds / prescriptions))
            appendLine("hurt binds            : $hurtBinds")
            appendLine("per-exercise cap binds: " + bindsByExercise.entries.sortedByDescending { it.value }
                .joinToString { "ex ${it.key}=${it.value}" })
            val inc = io.github.fowles.stochastic_strength.domain.WeightFormatter.minIncrement(data.weightUnit)
            val inIncrements = bindOvershoots.map { it / inc }
            appendLine("bind magnitude (grid increments of %.2f kg):".format(inc))
            appendLine("  ≤1: ${inIncrements.count { it <= 1f + 1e-3f }}  " +
                "≤2: ${inIncrements.count { it <= 2f + 1e-3f }}  " +
                ">2: ${inIncrements.count { it > 2f + 1e-3f }}")
            appendLine("  mean %.2f  max %.2f".format(inIncrements.average(), inIncrements.max()))
            appendLine("post-policy failure-invariant violations: ${violations.size}")
            violations.forEach { appendLine("  $it") }
        }
        println(report)
        assertTrue(report, violations.isEmpty())
    }
}
