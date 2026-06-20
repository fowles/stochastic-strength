# Last-Set Autoregulation Baseline Controller Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Replace the implied-1RM/confidence baseline heuristic with a last-set RIR autoregulation controller that removes the high-rep fatigue downward bias while preserving the existing mid-session reduction behavior.

**Architecture:** A new `LastSetAutoregulationHeuristic` implements the existing `BaselineHeuristic` interface (pure function over `BaselineComputationInput`). Per muscle it reads each exercise's last working set at full weight, maps its feedback to a target percentage, averages the contributing percentages, floors the move to whole weight increments, then applies the existing reduction clamp. It is swapped in via constructor injection in `StochasticStrengthApp`; the old heuristic and its test are deleted. A replay regression test guards the `SeedNormalizer` interaction.

**Tech Stack:** Kotlin, Android, JUnit4 (JVM unit tests under `src/test`), Room (instrumented tests under `src/androidTest`), Gradle.

## Global Constraints

- Package: `io.github.fowles.stochastic_strength`.
- Min SDK 33, Target SDK 36. No new dependencies.
- The app has real users: any Room schema/version change requires a proper `Migration`, never destructive fallback. **This plan introduces no schema change** (the heuristic output is derived state, recomputed from the event log).
- Use the `BaselineHeuristic` interface and `BaselineComputationInput` as-is — do not change their signatures.
- Weight rounding/increments come from `WeightFormatter` only: `round(kg, unit)` snaps to 2.5 kg / 5 lb; `minIncrement(unit)` returns 2.5 kg (KG) or `5f / 2.20462f` kg (LBS).
- Unit tests run on the JVM: `./gradlew :app:testDebugUnitTest`. Instrumented tests need a device/emulator: `./gradlew :app:connectedAndroidTest`.

---

