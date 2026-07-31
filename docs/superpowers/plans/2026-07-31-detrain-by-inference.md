# Detrain-by-inference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stored/dialog-driven detraining feature with an automatic, inferred-from-the-session-timeline reduction of the comeback prescription, surfaced by a lightweight notice.

**Architecture:** Detraining stops being durable state and a user dialog. Instead, `buildPlanner` applies a **prospective** decay to the prescribed 1RMs based on the gap since the last completed session (`now − lastCompletedEnd`), using the existing `DetrainingModel` curve. Because beliefs are derived from the *set log*, the comeback sets self-correct the belief within the session, so no belief-history reset (and no gate re-baseline) is needed. This is the first of three plans (see spec `2026-07-31-fitted-coefficients-and-derived-state-cleanup-design.md`); it removes the DETRAIN writer so a later plan can delete `exercise_strength_override`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room, JUnit (JVM `src/test`) + instrumented (`src/androidTest`), jj (Jujutsu) for commits.

## Global Constraints

- **Package:** `io.github.fowles.stochastic_strength`. Min SDK 33.
- **No schema change in this plan.** `exercise_strength_override` still exists; we only stop *writing* DETRAIN rows. No Room migration, no `AppDatabase` version bump.
- **Belief backtest gate must stay green unchanged.** This plan does not touch replay, `BeliefSessionStep`, or `BeliefHeldOutScorer`; `BeliefScoreTest` must pass with its existing pinned number (the prescription is not scored, and `history.json` has no DETRAIN rows).
- **Real users:** old `DETRAIN` rows already in a user's DB remain valid and keep applying in replay (they are real past events); we simply stop creating new ones.
- **Commits:** `jj commit -m "..."` at the end of each task. Run the most specific test target after each change; run the full suite at plan end.
- **Detraining curve (verbatim, existing):** 5% per whole week off, capped 50%, offered/applied only at ≥ 1 full week (`DetrainingModel.PER_WEEK = 0.05f`, `MAX_FRACTION = 0.50f`, `qualifies(weeks) = weeks >= 1`).

---

### Task 1: `DetrainingModel.retention(gapMillis)` — the inferred decay factor

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/DetrainingModel.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/DetrainingModelTest.kt` (create)

**Interfaces:**
- Produces: `DetrainingModel.retention(gapMillis: Long): Float` — the multiplicative fresh-1RM retention across an idle gap, `= 1f - suggestedFraction(weeksOff(gap))`. Returns `1f` for gaps under one week; floors at `1 - MAX_FRACTION = 0.5f`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DetrainingModelTest {
    private val week = DetrainingModel.WEEK_MILLIS

    @Test fun retention_isFullBelowOneWeek() {
        assertEquals(1f, DetrainingModel.retention(0L), 1e-6f)
        assertEquals(1f, DetrainingModel.retention(week - 1), 1e-6f)
    }

    @Test fun retention_dropsFivePercentPerWholeWeek() {
        assertEquals(0.95f, DetrainingModel.retention(week), 1e-6f)        // 1 week -> 5%
        assertEquals(0.90f, DetrainingModel.retention(2 * week), 1e-6f)    // 2 weeks -> 10%
    }

    @Test fun retention_floorsAtHalf() {
        assertEquals(0.5f, DetrainingModel.retention(20 * week), 1e-6f)    // capped at 50%
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DetrainingModelTest"`
Expected: FAIL — `retention` is unresolved.

- [ ] **Step 3: Add the function**

Append to `DetrainingModel` (after `reduce`):

```kotlin
    /**
     * Multiplicative fresh-1RM retention across an idle gap of [gapMillis] — the inferred
     * detraining factor. `1f` below one week; drops [PER_WEEK] per whole week, floored at
     * `1 - MAX_FRACTION`. Applied prospectively to the comeback prescription; the set log
     * self-corrects the belief afterward.
     */
    fun retention(gapMillis: Long): Float {
        val weeks = (gapMillis / WEEK_MILLIS).toInt().coerceAtLeast(0)
        return 1f - suggestedFraction(weeks)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DetrainingModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(detrain): add DetrainingModel.retention(gapMillis) inferred decay factor"
```

