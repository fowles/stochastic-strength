# Coefficient Estimator Robustness & Tuning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make the peer-consensus coefficient reference degrade gracefully at small peer counts, add optional peer-support confidence attenuation, and re-tune the damper parameters using a simulation harness that measures convergence and stability.

**Architecture:** All production changes are confined to `domain/EstCoefConsensusHeuristic.kt` (a pure `CoefficientHeuristic`) and its single production construction site in `StochasticStrengthApp.kt`. A new JVM test acts as a deterministic simulation harness: it drives the heuristic over many synthetic sessions, prints a parameter-sweep table, and (once values are chosen) asserts convergence/jitter/step bounds at those values. Class-level constructor defaults stay at their current values so the existing 27 unit tests remain valid; tuned values are applied at the production call site.

**Tech Stack:** Kotlin, JUnit4 (JVM unit tests via `./gradlew :app:testDebugUnitTest`), Jetpack/Room app (untouched here).

## Global Constraints

- Package: `io.github.fowles.stochastic_strength` (sub-package `domain`).
- No DB schema changes; no Room migration (all changes are code constants and pure functions).
- The existing 27 `EstCoefConsensusHeuristicTest` tests MUST stay green. Do NOT change class-level constructor defaults of `EstCoefConsensusHeuristic`; apply tuned values only at the production call site (`StochasticStrengthApp.kt:39`).
- `maxLogStep` is the single-session safety cap; its value (`ln(1.05f)` vs `ln(1.10f)`) is chosen by the harness from measured convergence-vs-worst-step impact, not loosened blindly.
- All location/center estimators added here must be scale-equivariant (`f(k·x) = k·f(x)`) so the systemic-drift cancellation property is preserved.
- Run the targeted test after each change; run the full `domain` suite at the end of each task.

---

### Task 1: Interpolated weighted median (pure function)

Add a continuous (interpolated) weighted median alongside the existing data-point `weightedMedian`. The new function blends the two straddling values at the exact half-weight crossing instead of returning the lower one, so it degrades gracefully at small n (a blend, not a coin-flip).

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt` (add a function near the existing `weightedMedian` at the bottom of the class)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

**Interfaces:**
- Produces: `internal fun interpolatedWeightedMedian(valueWeights: List<Pair<Float, Float>>): Float` — `valueWeights` is a list of `(value, weight)`; returns the value at the 50%-of-total-weight crossing, linearly interpolated between the two adjacent values whose weight-mass midpoints straddle the crossing. n=1 → that value; all-equal values → that value; total weight ≤ 0 → the middle element's value.

- [x] **Step 1: Write the failing tests**

Add to `EstCoefConsensusHeuristicTest.kt` (after the existing `weightedMedian`-related tests; if none, anywhere in the class):

```kotlin
    @Test
    fun interpolatedWeightedMedian_singleValue_returnsThatValue() {
        assertEquals(42f, heuristic.interpolatedWeightedMedian(listOf(42f to 1f)), 0.0001f)
    }

    @Test
    fun interpolatedWeightedMedian_twoEqualWeights_returnsMidpoint() {
        // equal weights -> midpoint blend, not a hard pick of either
        assertEquals(110f, heuristic.interpolatedWeightedMedian(listOf(100f to 1f, 120f to 1f)), 0.0001f)
    }

    @Test
    fun interpolatedWeightedMedian_twoUnequalWeights_leansTowardHeavier() {
        // weights 0.3 (@100) and 0.5 (@130): total 0.8, target 0.4
        // midpoints p0=0.15, p1=0.30+0.25=0.55; t=(0.4-0.15)/(0.55-0.15)=0.625
        // value = 100 + 0.625*(130-100) = 118.75
        assertEquals(118.75f, heuristic.interpolatedWeightedMedian(listOf(100f to 0.3f, 130f to 0.5f)), 0.001f)
    }

    @Test
    fun interpolatedWeightedMedian_allEqualValues_returnsThatValue() {
        assertEquals(50f, heuristic.interpolatedWeightedMedian(listOf(50f to 1f, 50f to 2f, 50f to 0.5f)), 0.0001f)
    }

    @Test
    fun interpolatedWeightedMedian_isScaleEquivariant() {
        val pts = listOf(100f to 0.3f, 130f to 0.5f, 90f to 0.2f)
        val base = heuristic.interpolatedWeightedMedian(pts)
        val scaled = heuristic.interpolatedWeightedMedian(pts.map { (v, w) -> (v * 3f) to w })
        assertEquals(base * 3f, scaled, 0.001f)
    }
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"`
Expected: FAIL — compilation error, `interpolatedWeightedMedian` is unresolved.

