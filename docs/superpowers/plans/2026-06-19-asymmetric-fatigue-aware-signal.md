# Asymmetric, Fatigue-Aware Progression Signal — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reshape the per-session capacity signal so the baseline settles where the last, fatigued set is completable at RIR_0_1 — gently progressing, never growing on a session that contained a failure, with high-rep regimes more forgiving of a final-set miss.

**Architecture:** All signal-semantics changes live in `SessionSignalExtractor`; the `RollingConservingProgressionController` (control dynamics, gauge conservation, HURT) is untouched. Each set collapses to a signed rep-deviation; a per-exercise asymmetric aggregation lets failures dominate the down-pull while non-failing sets only soften it (rep-scaled). The simulation harness gains a cross-set fatigue model and a behavioral steady-state metric so the new behavior is validated and locked.

**Tech Stack:** Kotlin, JUnit4, Gradle (`./gradlew :app:testDebugUnitTest`), Jujutsu (`jj`) for commits.

## Global Constraints

- Rep target spans **`[1, 20]`** (`RepRangePicker` round values 1,2,3,5,8,10,12,15,18,20 plus chosen extrema). Every rep-dependent formula must be defined over the full range.
- **Do not** modify `RollingConservingProgressionController`, `WorkoutPlanner`, weight selection, or rounding.
- `SessionSignalExtractor.aggregateSession(sets: List<WorkoutSet>): SessionAggregate?` keeps its signature, and `SessionAggregate(est1RM: Float, sessionConfidence: Float)` keeps its fields, so the caller in `WorkoutRepository` (line ~100) needs no change.
- Reserve offsets (reps-in-reserve per non-failing bucket): `RIR_5_PLUS → +6`, `RIR_2_4 → +3`, `RIR_0_1 → +0.5`.
- Softening: `σ(reps) = 0.10 + 0.70 · (clamp(reps,1,20) − 1) / 19`.
- est-1RM is computed **unrounded** (fractional reps), consistent with the unrounded-baselines direction.
- Per-set confidences: `RIR_5_PLUS 0.40`, `RIR_2_4 0.70`, `RIR_0_1 0.85`, `TOO_HARD 0.95`.
- Run the specific test target after each change; run the full `:app:testDebugUnitTest` at the end.

---

### Task 1: Fractional-reps 1RM in `DefaultProgressionEngine`

The Option-2 gentle nudge (`RIR_0_1 → +0.5` rep) only survives if est-1RM is computed at fractional reps. Add a `Float`-reps raw 1RM and delegate the existing `Int` one to it.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/DefaultProgressionEngine.kt:19-24`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/DefaultProgressionEngineTest.kt`

