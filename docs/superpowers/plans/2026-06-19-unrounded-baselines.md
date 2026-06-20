# Unrounded Baselines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store per-muscle progression baselines at full precision, rounding to the weight grid only at exercise-weight selection.

**Architecture:** Remove the two `WeightFormatter.round` calls in `RollingConservingProgressionController` so baseline updates are stored as the raw `b * exp(dLogB)` (progression) and `b * hurtFactor` (HURT). `WorkoutPlanner` already rounds at selection time, so no other production code changes. This eliminates the rounding deadband that stalled progression on sub-grid pushes.

**Tech Stack:** Kotlin, JUnit4, Android (JVM unit tests via `:app:testDebugUnitTest`).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-06-19-unrounded-baselines-design.md`.
- No DB schema change, no Room migration. Same `Float` column; values simply stop being grid multiples.
- Do NOT touch `WorkoutPlanner` selection rounding (`:92`, `:167`), warmup rounding (`:113`), or `WeightFormatter`.
- The controller remains a pure P-loop — no integral/anchor term.
- Decision: **write every change** — no new baseline deadband.
- Build tool is jj (Jujutsu). Use `jj describe -m "…"` on the working copy, then `jj new` to open the next change. End commit descriptions with the `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer.

---

### Task 1: Store unrounded baselines in the controller

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt` (`:102` HURT, `:122` progression, and the `BaselineUpdate` KDoc near `:30-36`)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt`

**Interfaces:**
- Consumes: existing `RollingConservingProgressionController.step(ProgressionStepInput): ProgressionStepOutput`, `BaselineUpdate(muscleGroup, newBaseline, metadata)`, `ProgressionControllerConfig(minRelativeChange = …, kB = 0.5, hurtFactor = 0.85, maxLogStepB = ln(1.15))`.
- Produces: `BaselineUpdate.newBaseline` is now the unrounded controller output (raw kg), not grid-snapped.

- [x] **Step 1: Write the failing tests**

In `ProgressionControllerTest.kt`, add the `assertNotEquals` import next to the existing JUnit imports:

```kotlin
import org.junit.Assert.assertNotEquals
```

Add this new test (asserts the progression update is the exact raw value, off the 2.5 kg grid). With `baseline=100`, single on-pool exercise reading 10% high: `common = ln(1.10)`, `dLogB = 0.5·ln(1.10)` (well under the `ln(1.15)` clamp), so `newBaseline = 100·exp(0.5·ln(1.10)) ≈ 104.8808`, which rounds to `105.0` — proving it is not grid-snapped.

```kotlin
@Test
fun progression_storesUnroundedBaseline() {
    val baseline = 100f
    val coefs = mapOf(1L to 1.0f)
    // Single exercise reading 10% above prescription => common = ln(1.10), differential = 0.
    val o = listOf(obs(1, baseline * 1.0f * 1.10f))
    val out = controller().step(input(1000, o, baseline, coefs))
    val nb = out.baselineUpdates.single().newBaseline
    val expectedRaw = baseline * exp(0.5f * ln(1.10f)) // kB * common, unclamped
    assertEquals("baseline stored at full precision", expectedRaw, nb, 1e-3f)
    assertNotEquals(
        "must NOT snap to the weight grid",
        WeightFormatter.round(expectedRaw, unit),
        nb,
    )
}
```

Replace the existing `hurt_backsOffBaseline_andSkipsCoefficients` test with an off-grid version. `baseline=101` makes `101·0.85 = 85.85`, which the old rounding would snap to `85.0`:

```kotlin
@Test
fun hurt_backsOffBaseline_andSkipsCoefficients() {
    val baseline = 101f // 101 * 0.85 = 85.85 -> off the 2.5 kg grid (old code rounded to 85.0)
    val coefs = mapOf(1L to 1.0f)
    val in0 = input(1000, listOf(obs(1, baseline * 1.5f)), baseline, coefs)
        .copy(hurtMuscles = setOf(m))
    val out = controller().step(in0)
    val nb = out.baselineUpdates.single().newBaseline
    assertEquals("hurt backs off by exactly hurtFactor", baseline * 0.85f, nb, 1e-3f)
    assertNotEquals("hurt back-off must NOT snap to grid", WeightFormatter.round(nb, unit), nb)
    assertTrue("hurt suppresses coefficient moves", out.coefficientUpdates.isEmpty())
}
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: FAIL — `progression_storesUnroundedBaseline` (nb is `105.0`, not `104.88`) and `hurt_backsOffBaseline_andSkipsCoefficients` (nb is `85.0`, not `85.85`).

- [x] **Step 3: Remove rounding in the controller**

In `ProgressionController.kt`, the HURT branch (around `:102`), change:

```kotlin
                val bNew = WeightFormatter.round(b * config.hurtFactor, input.weightUnit)
