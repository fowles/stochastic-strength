# Bracket Capacity Snap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a demonstrated mid-session weight drop (fail at top weight → complete lighter) pull that exercise's next prescription down to ~the demonstrated capacity within 1–2 sessions, gauge-preservingly.

**Architecture:** Two changes to the progression pipeline. (1) `SessionSignalExtractor.aggregateSession` gains a *bracket path*: when a full-weight failure forced a drop, it estimates capacity from the heaviest *completed* set (failures act only as a ceiling) and flags high `bracketConfidence`. (2) `RollingConservingProgressionController` scales the per-exercise differential step (`kC` and the log clamp) by that confidence, so a genuine bracket overcomes the ±10%/session rate limiter while leaving ordinary sessions untouched.

**Tech Stack:** Kotlin, JUnit4, Gradle (`./gradlew :app:testDebugUnitTest`), Android app module `app/`.

## Global Constraints

- The progression math files are param-locked by tests. Do NOT loosen any existing assertion in `ProgressionControllerSimulationTest` or `ProgressionControllerTest`; the geomean-conservation ceiling stays as-is.
- `bracketConfidence` defaults to `0f` everywhere so existing call sites and tests compile and behave identically (the new path is inert at confidence 0).
- Gauge conservation under the snap is *bounded-drift*, not exact: amplifying one exercise's differential breaks the natural sum-zero the same way the existing clamp does (see the controller's existing comment at the `minRelativeChange` skip). Do NOT add recentering to "fix" it — that breaks existing gauge tests. The drift is guarded by the simulation test's existing geomean ceiling.
- No new dependencies.

---

### Task 1: Bracket-aware capacity estimator

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractor.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractorTest.kt`

**Interfaces:**
- Produces: `SessionAggregate(est1RM: Float, sessionConfidence: Float, bracketConfidence: Float = 0f)`. `aggregateSession(sets: List<WorkoutSet>): SessionAggregate?` unchanged signature.

- [ ] **Step 1: Write the failing tests**

Add to `SessionSignalExtractorTest.kt` (inside the class):

```kotlin
// ---- bracket path ------------------------------------------------------------------------------

@Test
fun drop_cascade_anchors_on_heaviest_completed_set_not_top_weight() {
    // 55 fail(2) -> 35 fail(2) -> 20 completed RIR_0_1. Capacity ~ the 20 set, not 55.
    val agg = SessionSignalExtractor.aggregateSession(
        listOf(
            set(55f, 10, SetFeedback.TOO_HARD, actual = 2, setNumber = 1),
            set(35f, 10, SetFeedback.TOO_HARD, actual = 2, setNumber = 2),
            set(20f, 10, SetFeedback.RIR_0_1, setNumber = 3),
        ),
    )!!
    // est1RM = heaviest completed (20 @ 10 + 0.5 reserve), capped by the 35 fail ceiling (not binding here).
    assertEquals(oneRm(20f, 10.5f), agg.est1RM, 1e-2f)
    // Far below what the old top-weight path would have produced.
    assertTrue(agg.est1RM < oneRm(55f, 2f))
    assertEquals(0.95f, agg.bracketConfidence, 1e-6f)
    assertEquals(0.95f, agg.sessionConfidence, 1e-6f)
}

@Test
fun all_failed_cascade_estimates_from_lightest_failed_set() {
    // Even the lightest weight failed -> strong downward estimate from that set's achieved reps.
    val agg = SessionSignalExtractor.aggregateSession(
        listOf(
            set(55f, 10, SetFeedback.TOO_HARD, actual = 2, setNumber = 1),
            set(35f, 10, SetFeedback.TOO_HARD, actual = 3, setNumber = 2),
            set(20f, 10, SetFeedback.TOO_HARD, actual = 4, setNumber = 3),
        ),
    )!!
    assertEquals(oneRm(20f, 4f), agg.est1RM, 1e-2f)
    assertEquals(0.95f, agg.bracketConfidence, 1e-6f)
}

@Test
fun top_failure_without_a_drop_keeps_old_path_and_zero_bracket_confidence() {
    // All sets at the same weight, last fails: existing same-weight behavior, NOT a bracket.
    val agg = SessionSignalExtractor.aggregateSession(
        listOf(
            set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
            set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
            set(100f, 5, SetFeedback.TOO_HARD, actual = 2, setNumber = 3),
        ),
    )!!
    assertEquals(0f, agg.bracketConfidence, 1e-6f)
    assertTrue(agg.est1RM < oneRm(100f, 5f) * 0.99f) // unchanged downward behavior
}