### Task 1: `LastSetAutoregulationHeuristic` — per-exercise signal extraction

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristic.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristicTest.kt`

**Interfaces:**
- Consumes: `BaselineHeuristic`, `BaselineProposal`, `BaselineComputationInput` (in `domain/BaselineHeuristic.kt`); `WorkoutSet` (`data/model/WorkoutSet.kt`, fields: `exerciseId`, `setNumber`, `targetWeight`, `targetReps`, `actualReps`, `feedback`); `SetFeedback` (`TOO_HARD, HURT, RIR_0_1, RIR_2_4, RIR_5_PLUS`).
- Produces: `class LastSetAutoregulationHeuristic(bigUpPct, moderateUpPct, tinyUpPct, smallDownPct, hurtFactor, nearMissReps) : BaselineHeuristic` with `override val name = "last-set-autoregulation"`. Internal helper `fun exerciseTargetPct(exerciseSets: List<WorkoutSet>): Float?` — returns the signed target fraction (e.g. `0.05f`, `-0.05f`, `0f`) for the exercise's governing set, or `null` if the exercise contributes no signal (reduced mid-session, no working sets, or no usable feedback). HURT is *not* handled here (returns `null`); it is handled at the muscle level in Task 2.

- [x] **Step 1: Write the failing test for the signal table**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastSetAutoregulationHeuristicTest {

    private val heuristic = LastSetAutoregulationHeuristic()

    private fun set(
        exerciseId: Long = 1L,
        setNumber: Int = 1,
        targetWeight: Float = 100f,
        targetReps: Int = 10,
        actualReps: Int? = null,
        feedback: SetFeedback? = null,
    ) = WorkoutSet(
        sessionId = 1L,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = targetWeight,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
    )

    @Test
    fun governingSet_mapsFeedbackToTargetPct() {
        assertEquals(0.15f, heuristic.exerciseTargetPct(listOf(set(feedback = SetFeedback.RIR_5_PLUS)))!!, 1e-6f)
        assertEquals(0.10f, heuristic.exerciseTargetPct(listOf(set(feedback = SetFeedback.RIR_2_4)))!!, 1e-6f)
        assertEquals(0.05f, heuristic.exerciseTargetPct(listOf(set(feedback = SetFeedback.RIR_0_1)))!!, 1e-6f)
    }

    @Test
    fun nearMissFailure_holds() {
        // target 10, got 9 → within nearMiss(1) → hold (0%).
        val s = set(targetReps = 10, actualReps = 9, feedback = SetFeedback.TOO_HARD)
        assertEquals(0f, heuristic.exerciseTargetPct(listOf(s))!!, 1e-6f)
    }

    @Test
    fun genuineFailure_decreases() {
        // target 10, got 6 → beyond nearMiss → -5%.
        val s = set(targetReps = 10, actualReps = 6, feedback = SetFeedback.TOO_HARD)
        assertEquals(-0.05f, heuristic.exerciseTargetPct(listOf(s))!!, 1e-6f)
    }

    @Test
    fun failureWithoutReps_holds() {
        val s = set(feedback = SetFeedback.TOO_HARD, actualReps = null)
        assertEquals(0f, heuristic.exerciseTargetPct(listOf(s))!!, 1e-6f)
    }

    @Test
    fun noFeedback_andHurt_andEmpty_contributeNothing() {
        assertNull(heuristic.exerciseTargetPct(listOf(set(feedback = null))))
        assertNull(heuristic.exerciseTargetPct(listOf(set(feedback = SetFeedback.HURT))))
        assertNull(heuristic.exerciseTargetPct(emptyList()))
    }

    @Test
    fun governingSet_isLastSetAtFullWeight() {
        // 3 sets, no reduction. Last set (RIR_0_1) governs, not earlier sets.
        val sets = listOf(
            set(setNumber = 1, feedback = SetFeedback.RIR_5_PLUS),
            set(setNumber = 2, feedback = SetFeedback.RIR_2_4),
            set(setNumber = 3, feedback = SetFeedback.RIR_0_1),
        )
        assertEquals(0.05f, heuristic.exerciseTargetPct(sets)!!, 1e-6f)
    }

    @Test
    fun reducedExercise_contributesNoUpSignal() {
        // Set 1 at full 100 failed, sets 2-3 dropped to 90 and hit target with reserve.
        val sets = listOf(
            set(setNumber = 1, targetWeight = 100f, targetReps = 10, actualReps = 7, feedback = SetFeedback.TOO_HARD),
            set(setNumber = 2, targetWeight = 90f, feedback = SetFeedback.RIR_2_4),
            set(setNumber = 3, targetWeight = 90f, feedback = SetFeedback.RIR_0_1),
        )
        assertNull(heuristic.exerciseTargetPct(sets))
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.LastSetAutoregulationHeuristicTest"`
Expected: FAIL — `LastSetAutoregulationHeuristic` is unresolved / does not compile.

- [x] **Step 3: Write the class with signal extraction (compute() stubbed)**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * Last-set RIR autoregulation controller. Per muscle, the most recent session's last
 * working set (at full, un-reduced weight) for each exercise maps to a target percentage;
 * the contributing percentages are averaged, floored to whole weight increments, then the
 * existing mid-session reduction clamp is applied. Replaces the implied-1RM estimator to
 * remove its high-rep fatigue downward bias. See
 * docs/superpowers/specs/2026-06-17-last-set-baseline-controller-design.md
 */
