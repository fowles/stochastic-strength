# Equipment-Aware Warmup Ramp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give non-barbell exercises (dumbbell, machine, cable, kettlebell, band, bodyweight) a sensible percentage-based warmup ramp instead of the barbell plates-and-quarters sequence anchored to a 45 lb bar.

**Architecture:** `WorkoutPlanner.computeWarmupSets` branches on `exercise.equipment`. `BARBELL` (and `exercise == null`) keeps the existing plates-and-quarters logic untouched; everything else routes to a new private `percentageRampWarmups` that builds the ramp by stepping *down* from the working weight with a minimum jump and a 40% floor.

**Tech Stack:** Kotlin, JUnit4 (JVM unit tests via `./gradlew :app:testDebugUnitTest`).

## Global Constraints

- Package: `io.github.fowles.stochastic_strength`.
- Barbell warmup behavior, the feeler single, and floor-deadlift handling must remain unchanged — all existing `computeWarmupSets` tests stay green.
- `kotlin.math.max` is NOT imported in `WorkoutPlanner.kt`; use the built-in `maxOf(a, b)` (no import needed).
- Non-barbell stops round to the standard 5 lb / 2.5 kg grid via `WeightFormatter.round`, NOT `WeightFormatter.roundForWarmup`.
- Min jump: 20 lb in LBS mode (`WeightUnit.LBS.toKg(20f)`), 10 kg in KG mode. Step = `maxOf(minJump, 0.20 × W)`. Floor = `0.40 × W`. No feeler single.

---

### Task 1: Equipment-aware warmup ramp

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt` (function `computeWarmupSets` at line 131; add new private helper after it)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt` (add tests near the existing `// computeWarmupSets` block around line 381)

**Interfaces:**
- Consumes: `WorkoutPlanner.weightUnit: WeightUnit`, `WeightFormatter.round(kg: Float, unit: WeightUnit): Float`, `WeightUnit.LBS.toKg(Float): Float`, `WarmupSet(weight: Float, reps: Int)`, `Exercise.equipment: Equipment`, `Equipment.BARBELL`.
- Produces: `WorkoutPlanner.computeWarmupSets(weightKg: Float, exercise: Exercise? = null): List<WarmupSet>` — unchanged signature, new behavior for non-barbell equipment; new `private fun percentageRampWarmups(weightKg: Float): List<WarmupSet>`.

- [ ] **Step 1: Write the failing tests**

Add these tests to `WorkoutPlannerTest.kt` (the `exercise(...)` helper at line 35 and `lbsPlanner()`/`lbsToKg`/`roundedLbs()` helpers already exist; `Equipment` is already imported):