@Test
fun voluntary_deload_without_failure_is_not_a_bracket() {
    // Existing reduced_weight_sets_are_ignored scenario must keep zero bracket confidence.
    val agg = SessionSignalExtractor.aggregateSession(
        listOf(
            set(100f, 5, SetFeedback.RIR_0_1, setNumber = 1),
            set(100f, 5, SetFeedback.RIR_0_1, setNumber = 2),
            set(80f, 5, SetFeedback.RIR_5_PLUS, setNumber = 3),
        ),
    )!!
    assertEquals(0f, agg.bracketConfidence, 1e-6f)
    assertEquals(oneRm(100f, 5.5f), agg.est1RM, 1e-2f)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SessionSignalExtractorTest"`
Expected: FAIL — `bracketConfidence` is not a member of `SessionAggregate` (compile error), and the new asserts don't pass.

- [ ] **Step 3: Add the `bracketConfidence` field and bracket path**

In `SessionSignalExtractor.kt`, change the `SessionAggregate` data class (line 33):

```kotlin
data class SessionAggregate(
    val est1RM: Float,
    val sessionConfidence: Float,
    val bracketConfidence: Float = 0f,
)
```

Add this constant near the other `RESERVE_*` constants (after line 29):

```kotlin
/** Confidence flag for a demonstrated drop-cascade (failure at top weight + a completed lighter set). */
const val BRACKET_CONFIDENCE = 0.95f
```

In `aggregateSession`, immediately after the `if (w0 <= 0f) return null` guard (current line 56), insert the bracket detection + dispatch:

```kotlin
        val topSets = sets.filter { it.targetWeight >= w0 - 1e-3f }
        val droppedSets = sets.filter { it.targetWeight < w0 - 1e-3f }
        val topFailed = topSets.any { it.feedback == SetFeedback.TOO_HARD }
        if (topFailed && droppedSets.isNotEmpty()) {
            return bracketAggregate(sets)
        }
```

Then add the helper functions at the bottom of the `object` (after `aggregateSession`):

```kotlin
    /** Reserve reps implied by a non-failure feedback bucket (reused for the completed-set anchor). */
    private fun reserveReps(feedback: SetFeedback): Float = when (feedback) {
        SetFeedback.RIR_0_1 -> RESERVE_RIR_0_1
        SetFeedback.RIR_2_4 -> RESERVE_RIR_2_4
        SetFeedback.RIR_5_PLUS -> RESERVE_RIR_5_PLUS
        else -> 0f
    }

    /**
     * Capacity estimate when a full-weight failure forced a mid-session drop. Anchor on the heaviest
     * COMPLETED set (capacity demonstrated at a sustainable rep count); failures only cap that anchor
     * from above. If every set failed, estimate from the lightest failed set's achieved reps.
     */
    private fun bracketAggregate(sets: List<WorkoutSet>): SessionAggregate {
        val completed = sets.filter { it.feedback?.isRepsInReserve == true }
        val failed = sets.filter { it.feedback == SetFeedback.TOO_HARD }

        val est1RM = if (completed.isNotEmpty()) {
            val anchor = completed.maxOf { s ->
                DefaultProgressionEngine.rawToOneRepMax(s.targetWeight, s.targetReps + reserveReps(s.feedback!!))
            }
            // A failed weight means target-rep capacity is below it: cap the anchor from above.
            val ceiling = failed.minOf { DefaultProgressionEngine.rawToOneRepMax(it.targetWeight, it.targetReps) }
            minOf(anchor, ceiling)
        } else {
            val lightest = failed.minByOrNull { it.targetWeight }!!
            val reps = lightest.actualReps ?: (lightest.targetReps / 2)
            DefaultProgressionEngine.rawToOneRepMax(lightest.targetWeight, reps.toFloat())
        }
        return SessionAggregate(
            est1RM = est1RM,
            sessionConfidence = BRACKET_CONFIDENCE,
            bracketConfidence = BRACKET_CONFIDENCE,
        )
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SessionSignalExtractorTest"`
Expected: PASS (all existing tests in the class still pass — the bracket path is only reached on a top-weight failure with a drop).

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat: bracket-aware capacity estimate for drop-cascade sessions" && jj new
```

---

### Task 2: Thread `bracketConfidence` through the observation

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt:11-16` (the `ProgressionObservation` data class)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt:101`

**Interfaces:**
- Produces: `ProgressionObservation(exerciseId, muscle, est1RM, confidence, bracketConfidence: Float = 0f)`.
- Consumes: `SessionAggregate.bracketConfidence` from Task 1.

- [ ] **Step 1: Add the field to `ProgressionObservation`**

In `ProgressionController.kt`, change the data class (lines 11-16):

```kotlin
data class ProgressionObservation(
    val exerciseId: Long,
    val muscle: MuscleGroup,
    val est1RM: Float,
    val confidence: Float,
    /** >0 only for a demonstrated drop-cascade; scales the differential step (snap). */
    val bracketConfidence: Float = 0f,
)
```

- [ ] **Step 2: Wire it from the repository**

In `WorkoutRepository.kt`, change the observation construction (line 101):

```kotlin
                ProgressionObservation(id, muscle, it.est1RM, it.sessionConfidence, it.bracketConfidence)
```

- [ ] **Step 3: Verify the module still compiles and all unit tests pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — the new field defaults to `0f`, so behavior is unchanged. This confirms the plumbing did not regress anything before the controller logic lands.

- [ ] **Step 4: Commit**

```bash
jj describe -m "feat: thread bracketConfidence into ProgressionObservation" && jj new
```

---

### Task 3: Confidence-scaled differential snap

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt` (`ProgressionControllerConfig` lines 50-59, and the differential loop lines 128-139)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt`

**Interfaces:**
- Consumes: `ProgressionObservation.bracketConfidence` from Task 2.
- Produces: snap behavior — at `bracketConfidence ≈ 0.95` the differential step uses `kC ≈ 1.0` and a wide clamp (`ln 2`); at `0` it is byte-identical to today.

- [ ] **Step 1: Write the failing test**

Add to `ProgressionControllerTest.kt`:

```kotlin
@Test
fun bracketSnap_movesCoefFartherThanClampedPath_inOneSession() {
    val baseline = 100f
    val coefs = mapOf(1L to 1.0f, 2L to 1.0f)
    val lowEst = baseline * 1.0f * 0.45f // id1 reads ~45% of prescription (a hard drop-cascade)

    fun run(bracket: Float): Float {
        val o = listOf(
            ProgressionObservation(1, m, lowEst, 0.95f, bracketConfidence = bracket),
            obs(2, baseline), // peer on-target
        )
        return controller().step(input(1000, o, baseline, coefs))
            .coefficientUpdates.single { it.exerciseId == 1L }.coefficient
    }

    val plainC1 = run(0f)
    val snapC1 = run(0.95f)

    assertTrue("no-bracket path is limited by the ~10% clamp", plainC1 > 0.88f)
    assertTrue("bracket snaps well past the 10% clamp", snapC1 < 0.80f)
    assertTrue("snap moves strictly further down than the clamped path", snapC1 < plainC1)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: FAIL — without the snap, `snapC1 == plainC1` (both clamped at ~0.909), so `snapC1 < 0.80f` and `snapC1 < plainC1` fail.

- [ ] **Step 3: Add the snap config knobs**

In `ProgressionControllerConfig` (lines 50-59), add two fields (keep all existing fields/defaults):

```kotlin
data class ProgressionControllerConfig(
    val kB: Float = 0.5f,
    val kC: Float = 0.5f,
    val emaBeta: Float = 0.5f,
    val halfLifeMs: Long = 21L * 24 * 60 * 60 * 1000,
    val maxLogStepB: Float = ln(1.15f),
    val maxLogStepC: Float = ln(1.10f),
    val hurtFactor: Float = 0.85f,
    val minRelativeChange: Float = 0.002f,
    /** Differential gain at full bracket confidence (snap). Interpolated from [kC]. */
    val kCSnap: Float = 1.0f,
    /** Differential log-step clamp at full bracket confidence (snap). Interpolated from [maxLogStepC]. */
    val maxLogStepCSnap: Float = ln(2f),
)
```

- [ ] **Step 4: Apply the per-exercise confidence scaling in the differential loop**

In `step`, build a bracket-confidence lookup just before the muscle loop (after line 95, alongside the other per-observation state writes):

```kotlin
        val bracketConfById = input.observations.associate { it.exerciseId to it.bracketConfidence }
```

Then replace the differential loop body (lines 128-139) with the confidence-scaled version:

```kotlin
            val maxW = pooled.maxOf { it.third }
            for ((id, e, w) in pooled) {
                val gain = w / maxW // freshest gets full K_c; staler proportionally less.
                val s = (bracketConfById[id] ?: 0f).coerceIn(0f, 1f) // snap scale; 0 for ordinary sessions
                val kCeff = config.kC + (config.kCSnap - config.kC) * s
                val maxStep = config.maxLogStepC + (config.maxLogStepCSnap - config.maxLogStepC) * s
                val dLogC = (kCeff * gain * (e - common)).coerceIn(-maxStep, maxStep)
                val cOld = input.coefficients.getValue(id)
                val cNew = cOld * exp(dLogC)
                // Suppressing near-zero moves relaxes the per-session sum-zero invariant slightly;
                // the residual gauge drift stays bounded (see ProgressionControllerSimulationTest's
                // coefInflation ceiling). The bracket snap amplifies one term the same way the clamp
                // does — bounded drift, not exact conservation. Do not "fix" this by recentering.
                if (abs(cNew - cOld) <= config.minRelativeChange * cOld) continue
                coefficientUpdates.add(CoefficientUpdate(id, cNew, "pi:d=${fmt(e - common)},w=${fmt(gain)},s=${fmt(s)}"))
            }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: PASS — including all pre-existing tests in the class (at `bracketConfidence = 0`, `kCeff = kC` and `maxStep = maxLogStepC`, so their math is unchanged).

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat: confidence-scaled differential snap for bracket sessions" && jj new
```

---

### Task 4: Full regression — simulation param-lock

**Files:**
- Run only (no edits expected): `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerSimulationTest.kt`

- [ ] **Step 1: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — every test including `ProgressionControllerSimulationTest`.

- [ ] **Step 2: If the simulation test fails, diagnose before touching it**

The simulation models mid-set weight drops, so the new bracket path WILL fire inside it. Two legitimate outcomes:
- **Geomean ceiling assert fails:** the bounded gauge drift exceeded its budget. This is the one invariant that must hold — investigate (do not loosen the ceiling). Most likely cause: `maxLogStepCSnap` too large. Reduce it (e.g. `ln(1.6f)`) and re-run; the Task 3 unit test must still pass (`snapC1 < 0.80f`).
- **Convergence/jitter asserts fail because the lifter now converges faster/tighter:** confirm the new steady state is *better* (lower prescribed error, fewer failures). The skill warning forbids *loosening* a bound to hide divergence; tightening or leaving bounds while behavior improves is fine. If a bound now reads backwards (e.g. an upper bound the run beats), document the change in this plan and the spec before adjusting.

- [ ] **Step 3: Confirm the headline behavior end-to-end (manual reasoning check, no code)**

Re-read `WorkoutPlanner.weightForExercise` (`WorkoutPlanner.kt:184-192`): prescription = `round(fromOneRepMax(baseline × coeff, reps))`. With the Bulgarian coefficient snapped down ~30–40% over two sessions and the baseline ~flat, confirm the prescribed weight drops by a full grid step or more (55 → ≤45 lb). Note the result in the commit message.

- [ ] **Step 4: Commit (if any tuning was needed) and finish**

```bash
jj describe -m "test: lock bracket-snap behavior in simulation param-lock" && jj new
```

---

## Self-Review Notes

- **Spec coverage:** bracket estimator (Task 1) ✓; failures-as-ceilings + all-failed fallback (Task 1) ✓; bracket-gated confidence so ordinary sessions don't snap (Task 1, `top_failure_without_a_drop...` + `voluntary_deload...` tests) ✓; confidence-scaled gauge-preserving differential (Task 3) ✓; simple weighted-mean common mode retained / baseline drift accepted (no change to common-mode code) ✓; tests in both `SessionSignalExtractorTest` and the controller/simulation tests (Tasks 1, 3, 4) ✓.
- **Bounded-drift honesty:** the spec's "exactly sum-zero" claim only holds if every pooled exercise shares the scale; in a real bracket only the failed exercise snaps, so conservation is bounded-drift like the existing clamp. Captured in Global Constraints and the Task 3 comment; guarded by the Task 4 geomean ceiling.
- **Type consistency:** `bracketConfidence: Float` used identically in `SessionAggregate`, `ProgressionObservation`, and `bracketConfById`; `kCSnap` / `maxLogStepCSnap` used exactly as named in the loop.
