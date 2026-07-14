# Phase 1: Policy Layer (Log-Fact Clamps) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a pure prescription-time policy layer (demonstrated-capacity cap, HURT backoff, rest cooldown) computed from raw set-log facts, wired into the live planner and verified by clamp-behavior invariants on real history — retiring the magic-number ProdBss 20 lb pin.

**Architecture:** New `domain/policy/` package: `SetIntervals` (bounds table, promoted from the backtest tree), `PolicyFacts` (plain restatement of the set log), and `PrescriptionPolicy` (semantic constants + `prescribe(rawE1rm, …) → weight`). `WorkoutPlanner.weightForExercise` routes machine prescriptions through `prescribe`; manual e1rm overrides bypass policy (explicit user decision). HURT moves out of the estimator (`ExerciseEstimateUpdater.hurt` deleted) into the policy backoff. The backtest harness gains a policy invariant test + clamp-bind-rate report; the raw held-out scorer is untouched.

**Tech Stack:** Kotlin, JUnit4 on JVM (`:app:testDebugUnitTest`), jj for commits.

**Spec:** `docs/superpowers/specs/2026-07-14-estimator-rebuild-design.md` (Phase 1 section + constitution).

## Global Constraints

- **Constitution rule 6 (boundary criterion):** policy code is plain arithmetic restatements of set-log facts — no decay-curve inference beyond the stated semantic fade, no uncertainty, no learned constants.
- **Constitution rule 3:** all policy constants are `semantic` — labeled in code comments, never tuned, invisible to the fitness function. `HeldOutScorer` must not change in any task.
- **Semantic constants (exact values from spec):** cap expiry 28 days; HURT depth 0.15, half-life 14 days, floor 0.6; cooldown 2 days; failure caps round **down** at the grid.
- **Phase-0 artifacts stay stable:** `CapViolationDiagnostic` (the 49-violation baseline) and `BaselineReportTest` math must not change. Baseline to preserve: total 26.7593 ln-units, mean/set 0.12563, 213 scored / 9 skipped, 49 cap violations.
- No Room schema change, no DB version bump.
- Commits via `jj commit -m "…"` at the end of every task (repo convention: `feat(...)`/`test(...)`/`refactor(...)` prefixes).
- `app/src/test/resources/backtest/history.json` is local-only (gitignored); tests over it must `Assume`-skip when absent.
- After each task run its specific test target; the final task runs the full JVM suite and `:app:connectedAndroidTest` (emulator is typically already running).

---

### Task 1: Promote the bounds table to prod (`domain/policy/SetIntervals.kt`)

The policy cap is defined in terms of the model-free set intervals, and the Phase-2 estimator will consume the same table, so it becomes prod code. Pure move + package rename; no behavior change.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/SetIntervals.kt`
- Delete: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/SetIntervals.kt`
- Move: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/SetIntervalsTest.kt` → `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/SetIntervalsTest.kt`
- Modify (imports only): `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/HeldOutScorer.kt`, `.../backtest/CapViolationDiagnostic.kt`, `.../backtest/HeldOutScorerTest.kt`

**Interfaces:**
- Produces: `io.github.fowles.stochastic_strength.domain.policy.LnInterval(lowerLn: Float?, upperLn: Float?)` with `distanceTo(pointLn: Float): Float`; `io.github.fowles.stochastic_strength.domain.policy.SetIntervals.impliedLn1RmInterval(set: WorkoutSet): LnInterval?` — exact same bodies as today's test-tree versions.

- [ ] **Step 1: Move the file.** Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/SetIntervals.kt` with the current content of the test-tree `SetIntervals.kt`, changing only the package line:

```kotlin
package io.github.fowles.stochastic_strength.domain.policy
```

(Everything else — `LnInterval`, `SetIntervals`, doc comments — byte-identical.) Delete the test-tree original.

- [ ] **Step 2: Update references.** In `HeldOutScorer.kt`, `CapViolationDiagnostic.kt`, and `HeldOutScorerTest.kt` add `import io.github.fowles.stochastic_strength.domain.policy.SetIntervals` (and `...policy.LnInterval` where `LnInterval` is named — grep each file). Move `SetIntervalsTest.kt` to `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/` and change its package to `io.github.fowles.stochastic_strength.domain.policy`.

- [ ] **Step 3: Run the backtest + moved tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.SetIntervalsTest" --tests "io.github.fowles.stochastic_strength.domain.backtest.*"`
Expected: all PASS (BaselineReportTest may skip if history.json absent — it is present locally, so it runs and prints the unchanged baseline).

- [ ] **Step 4: Commit**

```bash
jj commit -m "refactor(policy): promote SetIntervals bounds table from backtest tree to prod"
```

---

### Task 2: `WeightFormatter.roundDown` (grid floor)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WeightFormatter.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WeightFormatterTest.kt` (append tests)

**Interfaces:**
- Produces: `WeightFormatter.roundDown(kg: Float, unit: WeightUnit): Float` — floors to the prescription grid (2.5 kg / 5 lb) with a small epsilon so float noise at an exact grid multiple doesn't drop a full increment.

- [ ] **Step 1: Write the failing tests** (append to `WeightFormatterTest`):

```kotlin
@Test
fun roundDownFloorsToGrid() {
    assertEquals(22.5f, WeightFormatter.roundDown(24.9f, WeightUnit.KG), 1e-4f)
    assertEquals(25f, WeightFormatter.roundDown(25.0f, WeightUnit.KG), 1e-4f)
    val lbs101 = WeightUnit.LBS.toKg(101f)
    assertEquals(100f, WeightUnit.LBS.fromKg(WeightFormatter.roundDown(lbs101, WeightUnit.LBS)), 1e-3f)
}

@Test
fun roundDownIsStableAtExactGridMultiples() {
    // kg→lb→kg round-trips introduce ~1e-7 relative noise; an exact 100 lb must stay 100 lb.
    val exact100lb = WeightUnit.LBS.toKg(WeightUnit.LBS.fromKg(WeightUnit.LBS.toKg(100f)))
    assertEquals(100f, WeightUnit.LBS.fromKg(WeightFormatter.roundDown(exact100lb, WeightUnit.LBS)), 1e-3f)
    assertEquals(20f, WeightUnit.LBS.fromKg(WeightFormatter.roundDown(9.071858f, WeightUnit.LBS)), 1e-3f)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WeightFormatterTest"`