```kotlin
    // ──────────────────────────────────────────────────────────────────────
    // computeWarmupSets — non-barbell equipment (percentage ramp)
    // ──────────────────────────────────────────────────────────────────────

    private fun dumbbell(id: Long = 50L) =
        exercise(id, name = "Dumbbell Row", muscle = MuscleGroup.BACK, equipment = Equipment.DUMBBELL)

    @Test
    fun `computeWarmupSets 50lb dumbbell is a single light stop, not the 45lb bar`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(50f), dumbbell())
        assertEquals(listOf(30), warmups.map { it.roundedLbs() })
        assertEquals(listOf(3), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 100lb dumbbell steps down by 20 with proximity reps`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(100f), dumbbell())
        assertEquals(listOf(40, 60, 80), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 3, 2), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 40lb dumbbell yields one stop at the floor`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(40f), dumbbell())
        assertEquals(listOf(20), warmups.map { it.roundedLbs() })
        assertEquals(listOf(3), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 30lb dumbbell is too light for any warmup`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(30f), dumbbell())
        assertTrue(warmups.isEmpty())
    }

    @Test
    fun `computeWarmupSets machine uses percentage ramp in KG with 10kg min jump`() {
        val machine = exercise(60L, name = "Pec Deck", muscle = MuscleGroup.CHEST, equipment = Equipment.MACHINE)
        val warmups = planner().computeWarmupSets(40f, machine)
        assertEquals(listOf(20, 30), warmups.map { it.weight.roundToInt() })
        assertEquals(listOf(3, 2), warmups.map { it.reps })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`
Expected: FAIL — the new tests get the old barbell ramp (e.g. the 50 lb dumbbell returns an empty/`45`-based ramp, not `[30]`).

- [ ] **Step 3: Add the equipment branch and the new helper**

In `WorkoutPlanner.kt`, change the opening of `computeWarmupSets` (line 131) from:

```kotlin
    fun computeWarmupSets(weightKg: Float, exercise: Exercise? = null): List<WarmupSet> {
        val barKg = WeightFormatter.roundForWarmup(20f, weightUnit)
```

to:

```kotlin
    fun computeWarmupSets(weightKg: Float, exercise: Exercise? = null): List<WarmupSet> {
        // Non-barbell lifts have no bar and no plate math — ramp as a percentage
        // of the working weight instead of the barbell plates-and-quarters model.
        if (exercise != null && exercise.equipment != Equipment.BARBELL) {
            return percentageRampWarmups(weightKg)
        }

        val barKg = WeightFormatter.roundForWarmup(20f, weightUnit)
```

Then add this new private function immediately after `computeWarmupSets` closes (after its final `}` near line 196, before `private fun Exercise.isFloorDeadlift()`):

```kotlin
    // Percentage ramp for non-barbell lifts: step DOWN from the working weight by
    // max(minJump, 20% of W), collecting stops down to a 40% floor. No feeler —
    // the down-built ramp already ends close to the working weight.
    private fun percentageRampWarmups(weightKg: Float): List<WarmupSet> {
        if (weightKg <= 0f) return emptyList()

        val minJump = if (weightUnit == WeightUnit.LBS) WeightUnit.LBS.toKg(20f) else 10f
        val step = maxOf(minJump, weightKg * 0.20f)
        val floor = weightKg * 0.40f

        val stops = generateSequence(weightKg - step) { it - step }
            .takeWhile { it >= floor - 0.001f }
            .toList()
            .asReversed()
            .map { WeightFormatter.round(it, weightUnit) }
            .filter { it > 0f && it < weightKg }
            .distinct()

        return stops.map { w ->
            val reps = when {
                w < weightKg * 0.5f -> 5
                w < weightKg * 0.7f -> 3
                else -> 2
            }
            WarmupSet(w, reps)
        }
    }
```

- [ ] **Step 4: Run the full WorkoutPlanner suite to verify pass + no regressions**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`
Expected: PASS — the five new tests pass and every existing barbell/feeler/deadlift warmup test still passes (they pass `exercise == null` or a `BARBELL` exercise, so they keep the old path).

- [ ] **Step 5: Commit**

```bash
jj describe -m "WorkoutPlanner: equipment-aware warmup ramp

Non-barbell lifts (dumbbell/machine/cable/kettlebell/band/bodyweight) now ramp
as a percentage of the working weight, stepped down with a 20 lb / 10 kg min
jump to a 40% floor, instead of the barbell 45 lb-bar plates-and-quarters model.
Barbell behavior is unchanged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Full regression run

**Files:** none (verification only)

- [ ] **Step 1: Run the full JVM unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — whole suite green, confirming no other caller of `computeWarmupSets` (e.g. planner integration tests, `DurationCalculator`) regressed.

- [ ] **Step 2: If anything fails**, use superpowers:systematic-debugging before making changes; otherwise the feature is complete.

## Self-Review

- **Spec coverage:** Equipment branch (Task 1 Step 3) ✓; percentage ramp with min-jump/floor/step (Task 1 Step 3 `percentageRampWarmups`) ✓; 5 lb/2.5 kg rounding via `WeightFormatter.round` ✓; no feeler ✓; proximity reps ✓; worked examples pinned as tests (50/100/40/≤30 lb, KG machine) ✓; barbell untouched + existing tests green (Task 1 Step 4, Task 2) ✓.
- **Placeholder scan:** none — all steps contain concrete code and exact commands.
- **Type consistency:** `percentageRampWarmups(weightKg: Float): List<WarmupSet>` referenced consistently; `WarmupSet(weight, reps)` matches the data class; `WeightFormatter.round`/`WeightUnit.LBS.toKg` signatures match the source.