class LastSetAutoregulationHeuristic(
    private val bigUpPct: Float = 0.15f,
    private val moderateUpPct: Float = 0.10f,
    private val tinyUpPct: Float = 0.05f,
    private val smallDownPct: Float = 0.05f,
    private val hurtFactor: Float = 0.85f,
    private val nearMissReps: Int = 1,
) : BaselineHeuristic {

    override val name: String = "last-set-autoregulation"

    override fun compute(input: BaselineComputationInput): List<BaselineProposal> = emptyList()

    /**
     * Signed target fraction for the exercise's governing set, or null if the exercise
     * contributes no signal (reduced mid-session, no working sets, or no usable feedback).
     * HURT returns null here; it is handled at the muscle level in compute().
     */
    internal fun exerciseTargetPct(exerciseSets: List<WorkoutSet>): Float? {
        val working = exerciseSets.sortedBy { it.setNumber }
        if (working.isEmpty()) return null
        val fullWeight = working.first().targetWeight
        val eps = 0.001f
        val reduced = working.any { it.targetWeight < fullWeight - eps }
        if (reduced) return null // down-story handled by the reduction clamp
        val governing = working.lastOrNull { it.targetWeight >= fullWeight - eps } ?: return null
        return when (governing.feedback) {
            null -> null
            SetFeedback.HURT -> null
            SetFeedback.RIR_5_PLUS -> bigUpPct
            SetFeedback.RIR_2_4 -> moderateUpPct
            SetFeedback.RIR_0_1 -> tinyUpPct
            SetFeedback.TOO_HARD -> {
                val reps = governing.actualReps
                when {
                    reps == null -> 0f
                    reps >= governing.targetReps - nearMissReps -> 0f
                    else -> -smallDownPct
                }
            }
        }
    }
}
```

- [x] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.LastSetAutoregulationHeuristicTest"`
Expected: PASS (all signal-extraction tests).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristic.kt app/src/test/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristicTest.kt
git commit -m "feat(domain): last-set autoregulation signal extraction

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `compute()` — aggregation, percentage floor, clamp, HURT, no-op

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristic.kt` (replace the stubbed `compute`)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristicTest.kt` (add cases)

**Interfaces:**
- Consumes: `exerciseTargetPct` (Task 1); `WeightFormatter.round(kg, unit)`, `WeightFormatter.minIncrement(unit)`; `BaselineComputationInput` fields `sets`, `exerciseMuscle`, `currentBaselines`, `minReductionFractions`, `weightUnit`; `MuscleGroup`; `WeightUnit`.
- Produces: `compute(input): List<BaselineProposal>` — one `BaselineProposal(muscleGroup, newBaseline, metadata)` per muscle whose baseline changes.

- [x] **Step 1: Write the failing tests for compute()**

Add to `LastSetAutoregulationHeuristicTest`:

```kotlin
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import org.junit.Assert.assertTrue

private fun input(
    sets: List<WorkoutSet>,
    currentBaselines: Map<MuscleGroup, Float> = mapOf(MuscleGroup.CHEST to 100f),
    exerciseMuscle: Map<Long, MuscleGroup> = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
    minReductionFractions: Map<MuscleGroup, Float> = emptyMap(),
    weightUnit: WeightUnit = WeightUnit.KG,
) = BaselineComputationInput(
    sets = sets,
    exerciseMuscle = exerciseMuscle,
    currentCoefficients = mapOf(1L to 1.0f, 2L to 1.0f),
    currentBaselines = currentBaselines,
    recentHistory = emptyMap<MuscleGroup, List<BaselineHistory>>(),
    sessionReps = 10,
    minReductionFractions = minReductionFractions,
    asOf = 1_000_000L,
    weightUnit = weightUnit,
)

@Test
fun rir01_creepsOneIncrement_atHeavyBaseline() {
    // 5% of 100 kg = 5 kg → floor to 2.5 kg increments → 2 steps = 5 kg. B_new = 105.
    val s = set(targetWeight = 100f, feedback = SetFeedback.RIR_0_1)
    val r = heuristic.compute(input(listOf(s)))
    assertEquals(1, r.size)
    assertEquals(105f, r.single().newBaseline, 1e-4f)
}

@Test
fun rir01_holds_atLightBaseline_belowFloor() {
    // 5% of 40 kg = 2.0 kg < 2.5 kg increment → floor to 0 → no proposal.
    val s = set(targetWeight = 40f, feedback = SetFeedback.RIR_0_1)
    val r = heuristic.compute(input(listOf(s), currentBaselines = mapOf(MuscleGroup.CHEST to 40f)))
    assertTrue(r.isEmpty())
}

@Test
fun fatigueAcrossSets_doesNotPunish_holds() {
    // target 10 → 13,11,9 across 3 sets at full weight, no drop. Last set TOO_HARD/9
    // is a near-miss (within 1) → hold → no proposal. (The original bug: this used to drop.)
    val sets = listOf(
        set(setNumber = 1, targetReps = 10, feedback = SetFeedback.RIR_2_4),
        set(setNumber = 2, targetReps = 10, feedback = SetFeedback.RIR_0_1),
        set(setNumber = 3, targetReps = 10, actualReps = 9, feedback = SetFeedback.TOO_HARD),
    )
    val r = heuristic.compute(input(sets))
    assertTrue(r.isEmpty())
}

@Test
fun hurt_overridesAndBacksOff() {
    val sets = listOf(
        set(setNumber = 1, feedback = SetFeedback.RIR_5_PLUS),
        set(setNumber = 2, feedback = SetFeedback.HURT),
    )
    // round(100 * 0.85) = round(85) = 85.
    val r = heuristic.compute(input(sets))
    assertEquals(85f, r.single().newBaseline, 1e-4f)
}

@Test
fun reductionClamp_winsOverUpSignal() {
    // Clean RIR_5_PLUS (would be +15%) but the muscle was dropped 10% mid-session.
    // cap = round(100 * 0.90) = 90 → B_new clamped to 90.
    val s = set(targetWeight = 100f, feedback = SetFeedback.RIR_5_PLUS)
    val r = heuristic.compute(input(listOf(s), minReductionFractions = mapOf(MuscleGroup.CHEST to 0.10f)))
    assertEquals(90f, r.single().newBaseline, 1e-4f)
}

@Test
fun twoExercises_averageTheirPercentages() {
    // Ex1 RIR_5_PLUS (+15%), Ex2 RIR_0_1 (+5%) → avg 10% of 100 = 10 kg → floor 2.5 → 10 kg. B_new=110.
    val sets = listOf(
        set(exerciseId = 1L, feedback = SetFeedback.RIR_5_PLUS),
        set(exerciseId = 2L, feedback = SetFeedback.RIR_0_1),
    )
    val r = heuristic.compute(input(sets))
    assertEquals(110f, r.single().newBaseline, 1e-4f)
}

@Test
fun noSignal_noProposal() {
    val s = set(feedback = null)
    assertTrue(heuristic.compute(input(listOf(s))).isEmpty())
}
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.LastSetAutoregulationHeuristicTest"`
Expected: FAIL — `compute` returns `emptyList()`, so the non-empty assertions fail.

- [x] **Step 3: Implement compute()**

Replace the stubbed `compute` in `LastSetAutoregulationHeuristic.kt`:

```kotlin
    override fun compute(input: BaselineComputationInput): List<BaselineProposal> {
        val out = mutableListOf<BaselineProposal>()
        val setsByMuscle = input.sets.groupBy { input.exerciseMuscle[it.exerciseId] }
        val increment = WeightFormatter.minIncrement(input.weightUnit)
        for ((muscle, muscleSets) in setsByMuscle) {
            if (muscle == null) continue
            val bOld = input.currentBaselines[muscle] ?: continue
            if (bOld <= 0f) continue

            // Pain overrides everything.
            if (muscleSets.any { it.feedback == SetFeedback.HURT }) {
                val bNew = WeightFormatter.round(bOld * hurtFactor, input.weightUnit)
                if (bNew != bOld) out.add(BaselineProposal(muscle, bNew, "hurt"))
                continue
            }

            val pcts = muscleSets.groupBy { it.exerciseId }
                .values
                .mapNotNull { exerciseTargetPct(it) }
            val avgPct = if (pcts.isEmpty()) 0f else pcts.sum() / pcts.size

            // Floor the raw move to whole increments, toward zero, sign preserved.
            val rawMove = bOld * avgPct
            val steps = (kotlin.math.abs(rawMove) / increment).toInt()
            val flooredMove = if (rawMove >= 0f) steps * increment else -steps * increment
            var bNew = bOld + flooredMove

            // Reduction clamp: authoritative downward gate for mid-session drops.
            val minRed = input.minReductionFractions[muscle] ?: 0f
            if (minRed > 0f) {
                val cap = WeightFormatter.round(bOld * (1f - minRed), input.weightUnit)
                if (bNew > cap) bNew = cap
            }

            bNew = WeightFormatter.round(bNew, input.weightUnit)
            if (bNew == bOld) continue

            val meta = if (pcts.isEmpty()) "clamp" else "n=${pcts.size},avgPct=${"%.3f".format(java.util.Locale.ROOT, avgPct)}"
            out.add(BaselineProposal(muscle, bNew, meta))
        }
        return out
    }
```

`WeightFormatter` lives in the same `domain` package as this file, so no import is needed.

- [x] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.LastSetAutoregulationHeuristicTest"`
Expected: PASS (all signal + compute tests).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristic.kt app/src/test/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristicTest.kt
git commit -m "feat(domain): last-set autoregulation aggregation + clamp

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Wire in the new heuristic; remove the old one

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt:41` (constructor arg + import)
- Delete: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstBaselineConsensusHeuristic.kt`
- Delete: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstBaselineConsensusHeuristicTest.kt`

**Interfaces:**
- Consumes: `LastSetAutoregulationHeuristic` (Tasks 1-2); `WorkoutRepository(... baselineHeuristic: BaselineHeuristic ...)` constructor (unchanged).
- Produces: the running app uses `LastSetAutoregulationHeuristic` as its `BaselineHeuristic`. No interface changes.

- [x] **Step 1: Confirm `EstBaselineConsensusHeuristic` has no other references**

Run: `grep -rn "EstBaselineConsensusHeuristic" app/src`
Expected: only `StochasticStrengthApp.kt`, `EstBaselineConsensusHeuristic.kt`, and `EstBaselineConsensusHeuristicTest.kt`. If any other file references it, stop and report — that consumer must be migrated first.

- [x] **Step 2: Swap the heuristic in `StochasticStrengthApp`**

In `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt`:
- Replace the import `import io.github.fowles.stochastic_strength.domain.EstBaselineConsensusHeuristic` with `import io.github.fowles.stochastic_strength.domain.LastSetAutoregulationHeuristic`.
- Change line 41 `baselineHeuristic = EstBaselineConsensusHeuristic(),` to `baselineHeuristic = LastSetAutoregulationHeuristic(),`.

- [x] **Step 3: Delete the old heuristic and its test**

```bash
git rm app/src/main/java/io/github/fowles/stochastic_strength/domain/EstBaselineConsensusHeuristic.kt
git rm app/src/test/java/io/github/fowles/stochastic_strength/domain/EstBaselineConsensusHeuristicTest.kt
```

- [x] **Step 4: Build and run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, with no remaining references to `EstBaselineConsensusHeuristic`. (`EstCoefConsensusHeuristic` and its test remain — they are a different class; do not touch them.)

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(repo): wire LastSetAutoregulationHeuristic; remove old estimator

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Guard the `SeedNormalizer` interaction (replay regression)

**Files:**
- Modify (only if Step 2 shows a regression): `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` around `applyBaselineNormalization` (lines ~221-278)
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/FatigueNoDownwardBiasReplayTest.kt`