---

### Task 2: Apply the prospective decay in `buildPlanner`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (`buildPlanner`, ~lines 96–133)
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

**Interfaces:**
- Consumes: `DetrainingModel.retention(gapMillis)` (Task 1).
- Produces: `buildPlanner` prescribes 1RMs scaled by `retention(now − lastCompletedEnd)`. No signature change.

**Context:** `buildPlanner` already computes `prescribedE1rm` from `beliefPooling.effective(...) → BeliefPrescriber.targetE1rm` (line 105–108) and fetches `recentSessions = db.workoutSessionDao().getRecentCompletedSessions(limit = 50)` (line 113). Apply the decay to every prescribed 1RM.

- [ ] **Step 1: Write the failing instrumented test**

Add to `WorkoutRepositoryTest` (mirror the existing setup in that file for seeding a user + one completed session; reuse its helpers/ids):

```kotlin
@Test
fun buildPlanner_reducesPrescriptionAfterALayoff() = runBlocking {
    // Seed a user and one COMPLETED session whose endTime is 3 weeks in the past,
    // with a couple of sets so beliefs exist. (Use this file's existing seeding helpers.)
    val threeWeeksAgo = System.currentTimeMillis() - 3L * DetrainingModel.WEEK_MILLIS
    seedCompletedSession(endTime = threeWeeksAgo /* + sets for BENCH */)

    val planner = repository.buildPlanner(locationId = null, weightUnit = WeightUnit.KG)
    val afterLayoff = planner.prescribedE1rmFor(BENCH_EXERCISE_ID)  // expose via test accessor or read plan weight

    // Same fixture but a fresh (yesterday) session -> no decay.
    seedCompletedSession(endTime = System.currentTimeMillis() - DAY /* + same sets */)
    val fresh = repository.buildPlanner(locationId = null, weightUnit = WeightUnit.KG)
        .prescribedE1rmFor(BENCH_EXERCISE_ID)

    // 3 weeks -> 15% reduction.
    assertEquals(fresh * 0.85f, afterLayoff, fresh * 0.02f)
}
```

Note: if `prescribedE1rm` isn't directly readable from `WorkoutPlanner`, assert instead on the planned `sessionWeight` for the exercise (lower after layoff). Keep the assertion on the *ratio* (~0.85×) so it's independent of the seed magnitude.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: FAIL — prescription not reduced (ratio ≈ 1.0, not 0.85).

- [ ] **Step 3: Apply the decay in `buildPlanner`**

In `WorkoutRepository.buildPlanner`, after `recentSessions` is fetched, compute the retention and scale `prescribedE1rm`. Change:

```kotlin
        val prescribedE1rm = ctx.muscleExerciseIds.flatMap { (_, ids) ->
            beliefPooling.effective(beliefs, ctx.seedCoef, ids, now).effective.entries
                .map { it.key to BeliefPrescriber.targetE1rm(it.value) }
        }.toMap()
```

to (move `recentSessions` above `prescribedE1rm`, or read last end here):

```kotlin
        val recentSessions = db.workoutSessionDao().getRecentCompletedSessions(limit = 50)
        // Inferred detraining: a gap since the last completed session eases the comeback
        // prescription down (DetrainingModel curve). The set log self-corrects the belief after.
        val lastCompletedEnd = recentSessions.mapNotNull { it.endTime }.maxOrNull()
        val retention = lastCompletedEnd?.let { DetrainingModel.retention(now - it) } ?: 1f
        val prescribedE1rm = ctx.muscleExerciseIds.flatMap { (_, ids) ->
            beliefPooling.effective(beliefs, ctx.seedCoef, ids, now).effective.entries
                .map { it.key to BeliefPrescriber.targetE1rm(it.value) * retention }
        }.toMap()
```