```
to:
```kotlin
                val bNew = b * config.hurtFactor
```

In the progression branch (around `:122`), change:

```kotlin
            val bNew = WeightFormatter.round(b * exp(dLogB), input.weightUnit)
```
to:
```kotlin
            val bNew = b * exp(dLogB)
```

Leave the `if (bNew != b && bNew > 0f)` guards exactly as they are.

Update the `BaselineUpdate` KDoc (around `:30-36`) from:

```kotlin
/**
 * A controller's proposed update for one muscle's baseline.
 *
 * [newBaseline] is already rounded to the weight grid (kg or lb increment) by the controller;
 * the persistence layer stores it verbatim without further rounding.
 */
```
to:
```kotlin
/**
 * A controller's proposed update for one muscle's baseline.
 *
 * [newBaseline] is stored at full precision (raw kg); the controller does NOT round it.
 * Grid rounding (kg/lb increment) happens only at weight selection in WorkoutPlanner, so
 * sub-grid progression accumulates instead of being lost to a rounding deadband.
 */
```

If `WeightFormatter` is now unused in `ProgressionController.kt`, remove its import; if it is still referenced elsewhere in the file, keep it.

- [x] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: PASS (all tests in the class, including the two edited ones).

- [x] **Step 5: Commit**

```bash
jj describe -m "feat: store progression baselines unrounded

Round to the weight grid only at exercise-weight selection (WorkoutPlanner),
not in the progression controller. Sub-grid pushes now accumulate instead of
being lost to a rounding deadband.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

### Task 2: Verify simulation invariants and full suite

**Files:**
- Possibly modify (doc comments only): `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerSimulationTest.kt`

**Interfaces:**
- Consumes: Task 1's unrounded controller output. The simulation already applies `out.baselineUpdates.forEach { baselines[it.muscleGroup] = it.newBaseline }` verbatim and rounds only at session-weight selection (`WeightFormatter.round(fromOneRepMax(...))`), mirroring `WorkoutPlanner`.
- Produces: confidence that convergence/jitter/error/gauge ceilings still hold.

- [x] **Step 1: Run the simulation test**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerSimulationTest"`
Expected: PASS. Locked ceilings have headroom (convergence ≤ 8; trainedErr ≤ 4.0, doc ~2.3; jitter ≤ 1.0, doc ~0.5; coefInflation ∈ 0.97–1.03). Removing quantization should keep them green and likely lower jitter.

- [x] **Step 2: Reconcile the `// doc: ~X` comments if metrics drifted**

If the test passes but the realized metrics no longer match the inline `// doc: ~X` annotations (lines around `:244-254`), update only those comments to the new observed values. Do NOT loosen any `assertTrue(... <= ceiling)` bound. If a ceiling genuinely fails, STOP and report the numbers — do not adjust the ceiling without explicit approval.

- [x] **Step 3: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (no regressions across the module).

- [x] **Step 4: Commit**

Only if Step 2 changed the file:

```bash
jj describe -m "test: reconcile simulation doc annotations after unrounded baselines

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

If Step 2 made no changes, there is nothing to commit for this task — the verification stands on Task 1's commit.

---

## Notes for the implementer

- The harness used to investigate this (`BaselineReplayReconstructionTest`) was a throwaway and has already been deleted; do not recreate it.
- There is no UI or display change: `StrengthGrid` (via `WeightFormatter.format`), the debug baseline chart, and exercise detail already round for display. After this change they will show finer values (e.g. "282 lbs"), which is the intended effect.
- Existing users' baselines are recomputed in memory on next launch by `WorkoutRepository.replayDerivedState`; they will settle to their unrounded equilibrium with no migration.