**Interfaces:**
- Consumes: `WorkoutRepository` replay entry point used by existing instrumented tests (e.g. `WorkoutRepositoryTest` / `ReplayDerivedStateTest` — follow their setup pattern for building a DB, seeding baselines, inserting sessions/sets, and reading resulting `MuscleGroupStrength`/`BaselineHistory`).
- Produces: a regression test proving that a sequence of clean high-rep sessions (no drops, no pain, last sets at/above target) never drives a muscle's baseline *down* via the `EstCoeff` → `SeedNormalizer` path.

- [x] **Step 1: Read the existing instrumented test setup**

Read `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/ReplayDerivedStateTest.kt` and `WorkoutRepositoryTest.kt` to copy the exact in-memory `AppDatabase` construction, baseline seeding, and session/set insertion helpers. Reuse them; do not invent a new harness.

- [x] **Step 2: Write the regression test (expected to PASS already)**

Create `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/FatigueNoDownwardBiasReplayTest.kt`. Using the copied harness: seed one muscle baseline (e.g. CHEST at 100 kg) with one exercise (coef 1.0). Insert 3 sessions, each 3 working sets at the same full weight, last set a near-miss `TOO_HARD` with `actualReps = targetReps - 1` (the fatigue pattern), no reductions, no HURT. Run the replay/recompute. Assert the resulting CHEST baseline is `>= 100f` (never below the seed) — i.e. fatigue never drags it down through normalization.

```kotlin
// Skeleton — fill DB setup from ReplayDerivedStateTest:
@Test
fun cleanFatigueSessions_neverDriveBaselineDown() = runBlocking {
    // seed CHEST=100, one exercise coef=1.0
    // insert 3 sessions: sets at 100kg targetReps=10, last set TOO_HARD actualReps=9
    // run replayDerivedState()
    val baseline = db.muscleGroupStrengthDao().get(MuscleGroup.CHEST)?.baselineWeight ?: 0f
    assertTrue("baseline should not drop from fatigue, was $baseline", baseline >= 100f)
}
```

- [x] **Step 3: Run the test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.FatigueNoDownwardBiasReplayTest"`
Expected: PASS. If it PASSES, the `SeedNormalizer` path is safe with the new controller — skip Step 4, go to Step 5.

- [x] **Step 4: Only if Step 3 FAILS — gate the normalizer's downward move**

If normalization drives the baseline below the seed, restrict `applyBaselineNormalization` so a normalization proposal may not produce a *net downward* baseline move in the same session a progression proposal held or raised it. Minimal change: in the loop in `WorkoutRepository.applyBaselineNormalization`, skip applying a proposal when `newBaseline < oldBaseline` and the muscle had no reduction/HURT this session (i.e. only allow normalization to *rebalance upward or split*, not to lower baseline on a clean session). Re-run Step 3 to confirm PASS. Keep the change minimal and commented with a pointer to the spec's "Open risk" section.

- [x] **Step 5: Run the full instrumented suite for regressions**

Run: `./gradlew :app:connectedAndroidTest`
Expected: PASS (existing repository/replay tests still green).

- [x] **Step 6: Commit**

```bash
git add -A
git commit -m "test(repo): guard SeedNormalizer against fatigue downward bias

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Full regression + docs touch-up

**Files:**
- Modify (if needed): `CLAUDE.md` "Progression system" paragraph (it names the old estimator behavior)

- [x] **Step 1: Run the complete unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [x] **Step 2: Run the complete instrumented suite**

Run: `./gradlew :app:connectedAndroidTest`
Expected: PASS.

- [x] **Step 3: Update `CLAUDE.md` progression description**

In `CLAUDE.md`, the "Progression system" section describes `computeNextBaseline` / implied-1RM behavior. Update the sentence(s) that describe how the per-muscle baseline is updated to reflect the last-set autoregulation controller (percentage buckets keyed on the last full-weight set, floored to increments, reduction clamp preserved). Keep it to the existing paragraph's length; do not restructure the doc.

- [x] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: describe last-set autoregulation in CLAUDE.md

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