Remove the now-duplicated later `val recentSessions = …` line (formerly ~line 113); keep its single definition above. Add `import io.github.fowles.stochastic_strength.domain.DetrainingModel` if not present.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(detrain): infer comeback reduction from session gap in buildPlanner"
```

---

### Task 3: Remove the durable detrain write and the slider/apply plumbing

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (delete `applyDetrainingReduction`, ~lines 210–226)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlan.kt` (remove `detrainOverrides` + fold `effectiveOverrides`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt` (remove `applyDetraining`, the `applyDetrainingReduction` call in `startFirstExercise`, and `detrainOverrides` refs)
- Test: `app/src/androidTest/.../WorkoutRepositoryTest.kt` (delete the `applyDetrainingReduction_*` tests)

**Interfaces:**
- Removes: `WorkoutRepository.applyDetrainingReduction`, `WorkoutPlan.detrainOverrides`, `WorkoutSessionController.applyDetraining`. `WorkoutPlan.effectiveOverrides` becomes `= exerciseOverrides`.

- [ ] **Step 1: Delete the obsolete repository tests first**

In `WorkoutRepositoryTest`, delete `applyDetrainingReduction_*` test methods (they assert a behavior we are removing). Note their names for the commit message.

- [ ] **Step 2: Remove `applyDetrainingReduction` from `WorkoutRepository`**

Delete the whole method (main lines ~210–226).

- [ ] **Step 3: Drop `detrainOverrides` from `WorkoutPlan`**

Edit `WorkoutPlan.kt` to remove the `detrainOverrides` field and simplify:

```kotlin
data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
    val sessionReps: Int = 10,
    val sessionRejectedIds: Set<Long> = emptySet(),
    val exerciseOverrides: Map<Long, Float> = emptyMap(),     // per-exercise e1rm (manual edits)
) {
    val estimatedDurationSeconds: Int get() = exercises.sumOf { it.estimatedSeconds }
    val effectiveOverrides: Map<Long, Float> get() = exerciseOverrides
}
```

- [ ] **Step 4: Fix `WorkoutSessionController`**

Remove `applyDetraining(...)` (lines ~120–149) entirely. In `startFirstExercise`, delete the detrain line:

```kotlin
            repository.applyDetrainingReduction(sessionId, plan.detrainOverrides)   // DELETE
```

In `adjustExerciseWeight` (line ~267), replace `state.plan.detrainOverrides + updatedOverrides` with `updatedOverrides`. Leave `maybeOfferDetraining`/`applyManualExerciseOverrides` alone for now (Tasks 4 / Plan 2 handle them).

- [ ] **Step 5: Compile and run the full unit + affected instrumented suites**

Run: `./gradlew :app:testDebugUnitTest` then `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: PASS (compiles; deleted tests gone; `applyManualExerciseOverrides_*` still pass). Fix any remaining `detrainOverrides` references the compiler flags (search: `grep -rn detrainOverrides app/src`).

- [ ] **Step 6: Commit**

```bash
jj commit -m "refactor(detrain): drop applyDetrainingReduction, detrainOverrides, and the apply-slider path"
```

---

### Task 4: Replace the detraining dialog with a lightweight notice

**Files:**
- Delete: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/DetrainingDialog.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt` (`DetrainingPrompt` → `DetrainingNotice`; `PlanPreview.detraining` type)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt` (`maybeOfferDetraining` → `maybeNoteDetraining`; add `dismissDetrainingNotice`; remove `skipDetraining` if now unused)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt` (dialog block ~lines 107–114 → inline notice)
- Modify: ViewModel exposing `applyDetraining`/`skipDetraining` (search `viewModel::applyDetraining`) — remove those bindings; add `dismissDetrainingNotice`.

**Interfaces:**
- Removes: `DetrainingPrompt`, `DetrainingDialog`, `applyDetraining`, `skipDetraining`.
- Produces: `DetrainingNotice(weeksOff: Int)`; `WorkoutState.PlanPreview.detraining: DetrainingNotice?`; `WorkoutSessionController.dismissDetrainingNotice()`.

- [ ] **Step 1: Replace the state type**

In `WorkoutState.kt`, change `PlanPreview.detraining`'s type and replace the data class:

```kotlin
    data class PlanPreview(
        val plan: WorkoutPlan,
        val locationName: String? = null,
        val repMin: Int = 5,
        val repMax: Int = 10,
        val detraining: DetrainingNotice? = null,
    ) : WorkoutState