- [x] **Step 3: Implement the function**

Add this `internal` function inside `EstCoefConsensusHeuristic`, immediately below the existing private `weightedMedian` (around `:202-212`):

```kotlin
    internal fun interpolatedWeightedMedian(valueWeights: List<Pair<Float, Float>>): Float {
        if (valueWeights.isEmpty()) return 0f
        val sorted = valueWeights.sortedBy { it.first }
        val total = sorted.sumOf { it.second.toDouble() }.toFloat()
        if (total <= 0f) return sorted[sorted.size / 2].first
        val target = total / 2f
        // weight-mass midpoint of each point along the cumulative axis
        val positions = FloatArray(sorted.size)
        var cum = 0f
        for (i in sorted.indices) {
            positions[i] = cum + sorted[i].second / 2f
            cum += sorted[i].second
        }
        if (target <= positions.first()) return sorted.first().first
        if (target >= positions.last()) return sorted.last().first
        for (i in 0 until sorted.size - 1) {
            val pLo = positions[i]
            val pHi = positions[i + 1]
            if (target in pLo..pHi) {
                val t = (target - pLo) / (pHi - pLo)
                return sorted[i].first + t * (sorted[i + 1].first - sorted[i].first)
            }
        }
        return sorted.last().first
    }
```

- [x] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"`
Expected: PASS (all existing tests plus the 5 new ones).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt
git commit -m "feat: add interpolated weighted median for coefficient reference

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Use the interpolated median for the peer reference

