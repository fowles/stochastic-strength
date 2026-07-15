# Phase 2: Belief Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the new estimator — `Belief(mu, sigma2)` Kalman core with boundary-pull Gaussian folds, fatigue shift φ, aging q, single-τ pooling, percentile-z prescription — fit its constants against real history via the Phase-0 harness, and beat main's held-out baseline (26.7593 ln-units total / 0.12563 per set). **No prod wiring** — the swap is Phase 3; everything ships dark (new `domain/belief/` package used only by the test-tree backtest) except two small policy-layer extensions that default off.

**Architecture:** Per spec `docs/superpowers/specs/2026-07-14-estimator-rebuild-design.md` (binding constitution). Prod package `domain/belief/` holds four pure components: `Belief`+`BeliefConfig`, `BeliefFold` (aging + fatigue shift + boundary-pull Gaussian fold), `BeliefPooling` (precision-weighted muscle level + leave-one-out effective belief), `BeliefPrescriber` (percentile-z target). The policy layer gains the overload nudge (behind a default-false flag) and the `allEasy` log-fact. Test tree gains `BeliefStackReplay` (forward-chained replay of the new stack), `BeliefHeldOutScorer` (per-set scoring against the Phase-0 intervals), and `BeliefFitHarness` (coordinate-descent fitting with sensitivity curves).

**Tech Stack:** Kotlin, JUnit4 JVM unit tests, existing Phase-0 backtest harness (`app/src/test/.../domain/backtest/`), jj for version control.

## Global Constraints