```

```kotlin
/** Informational "you've been away — starting lighter" banner; carries no adjustable state. */
data class DetrainingNotice(val weeksOff: Int)
```

Remove the old `DetrainingPrompt` data class and its `import MuscleGroupStrength` if now unused.

- [ ] **Step 2: Rewire the controller**

Replace `maybeOfferDetraining` with:

```kotlin
    private suspend fun maybeNoteDetraining() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val lastCompleted = database.workoutSessionDao().getRecentCompletedSessions(limit = 1)
            .firstOrNull()?.endTime ?: return
        val weeks = DetrainingModel.weeksOff(lastCompleted, System.currentTimeMillis())
        if (!DetrainingModel.qualifies(weeks)) return
        setState(preview.copy(detraining = DetrainingNotice(weeksOff = weeks)))
    }

    fun dismissDetrainingNotice() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        setState(preview.copy(detraining = null))
    }
```

Update the call site (line ~98) `maybeOfferDetraining()` → `maybeNoteDetraining()`. Delete `skipDetraining` (its only callers were the dialog and `applyDetraining`, both gone). The notice is informational — the plan's weights are already reduced by Task 2, so no `repository.getMuscleGroupStrengths()` call is needed.

- [ ] **Step 3: Delete the dialog, add the notice in the screen**

Delete `DetrainingDialog.kt`. In `WorkoutScreen.kt`, replace the `s.detraining?.let { prompt -> DetrainingDialog(...) }` block with an inline dismissible banner:

```kotlin
                    s.detraining?.let { notice ->
                        val weeks = notice.weeksOff
                        androidx.compose.material3.Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Welcome back — it's been $weeks ${if (weeks == 1) "week" else "weeks"}. " +
                                        "We've started you a little lighter; it'll climb back as you go.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = viewModel::dismissDetrainingNotice) { Text("Got it") }
                            }
                        }
                    }
```

Add any missing imports (`Card`, `Row`, `Alignment`, `Arrangement`, `padding`, `TextButton`, `Text`, `MaterialTheme`, `dp`). Remove the now-unused `weightUnit` reference if it was only used by the dialog.

- [ ] **Step 4: Update the ViewModel bindings**

Find the ViewModel (`grep -rn "viewModel::applyDetraining\|fun applyDetraining\|fun skipDetraining" app/src/main`). Remove `applyDetraining`/`skipDetraining` delegations; add `fun dismissDetrainingNotice() = controller.dismissDetrainingNotice()`.

- [ ] **Step 5: Build the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no references to `DetrainingDialog`, `DetrainingPrompt`, `applyDetraining`).

- [ ] **Step 6: Full regression**

Run: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:connectedAndroidTest`
Expected: PASS. Confirm `BeliefScoreTest` is green with its unchanged pinned number.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat(detrain): replace detraining dialog with an informational welcome-back notice"
```

---

## Deferred to later plans (not this plan)

- **Plan 2 — seed/override consolidation:** live seed expansion from per-muscle baselines × current table, manual override → ephemeral (drop `applyManualExerciseOverrides` durable write), delete `exercise_strength_override` (the one-time migration + backup/DAO/model removal). This plan's Task 3 already removed the DETRAIN writer, so after Plan 2 removes the seed + manual writers the table has none.
- **Plan 3 — coefficient generator + fit:** `CoefficientGuesses`, the compressed `ExerciseCoefficients` + assertion test, global λ fit via the salvaged `usv` harness, gate re-baseline.

## Self-review notes

- **Spec coverage:** implements the spec's "Detrain → inferred" bullet (A3) and its notification decision; the "delete table" and "manual → ephemeral" bullets are explicitly deferred to Plan 2, and the fit to Plan 3. No spec requirement for *this* plan is unaddressed.
- **No gate re-baseline:** confirmed — replay/scorer untouched, `history.json` has no DETRAIN rows, prescription isn't scored. If a future history gains DETRAIN rows, that's Plan 2's table-drop concern.
- **Type consistency:** `retention(gapMillis: Long): Float` used identically in Tasks 1–2; `DetrainingNotice(weeksOff: Int)` and `dismissDetrainingNotice()` named identically in Tasks 4.1/4.2/4.4.