Expected: FAIL — unresolved reference `roundDown`.

- [ ] **Step 3: Implement** (in `WeightFormatter`, next to `round`; add `import kotlin.math.floor`):

```kotlin
/**
 * Rounds DOWN to the prescription grid (2.5 kg / 5 lb). Used when a policy cap binds so grid
 * rounding can never push a prescription back above a demonstrated cap (the round-up-at-grid
 * edge bug). Epsilon absorbs unit-conversion float noise at exact grid multiples.
 */
fun roundDown(kg: Float, unit: WeightUnit): Float {
    return if (unit == WeightUnit.KG) {
        floor(kg / 2.5f + 1e-4f) * 2.5f
    } else {
        val lbs = unit.fromKg(kg)
        unit.toKg(floor(lbs / 5f + 1e-4f) * 5f)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WeightFormatterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(policy): WeightFormatter.roundDown grid floor for binding caps"
```

---

### Task 3: `PrescriptionPolicy` — semantic constants, `capLnFor`, `hurtMultiplier`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicy.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicyTest.kt`

**Interfaces:**
- Produces: `PrescriptionPolicy.CAP_EXPIRY_MS: Long`, `HURT_DEPTH: Float`, `HURT_HALF_LIFE_MS: Long`, `HURT_FLOOR: Float`, `COOLDOWN_MS: Long`; `PrescriptionPolicy.capLnFor(sessionSets: List<WorkoutSet>): Float?`; `PrescriptionPolicy.hurtMultiplier(hurtEventTimes: List<Long>, now: Long): Float`.
- Note: `CapViolationDiagnostic.capLnFor` (phase-0 artifact, interval **upper** bound for failures) is deliberately NOT unified with this: the policy cap uses the failure's implied 1RM = `1RM(w, a+½)` (spec Phase 1 / phase-0 table midpoint), which is stricter. The diagnostic stays frozen for baseline comparability.

- [ ] **Step 1: Write the failing tests:**

```kotlin
package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class PrescriptionPolicyTest {

    private fun set(feedback: SetFeedback?, w: Float = 100f, r: Int = 10, a: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = w, targetReps = r, actualReps = a, feedback = feedback)

    private fun lnRm(w: Float, reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))

    // --- capLnFor ---

    @Test
    fun failedSessionCapsAtTheFailuresImpliedOneRepMax() {
        // Counted failure: implied 1RM = 1RM(w, a + 0.5) (spec phase-0 table midpoint).
        val cap = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 35f, a = 2)))
        assertEquals(lnRm(35f, 2.5f), cap!!, 1e-6f)
    }

    @Test
    fun failedSessionTakesTheMinOverFailedSetsAndIgnoresSuccesses() {
        val cap = PrescriptionPolicy.capLnFor(
            listOf(
                set(SetFeedback.TOO_HARD, w = 24.9f, a = 2),
                set(SetFeedback.TOO_HARD, w = 15.9f, a = 2),
                set(SetFeedback.RIR_0_1, w = 9.1f),  // success does not lift a failed session's cap
            )
        )
        assertEquals(lnRm(15.9f, 2.5f), cap!!, 1e-6f)
    }

    @Test
    fun uncountedFailureCapsAtTargetRepsBound() {
        val cap = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 35f, a = null)))
        assertEquals(lnRm(35f, 10f), cap!!, 1e-6f)
    }

    @Test
    fun cleanSessionCapsAtMaxDemonstratedUpperBound() {
        // RIR_0_1 at (w, r) → upper 1RM(w, r+2); RIR_2_4 → 1RM(w, r+5). Max wins.
        val cap = PrescriptionPolicy.capLnFor(
            listOf(set(SetFeedback.RIR_0_1, w = 20f), set(SetFeedback.RIR_2_4, w = 18f))
        )
        assertEquals(maxOf(lnRm(20f, 12f), lnRm(18f, 15f)), cap!!, 1e-6f)
    }

    @Test
    fun anyRir5PlusSetUncapsACleanSession() {
        assertNull(PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.RIR_5_PLUS), set(SetFeedback.RIR_0_1))))
    }

    @Test
    fun hurtOnlyOrFeedbacklessSessionHasNoCap() {
        assertNull(PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.HURT), set(null))))
        assertNull(PrescriptionPolicy.capLnFor(emptyList()))
    }

    // --- hurtMultiplier ---

    @Test
    fun freshHurtBacksOffByDepthAndFadesWithHalfLife() {
        val now = 0L
        assertEquals(0.85f, PrescriptionPolicy.hurtMultiplier(listOf(now), now), 1e-4f)
        val after14d = 14L * 24 * 60 * 60 * 1000
        assertEquals(0.925f, PrescriptionPolicy.hurtMultiplier(listOf(0L), after14d), 1e-4f)
    }

    @Test
    fun noHurtEventsMeansNoBackoff() {
        assertEquals(1f, PrescriptionPolicy.hurtMultiplier(emptyList(), 0L), 0f)
    }

    @Test
    fun stackedHurtsFloorAtSixtyPercent() {
        val m = PrescriptionPolicy.hurtMultiplier(List(7) { 0L }, 0L)  // 0.85^7 ≈ 0.32 → floored
        assertEquals(0.6f, m, 1e-6f)
        assertTrue(PrescriptionPolicy.hurtMultiplier(List(2) { 0L }, 0L) > 0.6f)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicyTest"`