- **Constitution rule 1:** the ONLY tuning authority is the forward-chaining held-out score on `app/src/test/resources/backtest/history.json` (local, gitignored; tests `Assume`-skip without it).
- **Constitution rule 2:** every constant is labeled `fitted` (interior optimum + sensitivity curve recorded), `flat` (shown insensitive, frozen), or `semantic` (plain gym-language choice, never tuned). No unlabeled defaults.
- **Constitution rule 3/6:** safety stays in the policy layer (plain log-fact arithmetic); the estimator is scored raw (pre-clamp, pre-z). No new estimator mechanism to fix a safety-looking behavior.
- **Constitution rule 5:** no simulator. Code correctness = small deterministic unit tests with hand-computable numbers; behavioral quality = real-history harness only.
- **Prod behavior must not change in this phase.** The old estimator stays live and bit-identical; `ExerciseEstimatorSimulationTest` and all existing tests stay green and unmodified (except where a task explicitly extends a file). The overload nudge defaults OFF.
- **Unit tests always construct an explicit `BeliefConfig(...)`** — never rely on the defaults, so Task 10's adoption of fitted values cannot break them.
- **Version control is jj**, not git. Each task ends with exactly ONE `jj commit -m "..."`. If jj reports a divergent change, STOP and report — do not retry or abandon anything.
- **Number to beat (Phase-0 baseline, main's stack):** total 26.7593 ln-units / 0.12563 per set / 213 scored / 9 skipped / 49 cap violations. Phase-1 policy-over-main report: 1560 prescriptions, 49 cap binds (3.1%), 0 violations. Chronic cap-bind exercises 21, 77, 30 are the estimator bug this phase fixes.
- Run tests with `./gradlew :app:testDebugUnitTest --tests "<class>"`; full suite at the end.
- SDD note: prefix any report/brief files in `.superpowers/sdd/` with `phase2-` (files collide across runs otherwise).

---

### Task 1: Carry-forwards — raw rep-max on `ProgressionEngine`, shared grid epsilon

Phase-1 left two cleanups: `PrescriptionPolicy.prescribe` takes an `engine: ProgressionEngine` but hardcodes `DefaultProgressionEngine.rawFromOneRepMax` for the cap weight; and the `1e-4f` grid epsilon is duplicated in `WeightFormatter.roundDown` and `PrescriptionPolicy.prescribe`.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionEngine.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/DefaultProgressionEngine.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WeightFormatter.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicy.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/DefaultProgressionEngineTest.kt` (extend)

**Interfaces:**
- Produces: `ProgressionEngine.rawToOneRepMax(weight: Float, reps: Float): Float` and `ProgressionEngine.rawFromOneRepMax(oneRepMax: Float, reps: Int): Float` (un-rounded, fractional reps allowed on the forward direction); `WeightFormatter.GRID_EPSILON: Float = 1e-4f`. Later tasks call these through the interface.

- [ ] **Step 1: Write the failing test** — append to `DefaultProgressionEngineTest.kt`:

```kotlin
@Test
fun rawConversionsAreExposedOnTheInterface() {
    val engine: ProgressionEngine = DefaultProgressionEngine
    // Un-rounded round trip: raw inverse of raw forward recovers the weight (no 0.5 kg grid).
    val oneRm = engine.rawToOneRepMax(101.3f, 5f)
    assertEquals(101.3f, engine.rawFromOneRepMax(oneRm, 5), 0.05f)
    // Fractional reps are meaningful (used by the interval bounds table).
    assertTrue(engine.rawToOneRepMax(100f, 5.5f) > engine.rawToOneRepMax(100f, 5f))
}
```

(Add `import org.junit.Assert.assertTrue` if missing.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DefaultProgressionEngineTest"`
Expected: compile error — `rawToOneRepMax` is not a member of `ProgressionEngine`.

- [ ] **Step 3: Implement**

`ProgressionEngine.kt`:

```kotlin
interface ProgressionEngine {
    val repOptions: List<Int>
    fun toOneRepMax(weight: Float, reps: Int): Float
    fun fromOneRepMax(oneRepMax: Float, reps: Int): Float
    fun scaleReps(weight: Float, from: Int, to: Int): Float

    /** Un-rounded rep-max conversion; fractional reps allowed (log-space interval math). */
    fun rawToOneRepMax(weight: Float, reps: Float): Float

    /** Un-rounded inverse of [rawToOneRepMax] at integer reps. */
    fun rawFromOneRepMax(oneRepMax: Float, reps: Int): Float
}
```

`DefaultProgressionEngine.kt`: change the two `internal fun rawToOneRepMax(weight: Float, reps: Float)` / `internal fun rawFromOneRepMax(...)` declarations to `override fun` (keep the `internal fun rawToOneRepMax(weight: Float, reps: Int)` convenience overload as-is). `DefaultProgressionEngine` is the only implementation in the codebase (verified) — no other class needs updating.

`WeightFormatter.kt`: add to the object body and use it at both `roundDown` call sites:

```kotlin
/** Absorbs float noise at exact grid multiples (unit conversion, rep-max inverse). */
const val GRID_EPSILON = 1e-4f
```

(`floor(kg / 2.5f + GRID_EPSILON)`, `floor(lbs / 5f + GRID_EPSILON)`.)

`PrescriptionPolicy.kt` (prescribe): replace `DefaultProgressionEngine.rawFromOneRepMax(exp(capLn), sessionReps)` with `engine.rawFromOneRepMax(exp(capLn), sessionReps)` and `capWeight + 1e-4f` with `capWeight + WeightFormatter.GRID_EPSILON`. Remove the now-unused `DefaultProgressionEngine` import **only if** it is otherwise unused (`capLnFor` still uses it — it will remain).

- [ ] **Step 4: Run the affected suites**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DefaultProgressionEngineTest" --tests "io.github.fowles.stochastic_strength.domain.policy.*" --tests "io.github.fowles.stochastic_strength.domain.WeightFormatterTest" --tests "io.github.fowles.stochastic_strength.domain.WeightFormatterMinIncrementTest"`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "refactor: promote raw rep-max onto ProgressionEngine; shared grid epsilon (phase-1 carry-forwards)"
```

---

### Task 2: `Belief`, `BeliefConfig`, and aging

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/belief/Belief.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/belief/BeliefFold.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/belief/BeliefFoldTest.kt`

**Interfaces:**
- Produces: `data class Belief(mu: Float, sigma2: Float, updatedAt: Long)`; `data class BeliefConfig(...)` (fields below, exact names); `class BeliefFold(config: BeliefConfig)` with `fun aged(b: Belief, now: Long): Belief`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

import org.junit.Assert.assertEquals
import org.junit.Test

class BeliefFoldTest {
    private val DAY = 24L * 60 * 60 * 1000

    // Explicit config in every test — defaults are re-fit later and must not be load-bearing here.
    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        phi = 0.05f, qPerDay = 1e-3f,
        sigmaObsRir = 0.10f, sigmaObsFail = 0.10f,
        tau = 0.10f, sigma2Floor = 4e-4f, sigma2Cap = 0.25f,
    )
    private val fold = BeliefFold(config)

    @Test
    fun agingGrowsVarianceLinearlyPerIdleDay() {
        val b = Belief(mu = 4.6f, sigma2 = 0.01f, updatedAt = 0L)
        val aged = fold.aged(b, now = 10 * DAY)
        assertEquals(4.6f, aged.mu, 1e-6f)                     // mu never drifts
        assertEquals(0.01f + 10 * 1e-3f, aged.sigma2, 1e-6f)   // q per idle day
        assertEquals(10 * DAY, aged.updatedAt)
    }

    @Test
    fun agingIsClampedToTheVarianceCapAndNeverNegativeTime() {
        val b = Belief(mu = 4.6f, sigma2 = 0.24f, updatedAt = 10 * DAY)
        assertEquals(0.25f, fold.aged(b, now = 100 * DAY).sigma2, 1e-6f)  // cap (flat guard)
        assertEquals(0.24f, fold.aged(b, now = 0L).sigma2, 1e-6f)         // clock skew: age >= 0
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefFoldTest"`
Expected: FAIL to compile (`Belief` unresolved).

- [ ] **Step 3: Implement**

`Belief.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.exp

/**
 * One exercise's belief about ln(fresh 1RM, kg) — the whole estimator state (spec Phase 2).
 * [sigma2] is the variance in ln-units². Replaces ExerciseEstimate at the Phase-3 swap.
 */
data class Belief(val mu: Float, val sigma2: Float, val updatedAt: Long) {
    val e1rm: Float get() = exp(mu)
}

/**
 * Constant ledger for the belief stack (constitution rule 2 — every constant labeled).
 * The `fitted` values are adopted from BeliefFitTest's coordinate descent on real history;
 * sensitivity curves live in docs/superpowers/plans/2026-07-14-phase2-belief-core.md (appendix).
 */
data class BeliefConfig(
    /** `semantic`: a seed (initial override row) is trusted to roughly ±15%. */
    val sigmaSeed: Float = 0.15f,
    /** `semantic`: a deliberate user edit / detraining row is trusted a bit more, ±10%. */
    val sigmaOverride: Float = 0.10f,
    /** `fitted`: fractional fresh-capacity loss per prior set of the same exercise (Task 10). */
    val phi: Float = 0.03f,
    /** `fitted`: sigma2 growth per idle day (Task 10). */
    val qPerDay: Float = 3e-4f,
    /** `fitted`: observation sigma for RIR-bucket folds (Task 10). */
    val sigmaObsRir: Float = 0.10f,
    /** `fitted`: observation sigma for TOO_HARD folds (Task 10). */
    val sigmaObsFail: Float = 0.07f,
    /** `fitted`: transfer noise between same-muscle exercises in pooling (Task 10). */
    val tau: Float = 0.15f,
    /** `flat` guard: sigma never collapses below ±2%. */
    val sigma2Floor: Float = 4e-4f,
    /** `flat` guard: sigma never exceeds ±50% (aging saturates). */
    val sigma2Cap: Float = 0.25f,
)
```

`BeliefFold.kt` (aging only for now; Task 3 extends it):

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

/** Pure belief updates: aging (this task), fatigue shift + boundary-pull fold (Task 3). */
class BeliefFold(private val config: BeliefConfig) {
    private val dayMs = 24L * 60 * 60 * 1000

    /** sigma² grows by q per idle day (mu untouched); clamped by the flat guards. */
    fun aged(b: Belief, now: Long): Belief {
        val idleDays = (now - b.updatedAt).coerceAtLeast(0L).toFloat() / dayMs
        val s2 = (b.sigma2 + config.qPerDay * idleDays).coerceIn(config.sigma2Floor, config.sigma2Cap)
        return Belief(b.mu, s2, now)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefFoldTest"`
Expected: 2 PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(belief): Belief state + labeled BeliefConfig ledger + aging"
```

---

### Task 3: Fatigue shift + boundary-pull Gaussian fold

The fold consumes the Phase-0/1 interval table (`SetIntervals.impliedLn1RmInterval`) — the estimator and the metric read the same bounds, per spec.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/belief/BeliefFold.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/belief/BeliefFoldTest.kt` (extend)

**Interfaces:**
- Consumes: `LnInterval(lowerLn: Float?, upperLn: Float?)` and `SetIntervals.impliedLn1RmInterval(set: WorkoutSet): LnInterval?` from `domain.policy`; `WorkoutSet.feedback: SetFeedback?`.
- Produces (on `BeliefFold`): `fun fatigueShift(rank: Int): Float`; `fun obsSigma(feedback: SetFeedback): Float`; `fun fold(b: Belief, interval: LnInterval, shift: Float, obsSigma: Float, at: Long): Belief`; `fun foldSession(prior: Belief, exSets: List<WorkoutSet>, asOf: Long): Belief`.

- [ ] **Step 1: Write the failing tests** — append to `BeliefFoldTest.kt` (add imports `io.github.fowles.stochastic_strength.data.model.SetFeedback`, `io.github.fowles.stochastic_strength.data.model.WorkoutSet`, `io.github.fowles.stochastic_strength.domain.policy.LnInterval`, `kotlin.math.ln`):

```kotlin
@Test
fun fatigueShiftIsMinusLnOfRemainingCapacity() {
    assertEquals(0f, fold.fatigueShift(1), 1e-7f)                       // first set: fresh
    assertEquals(-ln(1f - 0.05f * 2), fold.fatigueShift(3), 1e-6f)      // phi·(k−1), k=3
}

@Test
fun foldBelowTheIntervalPullsMuUpByKalmanGain() {
    // Prior sigma2=0.04, obs s2=0.01 → gain = 0.04/0.05 = 0.8; posterior sigma2 = 0.04·0.01/0.05 = 0.008.
    val b = Belief(mu = ln(100f), sigma2 = 0.04f, updatedAt = 0L)
    val lower = ln(110f)
    val out = fold.fold(b, LnInterval(lower, null), shift = 0f, obsSigma = 0.1f, at = 5L)
    assertEquals(b.mu + 0.8f * (lower - b.mu), out.mu, 1e-6f)
    assertEquals(0.008f, out.sigma2, 1e-6f)
    assertEquals(5L, out.updatedAt)
}

@Test
fun foldAboveTheIntervalPullsMuDownSymmetrically() {
    // Symmetric up/down (spec): a failure moves the belief down exactly as hard.
    val b = Belief(mu = ln(120f), sigma2 = 0.04f, updatedAt = 0L)
    val upper = ln(110f)
    val out = fold.fold(b, LnInterval(null, upper), shift = 0f, obsSigma = 0.1f, at = 5L)
    assertEquals(b.mu + 0.8f * (upper - b.mu), out.mu, 1e-6f)
    assertEquals(0.008f, out.sigma2, 1e-6f)
}

@Test
fun foldInsideTheIntervalConfirmsMuAndOnlyShrinksSigma() {
    val b = Belief(mu = ln(105f), sigma2 = 0.04f, updatedAt = 0L)
    val out = fold.fold(b, LnInterval(ln(100f), ln(110f)), shift = 0f, obsSigma = 0.1f, at = 5L)
    assertEquals(b.mu, out.mu, 1e-7f)
    assertEquals(0.008f, out.sigma2, 1e-6f)   // same shrink a boundary fold would give
}

@Test
fun shiftMovesTheIntervalUpBeforeComparing() {
    // mu inside the raw interval but below it once shifted: a fatigued success implies MORE fresh capacity.
    val b = Belief(mu = ln(100f), sigma2 = 0.04f, updatedAt = 0L)
    val raw = LnInterval(ln(99f), ln(101f))
    val shift = 0.10f
    val out = fold.fold(b, raw, shift = shift, obsSigma = 0.1f, at = 5L)
    assertEquals(b.mu + 0.8f * (ln(99f) + shift - b.mu), out.mu, 1e-6f)
}

@Test
fun foldSessionRanksAllRowsButFoldsOnlyScoreableOnes() {
    // Three rows: RIR success, feedback-less (rank counts, no fold), TOO_HARD at rank 3.
    val sets = listOf(
        WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
        WorkoutSet(id = 2, sessionId = 1, exerciseId = 1, setNumber = 2, targetWeight = 100f, targetReps = 5),
        WorkoutSet(id = 3, sessionId = 1, exerciseId = 1, setNumber = 3, targetWeight = 100f, targetReps = 5, actualReps = 3, feedback = SetFeedback.TOO_HARD),
    )
    val prior = Belief(mu = ln(100f), sigma2 = 0.01f, updatedAt = 0L)
    val asOf = 24L * 60 * 60 * 1000
    // Hand-fold with the same components: aging first, then set 1 (rank 1) and set 3 (rank 3).
    var expected = fold.aged(prior, asOf)
    expected = fold.fold(expected, io.github.fowles.stochastic_strength.domain.policy.SetIntervals.impliedLn1RmInterval(sets[0])!!, fold.fatigueShift(1), fold.obsSigma(SetFeedback.RIR_0_1), asOf)
    expected = fold.fold(expected, io.github.fowles.stochastic_strength.domain.policy.SetIntervals.impliedLn1RmInterval(sets[2])!!, fold.fatigueShift(3), fold.obsSigma(SetFeedback.TOO_HARD), asOf)
    assertEquals(expected, fold.foldSession(prior, sets, asOf))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefFoldTest"`
Expected: FAIL to compile (`fatigueShift` unresolved).

- [ ] **Step 3: Implement** — extend `BeliefFold`:

```kotlin
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.LnInterval
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals
import kotlin.math.ln

    /**
     * Set rank k (1-based, ALL of the exercise's rows in the session, including feedback-less and
     * HURT) observes fresh capacity reduced by phi·(k−1); the implied interval shifts UP by
     * −ln(1 − phi·(k−1)) before folding. Clamped so the shift stays finite.
     */
    fun fatigueShift(rank: Int): Float =
        -ln(1f - (config.phi * (rank - 1)).coerceAtMost(0.9f))

    /** Observation sigma per bucket type: failures carry their own noise constant. */
    fun obsSigma(feedback: SetFeedback): Float =
        if (feedback == SetFeedback.TOO_HARD) config.sigmaObsFail else config.sigmaObsRir

    /**
     * Boundary-pull Gaussian fold (spec Phase 2). If mu lies inside the shifted interval the set
     * confirms: mu unchanged, sigma shrinks exactly as a Gaussian fold at the nearer boundary
     * would (the Kalman variance update is innovation-independent). If mu lies outside, one
     * Kalman line at the violated boundary. Symmetric up/down — no off-day damping, no down-snap.
     */
    fun fold(b: Belief, interval: LnInterval, shift: Float, obsSigma: Float, at: Long): Belief {
        val lower = interval.lowerLn?.plus(shift)
        val upper = interval.upperLn?.plus(shift)
        val s2 = obsSigma * obsSigma
        val gain = b.sigma2 / (b.sigma2 + s2)
        val mu = when {
            lower != null && b.mu < lower -> b.mu + gain * (lower - b.mu)
            upper != null && b.mu > upper -> b.mu + gain * (upper - b.mu)
            else -> b.mu
        }
        val sigma2 = (b.sigma2 * s2 / (b.sigma2 + s2)).coerceIn(config.sigma2Floor, config.sigma2Cap)
        return Belief(mu, sigma2, at)
    }

    /**
     * One exercise's session: age to [asOf], then fold each row in set-id order. Rank counts every
     * row; only rows with an implied interval fold (HURT and feedback-less rows carry no load
     * observation — policy handles HURT).
     */
    fun foldSession(prior: Belief, exSets: List<WorkoutSet>, asOf: Long): Belief {
        var b = aged(prior, asOf)
        exSets.sortedBy { it.id }.forEachIndexed { idx, set ->
            val interval = SetIntervals.impliedLn1RmInterval(set) ?: return@forEachIndexed
            b = fold(b, interval, fatigueShift(idx + 1), obsSigma(set.feedback!!), asOf)
        }
        return b
    }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefFoldTest"`
Expected: 8 PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(belief): fatigue shift + boundary-pull Gaussian fold + per-session fold"
```

---

### Task 4: Pooling — precision-weighted level + leave-one-out effective belief

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/belief/BeliefPooling.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/belief/BeliefPoolingTest.kt`

**Interfaces:**
- Consumes: `Belief`, `BeliefConfig`, `BeliefFold.aged`.
- Produces: `data class EffectiveBelief(mu: Float, sigma2: Float)`; `data class MusclePoolResult(levelLn: Float?, effective: Map<Long, EffectiveBelief>)`; `class BeliefPooling(config: BeliefConfig)` with `fun effective(beliefs: Map<Long, Belief>, seedCoef: Map<Long, Float>, muscleExerciseIds: List<Long>, now: Long): MusclePoolResult`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class BeliefPoolingTest {
    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        phi = 0.05f, qPerDay = 0f,           // qPerDay=0: aging is a no-op so numbers are exact
        sigmaObsRir = 0.10f, sigmaObsFail = 0.10f,
        tau = 0.10f, sigma2Floor = 4e-4f, sigma2Cap = 0.25f,
    )
    private val pooling = BeliefPooling(config)

    // Muscle: A (coef 1.0, tight belief), B (coef 0.8, looser), C (coef 0.5, no belief).
    private val beliefs = mapOf(
        1L to Belief(mu = ln(100f), sigma2 = 0.01f, updatedAt = 0L),
        2L to Belief(mu = ln(72f), sigma2 = 0.04f, updatedAt = 0L),
    )
    private val coef = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.5f)
    private val ids = listOf(1L, 2L, 3L)

    // Transparent restatement of the spec math (weights, not the SUT's code).
    private fun w(sigma2: Float) = 1f / (sigma2 + config.tau * config.tau)

    @Test
    fun ownBeliefBlendsWithLeaveOneOutSiblingPrediction() {
        val result = pooling.effective(beliefs, coef, ids, now = 0L)
        // LOO level for A = B's vote alone; prediction variance = level var + tau².
        val vB = ln(72f) - ln(0.8f)
        val predVar = 1f / w(0.04f) + config.tau * config.tau
        val pOwn = 1f / 0.01f
        val pSib = 1f / predVar
        val expectedMu = (pOwn * ln(100f) + pSib * (ln(1.0f) + vB)) / (pOwn + pSib)
        val a = result.effective[1L]!!
        assertEquals(expectedMu, a.mu, 1e-5f)
        assertEquals(1f / (pOwn + pSib), a.sigma2, 1e-6f)
    }

    @Test
    fun beliefLessExerciseIsPredictedFromTheFullPool() {
        val result = pooling.effective(beliefs, coef, ids, now = 0L)
        val wA = w(0.01f); val wB = w(0.04f)
        val level = (wA * (ln(100f) - ln(1.0f)) + wB * (ln(72f) - ln(0.8f))) / (wA + wB)
        val c = result.effective[3L]!!
        assertEquals(ln(0.5f) + level, c.mu, 1e-5f)
        assertEquals(1f / (wA + wB) + config.tau * config.tau, c.sigma2, 1e-6f)
        assertEquals(level, result.levelLn!!, 1e-5f)
    }

    @Test
    fun lonelyVoterFallsBackToItsOwnBelief() {
        // Only A has a belief: its LOO pool is empty → effective = own belief, unshrunk.
        val result = pooling.effective(beliefs.filterKeys { it == 1L }, coef, ids, now = 0L)
        assertEquals(ln(100f), result.effective[1L]!!.mu, 1e-6f)
        assertEquals(0.01f, result.effective[1L]!!.sigma2, 1e-6f)
        // Belief-less siblings still get the full-pool (= A-only) prediction.
        assertEquals(ln(0.8f) + ln(100f), result.effective[2L]!!.mu, 1e-5f)
    }

    @Test
    fun coldMuscleHasNoLevelAndNoPredictions() {
        val result = pooling.effective(emptyMap(), coef, ids, now = 0L)
        assertNull(result.levelLn)
        assertFalse(result.effective.containsKey(3L))
    }

    @Test
    fun zeroCoefficientExercisesNeitherVoteNorReceive() {
        val result = pooling.effective(beliefs, coef + (2L to 0f), ids, now = 0L)
        assertFalse(result.effective.containsKey(2L))
        assertEquals(ln(100f) - ln(1.0f), result.levelLn!!, 1e-5f)  // level from A alone
    }

    @Test
    fun beliefsAreAgedBeforeVoting() {
        val aging = BeliefPooling(config.copy(qPerDay = 1e-3f))
        val tenDays = 10 * 24L * 60 * 60 * 1000
        val result = aging.effective(beliefs, coef, ids, now = tenDays)
        // A's own variance is aged from 0.01 → 0.02 before blending.
        val vB = ln(72f) - ln(0.8f)
        val predVar = 1f / (1f / (0.05f + 0.01f)) + 0.01f   // B aged to 0.05, tau²=0.01
        val pOwn = 1f / 0.02f
        val pSib = 1f / predVar
        assertEquals((pOwn * ln(100f) + pSib * vB) / (pOwn + pSib), result.effective[1L]!!.mu, 1e-5f)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefPoolingTest"`
Expected: FAIL to compile (`BeliefPooling` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.ln

/** The blended read-time view of one exercise: what prescription and scoring consume. */
data class EffectiveBelief(val mu: Float, val sigma2: Float)

data class MusclePoolResult(
    /** Precision-weighted muscle level (ln, seed-relative); null when no exercise has a belief. */
    val levelLn: Float?,
    /** Effective belief per loaded exercise; absent when there is nothing to say (cold + no pool). */
    val effective: Map<Long, EffectiveBelief>,
)

/**
 * Read-time pooling (spec Phase 2; never mutates beliefs). Each exercise with a belief votes
 * mu_j − ln(coef_j) with precision 1/(sigma_j² + tau²); the effective belief is the precision
 * blend of the own aged belief with the leave-one-out sibling prediction
 * (ln coef_i + L₋ᵢ, var(L₋ᵢ) + tau²). Fresh tight evidence mathematically outvotes siblings;
 * stale (aged) exercises lean on them. Exercises without a belief take the full-pool prediction —
 * no separate seed-anchor constant: seeded-cold exercises sit at seed with sigmaSeed and anchor
 * the level automatically.
 */
class BeliefPooling(private val config: BeliefConfig) {
    private val fold = BeliefFold(config)

    fun effective(
        beliefs: Map<Long, Belief>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
    ): MusclePoolResult {
        val tau2 = config.tau * config.tau
        // Loaded voters, aged to now.
        data class Voter(val id: Long, val vote: Float, val weight: Float)
        val voters = muscleExerciseIds.mapNotNull { id ->
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) return@mapNotNull null
            val b = beliefs[id]?.let { fold.aged(it, now) } ?: return@mapNotNull null
            Voter(id, b.mu - ln(coef), 1f / (b.sigma2 + tau2))
        }
        val sumW = voters.sumOf { it.weight.toDouble() }.toFloat()
        val sumWV = voters.sumOf { (it.weight * it.vote).toDouble() }.toFloat()
        val levelLn = if (sumW > 0f) sumWV / sumW else null

        val effective = mutableMapOf<Long, EffectiveBelief>()
        for (id in muscleExerciseIds) {
            val coef = seedCoef[id] ?: continue
            if (coef <= 0f) continue
            val own = beliefs[id]?.let { fold.aged(it, now) }
            // Leave-one-out sums so an exercise never borrows its own evidence back.
            val voter = voters.firstOrNull { it.id == id }
            val looW = sumW - (voter?.weight ?: 0f)
            val looWV = sumWV - ((voter?.weight ?: 0f) * (voter?.vote ?: 0f))
            val sibling: EffectiveBelief? = if (looW > 0f) {
                EffectiveBelief(mu = ln(coef) + looWV / looW, sigma2 = 1f / looW + tau2)
            } else null
            effective[id] = when {
                own != null && sibling != null -> {
                    val pOwn = 1f / own.sigma2
                    val pSib = 1f / sibling.sigma2
                    EffectiveBelief(
                        mu = (pOwn * own.mu + pSib * sibling.mu) / (pOwn + pSib),
                        sigma2 = 1f / (pOwn + pSib),
                    )
                }
                own != null -> EffectiveBelief(own.mu, own.sigma2)
                sibling != null -> sibling
                else -> continue
            }
        }
        return MusclePoolResult(levelLn, effective)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefPoolingTest"`
Expected: 6 PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(belief): precision-weighted pooling with leave-one-out effective beliefs"
```

---

### Task 5: `BeliefPrescriber` — percentile-z raw target

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/belief/BeliefPrescriber.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/belief/BeliefPrescriberTest.kt`

**Interfaces:**
- Consumes: `EffectiveBelief`.
- Produces: `object BeliefPrescriber { const val Z: Float; fun targetE1rm(eff: EffectiveBelief): Float }`. Phase 3 wires this in front of `PrescriptionPolicy.prescribe`; the belief backtest (Task 11) uses it the same way.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class BeliefPrescriberTest {
    @Test
    fun targetIsThe30thPercentileOfBelievedCapacity() {
        val eff = EffectiveBelief(mu = ln(100f), sigma2 = 0.04f)
        assertEquals(exp(ln(100f) - BeliefPrescriber.Z * sqrt(0.04f)), BeliefPrescriber.targetE1rm(eff), 1e-4f)
    }

    @Test
    fun coldStartsAreAutomaticallyHumbleAndCertaintyRaisesTheTarget() {
        val cold = BeliefPrescriber.targetE1rm(EffectiveBelief(ln(100f), 0.25f))
        val warm = BeliefPrescriber.targetE1rm(EffectiveBelief(ln(100f), 0.0025f))
        assertTrue(cold < warm)
        assertTrue(warm < 100f)   // never above mu
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefPrescriberTest"`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Raw prescription target from an effective belief (spec Phase 2). Policy caps and grid rounding
 * apply downstream (PrescriptionPolicy.prescribe); the estimator itself is scored raw, pre-z.
 */
object BeliefPrescriber {
    /** `semantic`: prescribe at roughly the 30th percentile of believed capacity (Φ(z) = 0.70). */
    const val Z = 0.5244f

    fun targetE1rm(eff: EffectiveBelief): Float = exp(eff.mu - Z * sqrt(eff.sigma2))
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefPrescriberTest"`
Expected: 2 PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(belief): percentile-z prescriber (semantic 30th percentile)"
```

---

### Task 6: Overload nudge — `allEasy` log-fact + default-off policy hook

The nudge is plain log-fact arithmetic ("last session was all RIR ≥ 2 → add the smallest plate"), so it lives in the policy layer per the boundary criterion. It is gated by a new `overloadNudge` parameter **defaulting to false** because main's live estimator has its own up-drift (`wUp`); only the belief stack needs it (in-band feedback legitimately leaves mu unmoved). Phase 3 flips the flag at the swap. **Prod behavior is unchanged by this task.**

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/PolicyFacts.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicy.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/PolicyFactsTest.kt` (extend)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicyTest.kt` (extend)

**Interfaces:**
- Produces: `ExerciseCapFact` gains `val allEasy: Boolean = false` (true iff every feedback-bearing set of the most recent feedback session — HURT included in the check — is RIR_2_4 or RIR_5_PLUS). `PrescriptionPolicy.prescribe(...)` gains a trailing parameter `overloadNudge: Boolean = false`; when true and the exercise's fact has `allEasy` within `CAP_EXPIRY_MS`, the rounded uncapped weight is bumped by `WeightFormatter.minIncrement(weightUnit)` **before** the cap comparison (the demonstrated-capacity cap applies on top, per spec).

- [ ] **Step 1: Write the failing tests.** Read both existing test files first and follow their local fixture helpers/style. Add tests asserting:

To `PolicyFactsTest.kt` (adapt set-building to the file's existing helpers):

```kotlin
@Test
fun allEasyIsTrueOnlyWhenEveryFeedbackSetOfTheLatestSessionIsRir2Plus() {
    // Session 1 (older): all easy. Session 2 (newer): contains an RIR_0_1 → allEasy = false.
    val sets = listOf(
        set(sessionId = 1, exerciseId = 7, feedback = SetFeedback.RIR_2_4, completedAt = 1_000L),
        set(sessionId = 1, exerciseId = 7, feedback = SetFeedback.RIR_5_PLUS, completedAt = 2_000L),
        set(sessionId = 2, exerciseId = 7, feedback = SetFeedback.RIR_0_1, completedAt = 9_000L),
        set(sessionId = 2, exerciseId = 7, feedback = SetFeedback.RIR_2_4, completedAt = 9_500L),
    )
    val facts = PolicyFacts.build(sets, muscleMap)
    assertFalse(facts.capByExercise[7L]!!.allEasy)

    // Only the older session → allEasy = true.
    val factsEasy = PolicyFacts.build(sets.filter { it.sessionId == 1L }, muscleMap)
    assertTrue(factsEasy.capByExercise[7L]!!.allEasy)
}

@Test
fun aHurtSetVetoesAllEasy() {
    val sets = listOf(
        set(sessionId = 1, exerciseId = 7, feedback = SetFeedback.RIR_2_4, completedAt = 1_000L),
        set(sessionId = 1, exerciseId = 7, feedback = SetFeedback.HURT, completedAt = 2_000L),
    )
    assertFalse(PolicyFacts.build(sets, muscleMap).capByExercise[7L]!!.allEasy)
}
```

To `PrescriptionPolicyTest.kt`:

```kotlin
@Test
fun overloadNudgeAddsOneIncrementOnlyWhenEnabledAndLastSessionWasAllEasy() {
    val facts = PolicyFacts(capByExercise = mapOf(
        7L to ExerciseCapFact(capLn = null, demonstratedAt = 0L, allEasy = true),
    ))
    val base = PrescriptionPolicy.prescribe(100f, 5, 7L, MuscleGroup.QUADS, facts, now = 1_000L,
        weightUnit = WeightUnit.KG, engine = DefaultProgressionEngine)
    val nudged = PrescriptionPolicy.prescribe(100f, 5, 7L, MuscleGroup.QUADS, facts, now = 1_000L,
        weightUnit = WeightUnit.KG, engine = DefaultProgressionEngine, overloadNudge = true)
    assertEquals(base.weightKg + WeightFormatter.minIncrement(WeightUnit.KG), nudged.weightKg, 1e-4f)
}

@Test
fun overloadNudgeExpiresWithTheCapWindowAndNeverPiercesACap() {
    val old = PolicyFacts(capByExercise = mapOf(
        7L to ExerciseCapFact(capLn = null, demonstratedAt = 0L, allEasy = true),
    ))
    val expired = PrescriptionPolicy.prescribe(100f, 5, 7L, MuscleGroup.QUADS, old,
        now = PrescriptionPolicy.CAP_EXPIRY_MS + 1, weightUnit = WeightUnit.KG,
        engine = DefaultProgressionEngine, overloadNudge = true)
    val base = PrescriptionPolicy.prescribe(100f, 5, 7L, MuscleGroup.QUADS, old,
        now = PrescriptionPolicy.CAP_EXPIRY_MS + 1, weightUnit = WeightUnit.KG,
        engine = DefaultProgressionEngine)
    assertEquals(base.weightKg, expired.weightKg, 1e-4f)

    // A capped exercise: nudge cannot climb past the cap (cap applies on top, spec Phase 2).
    val capLn = ln(DefaultProgressionEngine.rawToOneRepMax(80f, 5.5f))
    val capped = PolicyFacts(capByExercise = mapOf(
        7L to ExerciseCapFact(capLn = capLn, demonstratedAt = 0L, allEasy = true),
    ))
    val withNudge = PrescriptionPolicy.prescribe(100f, 5, 7L, MuscleGroup.QUADS, capped, now = 1_000L,
        weightUnit = WeightUnit.KG, engine = DefaultProgressionEngine, overloadNudge = true)
    val withoutNudge = PrescriptionPolicy.prescribe(100f, 5, 7L, MuscleGroup.QUADS, capped, now = 1_000L,
        weightUnit = WeightUnit.KG, engine = DefaultProgressionEngine)
    assertEquals(withoutNudge.weightKg, withNudge.weightKg, 1e-4f)
    assertTrue(withNudge.capBound)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PolicyFactsTest" --tests "io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicyTest"`
Expected: FAIL to compile (`allEasy` unresolved).

- [ ] **Step 3: Implement**

`PolicyFacts.kt` — extend the fact and its construction:

```kotlin
data class ExerciseCapFact(
    val capLn: Float?,
    val demonstratedAt: Long,
    /** True iff every feedback-bearing set of that session (HURT included) was RIR ≥ 2. */
    val allEasy: Boolean = false,
)
```

In `build`, where the `latest` session's fact is constructed:

```kotlin
val feedbacks = latest.mapNotNull { it.feedback }
exerciseId to ExerciseCapFact(
    capLn = PrescriptionPolicy.capLnFor(latest),
    demonstratedAt = latest.maxOf { it.completedAt!! },
    allEasy = feedbacks.isNotEmpty() && feedbacks.all {
        it == SetFeedback.RIR_2_4 || it == SetFeedback.RIR_5_PLUS
    },
)
```

`PrescriptionPolicy.kt` — add the parameter and the bump (kdoc: nudge is `semantic` = one grid increment; note it defaults off until the Phase-3 swap because main's estimator has its own up-drift):

```kotlin
fun prescribe(
    rawE1rm: Float,
    sessionReps: Int,
    exerciseId: Long,
    muscle: MuscleGroup,
    facts: PolicyFacts,
    now: Long,
    weightUnit: WeightUnit,
    engine: ProgressionEngine,
    overloadNudge: Boolean = false,
): Prescription {
    val mult = hurtMultiplier(facts.hurtEventsByMuscle[muscle].orEmpty(), now)
    val backed = rawE1rm * mult
    val fact = facts.capByExercise[exerciseId]
    val withinWindow = fact != null && now - fact.demonstratedAt <= CAP_EXPIRY_MS
    val capLn = fact?.capLn?.takeIf { withinWindow }
    // `fact?.allEasy == true` (not `fact.allEasy`): no smart cast through the withinWindow Boolean.
    val nudge = if (overloadNudge && withinWindow && fact?.allEasy == true)
        WeightFormatter.minIncrement(weightUnit) else 0f
    val uncapped = WeightFormatter.round(engine.fromOneRepMax(backed, sessionReps), weightUnit) + nudge
    if (capLn == null) return Prescription(uncapped, capBound = false, hurtMultiplier = mult)
    // (existing post-rounding cap comparison, unchanged — the nudged weight is what the cap sees)
    val capWeight = engine.rawFromOneRepMax(exp(capLn), sessionReps)
    if (uncapped <= capWeight + WeightFormatter.GRID_EPSILON) return Prescription(uncapped, capBound = false, hurtMultiplier = mult)
    return Prescription(WeightFormatter.roundDown(capWeight, weightUnit), capBound = true, hurtMultiplier = mult)
}
```

- [ ] **Step 4: Run the whole policy + backtest suites** (proves default-off keeps Phase-1 behavior bit-identical, including `PolicyBacktestTest` if history is present):

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.*" --tests "io.github.fowles.stochastic_strength.domain.backtest.*" --tests "io.github.fowles.stochastic_strength.domain.ProdBssPrescriptionTest"`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(policy): allEasy log-fact + default-off overload nudge (one grid increment, cap applies on top)"
```

---

### Task 7: `BeliefStackReplay` — forward-chained replay of the new stack

Test-tree only, mirroring `MainStackReplay` line-for-line where the semantics are shared (override rows, session ordering, empty-session skip).

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefStackReplay.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefStackReplayTest.kt`

**Interfaces:**
- Consumes: `BacktestData` (`.sessions`, `.setsBySession`, `.initialOverrides`, `.sessionOverrides`, `.newSnapshot()`), `BeliefConfig`, `BeliefFold`, `BeliefPooling`, `EffectiveBelief`.
- Produces:

```kotlin
object BeliefStackReplay {
    data class SetPrediction(val set: WorkoutSet, val rank: Int, val predictedLn: Float?)
    fun interface SessionObserver {
        fun onSession(
            sessionId: Long, asOf: Long,
            predictions: List<SetPrediction>,               // pre-fold, per set, fatigue-adjusted
            effective: Map<Long, EffectiveBelief>,          // pre-fold effective beliefs at asOf
            beliefs: Map<Long, Belief>,                     // post-fold state (read, never retain)
        )
    }
    fun run(data: BacktestData, config: BeliefConfig, observer: SessionObserver)
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefFold
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class BeliefStackReplayTest {

    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        phi = 0.05f, qPerDay = 1e-3f,
        sigmaObsRir = 0.10f, sigmaObsFail = 0.07f,
        tau = 0.10f, sigma2Floor = 4e-4f, sigma2Cap = 0.25f,
    )
    // Barbell Squat coef 1.00, Front Squat coef 0.80 — both QUADS (ExerciseCoefficients).
    private val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
    private val front = Exercise(id = 2, name = "Front Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)

    @Test
    fun predictionsArePreFoldPerSetAndFatigueAdjusted() {
        val sets = listOf(
            WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            WorkoutSet(id = 2, sessionId = 1, exerciseId = 1, setNumber = 2, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
        )
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = sets,
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        val fold = BeliefFold(config)
        val pooling = BeliefPooling(config)

        var seen = listOf<BeliefStackReplay.SetPrediction>()
        var folded = mapOf<Long, Belief>()
        BeliefStackReplay.run(data, config) { _, _, predictions, _, beliefs ->
            seen = predictions; folded = beliefs.toMap()
        }

        // Hand-replay: seed belief, pool at asOf, per-set fatigue-shifted point predictions.
        val seedBeliefs = mapOf(1L to Belief(ln(110f), config.sigmaSeed * config.sigmaSeed, 0L))
        val snapshot = data.newSnapshot()
        val eff = pooling.effective(seedBeliefs, snapshot.seedCoefficients,
            snapshot.muscleExerciseIds[MuscleGroup.QUADS]!!, 1 * DAY_MS).effective[1L]!!
        assertEquals(2, seen.size)
        assertEquals(eff.mu - fold.fatigueShift(1), seen[0].predictedLn!!, 1e-5f)
        assertEquals(eff.mu - fold.fatigueShift(2), seen[1].predictedLn!!, 1e-5f)
        assertEquals(1, seen[0].rank); assertEquals(2, seen[1].rank)

        // Post-fold state matches foldSession on the aged seed.
        assertEquals(fold.foldSession(seedBeliefs[1L]!!, sets, 1 * DAY_MS), folded[1L])
    }

    @Test
    fun coldExerciseIsInitializedFromItsSiblingPredictionBeforeFolding() {
        // Session 1 trains the squat (seeded); session 2 trains the cold front squat.
        val sets1 = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1))
        val sets2 = listOf(WorkoutSet(id = 2, sessionId = 2, exerciseId = 2, setNumber = 1, targetWeight = 70f, targetReps = 5, feedback = SetFeedback.RIR_0_1))
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat, front),
            sessions = listOf(
                WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS),
                WorkoutSession(id = 2, startTime = 0, endTime = 3 * DAY_MS),
            ),
            sets = sets1 + sets2,
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        var effAtSession2: EffectiveBelief? = null
        var beliefsAfter1: Map<Long, Belief> = emptyMap()
        var beliefsAfter2: Map<Long, Belief> = emptyMap()
        BeliefStackReplay.run(data, config) { sessionId, _, _, effective, beliefs ->
            if (sessionId == 1L) beliefsAfter1 = beliefs.toMap()
            if (sessionId == 2L) { effAtSession2 = effective[2L]; beliefsAfter2 = beliefs.toMap() }
        }
        assertNull(beliefsAfter1[2L])   // not materialized before it is trained
        // The cold prior IS the pre-fold sibling prediction; folding it yields the stored belief.
        val fold = BeliefFold(config)
        val expected = fold.foldSession(
            Belief(effAtSession2!!.mu, effAtSession2!!.sigma2, 3 * DAY_MS), sets2, 3 * DAY_MS)
        assertEquals(expected, beliefsAfter2[2L])
    }
}
```

(Add `import io.github.fowles.stochastic_strength.domain.belief.EffectiveBelief` to the test's imports.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefStackReplayTest"`
Expected: FAIL to compile (`BeliefStackReplay` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefFold
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.EffectiveBelief
import kotlin.math.ln

/**
 * Forward-chained replay of the BELIEF stack over parsed backup data (spec Phase 2). Mirrors
 * MainStackReplay's session semantics exactly (override rows seed/reset beliefs; session-k
 * overrides apply before session k; sessions sorted by (endTime, id); empty-set sessions skip) —
 * KEEP IN SYNC with MainStackReplay. Predictions are per SET (fatigue-aware), captured pre-fold.
 *
 * Cold exercises (no belief yet) fold their first session against the sibling prediction as the
 * prior — the pool is the prior, no extra constant (spec: cold exercises lean on siblings).
 */
object BeliefStackReplay {

    data class SetPrediction(val set: WorkoutSet, val rank: Int, val predictedLn: Float?)

    fun interface SessionObserver {
        fun onSession(
            sessionId: Long,
            asOf: Long,
            predictions: List<SetPrediction>,
            effective: Map<Long, EffectiveBelief>,
            beliefs: Map<Long, Belief>,
        )
    }

    fun run(data: BacktestData, config: BeliefConfig, observer: SessionObserver) {
        val fold = BeliefFold(config)
        val pooling = BeliefPooling(config)
        val snapshot = data.newSnapshot()
        val beliefs = mutableMapOf<Long, Belief>()
        val sigmaSeed2 = config.sigmaSeed * config.sigmaSeed
        val sigmaOverride2 = config.sigmaOverride * config.sigmaOverride

        for (init in data.initialOverrides) {
            beliefs[init.exerciseId] = Belief(ln(init.e1rm), sigmaSeed2, init.asOf)
        }
        for (session in data.sessions) {
            data.sessionOverrides[session.id]?.forEach { o ->
                beliefs[o.exerciseId] = Belief(ln(o.e1rm), sigmaOverride2, o.asOf)
            }
            val sets = data.setsBySession[session.id].orEmpty()
            if (sets.isEmpty()) continue
            val asOf = session.endTime!!

            // Pre-fold effective beliefs for every muscle at asOf = the held-out state.
            val effective = mutableMapOf<Long, EffectiveBelief>()
            for ((_, ids) in snapshot.muscleExerciseIds) {
                effective.putAll(pooling.effective(beliefs, snapshot.seedCoefficients, ids, asOf).effective)
            }
            // Per-set predictions: rank over ALL of the exercise's rows (id order), fatigue-shifted.
            val predictions = sets.groupBy { it.exerciseId }.flatMap { (id, exSets) ->
                val eff = effective[id]
                exSets.sortedBy { it.id }.mapIndexed { idx, s ->
                    SetPrediction(s, idx + 1, eff?.let { it.mu - fold.fatigueShift(idx + 1) })
                }
            }
            // Fold: existing belief, or sibling prediction as the cold prior.
            sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
                if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
                val prior = beliefs[id]
                    ?: effective[id]?.let { Belief(it.mu, it.sigma2, asOf) }
                    ?: return@forEach
                beliefs[id] = fold.foldSession(prior, exSets, asOf)
            }
            observer.onSession(session.id, asOf, predictions, effective, beliefs)
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefStackReplayTest"`
Expected: 2 PASS. If the hand-computed expectations mismatch, debug the TEST's hand-replay first (the replay must mirror `foldSession`/`effective` exactly — a mismatch usually means the test replays in a different order than the SUT).

- [ ] **Step 5: Commit**

```bash
jj commit -m "test(backtest): BeliefStackReplay — forward-chained belief-stack replay with per-set predictions"
```

---

### Task 8: `BeliefHeldOutScorer` + provisional score report

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefHeldOutScorer.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefHeldOutScorerTest.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefScoreTest.kt`

**Interfaces:**
- Consumes: `BeliefStackReplay`, `SetIntervals`, `ScoreReport`/`SessionScore` (from `HeldOutScorer.kt`), `BacktestData.baselineFile()`.
- Produces: `object BeliefHeldOutScorer { data class BeliefScoreReport(val report: ScoreReport, val coveredSets: Int); fun score(data: BacktestData, config: BeliefConfig): BeliefScoreReport }`. Distance uses the UNSHIFTED interval vs the fatigue-adjusted per-set point — identical metric semantics to `HeldOutScorer`, so the totals are directly comparable. `coveredSets` (distance == 0) is the supplementary coverage report (not an authority).

- [ ] **Step 1: Write the failing unit test** (`BeliefHeldOutScorerTest.kt`) — reuse the single-exercise fixture from Task 7's first test and assert:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals
import org.junit.Assert.assertEquals
import org.junit.Test

class BeliefHeldOutScorerTest {
    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        phi = 0.05f, qPerDay = 1e-3f,
        sigmaObsRir = 0.10f, sigmaObsFail = 0.07f,
        tau = 0.10f, sigma2Floor = 4e-4f, sigma2Cap = 0.25f,
    )

    @Test
    fun scoreSumsPerSetDistancesAgainstUnshiftedIntervals() {
        val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
        val sets = listOf(
            WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            WorkoutSet(id = 2, sessionId = 1, exerciseId = 1, setNumber = 2, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
            WorkoutSet(id = 3, sessionId = 1, exerciseId = 1, setNumber = 3, targetWeight = 100f, targetReps = 5),  // no feedback: not scored
        )
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = sets,
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        // Expected: replay once, sum interval.distanceTo(predictedLn) over the two feedback sets.
        var expected = 0.0
        BeliefStackReplay.run(data, config) { _, _, predictions, _, _ ->
            for (p in predictions) {
                val interval = SetIntervals.impliedLn1RmInterval(p.set) ?: continue
                expected += interval.distanceTo(p.predictedLn!!).toDouble()
            }
        }
        val result = BeliefHeldOutScorer.score(data, config)
        assertEquals(expected, result.report.totalDistance, 1e-9)
        assertEquals(2, result.report.scoredSets)
        assertEquals(0, result.report.skippedSets)
    }

    @Test
    fun setsWithoutAPredictionAreSkippedNotScored() {
        // No override, single lonely exercise: session 1 has no prediction for it.
        val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1)),
        ))
        val result = BeliefHeldOutScorer.score(data, config)
        assertEquals(0, result.report.scoredSets)
        assertEquals(1, result.report.skippedSets)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefHeldOutScorerTest"`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals

/**
 * Held-out score of the BELIEF stack (same authority metric as HeldOutScorer, same intervals,
 * per-SET predictions). Scores the RAW estimator — pre-z, pre-clamp (constitution rule 6).
 * [coveredSets] (= sets landing inside their interval) is a supplementary report only.
 */
object BeliefHeldOutScorer {
    data class BeliefScoreReport(val report: ScoreReport, val coveredSets: Int)

    fun score(data: BacktestData, config: BeliefConfig): BeliefScoreReport {
        var total = 0.0
        var scored = 0
        var skipped = 0
        var covered = 0
        val perSession = mutableListOf<SessionScore>()
        BeliefStackReplay.run(data, config) { sessionId, _, predictions, _, _ ->
            var d = 0.0
            var n = 0
            for (p in predictions) {
                val interval = SetIntervals.impliedLn1RmInterval(p.set) ?: continue
                val pred = p.predictedLn
                if (pred == null) { skipped++; continue }
                val dist = interval.distanceTo(pred).toDouble()
                if (dist == 0.0) covered++
                d += dist
                n++
            }
            total += d
            scored += n
            perSession += SessionScore(sessionId, d, n)
        }
        return BeliefScoreReport(ScoreReport(total, scored, skipped, perSession), covered)
    }
}
```

- [ ] **Step 4: Write the real-history report test** (`BeliefScoreTest.kt`) — report only in this task; Task 10 adds the gate assertion after fitting:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Phase-2 score of the belief stack on real history vs the Phase-0 baseline (main's estimator:
 * total 26.7593 / per-set 0.12563 / 213 scored / 9 skipped). Skips without history.json.
 * NOTE: the belief stack may score sets main skipped (cold-start via siblings), which can only
 * ADD distance — comparing totals is conservative in main's favor.
 */
class BeliefScoreTest {

    @Test
    fun reportBeliefStackHeldOutScore() {
        val data = BacktestData.loadOrNull()
        Assume.assumeTrue("backtest/history.json not present; skipping", data != null)
        data!!

        val result = BeliefHeldOutScorer.score(data, BeliefConfig())
        val r = result.report
        assertTrue("belief stack must score real sets", r.scoredSets > 0)

        val baseline = BacktestData.baselineFile().takeIf { it.exists() }
            ?.let { JSONObject(it.readText()) }
        val sb = StringBuilder()
        sb.appendLine("=== Phase 2: belief stack held-out score (config = adopted defaults) ===")
        sb.appendLine("sets scored     : ${r.scoredSets} (skipped: ${r.skippedSets})")
        sb.appendLine("total distance  : ${"%.4f".format(r.totalDistance)} ln-units")
        sb.appendLine("mean per set    : ${"%.5f".format(r.totalDistance / r.scoredSets)} ln-units")
        sb.appendLine("coverage        : ${result.coveredSets}/${r.scoredSets} sets inside their interval")
        if (baseline != null) {
            sb.appendLine("main baseline   : total ${"%.4f".format(baseline.getDouble("totalDistance"))} / per-set ${"%.5f".format(baseline.getDouble("meanDistancePerSet"))} (${baseline.getInt("scoredSets")} sets)")
        }
        println(sb)
    }
}
```

- [ ] **Step 5: Run everything from this task** (the report test skips without history; on this machine history exists — read the printed numbers and copy them into the Results appendix of this plan):

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefHeldOutScorerTest" --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefScoreTest"`
Expected: PASS; report printed in the test output (`app/build/test-results/` or console with `--info`).

- [ ] **Step 6: Commit**

```bash
jj commit -m "test(backtest): belief-stack held-out scorer + provisional real-history report"
```

---

### Task 9: `BeliefFitHarness` — coordinate descent with sensitivity curves

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefFitHarness.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefFitHarnessTest.kt`

**Interfaces:**
- Consumes: `BeliefConfig`.
- Produces:

```kotlin
object BeliefFitHarness {
    data class Axis(val name: String, val values: List<Float>,
                    val get: (BeliefConfig) -> Float, val with: (BeliefConfig, Float) -> BeliefConfig)
    val AXES: List<Axis>   // phi, qPerDay, sigmaObsRir, sigmaObsFail, tau
    data class FitResult(val best: BeliefConfig, val bestScore: Double,
                         val curves: Map<String, List<Pair<Float, Double>>>)
    fun fit(start: BeliefConfig, axes: List<Axis> = AXES, passes: Int = 3,
            score: (BeliefConfig) -> Double): FitResult
}
```

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class BeliefFitHarnessTest {
    @Test
    fun coordinateDescentFindsTheGridMinimumOfASeparableBowl() {
        val axes = listOf(
            BeliefFitHarness.Axis("phi", listOf(0f, 0.01f, 0.02f, 0.05f), { it.phi }, { c, v -> c.copy(phi = v) }),
            BeliefFitHarness.Axis("tau", listOf(0.05f, 0.10f, 0.20f), { it.tau }, { c, v -> c.copy(tau = v) }),
        )
        // Separable bowl with minimum at phi=0.02, tau=0.10.
        val score = { c: BeliefConfig -> (abs(c.phi - 0.02f) + abs(c.tau - 0.10f)).toDouble() }
        val result = BeliefFitHarness.fit(BeliefConfig(phi = 0f, tau = 0.20f), axes, passes = 2, score = score)
        assertEquals(0.02f, result.best.phi, 1e-7f)
        assertEquals(0.10f, result.best.tau, 1e-7f)
        assertEquals(0.0, result.bestScore, 1e-9)
        // Sensitivity curves cover every grid value of every axis, scored at the optimum of the others.
        assertEquals(4, result.curves["phi"]!!.size)
        assertEquals(0.02, result.curves["phi"]!![0].second, 1e-6)   // |0−0.02| at tau*=0.10
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefFitHarnessTest"`
Expected: FAIL to compile.

- [ ] **Step 3: Implement**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig

/**
 * Coordinate-descent fitting of the belief stack's `fitted` constants against the ONE authority
 * (held-out score on real history — constitution rule 1). Sensitivity curves are 1-D sweeps at the
 * final optimum, recorded in the plan when a constant is admitted (rule 2). The fitness function
 * never sees policy clamps (rule 3).
 */
object BeliefFitHarness {

    data class Axis(
        val name: String,
        val values: List<Float>,
        val get: (BeliefConfig) -> Float,
        val with: (BeliefConfig, Float) -> BeliefConfig,
    )

    /** Wide log-spaced grids; an optimum on a grid EDGE means "widen the grid", not "adopt". */
    val AXES = listOf(
        Axis("phi", listOf(0f, 0.01f, 0.02f, 0.03f, 0.05f, 0.08f), { it.phi }, { c, v -> c.copy(phi = v) }),
        Axis("qPerDay", listOf(1e-5f, 3e-5f, 1e-4f, 3e-4f, 1e-3f, 3e-3f), { it.qPerDay }, { c, v -> c.copy(qPerDay = v) }),
        Axis("sigmaObsRir", listOf(0.02f, 0.04f, 0.07f, 0.10f, 0.15f, 0.25f), { it.sigmaObsRir }, { c, v -> c.copy(sigmaObsRir = v) }),
        Axis("sigmaObsFail", listOf(0.02f, 0.04f, 0.07f, 0.10f, 0.15f, 0.25f), { it.sigmaObsFail }, { c, v -> c.copy(sigmaObsFail = v) }),
        Axis("tau", listOf(0.05f, 0.08f, 0.12f, 0.20f, 0.30f, 0.50f), { it.tau }, { c, v -> c.copy(tau = v) }),
    )

    data class FitResult(
        val best: BeliefConfig,
        val bestScore: Double,
        val curves: Map<String, List<Pair<Float, Double>>>,
    )

    fun fit(
        start: BeliefConfig,
        axes: List<Axis> = AXES,
        passes: Int = 3,
        score: (BeliefConfig) -> Double,
    ): FitResult {
        var best = start
        var bestScore = score(best)
        repeat(passes) {
            for (axis in axes) {
                for (v in axis.values) {
                    if (v == axis.get(best)) continue
                    val s = score(axis.with(best, v))
                    if (s < bestScore - 1e-12) { best = axis.with(best, v); bestScore = s }
                }
            }
        }
        val curves = axes.associate { axis ->
            axis.name to axis.values.map { v ->
                v to if (v == axis.get(best)) bestScore else score(axis.with(best, v))
            }
        }
        return FitResult(best, bestScore, curves)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefFitHarnessTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "test(backtest): coordinate-descent fit harness with per-axis sensitivity curves"
```

---

### Task 10: Fit on real history, adopt constants, assert the gate — **CHECKPOINT (user reviews before commit)**

This is the constitution's admission ceremony. The implementer runs the fit, records the evidence, adopts values, and STOPS for user review before committing.

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefFitTest.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/belief/Belief.kt` (BeliefConfig defaults + labels)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefScoreTest.kt` (add gate assertion)
- Modify: `docs/superpowers/plans/2026-07-14-phase2-belief-core.md` (Results appendix)

- [ ] **Step 1: Write the fit-runner test** (report-style, skips without history):

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import org.junit.Assume
import org.junit.Test

/**
 * Runs the coordinate-descent fit of the belief stack's `fitted` constants on real history and
 * prints best config + per-axis sensitivity curves (constitution rule 2 admission evidence).
 * Human-gated: the printed values are adopted into BeliefConfig's defaults by hand, with the
 * curves recorded in the phase-2 plan. Skips without history.json.
 */
class BeliefFitTest {

    @Test
    fun fitBeliefConstantsOnRealHistory() {
        val data = BacktestData.loadOrNull()
        Assume.assumeTrue("backtest/history.json not present; skipping", data != null)
        data!!

        val result = BeliefFitHarness.fit(start = BeliefConfig()) { config ->
            BeliefHeldOutScorer.score(data, config).report.totalDistance
        }
        val sb = StringBuilder()
        sb.appendLine("=== Phase 2 fit: belief constants on real history (authority: held-out total) ===")
        sb.appendLine("best score : ${"%.4f".format(result.bestScore)} ln-units")
        sb.appendLine("best config: phi=${result.best.phi} qPerDay=${result.best.qPerDay} " +
            "sigmaObsRir=${result.best.sigmaObsRir} sigmaObsFail=${result.best.sigmaObsFail} tau=${result.best.tau}")
        for ((axis, curve) in result.curves) {
            sb.appendLine("curve $axis : " + curve.joinToString("  ") { (v, s) -> "$v→${"%.4f".format(s)}" })
        }
        val cov = BeliefHeldOutScorer.score(data, result.best)
        sb.appendLine("coverage at best: ${cov.coveredSets}/${cov.report.scoredSets} " +
            "(skipped ${cov.report.skippedSets})")
        println(sb)
    }
}
```

- [ ] **Step 2: Run the fit** (single test; expect a few minutes — ~90 scored replays of 24 sessions):

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefFitTest" --info | grep -A 40 "Phase 2 fit"`
Expected: printed best config, best score, five sensitivity curves, coverage.

- [ ] **Step 3: Judge each constant against the constitution** (rule 2), per axis:
  - **Interior optimum** (best value strictly inside the grid, curve clearly bowl-shaped) → label stays `fitted`; adopt the value.
  - **Optimum on a grid edge** → widen that axis's grid in `BeliefFitHarness.AXES` (extend one decade in that direction), rerun Step 2 once. Still edge-pinned → report to the user at the checkpoint; do not silently adopt.
  - **Flat curve** (spread over the whole axis < ~1% of the best score) → adopt the middle-of-flat-range value and relabel that constant `flat` (frozen, never revisited) in `BeliefConfig`'s kdoc.
  - **sigmaObsRir vs sigmaObsFail:** if their curves are flat against each other (either equals the other's optimum within the flat threshold), collapse to ONE `sigmaObs` constant (spec allows 1–2): merge the fields, update `BeliefFold.obsSigma`, re-run the fit once to confirm.

- [ ] **Step 4: Adopt** — edit `BeliefConfig` defaults to the fitted values; update each kdoc label (`fitted` values get "fitted 2026-07-XX, curve in phase-2 plan appendix"; any `flat` relabels). Do NOT touch `sigmaSeed`/`sigmaOverride` (semantic) or the guards (flat).

- [ ] **Step 5: Add the phase gate** to `BeliefScoreTest.reportBeliefStackHeldOutScore` (after the report `println`):

```kotlin
// PHASE-2 SHIP GATE: beat main's Phase-0 baseline on the same metric. The belief stack may
// score MORE sets (cold-start via siblings), which only adds distance — conservative gate.
if (baseline != null) {
    assertTrue(
        "belief stack (${r.totalDistance}) must beat main's baseline (${baseline.getDouble("totalDistance")})",
        r.totalDistance < baseline.getDouble("totalDistance"),
    )
}
```

- [ ] **Step 6: Record results in this plan** — fill in the "Results appendix" section at the bottom: best config, best score vs 26.7593, all sensitivity curves verbatim, coverage, per-constant label decisions.

- [ ] **Step 7: Re-run the backtest suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.*" --tests "io.github.fowles.stochastic_strength.domain.belief.*"`
Expected: all PASS, including the new gate. If the gate FAILS (belief ≥ baseline), STOP — report the numbers to the user; per the constitution there is no tolerance band and no constant may be hand-adjusted to force it.

- [ ] **Step 8: CHECKPOINT — present to the user before committing:** fitted values + labels, sensitivity curves, gate margin, coverage, any edge-pinned or collapsed constants. Wait for approval.

- [ ] **Step 9: Commit (after approval)**

```bash
jj commit -m "feat(belief): adopt harness-fitted constants (sensitivity curves in plan); phase-2 gate green vs 26.7593 baseline"
```

---

### Task 11: Belief-stack clamp-bind health + failure invariant on real history

Constitution rules 3/4: replay the policy layer over the BELIEF stack's prescriptions (z + nudge, as Phase 3 will wire them), assert the failure invariant, and report the clamp-bind rate — chronic binders 21, 77, 30 are the estimator bug this phase was meant to fix.

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefPolicyBacktestTest.kt`

**Interfaces:**
- Consumes: `BeliefStackReplay` (the `effective` observer arg), `BeliefPrescriber.targetE1rm`, `PolicyFacts.build`, `PrescriptionPolicy.prescribe(..., overloadNudge = true)`.

- [ ] **Step 1: Write the test** — mirror `PolicyBacktestTest.kt`'s structure exactly (same `seen`/`lastFailure` bookkeeping, same invariant), with these differences: predictions come from `BeliefStackReplay.run(data, BeliefConfig()) { sessionId, asOf, _, effective, _ -> ... }`; the raw prescription input is `BeliefPrescriber.targetE1rm(eff)` per exercise; `prescribe(..., overloadNudge = true)`; count `nudges` (`prescriptions where the easy-fact bump applied` is not directly observable — count `capBinds`, `hurtBinds` as before) and accumulate per-exercise cap-bind counts. Print:

```kotlin
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
```

The invariant assertion (violations must be empty) is hard; the bind rate is a report. Note in the test kdoc: Phase-1 rate over main was 3.1% with exercises 21/77/30 chronic — if the belief stack does not visibly reduce those, flag it at review (rule 4: frequent binds = estimator bug).

- [ ] **Step 2: Run it**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefPolicyBacktestTest" --info | grep -A 20 "Phase 2 clamp-bind"`
Expected: PASS (0 violations); record the bind rate + per-exercise binds in the Results appendix.

- [ ] **Step 3: Commit**

```bash
jj commit -m "test(backtest): belief-stack clamp-bind report + failure invariant on real history"
```

---

### Task 12: Full verification + docs

- [ ] **Step 1: Full JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all PASS (Phase-1 count was 297 + this phase's additions; zero failures). `ExerciseEstimatorSimulationTest` and all old-estimator pins must be untouched and green.

- [ ] **Step 2: Instrumented suite** (emulator is typically running — attempt directly):

Run: `./gradlew :app:connectedAndroidTest`
Expected: 83/83 PASS (no instrumented surface changed this phase; this is the regression check).

- [ ] **Step 3: Lint**

Run: `./gradlew :app:lint`
Expected: no new errors.

- [ ] **Step 4:** Mark every checkbox in this plan done; confirm the Results appendix is filled (Task 8 provisional score, Task 10 fit + gate, Task 11 bind report).

- [ ] **Step 5: Commit**

```bash
jj commit -m "docs: phase-2 belief-core plan complete (fit results + gate + bind report recorded)"
```

---

## Self-review notes (spec → task map)

- Belief(mu, sigma2, updatedAt) on ln fresh 1RM → Task 2. Set→interval = shared `SetIntervals` → consumed in Task 3. Fatigue shift φ·(k−1), rank over ALL rows → Task 3. Boundary-pull Gaussian fold, symmetric, per-bucket obs σ → Task 3. Aging q/idle-day, no mu drift → Task 2. "Nothing else" → no other mechanism anywhere. HURT carries no load observation → Task 3 (`foldSession` skips null intervals). Override rows seed with σ_seed/σ_override → Task 7 replay.
- Pooling: votes 1/(σ²+τ²), one τ, LOO, no seed-anchor constant, never mutates → Task 4.
- Prescription: z (semantic 30th percentile) → Task 5; overload nudge (semantic, one increment, cap on top) → Task 6; policy caps unchanged (Phase 1).
- Authority + fitting + sensitivity curves + labels → Tasks 8–10. Clamp-bind health + invariant → Task 11. Ship gate (beat 26.7593 raw) → Task 10 Step 5.
- In-memory replay/DerivedStateStore wiring, charts, "why this weight" trace, deletions of the old estimator → **Phase 3, deliberately out of scope.**
- Constant census: σ_seed/σ_override (semantic), φ/q/σ_obs×2/τ (fitted, Task 10 may collapse σ_obs or relabel flat), floor/cap (flat guards), z + nudge (semantic) — matches the spec ledger (~7 estimator + policy semantics).

## Results appendix (filled during execution)

- Task 8 provisional score (defaults before fitting):
```
=== Phase 2: belief stack held-out score (config = adopted defaults) ===
sets scored     : 213 (skipped: 9)
total distance  : 27.2316 ln-units
mean per set    : 0.12785 ln-units
coverage        : 51/213 sets inside their interval
main baseline   : total 26.7593 / per-set 0.12563 (213 sets)
```
- Task 10 fit — best config, best score vs baseline 26.7593, per-axis curves, coverage, label decisions: _(Task 10 Step 6 pastes the printed fit output and decisions here)_
- Task 11 bind report — rate, per-exercise, vs Phase-1's 3.1% and chronic 21/77/30: _(Task 11 Step 2 pastes the printed report here)_