**Interfaces:**
- Produces: `internal fun DefaultProgressionEngine.rawToOneRepMax(weight: Float, reps: Float): Float` — unrounded 1RM at fractional reps; `reps ≤ 1f` returns `weight`. Used by Task 2.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/DefaultProgressionEngineTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProgressionEngineTest {

    @Test
    fun fractional_reps_match_int_at_whole_numbers() {
        assertEquals(
            DefaultProgressionEngine.rawToOneRepMax(100f, 5),
            DefaultProgressionEngine.rawToOneRepMax(100f, 5f),
            1e-3f,
        )
    }

    @Test
    fun fractional_reps_interpolate_monotonically() {
        val a = DefaultProgressionEngine.rawToOneRepMax(100f, 5f)
        val mid = DefaultProgressionEngine.rawToOneRepMax(100f, 5.5f)
        val b = DefaultProgressionEngine.rawToOneRepMax(100f, 6f)
        assertTrue("expected $a < $mid < $b", a < mid && mid < b)
    }

    @Test
    fun fractional_reps_at_or_below_one_return_weight() {
        assertEquals(100f, DefaultProgressionEngine.rawToOneRepMax(100f, 0.5f), 1e-6f)
        assertEquals(100f, DefaultProgressionEngine.rawToOneRepMax(100f, 1f), 1e-6f)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DefaultProgressionEngineTest"`
Expected: FAIL — compile error, `rawToOneRepMax(Float, Float)` not resolved.

- [x] **Step 3: Add the fractional overload and delegate**

In `DefaultProgressionEngine.kt`, replace the existing `internal fun rawToOneRepMax(weight: Float, reps: Int)` block (lines 19-24):

```kotlin
    internal fun rawToOneRepMax(weight: Float, reps: Int): Float = rawToOneRepMax(weight, reps.toFloat())

    internal fun rawToOneRepMax(weight: Float, reps: Float): Float {
        if (weight <= 0f || reps <= 1f) return weight
        val denom = -2.55f + 4.58f * ln(weight)
        if (denom <= 0f) return weight * (1f + reps / 30f)
        return weight * (1f + (reps - 1f).pow(0.85f) / denom)
    }
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DefaultProgressionEngineTest"`
Expected: PASS (3 tests).

- [x] **Step 5: Commit**

```bash
jj commit -m "feat: fractional-reps 1RM in DefaultProgressionEngine

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Rewrite `SessionSignalExtractor` (asymmetric, fatigue-aware signal)

Replace the whole extractor. Per-set signed rep-deviation; per-exercise asymmetric aggregation over full-weight sets with `confidence × position` weighting; failures dominate and are capped at no-growth; rep-scaled softening. Rewrite its unit test. Because this changes steady-state behavior, the two simulation asserts no longer hold under the (still fatigue-free) harness — quarantine them with `@Ignore` here; Task 3 re-locks them.

**Files:**
- Modify (full rewrite): `app/src/main/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractor.kt`
- Modify (full rewrite): `app/src/test/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractorTest.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerSimulationTest.kt:237,249` (add `@Ignore`)

**Interfaces:**
- Consumes: `DefaultProgressionEngine.rawToOneRepMax(weight: Float, reps: Float)` (Task 1).
- Produces:
  - `const val SessionSignalExtractor.RESERVE_RIR_0_1 = 0.5f`, `RESERVE_RIR_2_4 = 3f`, `RESERVE_RIR_5_PLUS = 6f` (used by Task 4).
  - `fun SessionSignalExtractor.softening(reps: Int): Float`.
  - `data class SetSignal(val repDeviation: Float, val confidence: Float, val isFailure: Boolean)`.
  - `fun setSignal(set: WorkoutSet): SetSignal?`.
  - `fun aggregateSession(sets: List<WorkoutSet>): SessionAggregate?` with `SessionAggregate(est1RM, sessionConfidence)`.

- [x] **Step 1: Write the failing tests**

Replace the entire contents of `SessionSignalExtractorTest.kt` with:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSignalExtractorTest {

    private fun set(weight: Float, reps: Int, fb: SetFeedback?, actual: Int? = null, setNumber: Int = 1) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = setNumber,
            targetWeight = weight, targetReps = reps, actualReps = actual, feedback = fb)

    private fun oneRm(weight: Float, repsF: Float) = DefaultProgressionEngine.rawToOneRepMax(weight, repsF)

    // ---- setSignal -------------------------------------------------------------------------------

    @Test
    fun set_signal_maps_each_bucket() {
        assertEquals(0.5f, SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_0_1))!!.repDeviation, 1e-6f)
        assertEquals(3f, SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_2_4))!!.repDeviation, 1e-6f)
        assertEquals(6f, SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_5_PLUS))!!.repDeviation, 1e-6f)
        assertFalse(SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_0_1))!!.isFailure)
    }

    @Test
    fun too_hard_with_reps_is_negative_failure() {
        val s = SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.TOO_HARD, actual = 2))!!
        assertEquals(-3f, s.repDeviation, 1e-6f) // 2 - 5
        assertTrue(s.isFailure)
        assertEquals(0.95f, s.confidence, 1e-6f)
    }

    @Test
    fun too_hard_without_reps_assumes_half_target_shortfall() {
        val s = SessionSignalExtractor.setSignal(set(100f, 10, SetFeedback.TOO_HARD, actual = null))!!
        assertEquals(-5f, s.repDeviation, 1e-6f) // -(10 / 2)
        assertTrue(s.isFailure)
    }

    @Test
    fun hurt_yields_no_set_signal() {
        assertNull(SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.HURT)))
    }

    // ---- softening -------------------------------------------------------------------------------

    @Test
    fun softening_spans_full_rep_range_monotonically() {
        assertEquals(0.10f, SessionSignalExtractor.softening(1), 1e-4f)
        assertEquals(0.80f, SessionSignalExtractor.softening(20), 1e-4f)
        assertTrue(SessionSignalExtractor.softening(5) < SessionSignalExtractor.softening(10))
        assertTrue(SessionSignalExtractor.softening(10) < SessionSignalExtractor.softening(15))
        // out-of-range clamps
        assertEquals(0.10f, SessionSignalExtractor.softening(0), 1e-4f)
        assertEquals(0.80f, SessionSignalExtractor.softening(25), 1e-4f)
    }

    // ---- aggregateSession --------------------------------------------------------------------------

    @Test
    fun rir01_only_nudges_up_gently() {
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 3),
            ),
        )!!
        // offset = +0.5; above target (up), but below the old +1.
        assertTrue(agg.est1RM > oneRm(100f, 5f))
        assertTrue(agg.est1RM < oneRm(100f, 6f))
        assertEquals(oneRm(100f, 5.5f), agg.est1RM, 1e-2f)
    }

    @Test
    fun easy_early_sets_do_not_dominate_the_last_set() {
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_5_PLUS, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_2_4, setNumber = 2),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 3),
            ),
        )!!
        // position-weighting pulls the offset well below the plain mean (3.17) toward the last set.
        assertTrue(agg.est1RM < oneRm(100f, 8f)) // offset < 3
    }

    @Test
    fun a_failure_can_never_grow_the_session() {
        // two very easy sets then a small final miss: softened, but capped at no-growth.
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_5_PLUS, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_5_PLUS, setNumber = 2),
                set(100f, 5, SetFeedback.TOO_HARD, actual = 4, setNumber = 3),
            ),
        )!!
        assertTrue(agg.est1RM <= oneRm(100f, 5f) + 1e-2f)
    }

    @Test
    fun a_big_failure_dominates_downward() {
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
                set(100f, 5, SetFeedback.TOO_HARD, actual = 2, setNumber = 3),
            ),
        )!!
        assertTrue(agg.est1RM < oneRm(100f, 5f) * 0.99f)
    }

    @Test
    fun high_reps_more_forgiving_of_final_miss_than_low_reps() {
        fun scenario(reps: Int): Float {
            val agg = SessionSignalExtractor.aggregateSession(
                listOf(
                    set(100f, reps, SetFeedback.RIR_2_4, setNumber = 1),
                    set(100f, reps, SetFeedback.RIR_2_4, setNumber = 2),
                    set(100f, reps, SetFeedback.TOO_HARD, actual = reps - 2, setNumber = 3),
                ),
            )!!
            return agg.est1RM / oneRm(100f, reps.toFloat()) // ratio vs on-target
        }
        // high-rep ratio is closer to (or at) 1.0 — less downward — than low-rep.
        assertTrue(scenario(20) > scenario(5))
    }

    @Test
    fun reduced_weight_sets_are_ignored() {
        // last set dropped to a lighter weight after a miss; only the full-weight sets count.
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
                set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
                set(80f, 5, SetFeedback.RIR_5_PLUS, setNumber = 3), // dropped weight, easy — ignored
            ),
        )!!
        // est1RM derives from w0 = 100 and the RIR_0_1 offset, not the easy 80kg set.
        assertEquals(oneRm(100f, 5.5f), agg.est1RM, 1e-2f)
    }

    @Test
    fun only_hurt_sets_yield_null() {
        assertNull(SessionSignalExtractor.aggregateSession(listOf(set(100f, 5, SetFeedback.HURT))))
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SessionSignalExtractorTest"`
Expected: FAIL — `repDeviation`/`isFailure`/`softening` unresolved.

- [x] **Step 3: Rewrite the extractor**

Replace the entire contents of `SessionSignalExtractor.kt` with:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * Feedback -> (implied 1RM, confidence) extraction.
 *
 * Each full-weight set collapses to a signed rep-deviation from the target: reps of reserve
 * (positive) for a completed set, or shortfall (negative) for a failure. The planner prescribes the
 * weight doable for exactly `targetReps` reps, so RIR_0_1 — the intended target effort — is only a
 * small up-signal (Option 2: progressive overload, gently), not a large one.
 *
 * Within one exercise, sets aggregate asymmetrically over the full-weight sets, weighted by
 * `confidence x setNumber` so the last (most-fatigued) set dominates:
 *   - no failure: the weighted-mean reserve nudges the baseline up;
 *   - any failure: the worst shortfall dominates, only *softened* by the non-failing sets, and the
 *     result is capped at zero — a session containing a failure can never grow the weight. How much
 *     the good sets soften the down-pull scales with the rep target (low reps strict, high reps
 *     forgiving) via [softening].
 *
 * Dropped/reduced-weight sets carry no signal; the failure that triggered the drop is itself a
 * full-weight TOO_HARD set and is already captured. HURT carries no load signal.
 */
object SessionSignalExtractor {

    const val RESERVE_RIR_0_1 = 0.5f
    const val RESERVE_RIR_2_4 = 3f
    const val RESERVE_RIR_5_PLUS = 6f

    data class SetSignal(val repDeviation: Float, val confidence: Float, val isFailure: Boolean)

    data class SessionAggregate(val est1RM: Float, val sessionConfidence: Float)

    /** Rep-scaled softening of a failure's down-pull: strict at low reps, forgiving at high reps. */
    fun softening(reps: Int): Float = 0.10f + 0.70f * (reps.coerceIn(1, 20) - 1) / 19f

    fun setSignal(set: WorkoutSet): SetSignal? {
        val feedback = set.feedback ?: return null
        return when (feedback) {
            SetFeedback.HURT -> null
            SetFeedback.RIR_5_PLUS -> SetSignal(RESERVE_RIR_5_PLUS, 0.4f, isFailure = false)
            SetFeedback.RIR_2_4 -> SetSignal(RESERVE_RIR_2_4, 0.7f, isFailure = false)
            SetFeedback.RIR_0_1 -> SetSignal(RESERVE_RIR_0_1, 0.85f, isFailure = false)
            SetFeedback.TOO_HARD -> {
                val reps = set.actualReps
                val shortfall = if (reps != null) (reps - set.targetReps).toFloat() else -(set.targetReps / 2f)
                SetSignal(shortfall, 0.95f, isFailure = true)
            }
        }
    }

    fun aggregateSession(sets: List<WorkoutSet>): SessionAggregate? {
        if (sets.isEmpty()) return null
        val w0 = sets.maxOf { it.targetWeight }
        if (w0 <= 0f) return null

        // Only full-weight sets carry the capacity signal.
        val contributions = sets
            .filter { it.targetWeight >= w0 - 1e-3f }
            .mapNotNull { s -> setSignal(s)?.let { s to it } }
        if (contributions.isEmpty()) return null

        val targetReps = contributions.first().first.targetReps
        fun weightOf(s: WorkoutSet, sig: SetSignal) = sig.confidence * s.setNumber

        val reserves = contributions.filter { !it.second.isFailure }
        val fails = contributions.filter { it.second.isFailure }

        val upWsum = reserves.sumOf { weightOf(it.first, it.second).toDouble() }.toFloat()
        val upAgg = if (upWsum > 0f) {
            reserves.sumOf { (it.second.repDeviation * weightOf(it.first, it.second)).toDouble() }
                .toFloat() / upWsum
        } else {
            0f
        }

        val aggOffset = if (fails.isEmpty()) {
            upAgg
        } else {
            val worstFail = fails.minOf { it.second.repDeviation }
            minOf(0f, worstFail + softening(targetReps) * upAgg)
        }

        val est1RM = DefaultProgressionEngine.rawToOneRepMax(w0, targetReps + aggOffset)
        val sessionConfidence = contributions.maxOf { it.second.confidence }
        return SessionAggregate(est1RM = est1RM, sessionConfidence = sessionConfidence)
    }
}
```

- [x] **Step 4: Run the extractor tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SessionSignalExtractorTest"`
Expected: PASS (11 tests).

- [x] **Step 5: Quarantine the now-stale simulation asserts**

In `ProgressionControllerSimulationTest.kt`, add the import and `@Ignore` to both `@Test` methods (the fatigue-free harness no longer models the behavior these lock; Task 3 re-establishes them):

Add to imports:
```kotlin
import org.junit.Ignore
```

Above `fun production_gains_hold_convergence_and_gauge_ceilings()` (currently line 237-238):
```kotlin
    @Ignore("Re-locked in 2026-06-19-asymmetric-fatigue-aware-signal Task 3: harness needs a cross-set fatigue model")
    @Test
    fun production_gains_hold_convergence_and_gauge_ceilings() {
```

Above `fun production_gains_conserve_gauge_under_strengthening()` (currently line 249-250):
```kotlin
    @Ignore("Re-locked in 2026-06-19-asymmetric-fatigue-aware-signal Task 3: harness needs a cross-set fatigue model")
    @Test
    fun production_gains_conserve_gauge_under_strengthening() {
```

- [x] **Step 6: Verify the module compiles and the rest of the suite is green**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, with the two simulation tests reported as skipped/ignored.

- [x] **Step 7: Commit**

```bash
jj commit -m "feat: asymmetric, fatigue-aware session signal in SessionSignalExtractor

Failures dominate the down-pull, capped at no-growth; non-failing sets only
soften it (rep-scaled). RIR_0_1 is a gentle up-signal. Last set dominates via
confidence x position. Simulation asserts quarantined pending the fatigue harness.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Simulation harness — cross-set fatigue, full rep range, behavioral metric

Give the synthetic lifter cross-set fatigue, draw reps from `[1, 20]`, and replace the "recover true 1RM" metric with the spec's behavioral one (last set centered on RIR_0_1) plus a fatigue-adjusted convergence target and the unchanged gauge-conservation ceiling. Then tune the signal constants and lock.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerSimulationTest.kt`
- Possibly tune: `app/src/main/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractor.kt` (`σ`, reserve offsets, `confidence × position` curve) if the behavioral asserts cannot be met.

**Interfaces:**
- Consumes: `SessionSignalExtractor.aggregateSession`, `RepRangePicker.pick`.

- [x] **Step 1: Add the fatigue constant and full-range rep draw**

In `ProgressionControllerSimulationTest.kt`, add a companion-level constant near the top of the class body (after `private val unit = WeightUnit.KG`):

```kotlin
    /** Fraction of effective 1RM lost per additional set within an exercise (cross-set fatigue). */
    private val fatiguePerSet = 0.03f
```

Replace the session rep draw (currently `val reps = listOf(5, 8, 10).random(rng)`, ~line 146) with the full allowed range:

```kotlin
            val reps = RepRangePicker.pick(1, 20, rng)
```

- [x] **Step 2: Apply cross-set fatigue in the set loop**

In the inner `for (setNum in 1..PlannedExercise.DEFAULT_SETS)` loop, compute a fatigued per-set 1RM and feed it to `feedbackFor`. Replace:

```kotlin
                    val noise = gauss.nextGaussian() * repNoiseStd
                    val (fb, ar) = feedbackFor(w, reps, true1RM, noise)
```

with:

```kotlin
                    val noise = gauss.nextGaussian() * repNoiseStd
                    val setTrue1RM = true1RM * (1f - fatiguePerSet * (setNum - 1))
                    val (fb, ar) = feedbackFor(w, reps, setTrue1RM, noise)
```

- [x] **Step 3: Capture the last full-weight set's RIR during the tail**

Add tail accumulators next to `tailTrainedErr` (~line 132):

```kotlin
        val tailLastSetRir = mutableListOf<Float>() // (achievable reps - target) on the last full-weight set
        val tailLastSetFail = mutableListOf<Float>() // 1f if that set failed, else 0f
```

Inside the per-exercise block, the last set at `w == w0` is the governing fatigued set. After the `for (setNum ...)` loop for an exercise, when in the tail, record its RIR from the **last full-weight** set. Track it during the loop by capturing the reps on each full-weight set. Replace the inner loop body's set-construction region so it records the last full-weight reps; specifically, declare before the loop:

```kotlin
                var lastFullReps: Double? = null
```

and immediately after computing `(fb, ar)` inside the loop, add:

```kotlin
                    if (w >= w0 - 1e-3f) {
                        lastFullReps = achievableReps(w, setTrue1RM, noise)
                    }
```

Then, after the `for (setNum ...)` loop closes (right after the `if (w < w0) { reductions[...] }` block) and still inside `if (c > 0f)` for the exercise, add the tail capture:

```kotlin
                if (s >= sessions) {
                    lastFullReps?.let {
                        tailLastSetRir.add((it - reps).toFloat())
                        tailLastSetFail.add(if (it < reps) 1f else 0f)
                    }
                }
```

- [x] **Step 4: Redefine the convergence target to the fatigued steady state and extend metrics**

The baseline should settle at the **last set's** effective 1RM, not the fresh `true1RM`. Replace `errOf` (~line 135-139):

```kotlin
        // Steady-state target: the last (most fatigued) set's effective 1RM — where RIR_0_1 lands.
        val steadyFactor = 1f - fatiguePerSet * (PlannedExercise.DEFAULT_SETS - 1)
        fun errOf(id: Long, gMul: Float): Float {
            val m = exMuscle.getValue(id)
            val target1RM = trueBaseline.getValue(m) * gMul * trueCoef.getValue(id) * steadyFactor
            return abs(baselines.getValue(m) * coefs.getValue(id) - target1RM) / target1RM
        }
```

Extend `RMetrics` (lines 66-71) with the behavioral fields:

```kotlin
    data class RMetrics(
        val convSessions: Int,    // sessions until mean error over ever-trained exercises <= 10%
        val trainedEndErr: Float, // tail mean prescribed error over well-trained (>=3 sessions) exercises (%)
        val jitter: Float,        // tail std of prescribed/true over well-trained exercises (%)
        val coefInflation: Float, // geomean(coef/seedCoef) over loaded — 1.0 = no gauge creep
        val lastSetRir: Float,    // tail mean (achievable reps - target) on the last full-weight set
        val failRate: Float,      // tail fraction of last full-weight sets that failed
    )
```

Update `metricsFinite` (lines 73-75):

```kotlin
    private fun metricsFinite(m: RMetrics) = listOf(
        m.trainedEndErr, m.jitter, m.coefInflation, m.lastSetRir, m.failRate,
    ).none { it.isNaN() || it.isInfinite() }
```

In `simulateRealistic`, change the `jitter` ratio reference from `true1RM` to the fatigued target (so jitter measures stability about the real equilibrium). Replace, in the tail block (~line 211):

```kotlin
                    tailRatio.getValue(ex.id).add(baselines.getValue(m) * coefs.getValue(ex.id) / (trueBaseline.getValue(m) * gMul * trueCoef.getValue(ex.id) * steadyFactor))
```

And construct the returned metrics (lines 227-232):

```kotlin
        return RMetrics(
            convSessions = if (convAt < 0) total else convAt,
            trainedEndErr = tailTrainedErr.average().toFloat(),
            jitter = jitter,
            coefInflation = coefInflation,
            lastSetRir = if (tailLastSetRir.isEmpty()) Float.NaN else tailLastSetRir.average().toFloat(),
            failRate = if (tailLastSetFail.isEmpty()) Float.NaN else tailLastSetFail.average().toFloat(),
        )
```

- [x] **Step 5: Replace the locked asserts and remove the `@Ignore`s**

Replace the two test methods (remove the `@Ignore` added in Task 2). The primary assert is now behavioral; the gauge ceiling is unchanged:

```kotlin
    @Test
    fun production_gains_settle_last_set_near_rir01() {
        val rows = seeds.map { simulateRealistic(0.8f, it, sessions = 120, tail = 30) }
        fun avg(sel: (RMetrics) -> Float) = rows.map(sel).average().toFloat()
        rows.forEach { assertTrue("non-finite metric: $it", metricsFinite(it)) }

        // Behavioral spec: the prescribed weight settles where the last, fatigued set lands at
        // ~RIR_0_1 — a small positive reserve on average, and failures stay a minority.
        val rir = avg { it.lastSetRir }
        assertTrue("lastSetRir $rir outside RIR_0_1 band", rir in -0.5f..1.5f)
        assertTrue("failRate ${avg { it.failRate }} too high", avg { it.failRate } <= 0.30f)

        // Convergence + stability about the fatigued steady state.
        val convSess = rows.map { it.convSessions }.average()
        assertTrue("convergence $convSess > budget", convSess <= 12.0)
        assertTrue("trainedErr ${avg { it.trainedEndErr }} > ceiling", avg { it.trainedEndErr } <= 6.0f)
        assertTrue("jitter ${avg { it.jitter }} > ceiling", avg { it.jitter } <= 1.5f)
    }

    @Test
    fun production_gains_conserve_gauge_under_strengthening() {
        for (growth in listOf(0.0f, 0.002f, 0.004f)) {
            val rows = seeds.map { simulateRealistic(1.0f, it, sessions = 120, tail = 30, growthPerSession = growth) }
            val infl = rows.map { it.coefInflation }.average()
            assertTrue("coefInflation $infl drifted at growth=$growth", infl in 0.97..1.03)
        }
    }
```

Update the class KDoc (lines 19-29) to describe the cross-set fatigue model and the behavioral metric, replacing the "Two locked asserts gate convergence/accuracy/jitter" sentence with one noting the behavioral last-set-RIR lock plus the gauge ceiling.

- [x] **Step 6: Run the simulation test**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerSimulationTest"`
Expected: ideally PASS. If it FAILS, go to Step 7; otherwise skip to Step 8.

- [x] **Step 7: Tune to meet the behavioral + gauge asserts**

Adjust only these knobs, re-running Step 6 after each change, until both tests pass. Stay within the listed bounds; record the final values in a comment in `SessionSignalExtractor.kt`.

- If `lastSetRir` is **too high** (system over-shoots, last set too easy on average): lower `RESERVE_RIR_0_1` toward `0.25f`, and/or steepen the last-set dominance by replacing the position weight `sig.confidence * s.setNumber` with `sig.confidence * s.setNumber * s.setNumber` (quadratic) in `aggregateSession`.
- If `lastSetRir` is **too low / `failRate` too high** (system under-shoots, failing too often): raise `RESERVE_RIR_0_1` toward `0.75f`, and/or raise the high end of `softening` (the `0.70f` slope toward `0.85f`) so failures are absorbed more readily.
- If `coefInflation` drifts outside `[0.97, 1.03]`: do **not** touch the controller — this signals the signal is biased per-exercise; re-check that reduced-weight sets are excluded and that `upAgg` uses only non-failing sets.
- The synthetic-lifter `fatiguePerSet` (0.03f) is a property of the simulated world; only change it if the fatigue effect is unrealistically large/small (keep within `0.02f..0.04f`).

- [x] **Step 8: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (no ignored tests remain from this plan).

- [x] **Step 9: Commit**

```bash
jj commit -m "test: fatigue-aware simulation harness locks last-set-RIR behavior

Synthetic lifter now models cross-set fatigue; reps drawn from the full [1,20]
range; primary metric is last-set-centered-on-RIR_0_1 plus the unchanged gauge
ceiling. Re-locks the asymmetric signal.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Align the debug change-events formatter with the new reserve offsets

`formatBaselineSetLine` renders RIR feedbacks as estimated rep counts using the old `+1/+3/+7` offsets. Point it at the shared `RESERVE_*` constants so the debug feed matches the real model (only `RIR_5_PLUS` display changes, `+7 → +6`).

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt:46-66`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModelTest.kt:60-65`

**Interfaces:**
- Consumes: `SessionSignalExtractor.RESERVE_RIR_0_1 / RESERVE_RIR_2_4 / RESERVE_RIR_5_PLUS` (Task 2).

- [x] **Step 1: Update the failing test to the new RIR_5_PLUS display**

In `MuscleBaselineDetailViewModelTest.kt`, change the `RIR_5_PLUS` expectation (line ~65) from `"~12@20.0kg"` to the new `+6` offset:

```kotlin
        assertEquals("~11@20.0kg", out)
```

(Rename the test if desired: `renders RIR_5_PLUS as target plus six`. The `RIR_0_1` test stays `~11` — `10 + 0.5` rounds up to 11 — and `RIR_2_4` stays `~11` for target 8.)

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.MuscleBaselineDetailViewModelTest"`
Expected: FAIL on the RIR_5_PLUS case (still renders `~12`).

- [x] **Step 3: Point the formatter at the shared constants**

In `MuscleBaselineDetailViewModel.kt`, update the KDoc (lines 46-55) to reference the new offsets and replace the RIR branches of `formatBaselineSetLine` (lines 58-64). Add the import `import kotlin.math.roundToInt` and `import io.github.fowles.stochastic_strength.domain.SessionSignalExtractor` if not already present:

```kotlin
    val repsPart = when (feedback) {
        SetFeedback.RIR_0_1 -> "~${(set.targetReps + SessionSignalExtractor.RESERVE_RIR_0_1).roundToInt()}"
        SetFeedback.RIR_2_4 -> "~${(set.targetReps + SessionSignalExtractor.RESERVE_RIR_2_4).roundToInt()}"
        SetFeedback.RIR_5_PLUS -> "~${(set.targetReps + SessionSignalExtractor.RESERVE_RIR_5_PLUS).roundToInt()}"
        SetFeedback.TOO_HARD -> set.actualReps?.toString() ?: "?"
        SetFeedback.HURT -> "hurt"
    }
```

- [x] **Step 4: Run the debug test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.MuscleBaselineDetailViewModelTest"`
Expected: PASS.

- [x] **Step 5: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [x] **Step 6: Commit**

```bash
jj commit -m "refactor: debug set-line formatter uses shared reserve offsets

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Asymmetric aggregation (failures dominate, others soften) → Task 2 (`aggregateSession`), locked behaviorally in Task 3.
- RIR_0_1 small up-signal (Option 2) → Task 2 (`RESERVE_RIR_0_1 = 0.5f`) + fractional 1RM in Task 1 (so the half-rep nudge survives).
- Rep-scaled softening over `[1,20]` → Task 2 (`softening`), validated across the full range in Task 3.
- Last-set dominance → Task 2 (`confidence × setNumber` weighting).
- Cross-set fatigue + behavioral metric + full rep range in the harness → Task 3.
- Gauge conservation unchanged → Task 3 keeps the `coefInflation ∈ [0.97,1.03]` assert; controller untouched.
- Debug feed consistency → Task 4.

**Placeholder scan:** No TBD/TODO; all code blocks are complete. The only judgment-based step is Task 3 Step 7 (tuning), which is bounded with explicit knobs, directions, and ranges.

**Type consistency:** `rawToOneRepMax(Float, Float)` (Task 1) is consumed in Task 2. `SetSignal(repDeviation, confidence, isFailure)`, `softening(Int)`, and `RESERVE_*` (Task 2) are consumed by the Task 2 tests and Task 4. `SessionAggregate(est1RM, sessionConfidence)` fields are preserved, so the `WorkoutRepository` caller is unaffected. `RMetrics` gains `lastSetRir`/`failRate`, updated consistently in `metricsFinite`, the constructor, and the asserts within Task 3.