Expected: FAIL — unresolved reference `PrescriptionPolicy`.

- [ ] **Step 3: Implement:**

```kotlin
package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import kotlin.math.ln
import kotlin.math.pow

/**
 * Prescription-time policy clamps (spec Phase 1). Constitution rule 6: every rule here is a plain
 * arithmetic restatement of set-log facts — no estimator state, no uncertainty, no learned
 * constants. Constitution rule 3: the constants are semantic gym-language choices, never tuned,
 * and invisible to the backtest fitness function (HeldOutScorer scores the raw estimator).
 */
object PrescriptionPolicy {

    // All constants below are `semantic` (constitution rule 2).

    /** Demonstrated-capacity caps expire 28 days after the session that demonstrated them. */
    const val CAP_EXPIRY_MS = 28L * 24 * 60 * 60 * 1000

    /** A HURT set backs its muscle's prescriptions off by 15%… */
    const val HURT_DEPTH = 0.15f

    /** …fading with a 14-day half-life… */
    const val HURT_HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000

    /** …and stacked HURT backoffs never push a prescription below 60% of raw. */
    const val HURT_FLOOR = 0.6f

    /** Muscles hard-stressed within 2 days are excluded at planning time (see WorkoutPlanner). */
    const val COOLDOWN_MS = 2L * 24 * 60 * 60 * 1000

    /**
     * The demonstrated-capacity cap implied by ONE session's sets for ONE exercise, in ln(1RM).
     * Null = uncapped (no scoreable feedback, or a clean session containing an unbounded
     * RIR_5_PLUS set). A failed session caps at the failure's implied 1RM — 1RM(w, a+½), the
     * midpoint of the phase-0 bounds table's TOO_HARD interval — min over failed sets; successes
     * in a failed session never lift the cap. A clean session caps at the max demonstrated upper
     * bound, so a narrow success supersedes an older failure ceiling proportionally.
     *
     * NOTE: deliberately stricter than CapViolationDiagnostic.capLnFor (phase-0 baseline
     * artifact, frozen), which uses the interval UPPER bound (a+1) for counted failures.
     */
    fun capLnFor(sessionSets: List<WorkoutSet>): Float? {
        val scoreable = sessionSets.filter {
            it.feedback != null && it.feedback != SetFeedback.HURT && it.targetWeight > 0f
        }
        if (scoreable.isEmpty()) return null
        val failed = scoreable.filter { it.feedback == SetFeedback.TOO_HARD }
        if (failed.isNotEmpty()) {
            return failed.minOf { s ->
                val reps = s.actualReps?.let { it + 0.5f } ?: s.targetReps.toFloat()
                ln(DefaultProgressionEngine.rawToOneRepMax(s.targetWeight, reps))
            }
        }
        val uppers = scoreable.map { SetIntervals.impliedLn1RmInterval(it)?.upperLn }
        if (uppers.any { it == null }) return null
        return uppers.filterNotNull().max()
    }

    /** Multiplicative HURT backoff for a muscle: 1 − depth·2^(−age/halfLife) per event, floored. */
    fun hurtMultiplier(hurtEventTimes: List<Long>, now: Long): Float {
        var m = 1f
        for (t in hurtEventTimes) {
            val age = (now - t).coerceAtLeast(0L)
            m *= 1f - HURT_DEPTH * 0.5f.pow(age.toFloat() / HURT_HALF_LIFE_MS)
        }
        return m.coerceAtLeast(HURT_FLOOR)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(policy): PrescriptionPolicy semantic constants, demonstrated-capacity cap, HURT backoff"
```

---