Switch only the peer-consensus reference (`B_others`) to the interpolated median; keep the data-point `weightedMedian` for the inner within-exercise estimate (where rejecting a freak session is desired).

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt:184` (inside `applyPeerConsensus`)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

**Interfaces:**
- Consumes: `interpolatedWeightedMedian` from Task 1.
- Produces: no signature change to `applyPeerConsensus` or `compute`.

- [x] **Step 1: Write the failing integration test**

Add to `EstCoefConsensusHeuristicTest.kt`. This builds one muscle with a target exercise plus two unequal-weight peers and asserts the reference is a blend (so the proposal is not equal to either single-peer selection). Use `minPeers = 2` explicitly so the scenario is exercised regardless of the production default.

```kotlin
    @Test
    fun peerReference_twoPeers_usesInterpolatedBlendNotSelection() {
        val h = EstCoefConsensusHeuristic(minPeers = 2, minRelativeChange = 0.0f)
        // Muscle CHEST, exercises 1 (target), 2 and 3 (peers). One session each.
        // Peers imply different baselines via different weights/strengths so the
        // data-point median would hard-pick one; the interpolated median blends.
        val sets = listOf(
            // target exercise 1
            WorkoutSet(sessionId = 1L, exerciseId = 1L, setNumber = 1,
                targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
            // peer 2 — heavier evidence (measured failure => high confidence/weight)
            WorkoutSet(sessionId = 2L, exerciseId = 2L, setNumber = 1,
                targetWeight = 120f, targetReps = 5, actualReps = 5, feedback = SetFeedback.TOO_HARD),
            // peer 3 — lighter evidence (RIR_5_PLUS => low confidence/weight)
            WorkoutSet(sessionId = 3L, exerciseId = 3L, setNumber = 1,
                targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
        )
        val input = CoefficientComputationInput(
            sets = sets,
            sessionTimes = mapOf(1L to 0L, 2L to 0L, 3L to 0L),
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST, 3L to MuscleGroup.CHEST),
            baselines = emptyMap(),
            currentCoefficients = mapOf(1L to 1.0f, 2L to 1.0f, 3L to 1.0f),
        )
        val result = h.compute(input).firstOrNull { it.exerciseId == 1L }
        assertNotNull(result)
        // Reference is interpolatedWeightedMedian over peers 2 and 3 (both implied
        // baselines E_j/c_j with c_j = 1). Compute it directly and confirm the
        // proposal matches E_1 / thatReference (after damp from current 1.0).
        val e2 = DefaultProgressionEngine.toOneRepMax(120f, 5)   // peer 2 measured failure
        val w2 = 0.95f
        val e3 = DefaultProgressionEngine.toOneRepMax(80f, 5 + 7) // peer 3 RIR_5_PLUS
        val w3 = 0.4f
        val reference = h.interpolatedWeightedMedian(listOf(e2 to w2, e3 to w3))
        val e1 = DefaultProgressionEngine.toOneRepMax(100f, 5 + 1) // target RIR_0_1
        val proposal = e1 / reference
        // damp from current 1.0: step = alpha*conf*ln(proposal); conf = target session conf (0.85)
        val step = (0.2f * 0.85f * kotlin.math.ln(proposal.toDouble())).toFloat()
            .coerceIn(-kotlin.math.ln(1.05f), kotlin.math.ln(1.05f))
        val expected = 1.0f * kotlin.math.exp(step.toDouble()).toFloat()
        assertEquals(expected, result!!.coefficient, 0.0005f)
    }
```

Add the import if missing: `import org.junit.Assert.assertNotNull` and `import io.github.fowles.stochastic_strength.data.model.MuscleGroup`.

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest.peerReference_twoPeers_usesInterpolatedBlendNotSelection"`
Expected: FAIL — the current code calls the data-point `weightedMedian`, so the reference (and thus the coefficient) differs from the interpolated `expected`.

- [x] **Step 3: Switch the peer reference to the interpolated median**

In `applyPeerConsensus`, change the reference line (currently `:184`):

```kotlin
                val reference = weightedMedian(others.map { it.impliedBaseline to it.weight })
```

to:

```kotlin
                val reference = interpolatedWeightedMedian(others.map { it.impliedBaseline to it.weight })
```

Leave the inner `computeEstimate` call to `weightedMedian` (`:148`) unchanged.

- [x] **Step 4: Run the new test, then the full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"`
Expected: PASS. The existing systemic-drift and equilibrium regression tests stay green because the interpolated median is scale-equivariant and their peer sets are symmetric (a symmetric peer set yields the same center under both estimators). If any existing test asserts an exact center value over an *asymmetric* peer set and now fails, recompute its expected value using `interpolatedWeightedMedian` over the same `(value, weight)` pairs and update the expectation (do not change the production code).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt
git commit -m "feat: use interpolated median for peer-consensus reference

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Peer-support confidence attenuation (behind a parameter)

Add an optional knob that scales a proposal's confidence down when the total peer evidence weight backing its reference is thin. Default is off (`null`) so existing tests and current production behavior are unchanged.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt` (constructor params `:8-15`; `applyPeerConsensus` body `:178-188`)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

**Interfaces:**
- Produces: new constructor param `peerSupportFullWeight: Float? = null`. When non-null and `> 0`, a proposal's confidence is multiplied by `min(1, totalOtherWeight / peerSupportFullWeight)`, where `totalOtherWeight` is the summed evidence weight of the peers forming the reference. When null, no attenuation.

- [x] **Step 1: Write the failing test**

```kotlin
    @Test
    fun peerSupportAttenuation_thinPeers_dampensMoveRelativeToNoAttenuation() {
        // Same scenario; target coefficient is wrong (0.7) so there is a move to make.
        fun run(attenuation: Float?): Float {
            val h = EstCoefConsensusHeuristic(minPeers = 2, minRelativeChange = 0.0f,
                peerSupportFullWeight = attenuation)
            val sets = listOf(
                WorkoutSet(sessionId = 1L, exerciseId = 1L, setNumber = 1,
                    targetWeight = 70f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
                WorkoutSet(sessionId = 2L, exerciseId = 2L, setNumber = 1,
                    targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
                WorkoutSet(sessionId = 3L, exerciseId = 3L, setNumber = 1,
                    targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
            )
            val input = CoefficientComputationInput(
                sets = sets,
                sessionTimes = mapOf(1L to 0L, 2L to 0L, 3L to 0L),
                exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST, 3L to MuscleGroup.CHEST),
                baselines = emptyMap(),
                currentCoefficients = mapOf(1L to 0.7f, 2L to 1.0f, 3L to 1.0f),
            )
            val r = input.let { h.compute(it) }.first { it.exerciseId == 1L }
            return kotlin.math.abs(r.coefficient - 0.7f)
        }
        val moveNoAtten = run(null)
        val moveAtten = run(100f) // threshold far above the thin peer weight (~0.8) -> heavy attenuation
        assertTrue("attenuated move should be smaller", moveAtten < moveNoAtten)
        assertTrue("attenuated move should be > 0", moveAtten > 0f)
    }
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest.peerSupportAttenuation_thinPeers_dampensMoveRelativeToNoAttenuation"`
Expected: FAIL — `peerSupportFullWeight` is not a constructor parameter (compilation error).

- [x] **Step 3: Add the parameter and apply attenuation**

Add the parameter to the constructor (after `minRelativeChange`, `:14`):

```kotlin
    private val minRelativeChange: Float = 0.005f,
    private val peerSupportFullWeight: Float? = null,
```

In `applyPeerConsensus`, replace the proposal emit (currently `:185-187`):

```kotlin
                val proposal = est.est1RM / reference
                out[id] = EmitProposal(proposal, est.confidence, "peer_consensus:peers=${others.size}")
```

with:

```kotlin
                val proposal = est.est1RM / reference
                val totalOtherWeight = others.sumOf { it.weight.toDouble() }.toFloat()
                val support = peerSupportFullWeight
                val attenuation =
                    if (support != null && support > 0f) minOf(1f, totalOtherWeight / support) else 1f
                out[id] = EmitProposal(
                    proposal,
                    est.confidence * attenuation,
                    "peer_consensus:peers=${others.size}",
                )
```

- [x] **Step 4: Run the new test, then the full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"`
Expected: PASS (default `peerSupportFullWeight = null` leaves all existing tests unchanged).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt
git commit -m "feat: optional peer-support confidence attenuation (default off)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Simulation harness (sweep + print)

Build a deterministic JVM test that drives the heuristic over many synthetic sessions, with realistic feedback derived from the gap between prescribed weight and true strength, and prints two parameter-sweep tables (damper sweep + thin-peer robustness sweep) to a file for inspection. No assertions yet — this is the measurement tool.

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/CoefficientConvergenceSimulationTest.kt`

**Interfaces:**
- Consumes: `EstCoefConsensusHeuristic` (with all params from Tasks 1–3), `DefaultProgressionEngine`, `WorkoutSet`, `CoefficientComputationInput`, `SetFeedback`, `MuscleGroup`.
- Produces: a `SimResult` data class and a `simulate(...)` helper, plus two `@Test` methods that write `build/reports/coefficient-sweep.md`.

- [x] **Step 1: Create the harness file**

Create `CoefficientConvergenceSimulationTest.kt` with the full content below:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Deterministic simulation harness for choosing coefficient-estimator parameters.
 * Not a behavioral assertion test (see Task 5 for the locked-value asserts) — it
 * drives the heuristic over synthetic sessions and prints sweep tables.
 */
class CoefficientConvergenceSimulationTest {

    private val muscle = MuscleGroup.CHEST
    private val baseline = 100f

    /** Reps achievable at [weight] given a true one-rep-max [true1RM], plus noise. */
    private fun achievableReps(weight: Float, true1RM: Float, noise: Double): Double {
        val denom = -2.55 + 4.58 * ln(weight.toDouble())
        val ratio = true1RM / weight - 1.0
        val raw = if (ratio <= 0.0 || denom <= 0.0) 1.0 else 1.0 + (ratio * denom).pow(1.0 / 0.85)
        return (raw + noise).coerceAtLeast(1.0)
    }

    private fun feedbackFor(weight: Float, repTarget: Int, true1RM: Float, noise: Double):
        Pair<SetFeedback, Int?> {
        val reps = achievableReps(weight, true1RM, noise)
        val rir = floor(reps).toInt() - repTarget
        return when {
            rir >= 5 -> SetFeedback.RIR_5_PLUS to null
            rir in 2..4 -> SetFeedback.RIR_2_4 to null
            rir in 0..1 -> SetFeedback.RIR_0_1 to null
            else -> SetFeedback.TOO_HARD to floor(reps).toInt().coerceAtLeast(1)
        }
    }

    data class SimResult(
        val worstConvSessions: Int,   // worst exercise's sessions-to-within-10%
        val avgJitterPct: Float,      // mean steady-state jitter across exercises (% of true)
        val maxStepPct: Float,        // largest single-session coefficient move (%)
        val avgEndErrorPct: Float,    // mean |c - c*| / c* at the end (%)
    )

    /**
     * @param trainPerSession null => train all exercises every session; else a random
     *   subset of that size (forces thin peer sets).
     */
    private fun simulate(
        heuristic: EstCoefConsensusHeuristic,
        trueCoefs: Map<Long, Float>,
        seedCoefs: Map<Long, Float>,
        convergenceSessions: Int,
        jitterTailSessions: Int,
        trainPerSession: Int?,
        sessionIntervalMs: Long,
        repNoiseStd: Double,
        seed: Long,
    ): SimResult {
        val rng = Random(seed)
        val ids = trueCoefs.keys.sorted()
        val current = seedCoefs.toMutableMap()
        val allSets = mutableListOf<WorkoutSet>()
        val sessionTimes = mutableMapOf<Long, Long>()
        val exMuscle = ids.associateWith { muscle }
        val convAt = ids.associateWith { -1 }.toMutableMap()
        val tail = ids.associateWith { mutableListOf<Float>() }
        var maxStepPct = 0f
        var t = 0L

        val totalSessions = convergenceSessions + jitterTailSessions
        for (s in 0 until totalSessions) {
            t += sessionIntervalMs
            val sessionId = s.toLong()
            sessionTimes[sessionId] = t
            val inTail = s >= convergenceSessions
            val noiseStd = if (inTail) repNoiseStd else 0.0
            val repTarget = listOf(5, 8, 10).random(rng)

            val trained = if (trainPerSession == null) ids
            else ids.shuffled(rng).take(trainPerSession)

            for (id in trained) {
                val target1RM = baseline * (current[id] ?: 0f)
                val weight = DefaultProgressionEngine.fromOneRepMax(target1RM, repTarget)
                val true1RM = baseline * trueCoefs.getValue(id)
                val noise = if (noiseStd > 0.0) rng.nextGaussian() * noiseStd else 0.0
                val (fb, ar) = feedbackFor(weight, repTarget, true1RM, noise)
                allSets.add(
                    WorkoutSet(
                        sessionId = sessionId, exerciseId = id, setNumber = 1,
                        targetWeight = weight, targetReps = repTarget,
                        actualReps = ar, feedback = fb,
                    )
                )
            }

            val input = CoefficientComputationInput(
                sets = allSets.toList(),
                sessionTimes = sessionTimes.toMap(),
                exerciseMuscle = exMuscle,
                baselines = emptyMap(),
                currentCoefficients = current.toMap(),
            )
            for (r in heuristic.compute(input)) {
                val old = current.getValue(r.exerciseId)
                if (old > 0f) {
                    val stepPct = abs(r.coefficient / old - 1f) * 100f
                    if (stepPct > maxStepPct) maxStepPct = stepPct
                }
                current[r.exerciseId] = r.coefficient
            }

            for (id in ids) {
                val cur = current.getValue(id)
                val tru = trueCoefs.getValue(id)
                if (convAt.getValue(id) < 0 && abs(cur - tru) / tru <= 0.10f) convAt[id] = s
                if (inTail) tail.getValue(id).add(cur)
            }
        }

        val worstConv = ids.maxOf { val c = convAt.getValue(it); if (c < 0) totalSessions else c }
        val jitter = ids.map { id ->
            val xs = tail.getValue(id)
            if (xs.size < 2) 0f else {
                val mean = xs.average().toFloat()
                val variance = xs.map { (it - mean) * (it - mean) }.average().toFloat()
                kotlin.math.sqrt(variance) / trueCoefs.getValue(id) * 100f
            }
        }.average().toFloat()
        val endErr = ids.map { abs(current.getValue(it) - trueCoefs.getValue(it)) / trueCoefs.getValue(it) }
            .average().toFloat() * 100f
        return SimResult(worstConv, jitter, maxStepPct, endErr)
    }

    // True coefficients and deliberately-wrong seeds (last one is 2x low).
    private val trueCoefs = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f, 4L to 0.4f, 5L to 0.30f)
    private val seedCoefs = mapOf(1L to 1.0f, 2L to 0.6f, 3L to 0.6f, 4L to 0.4f, 5L to 0.15f)

    private fun daysMs(days: Int): Long = days.toLong() * 24L * 60L * 60L * 1000L

    data class SweepRow(
        val scenario: String,
        val alpha: Float,
        val tauD: Int,
        val minRel: Float,
        val minPeers: Int,
        val atten: Float?,
        val result: SimResult,
    )

    private fun SimResult.metricsFinite(): Boolean =
        !avgJitterPct.isNaN() && !avgJitterPct.isInfinite() &&
            !maxStepPct.isNaN() && !maxStepPct.isInfinite() &&
            !avgEndErrorPct.isNaN() && !avgEndErrorPct.isInfinite()

    /** Damper sweep over alpha x tau x minRelChange (full peers). Writes the report and returns rows. */
    fun damperSweep(): List<SweepRow> {
        val rows = mutableListOf<SweepRow>()
        val sb = StringBuilder()
        sb.appendLine("# Coefficient damper sweep (full peers, seed err incl. 2x and 33% low)\n")
        sb.appendLine("| alpha | tau_d | minRelChg | worstConvSess | avgJitter% | maxStep% | endErr% |")
        sb.appendLine("|------:|------:|----------:|--------------:|-----------:|---------:|--------:|")
        for (alpha in listOf(0.2f, 0.3f, 0.4f)) {
            for (tauD in listOf(14, 21, 28)) {
                for (minRel in listOf(0.002f, 0.005f)) {
                    val h = EstCoefConsensusHeuristic(
                        alpha = alpha, tauHalfMs = daysMs(tauD),
                        minRelativeChange = minRel, minPeers = 3,
                    )
                    val r = simulate(
                        heuristic = h, trueCoefs = trueCoefs, seedCoefs = seedCoefs,
                        convergenceSessions = 60, jitterTailSessions = 40,
                        trainPerSession = null, sessionIntervalMs = daysMs(3),
                        repNoiseStd = 1.0, seed = 42L,
                    )
                    rows.add(SweepRow("damper", alpha, tauD, minRel, 3, null, r))
                    sb.appendLine("| $alpha | $tauD | $minRel | ${r.worstConvSessions} | " +
                        "%.2f | %.2f | %.2f |".format(r.avgJitterPct, r.maxStepPct, r.avgEndErrorPct))
                }
            }
        }
        writeReport(sb.toString(), append = false)
        println(sb.toString())
        return rows
    }

    /** Thin-peer robustness sweep over minPeers x attenuation (train 2/5). Writes report, returns rows. */
    fun thinPeerSweep(): List<SweepRow> {
        val rows = mutableListOf<SweepRow>()
        val sb = StringBuilder()
        sb.appendLine("\n# Thin-peer robustness sweep (train 2/5 per session)\n")
        sb.appendLine("| minPeers | attenFullW | worstConvSess | avgJitter% | maxStep% | endErr% |")
        sb.appendLine("|---------:|-----------:|--------------:|-----------:|---------:|--------:|")
        for (minPeers in listOf(2, 3)) {
            for (atten in listOf<Float?>(null, 2.0f)) {
                val h = EstCoefConsensusHeuristic(
                    alpha = 0.3f, tauHalfMs = daysMs(21),
                    minRelativeChange = 0.003f, minPeers = minPeers,
                    peerSupportFullWeight = atten,
                )
                val r = simulate(
                    heuristic = h, trueCoefs = trueCoefs, seedCoefs = seedCoefs,
                    convergenceSessions = 120, jitterTailSessions = 80,
                    trainPerSession = 2, sessionIntervalMs = daysMs(3),
                    repNoiseStd = 1.0, seed = 7L,
                )
                rows.add(SweepRow("thin", 0.3f, 21, 0.003f, minPeers, atten, r))
                sb.appendLine("| $minPeers | ${atten ?: "off"} | ${r.worstConvSessions} | " +
                    "%.2f | %.2f | %.2f |".format(r.avgJitterPct, r.maxStepPct, r.avgEndErrorPct))
            }
        }
        writeReport(sb.toString(), append = true)
        println(sb.toString())
        return rows
    }

    @Test
    fun damperSweep_producesFiniteMetrics() {
        val rows = damperSweep()
        assertTrue("damper sweep produced no rows", rows.isNotEmpty())
        rows.forEach {
            assertTrue("non-finite metric in $it", it.result.metricsFinite())
            assertTrue("conv beyond horizon in $it", it.result.worstConvSessions in 0..100)
        }
        // Task 5 adds the locked chosen-row bound assertions here.
    }

    @Test
    fun thinPeerSweep_producesFiniteMetrics() {
        val rows = thinPeerSweep()
        assertTrue("thin-peer sweep produced no rows", rows.isNotEmpty())
        rows.forEach {
            assertTrue("non-finite metric in $it", it.result.metricsFinite())
            assertTrue("conv beyond horizon in $it", it.result.worstConvSessions in 0..200)
        }
        // Task 5 adds the locked chosen-row bound assertions here.
    }

    private fun writeReport(text: String, append: Boolean) {
        val f = File("build/reports/coefficient-sweep.md")
        f.parentFile?.mkdirs()
        if (append) f.appendText(text) else f.writeText(text)
        println("Sweep written to: ${f.absolutePath}")
    }
}
```

Note: `Random.nextGaussian()` is available on `kotlin.random.Random` (JVM). If the toolchain's Kotlin version lacks it, replace `rng.nextGaussian()` with `java.util.Random(seed).nextGaussian()` driven by a `java.util.Random` instance created alongside `rng`.

- [x] **Step 2: Run the harness**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.CoefficientConvergenceSimulationTest"`
Expected: PASS — the two `*_producesFiniteMetrics` tests assert the sweeps return non-empty rows with finite metrics, and the file `app/build/reports/coefficient-sweep.md` is written with two markdown tables.

- [x] **Step 3: Inspect the output**

Open `app/build/reports/coefficient-sweep.md` and confirm both tables are populated with finite numbers (no `NaN`/`Infinity`). If any row shows `NaN`, the feedback model produced a degenerate weight — check that `seedCoefs` values are all `> 0` and `baseline > 1.74` (so `denom > 0`).

- [x] **Step 4: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/CoefficientConvergenceSimulationTest.kt
git commit -m "test: add coefficient-estimator simulation sweep harness

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Choose values, apply at production site, lock with asserts, update docs

Read the sweep tables, choose parameters per an explicit rule, confirm with the user, set them at the production construction site, convert the harness rows into locked-value assertions, and update the adaptation doc.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt:39`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/CoefficientConvergenceSimulationTest.kt` (add assertion tests)
- Modify: `docs/adaptation/03-coefficient-estimation.md`

**Interfaces:**
- Consumes: the sweep output from Task 4.
- Produces: production heuristic constructed with tuned values; locked bound assertions added to the two sweep `@Test` wrappers (`damperSweep_producesFiniteMetrics`, `thinPeerSweep_producesFiniteMetrics`).

- [x] **Step 1: Choose values from the damper sweep (selection rule)**

From the damper-sweep table, select the row that **minimizes `worstConvSess`** subject to **`avgJitter% ≤ 3.0` and `maxStep% ≤ 5.0`**. Break ties by preferring the **smaller `alpha`**, then the **smaller `tau_d`**. Record the chosen `(alpha, tau_d, minRelChange)`.

From the thin-peer-sweep table, choose `minPeers` and `peerSupportFullWeight`: pick the `(minPeers, attenFullW)` row with the **lowest `avgJitter%`** while keeping `worstConvSess` no worse than 1.3× the best row's. If `off` is within 0.5 percentage points of the best attenuated row on jitter, prefer `off` (smaller surface). Record the chosen `minPeers` and attenuation (`null` if `off`).

- [x] **Step 2: CHECKPOINT — confirm chosen values with the user**

Present the two chosen rows (with their metric numbers) and the resulting parameter set to the user. Wait for confirmation or an override before continuing. Record the final values as `ALPHA`, `TAU_DAYS`, `MIN_REL`, `MIN_PEERS`, `ATTEN` for use below.

- [x] **Step 3: Apply tuned values at the production construction site**

In `StochasticStrengthApp.kt:39`, replace:

```kotlin
            heuristic = EstCoefConsensusHeuristic(),
```

with the confirmed values (example shown with placeholders — substitute the confirmed numbers; `ATTEN` line omitted entirely if attenuation is `off`):

```kotlin
            heuristic = EstCoefConsensusHeuristic(
                alpha = ALPHA,
                tauHalfMs = TAU_DAYS * 24L * 60L * 60L * 1000L,
                minRelativeChange = MIN_REL,
                minPeers = MIN_PEERS,
                peerSupportFullWeight = ATTEN, // omit this line if attenuation is off
            ),
```

- [x] **Step 4: Lock the chosen values with bound assertions in the sweep tests**

Add the locked-value assertions to the two existing `@Test` wrappers in
`CoefficientConvergenceSimulationTest.kt` — asserting on the chosen row of each
sweep's returned `List<SweepRow>`. First, if the confirmed damper values differ
from `(0.3f, 21, 0.003f)`, update the fixed `alpha`/`tauHalfMs`/`minRelativeChange`
in `thinPeerSweep()` to the confirmed `(ALPHA, TAU_DAYS, MIN_REL)` so the thin-peer
lock reflects the final config (also update the `SweepRow("thin", ...)` constructor
args to match).

In `damperSweep_producesFiniteMetrics`, replace the trailing comment with (substitute
the confirmed `ALPHA`/`TAU_DAYS`/`MIN_REL` and the observed chosen-row bounds):

```kotlin
        val chosen = rows.single { it.alpha == ALPHA && it.tauD == TAU_DAYS && it.minRel == MIN_REL }
        assertTrue("convergence ${chosen.result.worstConvSessions} > budget",
            chosen.result.worstConvSessions <= CONV_BUDGET)
        assertTrue("jitter ${chosen.result.avgJitterPct} > ceiling",
            chosen.result.avgJitterPct <= JITTER_CEIL)
        assertTrue("step ${chosen.result.maxStepPct} exceeds cap",
            chosen.result.maxStepPct <= 5.0f + 0.01f)
```

In `thinPeerSweep_producesFiniteMetrics`, replace the trailing comment with
(substitute the confirmed `MIN_PEERS`/`ATTEN` and the observed chosen-row bounds):

```kotlin
        val chosen = rows.single { it.minPeers == MIN_PEERS && it.atten == ATTEN }
        assertTrue("thin jitter ${chosen.result.avgJitterPct} > ceiling",
            chosen.result.avgJitterPct <= THIN_JITTER_CEIL)
        assertTrue("thin step ${chosen.result.maxStepPct} exceeds cap",
            chosen.result.maxStepPct <= 5.0f + 0.01f)
```

`CONV_BUDGET`, `JITTER_CEIL`, and `THIN_JITTER_CEIL` are literals set from the observed
chosen-row metrics (e.g. observed `worstConvSess` rounded up to the next 5, observed
`avgJitter%` rounded up to one decimal). `maxStep%` is bounded by the chosen
`maxLogStep` cap (`ln(1.10)` → `≤ 10.0%`). Note `ATTEN` may be `null` (`it.atten == null` matches the off row).

- [x] **Step 5: Run the sweep/lock tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.CoefficientConvergenceSimulationTest"`
Expected: PASS. If a bound assertion fails, the bound was set too tight — set it to the
actually-observed value (these document what the chosen parameters deliver; they are
not targets to beat).

- [x] **Step 6: Update the adaptation doc**

In `docs/adaptation/03-coefficient-estimation.md`, update the Layer 4 and Layer 5 descriptions:
- Layer 4: the peer-consensus reference is now an **interpolated** weighted median (blends the straddling peers at the half-weight crossing, so it degrades gracefully when only two or three peers are present rather than hard-selecting one). Note the `minPeers` floor of `MIN_PEERS` and, if attenuation is on, that proposal confidence is scaled down when total peer evidence weight is thin.
- Layer 5: state the tuned `alpha`, recency half-life (`TAU_DAYS` days), and `minRelativeChange`, and the per-session safety cap `maxLogStep = ln(1.10)` (~10%).

- [x] **Step 7: Run the full domain suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.*"`
Expected: PASS (all existing tests + new median/attenuation tests + harness sweeps + locked-value assertion).

- [x] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/CoefficientConvergenceSimulationTest.kt \
        docs/adaptation/03-coefficient-estimation.md
git commit -m "feat: tune coefficient-estimator params from simulation + lock with asserts

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Interpolated weighted median for the peer reference → Tasks 1–2. ✅
- Keep inner data-point median → Task 2 Step 3 (explicit). ✅
- Peer-support confidence attenuation behind a knob, default off → Task 3. ✅
- Re-tune `alpha`/`tauHalfMs`/`minRelativeChange`/`minPeers` via harness, values not pinned in spec → Tasks 4–5. ✅
- `maxLogStep` chosen by harness (broadened to `ln(1.10)`) → Global Constraints + Task 5 Step 4 (step ≤ 10%). ✅
- JUnit harness: realistic gap-derived feedback, sweep+print, then lock with asserts → Task 4 (sweep+print) + Task 5 (asserts). ✅
- Existing 27 tests stay green (class defaults unchanged, tuned values at call site) → Global Constraints + Task 5 Step 3. ✅
- No DB migration; recompute-not-migration rollout note → covered in spec; no code change needed. ✅
- Docs update → Task 5 Step 6. ✅

**Placeholder scan:** The only deliberate placeholders are the chosen parameter values in Task 5 (`ALPHA`, `TAU_DAYS`, `MIN_REL`, `MIN_PEERS`, `ATTEN`, `CONV_BUDGET`, `JITTER_CEIL`) — these are intentionally determined by the harness output and confirmed at the Step 2 checkpoint, which is the whole point of the validate-via-harness approach. Every code step before Task 5 contains complete code.

**Type consistency:** `interpolatedWeightedMedian(List<Pair<Float,Float>>): Float` (Task 1) is consumed in Task 2 and Task 4. `peerSupportFullWeight: Float?` (Task 3) is used consistently in Tasks 4–5. `SimResult`/`simulate(...)` signatures (Task 4) match their call sites in Task 5. `CoefficientComputationInput` fields match the source (`sets`, `sessionTimes`, `exerciseMuscle`, `baselines`, `currentCoefficients`). `WorkoutSet` constructor args match the model (`sessionId`, `exerciseId`, `setNumber`, `targetWeight`, `targetReps`, `actualReps`, `feedback`).