### Task 4: `PolicyFacts` — the set-log restatement

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/PolicyFacts.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/PolicyFactsTest.kt`

**Interfaces:**
- Consumes: `PrescriptionPolicy.capLnFor` (Task 3).
- Produces: `ExerciseCapFact(capLn: Float?, demonstratedAt: Long)`; `PolicyFacts(capByExercise: Map<Long, ExerciseCapFact>, hurtEventsByMuscle: Map<MuscleGroup, List<Long>>)`; `PolicyFacts.EMPTY`; `PolicyFacts.build(sets: List<WorkoutSet>, exerciseMuscle: Map<Long, MuscleGroup>): PolicyFacts`.

- [ ] **Step 1: Write the failing tests:**

```kotlin
package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyFactsTest {

    private val DAY = 24L * 60 * 60 * 1000

    private fun set(
        sessionId: Long,
        exerciseId: Long = 1L,
        feedback: SetFeedback? = SetFeedback.RIR_0_1,
        w: Float = 100f,
        r: Int = 10,
        a: Int? = null,
        at: Long? = sessionId * DAY,
    ) = WorkoutSet(sessionId = sessionId, exerciseId = exerciseId, setNumber = 1, targetWeight = w, targetReps = r, actualReps = a, feedback = feedback, completedAt = at)

    private val muscles = mapOf(1L to MuscleGroup.QUADS, 2L to MuscleGroup.QUADS, 3L to MuscleGroup.CHEST)

    @Test
    fun capComesFromTheMostRecentFeedbackSessionOnly() {
        val facts = PolicyFacts.build(
            listOf(
                set(sessionId = 1, feedback = SetFeedback.TOO_HARD, w = 35f, a = 2),
                set(sessionId = 2, feedback = SetFeedback.RIR_0_1, w = 20f),
            ),
            muscles,
        )
        val fact = facts.capByExercise.getValue(1L)
        // Newer clean session supersedes the older failure entirely.
        assertEquals(PrescriptionPolicy.capLnFor(listOf(set(sessionId = 2, feedback = SetFeedback.RIR_0_1, w = 20f)))!!, fact.capLn!!, 1e-6f)
        assertEquals(2 * DAY, fact.demonstratedAt)
    }

    @Test
    fun allEasySessionYieldsAnUncappedFactThatSupersedesOlderCaps() {
        val facts = PolicyFacts.build(
            listOf(
                set(sessionId = 1, feedback = SetFeedback.TOO_HARD, w = 35f, a = 2),
                set(sessionId = 2, feedback = SetFeedback.RIR_5_PLUS, w = 20f),
            ),
            muscles,
        )
        val fact = facts.capByExercise.getValue(1L)
        assertNull(fact.capLn)  // present but uncapped: the clean easy session cleared it
    }

    @Test
    fun hurtOnlySessionDoesNotSupersedeACapAndFeedbacklessSetsAreIgnored() {
        val facts = PolicyFacts.build(
            listOf(
                set(sessionId = 1, feedback = SetFeedback.TOO_HARD, w = 35f, a = 2),
                set(sessionId = 2, feedback = SetFeedback.HURT),
                set(sessionId = 3, feedback = null),
                set(sessionId = 4, exerciseId = 3L, feedback = SetFeedback.RIR_0_1),  // other exercise
            ),
            muscles,
        )
        val fact = facts.capByExercise.getValue(1L)
        assertEquals(1 * DAY, fact.demonstratedAt)  // still the failure session
        assertEquals(PrescriptionPolicy.capLnFor(listOf(set(sessionId = 1, feedback = SetFeedback.TOO_HARD, w = 35f, a = 2)))!!, fact.capLn!!, 1e-6f)
    }

    @Test
    fun hurtEventsGroupByMuscleOnePerSession() {
        val facts = PolicyFacts.build(
            listOf(
                set(sessionId = 1, exerciseId = 1L, feedback = SetFeedback.HURT, at = 100L),
                set(sessionId = 1, exerciseId = 2L, feedback = SetFeedback.HURT, at = 200L),  // same muscle+session → one event
                set(sessionId = 2, exerciseId = 1L, feedback = SetFeedback.HURT, at = 300L),
                set(sessionId = 2, exerciseId = 3L, feedback = SetFeedback.HURT, at = 400L),  // CHEST
            ),
            muscles,
        )
        assertEquals(listOf(200L, 300L), facts.hurtEventsByMuscle.getValue(MuscleGroup.QUADS).sorted())
        assertEquals(listOf(400L), facts.hurtEventsByMuscle.getValue(MuscleGroup.CHEST))
    }

    @Test
    fun setsWithoutCompletedAtAreIgnored() {
        val facts = PolicyFacts.build(listOf(set(sessionId = 1, at = null)), muscles)
        assertTrue(facts.capByExercise.isEmpty())
        assertTrue(facts.hurtEventsByMuscle.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PolicyFactsTest"`
Expected: FAIL — unresolved reference `PolicyFacts`.

- [ ] **Step 3: Implement:**

```kotlin
package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * The cap demonstrated by an exercise's most recent feedback session (and only that session —
 * newer sessions supersede older ones entirely). [capLn] null = that session uncapped.
 */
data class ExerciseCapFact(val capLn: Float?, val demonstratedAt: Long)

/**
 * Raw set-log facts the policy layer needs at prescription time (constitution rule 6: plain
 * restatements of the log — no estimator state, rebuilt from sets alone). Sessions are identified
 * by sessionId; session time is the max completedAt of its sets. Sets without completedAt
 * (in-progress) are ignored.
 */
data class PolicyFacts(
    val capByExercise: Map<Long, ExerciseCapFact> = emptyMap(),
    val hurtEventsByMuscle: Map<MuscleGroup, List<Long>> = emptyMap(),
) {
    companion object {
        val EMPTY = PolicyFacts()

        fun build(sets: List<WorkoutSet>, exerciseMuscle: Map<Long, MuscleGroup>): PolicyFacts {
            val completed = sets.filter { it.completedAt != null }

            val capByExercise = completed.groupBy { it.exerciseId }
                .mapNotNull { (exerciseId, exSets) ->
                    // Only sessions with scoreable (non-HURT) feedback demonstrate capacity;
                    // a HURT-only or feedback-less session never supersedes an older cap.
                    val sessions = exSets.groupBy { it.sessionId }.filterValues { s ->
                        s.any { it.feedback != null && it.feedback != SetFeedback.HURT }
                    }
                    val latest = sessions.values.maxByOrNull { s -> s.maxOf { it.completedAt!! } }
                        ?: return@mapNotNull null
                    exerciseId to ExerciseCapFact(
                        capLn = PrescriptionPolicy.capLnFor(latest),
                        demonstratedAt = latest.maxOf { it.completedAt!! },
                    )
                }
                .toMap()

            val hurtEventsByMuscle = completed
                .filter { it.feedback == SetFeedback.HURT }
                .mapNotNull { s -> exerciseMuscle[s.exerciseId]?.let { m -> Triple(m, s.sessionId, s.completedAt!!) } }
                .groupBy { it.first }
                .mapValues { (_, events) ->
                    // One backoff event per (session, muscle), like the old muscle-level HURT fold.
                    events.groupBy { it.second }.map { (_, e) -> e.maxOf { it.third } }
                }

            return PolicyFacts(capByExercise, hurtEventsByMuscle)
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PolicyFactsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(policy): PolicyFacts set-log restatement (caps per exercise, HURT events per muscle)"
```

---

### Task 5: `PrescriptionPolicy.prescribe`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicy.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicyTest.kt` (append tests)

**Interfaces:**
- Consumes: `PolicyFacts`/`ExerciseCapFact` (Task 4), `WeightFormatter.roundDown` (Task 2).
- Produces:

```kotlin
data class Prescription(val weightKg: Float, val capBound: Boolean, val hurtMultiplier: Float)

fun prescribe(
    rawE1rm: Float, sessionReps: Int, exerciseId: Long, muscle: MuscleGroup,
    facts: PolicyFacts, now: Long, weightUnit: WeightUnit, engine: ProgressionEngine,
): Prescription
```

- [ ] **Step 1: Write the failing tests** (append to `PrescriptionPolicyTest`; add imports `io.github.fowles.stochastic_strength.data.model.MuscleGroup`, `io.github.fowles.stochastic_strength.data.model.WeightUnit`, `io.github.fowles.stochastic_strength.domain.WeightFormatter`, `org.junit.Assert.assertFalse`, `kotlin.math.exp`):

```kotlin
    // --- prescribe ---

    private val DAY = 24L * 60 * 60 * 1000

    private fun prescribe(
        rawE1rm: Float,
        facts: PolicyFacts,
        reps: Int = 10,
        now: Long = 30 * DAY,
        unit: WeightUnit = WeightUnit.KG,
    ) = PrescriptionPolicy.prescribe(
        rawE1rm = rawE1rm, sessionReps = reps, exerciseId = 1L, muscle = MuscleGroup.QUADS,
        facts = facts, now = now, weightUnit = unit, engine = DefaultProgressionEngine,
    )

    private fun capFacts(capLn: Float?, at: Long) =
        PolicyFacts(capByExercise = mapOf(1L to ExerciseCapFact(capLn, at)))

    @Test
    fun noFactsReproducesTheLegacyRoundedPrescription() {
        val raw = DefaultProgressionEngine.rawToOneRepMax(100f, 10f)
        val p = prescribe(raw, PolicyFacts.EMPTY)
        assertEquals(WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(raw, 10), WeightUnit.KG), p.weightKg, 1e-4f)
        assertFalse(p.capBound)
        assertEquals(1f, p.hurtMultiplier, 0f)
    }

    @Test
    fun bindingCapFloorsAtTheGridAndClosesTheFailThenNarrowSuccessHole() {
        // "fail 35 → narrowly succeed at 20 → engine says 35 again": most recent session was the
        // narrow success, so the cap is 1RM(20, 12) and the prescription creeps instead of jumping.
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.RIR_0_1, w = 20f)))
        val raw = DefaultProgressionEngine.rawToOneRepMax(35f, 10f)
        val p = prescribe(raw, capFacts(capLn, at = 29 * DAY))
        assertTrue(p.capBound)
        assertTrue("crept prescription, not a jump back to 35", p.weightKg < 25f)
        assertTrue("cap is above the demonstrated 20", p.weightKg >= 20f)
    }

    @Test
    fun prescriptionAfterAFailureIsStrictlyBelowTheFailedWeight() {
        // Light-weight edge: failed 10 kg × 10 doing 9 — even one rep short must prescribe < 10 kg.
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 10f, a = 9)))
        val p = prescribe(DefaultProgressionEngine.rawToOneRepMax(10f, 10f), capFacts(capLn, at = 29 * DAY))
        assertTrue(p.capBound)
        assertTrue(p.weightKg < 10f)
    }

    @Test
    fun expiredCapDoesNotBind() {
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 20f, a = 2)))
        val raw = DefaultProgressionEngine.rawToOneRepMax(35f, 10f)
        val p = prescribe(raw, capFacts(capLn, at = 1 * DAY), now = 30 * DAY)  // 29 days later
        assertFalse(p.capBound)
    }

    @Test
    fun rawBelowTheCapPassesThroughUnbound() {
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.RIR_0_1, w = 20f)))
        val raw = exp(capLn!!) * 0.9f
        val p = prescribe(raw, capFacts(capLn, at = 29 * DAY))
        assertFalse(p.capBound)
    }

    @Test
    fun hurtBackoffScalesThePrescriptionAndCapAppliesOnTop() {
        val now = 30 * DAY
        val facts = PolicyFacts(hurtEventsByMuscle = mapOf(MuscleGroup.QUADS to listOf(now)))
        val raw = DefaultProgressionEngine.rawToOneRepMax(100f, 10f)
        val backed = prescribe(raw, facts, now = now)
        val unbacked = prescribe(raw, PolicyFacts.EMPTY, now = now)
        assertEquals(0.85f, backed.hurtMultiplier, 1e-4f)
        assertTrue(backed.weightKg < unbacked.weightKg)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicyTest"`
Expected: FAIL — unresolved reference `prescribe` / `Prescription`.

- [ ] **Step 3: Implement** (append to `PrescriptionPolicy.kt`; add imports `io.github.fowles.stochastic_strength.data.model.MuscleGroup`, `io.github.fowles.stochastic_strength.data.model.WeightUnit`, `io.github.fowles.stochastic_strength.domain.ProgressionEngine`, `io.github.fowles.stochastic_strength.domain.WeightFormatter`, `kotlin.math.exp`):

```kotlin
/** One clamped prescription. [capBound]/[hurtMultiplier] feed the clamp-bind health report. */
data class Prescription(val weightKg: Float, val capBound: Boolean, val hurtMultiplier: Float)

/**
 * prescribe(rawTarget, PolicyFacts) → weight (spec Phase 1). Order: HURT backoff multiplies the
 * raw target, then the demonstrated-capacity cap ceilings it, then grid rounding. When the cap
 * binds, the weight is computed with the RAW rep-max inverse and floor-rounded at the grid —
 * pre-rounding to the 0.5 kg internal grid could nudge the weight back up to exactly the failed
 * weight, and nearest-rounding at the prescription grid could round above the cap.
 */
fun prescribe(
    rawE1rm: Float,
    sessionReps: Int,
    exerciseId: Long,
    muscle: MuscleGroup,
    facts: PolicyFacts,
    now: Long,
    weightUnit: WeightUnit,
    engine: ProgressionEngine,
): Prescription {
    val mult = hurtMultiplier(facts.hurtEventsByMuscle[muscle].orEmpty(), now)
    val backed = rawE1rm * mult
    val fact = facts.capByExercise[exerciseId]
    val capLn = fact?.capLn?.takeIf { now - fact.demonstratedAt <= CAP_EXPIRY_MS }
    if (capLn != null && ln(backed) > capLn) {
        val capWeight = DefaultProgressionEngine.rawFromOneRepMax(exp(capLn), sessionReps)
        return Prescription(WeightFormatter.roundDown(capWeight, weightUnit), capBound = true, hurtMultiplier = mult)
    }
    val weight = engine.fromOneRepMax(backed, sessionReps)
    return Prescription(WeightFormatter.round(weight, weightUnit), capBound = false, hurtMultiplier = mult)
}
```

(Add `prescribe` and `Prescription` inside/beside `object PrescriptionPolicy` — `Prescription` at file top level, `prescribe` as a member of the object.)

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(policy): prescribe() — HURT backoff + demonstrated-capacity cap with grid floor"
```

---

### Task 6: Wire policy into `WorkoutPlanner` + `WorkoutRepository`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (buildPlanner, ~line 82)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt` (append tests)

**Interfaces:**
- Consumes: `PolicyFacts` (Task 4), `PrescriptionPolicy.prescribe` + `COOLDOWN_MS` (Tasks 3/5).
- Produces: `WorkoutPlanner` gains constructor param `private val policyFacts: PolicyFacts = PolicyFacts.EMPTY` (default keeps every existing construction site compiling).

- [ ] **Step 1: Write the failing tests** (append to `WorkoutPlannerTest`; it already has `exercise(...)`/`planner(...)` helpers — add a policy-aware planner construction inline; imports: `io.github.fowles.stochastic_strength.domain.policy.PolicyFacts`, `io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy`):

```kotlin
    @Test
    fun machinePrescriptionIsCappedByDemonstratedCapacity() {
        val now = System.currentTimeMillis()
        val ex = exercise(1L, muscle = MuscleGroup.QUADS, equipment = Equipment.MACHINE)
        // Most recent session on this exercise: failed 35 kg × 10 at 2 reps.
        val failedSet = WorkoutSet(
            sessionId = 1L, exerciseId = 1L, setNumber = 1, targetWeight = 35f, targetReps = 10,
            actualReps = 2, feedback = SetFeedback.TOO_HARD, completedAt = now - 86_400_000L,
        )
        val facts = PolicyFacts.build(listOf(failedSet), mapOf(1L to MuscleGroup.QUADS))
        val p = WorkoutPlanner(
            availableExercises = listOf(ex),
            prescribedE1rm = mapOf(1L to 60f),  // entrenched raw estimate, way above the failure
            recentHistory = emptyMap(),
            weightUnit = WeightUnit.KG,
            locationId = null,
            nowMs = now,
            // ExerciseCoefficients is name-keyed; synthetic "Ex1" needs an explicit coefficient.
            coefficientSource = UserCoefficientSource(mapOf(1L to 1f)),
            policyFacts = facts,
        )
        val w = p.weightForExerciseTest(ex, sessionReps = 10)
        assertTrue("must be strictly below the failed 35 kg, was $w", w < 35f)
        assertTrue(w > 0f)
    }

    @Test
    fun manualOverrideBypassesPolicy() {
        val now = System.currentTimeMillis()
        val ex = exercise(1L, muscle = MuscleGroup.QUADS, equipment = Equipment.MACHINE)
        val failedSet = WorkoutSet(
            sessionId = 1L, exerciseId = 1L, setNumber = 1, targetWeight = 35f, targetReps = 10,
            actualReps = 2, feedback = SetFeedback.TOO_HARD, completedAt = now - 86_400_000L,
        )
        val facts = PolicyFacts.build(listOf(failedSet), mapOf(1L to MuscleGroup.QUADS))
        val p = WorkoutPlanner(
            availableExercises = listOf(ex),
            prescribedE1rm = mapOf(1L to 60f),
            recentHistory = emptyMap(),
            weightUnit = WeightUnit.KG,
            locationId = null,
            nowMs = now,
            coefficientSource = UserCoefficientSource(mapOf(1L to 1f)),
            policyFacts = facts,
            exerciseE1rmOverrides = mapOf(1L to 60f),  // user explicitly chose this
        )
        val w = p.weightForExerciseTest(ex, sessionReps = 10)
        assertTrue("manual override is the user's decision; policy must not cap it", w > 35f)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`
Expected: FAIL — no `policyFacts` parameter.

- [ ] **Step 3: Implement in `WorkoutPlanner`:**

1. Add imports `io.github.fowles.stochastic_strength.domain.policy.PolicyFacts` and `io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy`.
2. Add constructor param (after `exerciseE1rmOverrides`): `private val policyFacts: PolicyFacts = PolicyFacts.EMPTY,`
3. Delete `private const val TWO_DAYS_MS = 2L * 24 * 60 * 60 * 1000` and change `recentlyFailedMuscles`'s cutoff line to `val cutoff = nowMs - PrescriptionPolicy.COOLDOWN_MS` (spec Phase 1 rule 3: the existing rest cooldown, restated as the semantic policy constant).
4. Replace `weightForExercise`:

```kotlin
    private fun weightForExercise(exercise: Exercise, sessionReps: Int): Float {
        val coeff = coefficientSource.get(exercise) ?: return 0f
        if (coeff <= 0f) return 0f // unloadable (bodyweight/banded): no prescription
        // A manual e1rm override is the user's explicit decision — policy clamps machine
        // prescriptions only, so overrides take the plain legacy path.
        val manual = exerciseE1rmOverrides[exercise.id]
        if (manual != null) {
            if (manual <= 0f) return 0f
            return WeightFormatter.round(progressionEngine.fromOneRepMax(manual, sessionReps), weightUnit)
        }
        val e1rm = prescribedE1rm[exercise.id] ?: return 0f
        if (e1rm <= 0f) return 0f
        return PrescriptionPolicy.prescribe(
            rawE1rm = e1rm,
            sessionReps = sessionReps,
            exerciseId = exercise.id,
            muscle = exercise.primaryMuscle,
            facts = policyFacts,
            now = nowMs,
            weightUnit = weightUnit,
            engine = progressionEngine,
        ).weightKg
    }
```

5. In `WorkoutRepository.buildPlanner`, after `history` is computed, build the facts and pass them:

```kotlin
        val policyFacts = PolicyFacts.build(
            sets = history.values.flatten(),
            exerciseMuscle = available.associate { it.id to it.primaryMuscle },
        )
```

and add `policyFacts = policyFacts,` to the `WorkoutPlanner(...)` construction. Import `io.github.fowles.stochastic_strength.domain.policy.PolicyFacts`.

- [ ] **Step 4: Run planner + repository-adjacent tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest" --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerOverrideTest"`
Expected: PASS (existing tests construct planners without `policyFacts` → EMPTY default → behavior identical).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(policy): wire PrescriptionPolicy into WorkoutPlanner prescriptions"
```

---

### Task 7: Move HURT out of the estimator

Policy now owns HURT; keeping the estimator's muscle-wide ×0.85 would double-apply the backoff. The real history has **zero HURT sets** (the spec's bucket counts 87+45+69+159 sum to all 360 sets), so this must leave the phase-0 baseline bit-identical — verified below.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimateUpdater.kt` (delete `hurt`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimate.kt` (delete `EstimatorConfig.hurtFactor`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepper.kt` (delete the HURT block)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimateUpdaterTest.kt`, `.../SessionProgressionStepperTest.kt`

**Interfaces:**
- Consumes: nothing new. Produces: `SessionProgressionStepper.step` no longer mutates estimates for HURT sets (they already carry no load signal in `SessionSignalExtractor`).

- [ ] **Step 1: Write the replacement stepper test.** In `SessionProgressionStepperTest`, replace the whole `hurtBacksOffEveryLoadedExerciseInTheMuscle` test (lines ~55–66; it uses the file's existing `snapshot()`/`set(...)` helpers) with:

```kotlin
    @Test
    fun hurtSetsLeaveEstimatesUntouched() {
        // HURT is a policy concern (PrescriptionPolicy.hurtMultiplier); the estimator must not
        // mutate any estimate for a HURT set.
        val snap = snapshot()
        val before = snap.currentEstimates.toMap()
        val result = stepper.step(
            sets = listOf(set(2L, weight = 60f, reps = 5, feedback = SetFeedback.HURT)),
            snapshot = snap,
            asOf = 2_000L,
        )
        assertEquals(before, snap.currentEstimates.toMap())
        assertTrue(result.steps.isEmpty())
    }
```

In `ExerciseEstimateUpdaterTest`, delete `hurtBacksOffByConfiguredFactor` (lines ~60–66).

- [ ] **Step 2: Run to verify the new test fails** (estimates still mutated by the hurt block):

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepperTest"`
Expected: FAIL on `hurtSetsLeaveEstimatesUntouched`.

- [ ] **Step 3: Delete the estimator HURT mechanism:**

1. `ExerciseEstimateUpdater`: delete the `hurt(...)` function and its doc comment.
2. `EstimatorConfig` (in `ExerciseEstimate.kt`): delete `val hurtFactor: Float = 0.85f,` and its doc line.
3. `SessionProgressionStepper.step`: delete the "HURT first (muscle-level)" block (the `hurtMuscles` computation + loop) and the later `affectedMuscles.addAll(hurtMuscles)` line, and update the class doc comment (it starts "Pure per-session core of progression: HURT (muscle-level) → …") to drop HURT.
4. Grep for stragglers: `grep -rn "hurtFactor\|updater.hurt\|\.hurt(" app/src/main app/src/test app/src/androidTest` — fix any remaining references (expected: none beyond the two test files).

- [ ] **Step 4: Run the estimator test suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.*"`
Expected: PASS (including `ExerciseEstimatorSimulationTest` — it has no HURT pins).

- [ ] **Step 5: Verify the phase-0 baseline is unchanged**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BaselineReportTest" -i 2>&1 | grep -A6 "Phase 0 baseline"`
Expected: total 26.7593 ln-units, mean 0.12563, 213 scored / 9 skipped, 49 cap violations — identical to the recorded baseline (zero HURT sets in history ⇒ provably no-op). If ANY number differs, STOP and investigate before committing.

- [ ] **Step 6: Commit**

```bash
jj commit -m "refactor(estimator): remove HURT fold from estimator — policy owns HURT (baseline verified unchanged)"
```

---

### Task 8: Backtest invariant + clamp-bind-rate report

Constitution rule 4: every backtest run reports clamp-bind rate. This test replays real history, applies policy on top of main's raw predictions, asserts the failure invariant, and prints bind rates. It does NOT touch `HeldOutScorer` or `CapViolationDiagnostic`.

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/PolicyBacktestTest.kt`

**Interfaces:**
- Consumes: `MainStackReplay.run(data, observer)`, `BacktestData.loadOrNull()`, `PolicyFacts.build`, `PrescriptionPolicy.prescribe` / `CAP_EXPIRY_MS`.

- [ ] **Step 1: Write the test:**

```kotlin
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
```

- [ ] **Step 2: Run it**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.PolicyBacktestTest" -i 2>&1 | grep -A20 "clamp-bind report"`
Expected: PASS with 0 violations. Cap binds should be in the neighborhood of the phase-0 diagnostic's 49 (not identical: the policy cap is midpoint-strict, the diagnostic upper-bound). **Record the printed bind numbers in the Results section at the bottom of this plan.** Per constitution rule 4, a high bind rate is estimator-bug evidence — expected here and already on the books as the chronic exercises 21 & 77 finding; Phase 2 fixes the estimator.

- [ ] **Step 3: Commit**

```bash
jj commit -m "test(backtest): phase-1 policy invariant + clamp-bind-rate report on real history"
```

---

### Task 9: Retire the ProdBss 20 lb pin → clamp-behavior invariant; final verification

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProdBssPrescriptionTest.kt`
- Modify: `docs/superpowers/plans/2026-07-14-phase1-policy-layer.md` (Results section + checkboxes)

**Interfaces:**
- Consumes: `PolicyFacts.build`, `PrescriptionPolicy.prescribe` (Tasks 4/5); the existing fixture data in the test file stays.

- [ ] **Step 1: Rewrite the test class.** Keep the fixture data (`seedCoef`, `initials`, `endTimes`, `sets`, `EXPORTED_AT`) and the replay block exactly as they are; replace the class doc comment and the `reportBssPrescription` test with:

```kotlin
/**
 * Clamp-behavior invariant from the prod backup pulled 2026-06-24 (the Bulgarian-Split-Squat
 * over-prescription bug). The spec retires the magic-number 20 lb pin; what must hold is the
 * policy invariant: never prescribe at-or-above a weight failed in the exercise's most recent
 * session (session 18 failed 24.95 kg and 15.88 kg at 10 reps) — regardless of what the raw
 * estimator says. The estimator's raw quality is scored by the backtest harness, not here.
 */
class ProdBssPrescriptionTest {
    // ... fixture fields unchanged ...

    private val LIGHTEST_FAILED_KG = 15.875752449035645f  // session 18, set 2

    private fun bssFacts(): io.github.fowles.stochastic_strength.domain.policy.PolicyFacts =
        io.github.fowles.stochastic_strength.domain.policy.PolicyFacts.build(
            sets = sets.map { it.copy(completedAt = endTimes[it.sessionId]) },
            exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS },
        )

    private fun policyWeightKg(rawE1rm: Float): Float =
        io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy.prescribe(
            rawE1rm = rawE1rm, sessionReps = 10, exerciseId = 55L, muscle = MuscleGroup.QUADS,
            facts = bssFacts(), now = EXPORTED_AT, weightUnit = WeightUnit.LBS,
            engine = DefaultProgressionEngine,
        ).weightKg

    @Test
    fun bssPrescriptionStaysStrictlyBelowTheMostRecentFailedWeight() {
        // Full replay of the prod history through main's estimator (unchanged fixture replay):
        val exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS }
        val snapshot = ReplaySnapshot(exerciseMuscle = exerciseMuscle, seedCoefficients = seedCoef)
        for ((id, e1rm) in initials) snapshot.currentEstimates[id] = ExerciseEstimate.seed(e1rm, at = 0)
        val stepper = SessionProgressionStepper()
        for (sessionId in listOf(12L, 14L, 15L, 16L, 18L)) {
            stepper.step(sets.filter { it.sessionId == sessionId }, snapshot, endTimes[sessionId]!!)
        }
        val proj = MuscleStrengthProjector().project(
            estimates = snapshot.currentEstimates, seedCoef = seedCoef,
            muscleExerciseIds = seedCoef.keys.toList(), now = EXPORTED_AT,
        )

        val prescribed = policyWeightKg(proj.effectiveE1rm.getValue(55L))
        assertTrue(
            "policy prescription $prescribed kg must be strictly below the failed $LIGHTEST_FAILED_KG kg",
            prescribed < LIGHTEST_FAILED_KG,
        )
        assertTrue("must still prescribe something", prescribed > 0f)
    }

    @Test
    fun seatbeltHoldsEvenIfTheEstimatorRegressesToItsEntrenchedSeed() {
        // The scenario that produced three estimator mechanisms on the abandoned branch: an
        // entrenched high estimate (the 38.1 kg seed). Policy alone must contain it.
        val prescribed = policyWeightKg(38.101806640625f)
        assertTrue(
            "capped prescription $prescribed kg must be strictly below the failed $LIGHTEST_FAILED_KG kg",
            prescribed < LIGHTEST_FAILED_KG,
        )
    }
}
```

(Adjust imports at the top of the file: add `org.junit.Assert.assertTrue`, `io.github.fowles.stochastic_strength.domain.policy.PolicyFacts` / `PrescriptionPolicy` may be imported normally instead of fully qualified. Remove the now-unused `assertEquals` import and `WeightFormatter`/`WeightUnit.LBS.fromKg` conversion code if no longer referenced.)

- [ ] **Step 2: Run it**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProdBssPrescriptionTest"`
Expected: PASS — both invariants hold (the end-to-end path lands at the demonstrated ~20 lb; the forced-regression path is capped to ~20 lb by the clamp).

- [ ] **Step 3: Full JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all green (≈275+ tests; 268 pre-existing + new policy tests, minus the two deleted hurt tests).

- [ ] **Step 4: Instrumented suite** (emulator typically already running):

Run: `./gradlew :app:connectedAndroidTest`
Expected: 79/79 green. If no device is connected, report that to the user instead of skipping silently.

- [ ] **Step 5: Record results.** Fill in the Results section below (clamp-bind numbers from Task 8, final test counts), and check off all boxes in this plan.

- [ ] **Step 6: Commit**

```bash
jj commit -m "test: retire ProdBss 20lb pin as clamp-behavior invariants; phase-1 results recorded"
```

---

## Results (fill in at execution)

- Clamp-bind report (Task 8): prescriptions checked ___, cap binds ___ (___%), hurt binds ___, violations 0.
- Phase-0 baseline after HURT removal (Task 7): unchanged — total 26.7593 / mean 0.12563 / 49 (confirm).
- Final suites: JVM ___/0, instrumented ___/___.

## Constant ledger delta (spec section: Constant ledger)

Added, all `semantic`: `CAP_EXPIRY_MS` (28 d), `HURT_DEPTH` (0.15), `HURT_HALF_LIFE_MS` (14 d), `HURT_FLOOR` (0.6), `COOLDOWN_MS` (2 d, moved from planner's `TWO_DAYS_MS`).
Deleted: `EstimatorConfig.hurtFactor` (0.85 — behavior moved to policy).
