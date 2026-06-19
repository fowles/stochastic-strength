# Progression Controller Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three-component progression stack (`LastSetAutoregulationHeuristic` + `EstCoefConsensusHeuristic` + `SeedNormalizer`) with one gauge-conserving rolling-window common/differential P controller per muscle.

**Architecture:** A single `ProgressionController` is invoked once per session inside `WorkoutRepository.replayDerivedState`. Each session produces, per trained exercise, a log innovation `e_i = ln(observed1RM / (baseline·coef_i))`; the confidence-weighted common mode drives the muscle **baseline**, the sum-zero differential (applied to all recently-measured exercises in the muscle, weighted so `Σ Δlog c = 0`) drives each **coefficient**. Because the differential is gauge-conserving, the coefficient geomean is pinned for free and the normalizer is deleted. The controller carries a per-exercise recency-decayed EMA of `log(observed1RM)`; since replay is a full left-fold from seed on every session finish, that state is reconstructed each replay and never persisted.

**Tech Stack:** Kotlin, Android, Room (derived state is in-memory via `DerivedStateStore`), JUnit4 (JVM unit tests + instrumented androidTests).

## Global Constraints

- **Design source of truth:** `docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md`. All three open decisions are now resolved:
  - **#1 (differential form):** gauge-conserving rolling window (`RollingConservingPiController` in the sim). Plain-rolling and within-session are NOT shipped.
  - **#2 (estimation vs policy):** ship **pure PI, no separate overload-policy layer**. Progressive overload is intrinsic: the retained signal extractor maps RIR_5_PLUS/RIR_2_4/RIR_0_1 to implied 1RM at `targetReps + 7/3/1`, so successful sets yield positive innovations and the controller climbs to the edge of failure, self-limiting via TOO_HARD's negative innovation. An explicit overload-policy layer (prescribe at target RIR / +x%/week) is a documented FUTURE knob, out of scope here.
  - **#3 (reduction clamp):** **drop it.** Downward moves come organically from negative innovation, bounded by `maxLogStepB`/`maxLogStepC` and EMA smoothing. The validated controller has no clamp.
- **Locked production gains** (match the validated `RollingConservingPiController` defaults exactly): `kB = 0.5f`, `kC = 0.5f`, `emaBeta = 0.5f`, `halfLifeMs = 21L * 24 * 60 * 60 * 1000`, `maxLogStepB = ln(1.15f)`, `maxLogStepC = ln(1.10f)`, `hurtFactor = 0.85f`, `minRelativeChange = 0.002f`.
- **Kept unchanged:** signal extraction (feedback → implied 1RM + confidence), the 1RM load formula (`DefaultProgressionEngine`), the HURT back-off (`×0.85`, worst-wins per muscle), and persistence targets (baseline → `MuscleGroupStrength` + `BaselineHistory`; coefficient → `CoefficientHistory`).
- **No DB migration:** derived state is already in-memory (`DerivedStateStore`), rebuilt from the immutable session log + baseline overrides. No Room schema version bump.
- **Test discipline (from CLAUDE.md):** run the most specific test target after each change; run the full suite at the end. Build: `./gradlew :app:assembleDebug`. Unit: `./gradlew :app:testDebugUnitTest`. Instrumented: `./gradlew :app:connectedAndroidTest` (emulator is typically running — attempt directly).
- **Scope boundary:** `WorkoutRepository.finishSession(sessionId, exerciseReductions)` and `replayDerivedState(reductionsBySession)` keep their signatures, but `exerciseReductions` becomes unused by progression (the reduced-weight sets are already in the set log and flow as negative innovations). Removing those now-dead params cascades into the UI layer and is deferred as a follow-up; log it to `CLAUDE_TODO.md`, do not change caller signatures in this plan.

## File Structure

**Create:**
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractor.kt` — pure helper: `setSignal(WorkoutSet)` + `aggregateSession(List<WorkoutSet>)`, lifted verbatim from `EstCoefConsensusHeuristic`. One responsibility: feedback → (implied 1RM, confidence).
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt` — the `ProgressionController` interface, its input/output types, `ProgressionControllerConfig`, and `RollingConservingProgressionController`. One responsibility: fold a session into baseline + coefficient updates.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractorTest.kt`
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt`
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/FakeProgressionController.kt` — deterministic fake replacing `FakeBaselineHeuristic` for repo-mechanics androidTests.

**Modify:**
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt` — delegate `setSignal`/`aggregateSession` to `SessionSignalExtractor` (Task 1), then deleted entirely (Task 6).
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` — swap three seams for one controller; rewrite `applySessionProgression`.
- `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt` — construct the production controller.
- 7 androidTests that construct `WorkoutRepository(...)` (see Task 3).
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/ControllerReframeSimulationTest.kt` → transformed into the production param-lock test (Task 5).
- `docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md` — mark decisions resolved (Task 6).

**Delete (Task 6):**
- `LastSetAutoregulationHeuristic.kt` (+ `LastSetAutoregulationHeuristicTest.kt`)
- `EstCoefConsensusHeuristic.kt` (+ `EstCoefConsensusHeuristicTest.kt`)
- `SeedNormalizer.kt` (+ `SeedNormalizerTest.kt`)
- `BaselineNormalizer.kt`, `CoefficientHeuristic.kt`, `BaselineHeuristic.kt` (interfaces + their `*ComputationInput`/proposal types)
- `BaselineNormalizationThreshold.kt` (+ `BaselineNormalizationThresholdTest.kt`)
- `CoefficientConvergenceSimulationTest.kt`

---

### Task 1: Extract `SessionSignalExtractor`

Lift the feedback→(implied-1RM, confidence) logic out of `EstCoefConsensusHeuristic` into a standalone pure object so it survives that class's deletion. `EstCoefConsensusHeuristic` delegates to it, keeping all existing tests green.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractor.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractorTest.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`

**Interfaces:**
- Produces:
  - `SessionSignalExtractor.SetSignal(est1RM: Float, confidence: Float, isUpperBound: Boolean)`
  - `SessionSignalExtractor.SessionAggregate(est1RM: Float, sessionConfidence: Float)`
  - `fun SessionSignalExtractor.setSignal(set: WorkoutSet): SetSignal?`
  - `fun SessionSignalExtractor.aggregateSession(sets: List<WorkoutSet>): SessionAggregate?`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractorTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSignalExtractorTest {

    private fun set(weight: Float, reps: Int, fb: SetFeedback?, actual: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1,
            targetWeight = weight, targetReps = reps, actualReps = actual, feedback = fb)

    @Test
    fun rir01_implies_one_rep_in_reserve() {
        val s = SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.RIR_0_1))!!
        assertEquals(DefaultProgressionEngine.toOneRepMax(100f, 6), s.est1RM, 1e-3f)
        assertEquals(0.85f, s.confidence, 1e-6f)
        assertTrue(!s.isUpperBound)
    }

    @Test
    fun hurt_yields_no_signal() {
        assertNull(SessionSignalExtractor.setSignal(set(100f, 5, SetFeedback.HURT)))
    }

    @Test
    fun aggregate_confidence_weights_est1rm() {
        val agg = SessionSignalExtractor.aggregateSession(
            listOf(set(100f, 5, SetFeedback.RIR_2_4), set(100f, 5, SetFeedback.RIR_0_1)),
        )!!
        // both non-upper-bound; weighted mean of the two implied 1RMs by confidence.
        val a = DefaultProgressionEngine.toOneRepMax(100f, 8) // RIR_2_4 -> +3
        val b = DefaultProgressionEngine.toOneRepMax(100f, 6) // RIR_0_1 -> +1
        val expected = (a * 0.7f + b * 0.85f) / (0.7f + 0.85f)
        assertEquals(expected, agg.est1RM, 1e-2f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SessionSignalExtractorTest"`
Expected: FAIL — `SessionSignalExtractor` is unresolved.

- [ ] **Step 3: Create `SessionSignalExtractor` with the lifted logic**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractor.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * Pure feedback → (implied 1RM, confidence) extraction, lifted verbatim from the former
 * EstCoefConsensusHeuristic so it survives that class's removal. A set's RIR bucket maps to an
 * implied 1RM assuming `targetReps + {7,3,1}` reps in reserve; TOO_HARD reads the achieved reps
 * (or, if unknown, an upper bound just under target). HURT carries no load signal.
 */
object SessionSignalExtractor {

    data class SetSignal(val est1RM: Float, val confidence: Float, val isUpperBound: Boolean)

    data class SessionAggregate(val est1RM: Float, val sessionConfidence: Float)

    fun setSignal(set: WorkoutSet): SetSignal? {
        val feedback = set.feedback ?: return null
        return when (feedback) {
            SetFeedback.HURT -> null
            SetFeedback.RIR_5_PLUS -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 7),
                confidence = 0.4f, isUpperBound = false,
            )
            SetFeedback.RIR_2_4 -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 3),
                confidence = 0.7f, isUpperBound = false,
            )
            SetFeedback.RIR_0_1 -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 1),
                confidence = 0.85f, isUpperBound = false,
            )
            SetFeedback.TOO_HARD -> {
                val reps = set.actualReps
                if (reps != null) {
                    SetSignal(
                        est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, reps),
                        confidence = 0.95f, isUpperBound = false,
                    )
                } else {
                    SetSignal(
                        est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, maxOf(1, set.targetReps - 1)),
                        confidence = 0.5f, isUpperBound = true,
                    )
                }
            }
        }
    }

    fun aggregateSession(sets: List<WorkoutSet>): SessionAggregate? {
        val signals = sets.mapNotNull { setSignal(it) }
        if (signals.isEmpty()) return null

        val nonUpperBound = signals.filter { !it.isUpperBound }
        val included = if (nonUpperBound.isEmpty()) {
            signals
        } else {
            val nonBoundMean = nonUpperBound.sumOf { (it.est1RM * it.confidence).toDouble() }
                .toFloat() / nonUpperBound.sumOf { it.confidence.toDouble() }.toFloat()
            signals.filter { sig -> if (!sig.isUpperBound) true else nonBoundMean > sig.est1RM }
        }
        if (included.isEmpty()) return null

        val totalConf = included.sumOf { it.confidence.toDouble() }.toFloat()
        val weighted1RM = included.sumOf { (it.est1RM * it.confidence).toDouble() }.toFloat() / totalConf
        val avgConf = totalConf / included.size
        return SessionAggregate(est1RM = weighted1RM, sessionConfidence = avgConf)
    }
}
```

- [ ] **Step 4: Make `EstCoefConsensusHeuristic` delegate to the extractor**

In `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`, replace the body of `internal fun aggregateSession(...)` (lines 66-90) and `internal fun setSignal(...)` (lines 92-125) with thin delegations, and re-expose the data classes as aliases so existing test references keep compiling:

```kotlin
    data class SetSignal(
        val est1RM: Float,
        val confidence: Float,
        val isUpperBound: Boolean,
    )

    data class SessionAggregate(
        val est1RM: Float,
        val sessionConfidence: Float,
    )

    internal fun aggregateSession(sets: List<WorkoutSet>): SessionAggregate? =
        SessionSignalExtractor.aggregateSession(sets)?.let {
            SessionAggregate(it.est1RM, it.sessionConfidence)
        }

    internal fun setSignal(set: WorkoutSet): SetSignal? =
        SessionSignalExtractor.setSignal(set)?.let {
            SetSignal(it.est1RM, it.confidence, it.isUpperBound)
        }
```

(Leave the rest of `EstCoefConsensusHeuristic` untouched; it still uses its own `SetSignal`/`SessionAggregate` types internally.)

- [ ] **Step 5: Run the new test and the existing heuristic test**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SessionSignalExtractorTest" --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"`
Expected: PASS (both).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractor.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractorTest.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt
git commit -m "refactor: extract SessionSignalExtractor from EstCoefConsensusHeuristic"
```

---

### Task 2: `ProgressionController` + `RollingConservingProgressionController`

The unified controller, ported from the validated `RollingConservingPiController` in the sim, plus the HURT muscle-level back-off. Pure domain; no repo wiring yet.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt`

**Interfaces:**
- Consumes: `MuscleGroup`, `WeightUnit`, `WeightFormatter.round`.
- Produces:
  - `data class ProgressionObservation(exerciseId: Long, muscle: MuscleGroup, est1RM: Float, confidence: Float)`
  - `data class ProgressionStepInput(now: Long, observations: List<ProgressionObservation>, baselines: Map<MuscleGroup, Float>, coefficients: Map<Long, Float>, muscleExercises: Map<MuscleGroup, List<Long>>, hurtMuscles: Set<MuscleGroup>, weightUnit: WeightUnit)`
  - `data class BaselineUpdate(muscleGroup: MuscleGroup, newBaseline: Float, metadata: String?)`
  - `data class CoefficientUpdate(exerciseId: Long, coefficient: Float, metadata: String?)`
  - `data class ProgressionStepOutput(baselineUpdates: List<BaselineUpdate>, coefficientUpdates: List<CoefficientUpdate>)`
  - `interface ProgressionController { val name: String; fun step(input: ProgressionStepInput): ProgressionStepOutput }`
  - `data class ProgressionControllerConfig(kB, kC, emaBeta, halfLifeMs, maxLogStepB, maxLogStepC, hurtFactor, minRelativeChange)` with the locked defaults.
  - `class RollingConservingProgressionController(config: ProgressionControllerConfig = ProgressionControllerConfig()) : ProgressionController`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class ProgressionControllerTest {

    private val m = MuscleGroup.CHEST
    private val unit = WeightUnit.KG

    /** No min-change suppression / clamp interference for the pure-math properties. */
    private fun controller() = RollingConservingProgressionController(
        ProgressionControllerConfig(minRelativeChange = 0f),
    )

    private fun obs(id: Long, est1RM: Float, conf: Float = 0.85f) =
        ProgressionObservation(id, m, est1RM, conf)

    private fun input(
        now: Long, observations: List<ProgressionObservation>,
        baseline: Float, coefs: Map<Long, Float>,
    ) = ProgressionStepInput(
        now = now, observations = observations,
        baselines = mapOf(m to baseline), coefficients = coefs,
        muscleExercises = mapOf(m to coefs.keys.toList()),
        hurtMuscles = emptySet(), weightUnit = unit,
    )

    @Test
    fun allEasy_raisesBaseline_leavesCoefsFlat() {
        // Two exercises, identical innovation => common = that innovation, differential = 0.
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 0.5f)
        // est1RM 10% above prescription for both.
        val o = listOf(
            obs(1, baseline * 1.0f * 1.10f),
            obs(2, baseline * 0.5f * 1.10f),
        )
        val out = controller().step(input(1000, o, baseline, coefs))
        assertTrue("baseline should rise", out.baselineUpdates.single().newBaseline > baseline)
        assertTrue("coefficients should not move", out.coefficientUpdates.isEmpty())
    }

    @Test
    fun easyVsHard_sameAverage_baselineFlat_coefsDiverge() {
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 1.0f)
        // id1 reads 10% high, id2 reads 10% low => common ~ 0.
        val o = listOf(
            obs(1, baseline * 1.0f * 1.10f),
            obs(2, baseline * 1.0f * (1f / 1.10f)),
        )
        val out = controller().step(input(1000, o, baseline, coefs))
        assertTrue("baseline should be ~flat", out.baselineUpdates.isEmpty())
        val byId = out.coefficientUpdates.associateBy { it.exerciseId }
        assertTrue("id1 coef up", byId.getValue(1).coefficient > 1.0f)
        assertTrue("id2 coef down", byId.getValue(2).coefficient < 1.0f)
    }

    @Test
    fun differential_conservesGeomean_acrossSequence() {
        val c = controller()
        var coefs = mapOf(1L to 1.0f, 2L to 0.5f, 3L to 0.8f)
        val baseline = 100f
        val ids = coefs.keys
        var t = 0L
        repeat(5) { i ->
            t += 1000
            val o = ids.map { id ->
                // arbitrary, differing innovations each session
                obs(id, baseline * coefs.getValue(id) * (1f + 0.1f * ((id + i) % 3 - 1)))
            }
            val out = c.step(input(t, o, baseline, coefs))
            coefs = coefs.toMutableMap().apply {
                out.coefficientUpdates.forEach { this[it.exerciseId] = it.coefficient }
            }
        }
        val seed = mapOf(1L to 1.0f, 2L to 0.5f, 3L to 0.8f)
        val geomeanRatio = exp(ids.map { ln((coefs.getValue(it) / seed.getValue(it)).toDouble()) }.average())
        assertEquals("coefficient geomean must be conserved", 1.0, geomeanRatio, 1e-3)
    }

    @Test
    fun hurt_backsOffBaseline_andSkipsCoefficients() {
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f)
        val in0 = input(1000, listOf(obs(1, baseline * 1.5f)), baseline, coefs)
            .copy(hurtMuscles = setOf(m))
        val out = controller().step(in0)
        assertEquals(WeightFormatter.round(baseline * 0.85f, unit), out.baselineUpdates.single().newBaseline, 1e-3f)
        assertTrue("hurt suppresses coefficient moves", out.coefficientUpdates.isEmpty())
    }

    @Test
    fun singleExerciseSession_poolsRecentWindow_untrainedUntouched() {
        val c = controller()
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 0.5f)
        // Session A: both measured, establishing the pool.
        c.step(input(1000, listOf(obs(1, baseline * 1.0f), obs(2, baseline * 0.5f)), baseline, coefs))
        // Session B: only id1 trained, reads high. Pool still includes id2 (recent).
        val out = c.step(input(2000, listOf(obs(1, baseline * 1.0f * 1.10f)), baseline, coefs))
        val touched = out.coefficientUpdates.map { it.exerciseId }.toSet()
        assertTrue("trained exercise corrects", 1L in touched)
        // id2 carries near-zero recency-weighted differential vs id1; its move is negligible.
        val id2 = out.coefficientUpdates.firstOrNull { it.exerciseId == 2L }
        assertTrue("untrained barely moves", id2 == null || kotlin.math.abs(id2.coefficient - 0.5f) < 0.5f * 0.01f)
    }

    @Test
    fun midSetDrop_negativeInnovation_movesDownNeverUp() {
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 1.0f)
        // id1 failed (observed 1RM below prescription); id2 on-target.
        val o = listOf(obs(1, baseline * 1.0f * 0.85f, conf = 0.95f), obs(2, baseline * 1.0f))
        val out = controller().step(input(1000, o, baseline, coefs))
        val byId = out.coefficientUpdates.associateBy { it.exerciseId }
        assertTrue("failed exercise coef moves down", byId.getValue(1).coefficient < 1.0f)
        out.baselineUpdates.forEach {
            assertTrue("baseline never rises on a net-negative session", it.newBaseline <= baseline)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: FAIL — `ProgressionController`/`RollingConservingProgressionController` unresolved.

- [ ] **Step 3: Implement `ProgressionController.kt`**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** One trained exercise's aggregated session signal (from [SessionSignalExtractor]). */
data class ProgressionObservation(
    val exerciseId: Long,
    val muscle: MuscleGroup,
    val est1RM: Float,
    val confidence: Float,
)

data class ProgressionStepInput(
    val now: Long,
    val observations: List<ProgressionObservation>,
    val baselines: Map<MuscleGroup, Float>,
    val coefficients: Map<Long, Float>,
    /** Every loaded (coefficient > 0) exercise id per muscle — the rolling pool. */
    val muscleExercises: Map<MuscleGroup, List<Long>>,
    /** Muscles with a HURT set this session — baseline backs off, overriding the PI update. */
    val hurtMuscles: Set<MuscleGroup>,
    val weightUnit: WeightUnit,
)

data class BaselineUpdate(val muscleGroup: MuscleGroup, val newBaseline: Float, val metadata: String?)
data class CoefficientUpdate(val exerciseId: Long, val coefficient: Float, val metadata: String?)
data class ProgressionStepOutput(
    val baselineUpdates: List<BaselineUpdate>,
    val coefficientUpdates: List<CoefficientUpdate>,
)

interface ProgressionController {
    val name: String
    /** Fold one session into baseline + coefficient updates, advancing internal per-exercise state. */
    fun step(input: ProgressionStepInput): ProgressionStepOutput
}

data class ProgressionControllerConfig(
    val kB: Float = 0.5f,
    val kC: Float = 0.5f,
    val emaBeta: Float = 0.5f,
    val halfLifeMs: Long = 21L * 24 * 60 * 60 * 1000,
    val maxLogStepB: Float = ln(1.15f),
    val maxLogStepC: Float = ln(1.10f),
    val hurtFactor: Float = 0.85f,
    val minRelativeChange: Float = 0.002f,
)

/**
 * Gauge-conserving rolling-window common/differential P controller, one loop per muscle. Ported
 * from the validated `RollingConservingPiController` simulation prototype.
 *
 * Per session: advance each observed exercise's recency-decayed EMA of `log(observed1RM)`. For each
 * trained muscle, pool every loaded exercise with a recent measurement (weight = recency × confidence)
 * and split the innovations `e_i = ln(emaEst_i) − ln(baseline·coef_i)`:
 *   - common mode (weighted mean) → baseline;
 *   - differential `e_i − common`, applied to ALL pooled exercises scaled by `w_i / max w`, so the
 *     log-updates sum to zero → coefficient geomean (the gauge) is conserved with no normalizer.
 * HURT overrides: the muscle's baseline backs off by [hurtFactor] and no coefficient moves are emitted.
 */
class RollingConservingProgressionController(
    private val config: ProgressionControllerConfig = ProgressionControllerConfig(),
) : ProgressionController {

    override val name: String = "rolling-conserving-pi"

    private val emaLogEst = mutableMapOf<Long, Float>()
    private val lastConf = mutableMapOf<Long, Float>()
    private val lastTime = mutableMapOf<Long, Long>()
    private val ln2 = ln(2.0)

    override fun step(input: ProgressionStepInput): ProgressionStepOutput {
        val baselineUpdates = mutableListOf<BaselineUpdate>()
        val coefficientUpdates = mutableListOf<CoefficientUpdate>()

        for (o in input.observations) {
            if (o.est1RM <= 0f) continue
            val le = ln(o.est1RM)
            emaLogEst[o.exerciseId] =
                emaLogEst[o.exerciseId]?.let { (1f - config.emaBeta) * it + config.emaBeta * le } ?: le
            lastConf[o.exerciseId] = o.confidence
            lastTime[o.exerciseId] = input.now
        }

        val trainedMuscles = input.observations.map { it.muscle }.toSet() + input.hurtMuscles
        for (m in trainedMuscles) {
            val b = input.baselines[m] ?: continue
            if (b <= 0f) continue

            if (m in input.hurtMuscles) {
                val bNew = WeightFormatter.round(b * config.hurtFactor, input.weightUnit)
                if (bNew != b && bNew > 0f) baselineUpdates.add(BaselineUpdate(m, bNew, "hurt"))
                continue
            }

            val pooled = input.muscleExercises[m].orEmpty().mapNotNull { id ->
                val le = emaLogEst[id] ?: return@mapNotNull null
                val c = input.coefficients[id] ?: return@mapNotNull null
                if (c <= 0f) return@mapNotNull null
                val age = (input.now - (lastTime[id] ?: input.now)).coerceAtLeast(0L)
                val w = exp(-age * ln2 / config.halfLifeMs).toFloat() * (lastConf[id] ?: 0f)
                if (w <= 1e-6f) return@mapNotNull null
                Triple(id, le - ln(b * c), w)
            }
            if (pooled.isEmpty()) continue

            val wsum = pooled.sumOf { it.third.toDouble() }.toFloat()
            val common = if (wsum > 0f) pooled.sumOf { (it.second * it.third).toDouble() }.toFloat() / wsum else 0f

            val dLogB = (config.kB * common).coerceIn(-config.maxLogStepB, config.maxLogStepB)
            val bNew = WeightFormatter.round(b * exp(dLogB), input.weightUnit)
            if (bNew != b && bNew > 0f) {
                baselineUpdates.add(BaselineUpdate(m, bNew, "pi:n=${pooled.size},common=${fmt(common)}"))
            }

            val maxW = pooled.maxOf { it.third }
            for ((id, e, w) in pooled) {
                val gain = w / maxW // freshest gets full K_c; staler proportionally less. Preserves sum-zero.
                val dLogC = (config.kC * gain * (e - common)).coerceIn(-config.maxLogStepC, config.maxLogStepC)
                val cOld = input.coefficients.getValue(id)
                val cNew = cOld * exp(dLogC)
                if (abs(cNew - cOld) < config.minRelativeChange * cOld) continue
                coefficientUpdates.add(CoefficientUpdate(id, cNew, "pi:d=${fmt(e - common)},w=${fmt(gain)}"))
            }
        }
        return ProgressionStepOutput(baselineUpdates, coefficientUpdates)
    }

    private fun fmt(v: Float) = "%.4f".format(Locale.ROOT, v)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: PASS (all 6).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt
git commit -m "feat: add gauge-conserving rolling-window progression controller"
```

---

### Task 3: Wire the controller into `WorkoutRepository`

Replace the three injected seams with one controller factory and rewrite `applySessionProgression` to invoke the controller once per session. Replace `FakeBaselineHeuristic` with `FakeProgressionController` and update the 7 androidTests that build the repo. The old heuristic/normalizer classes still exist (deleted in Task 6) but are no longer referenced by the repo.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshot.kt` (trim dead filter methods/fields)
- Delete: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshotTest.kt` (tests only the removed methods)
- Create: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/FakeProgressionController.kt`
- Modify (construction sites, all in androidTest):
  - `domain/WorkoutRepositoryTest.kt`
  - `domain/WorkoutRepositoryDebugTest.kt`
  - `domain/ReplayDerivedStateTest.kt`
  - `domain/LiveInputWritesTest.kt`
  - `domain/FatigueNoDownwardBiasReplayTest.kt`
  - `domain/DerivedStateBackfillTest.kt`
  - `ui/workout/WorkoutSessionControllerTest.kt`
- Delete: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/FakeBaselineHeuristic.kt`

**Interfaces:**
- Consumes (from Task 1 & 2): `SessionSignalExtractor.aggregateSession`, `ProgressionController`, `ProgressionStepInput`, `ProgressionObservation`, `ProgressionStepOutput`, `BaselineUpdate`, `CoefficientUpdate`.
- Produces: `WorkoutRepository(db, derivedState, progressionEngine, progressionControllerFactory: () -> ProgressionController)`; `FakeProgressionController(upFactor: Float = 1.05f) : ProgressionController`.

- [ ] **Step 1: Write the failing fake + a repo-mechanics expectation**

Create `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/FakeProgressionController.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

/**
 * Deterministic test double: every trained muscle's baseline moves by [upFactor]; coefficients are
 * left untouched. Replaces the former FakeBaselineHeuristic for repo-mechanics androidTests, which
 * assert that replay writes the expected derived rows — not the controller's math.
 */
class FakeProgressionController(private val upFactor: Float = 1.05f) : ProgressionController {
    override val name: String = "fake-progression"
    override fun step(input: ProgressionStepInput): ProgressionStepOutput {
        val muscles = input.observations.map { it.muscle }.toSet()
        val baselineUpdates = muscles.mapNotNull { m ->
            val b = input.baselines[m] ?: return@mapNotNull null
            BaselineUpdate(m, WeightFormatter.round(b * upFactor, input.weightUnit), "fake")
        }
        return ProgressionStepOutput(baselineUpdates, emptyList())
    }
}
```

- [ ] **Step 2: Run the existing repo androidTest to confirm the current baseline (pre-change)**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: PASS on the current `FakeBaselineHeuristic` wiring (establishes the green starting point before the constructor change makes it fail to compile).

- [ ] **Step 3: Rewrite `WorkoutRepository` constructor + progression body**

In `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`:

Replace the constructor (lines 26-33):

```kotlin
class WorkoutRepository(
    private val db: AppDatabase,
    val derivedState: DerivedStateStore = DerivedStateStore(),
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val progressionControllerFactory: () -> ProgressionController,
) {
    private val replayMutex = Mutex()
```

Replace `applySessionProgression` and its three private helpers (`buildBaselineComputationInput`, `applyBaselineProposal`, `recomputeCoefficients`, `applyBaselineNormalization`, lines 82-278) with the controller-driven version and two persistence helpers:

```kotlin
    private suspend fun applySessionProgression(
        sessionId: Long,
        snapshot: ReplaySnapshot,
        asOf: Long,
        controller: ProgressionController,
        scratch: MutableDerivedState,
    ) {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        if (sets.isEmpty()) return

        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val exerciseById = db.exerciseDao().getByIds(exerciseIds).associateBy { it.id }
        val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
        val sessionReps = sets.firstOrNull { exerciseById[it.exerciseId]?.isTimed != true }?.targetReps ?: 5
        val exerciseMuscle = snapshot.exerciseMuscle

        val observations = sets.groupBy { it.exerciseId }.mapNotNull { (id, exSets) ->
            val muscle = exerciseMuscle[id] ?: return@mapNotNull null
            if ((snapshot.currentCoefficients[id] ?: 0f) <= 0f) return@mapNotNull null
            SessionSignalExtractor.aggregateSession(exSets)?.let {
                ProgressionObservation(id, muscle, it.est1RM, it.sessionConfidence)
            }
        }
        val hurtMuscles = sets.filter { it.feedback == io.github.fowles.stochastic_strength.data.model.SetFeedback.HURT }
            .mapNotNull { exerciseMuscle[it.exerciseId] }.toSet()
        val muscleExercises = snapshot.currentCoefficients.filterValues { it > 0f }.keys
            .mapNotNull { id -> exerciseMuscle[id]?.let { it to id } }
            .groupBy({ it.first }, { it.second })

        val output = controller.step(
            ProgressionStepInput(
                now = asOf,
                observations = observations,
                baselines = snapshot.currentBaselines.toMap(),
                coefficients = snapshot.currentCoefficients.toMap(),
                muscleExercises = muscleExercises,
                hurtMuscles = hurtMuscles,
                weightUnit = weightUnit,
            ),
        )

        val setsByMuscle = sets.groupBy { exerciseMuscle[it.exerciseId] }
        for (update in output.baselineUpdates) {
            writeBaselineUpdate(update, sessionId, snapshot, sessionReps, setsByMuscle, asOf, controller.name, scratch)
        }
        writeCoefficientUpdates(output.coefficientUpdates, snapshot, asOf, controller.name, scratch)
    }

    private fun writeBaselineUpdate(
        update: BaselineUpdate,
        sessionId: Long,
        snapshot: ReplaySnapshot,
        sessionReps: Int,
        setsByMuscle: Map<MuscleGroup?, List<WorkoutSet>>,
        asOf: Long,
        heuristicName: String,
        scratch: MutableDerivedState,
    ) {
        val current = snapshot.currentBaselines[update.muscleGroup] ?: return
        val rounded = update.newBaseline
        if (rounded <= 0f || rounded == current) return
        scratch.upsertMuscleGroupStrength(
            MuscleGroupStrength(muscleGroup = update.muscleGroup, baselineWeight = rounded),
        )
        snapshot.progressionBaselines[sessionId to update.muscleGroup] = current
        snapshot.currentBaselines[update.muscleGroup] = rounded
        val muscleFeedbacks = setsByMuscle[update.muscleGroup].orEmpty().mapNotNull { it.feedback }
        val historyRow = BaselineHistory(
            sessionId = sessionId,
            muscleGroup = update.muscleGroup,
            previousBaseline = current,
            newBaseline = rounded,
            changeReason = BaselineChangeReason.PROGRESSION,
            feedbacks = muscleFeedbacks.joinToString(",") { it.name }.ifEmpty { null },
            sessionReps = sessionReps,
            minReductionFraction = null,
            timestamp = asOf,
            heuristicName = heuristicName,
            heuristicMetadata = update.metadata,
        )
        scratch.insertBaselineHistory(historyRow)
        snapshot.baselineHistoryByMuscle.getOrPut(update.muscleGroup) { mutableListOf() }.add(historyRow)
    }

    private fun writeCoefficientUpdates(
        updates: List<CoefficientUpdate>,
        snapshot: ReplaySnapshot,
        asOf: Long,
        heuristicName: String,
        scratch: MutableDerivedState,
    ) {
        if (updates.isEmpty()) return
        val latestByExercise = scratch.coefficientHistoryLatestPerExercise().associateBy { it.exerciseId }
        for (update in updates) {
            val row = CoefficientHistory(
                exerciseId = update.exerciseId,
                previousCoefficient = latestByExercise[update.exerciseId]?.coefficient
                    ?: snapshot.seedCoefficients[update.exerciseId],
                coefficient = update.coefficient,
                heuristicName = heuristicName,
                heuristicMetadata = update.metadata,
                computedAt = asOf,
            )
            scratch.insertCoefficientHistory(row)
            snapshot.currentCoefficients[update.exerciseId] = update.coefficient
        }
    }
```

Update the call site in `replayDerivedState` (the loop near line 319-344): instantiate the controller once, pass it in, and drop the reductions threading. Replace:

```kotlin
    suspend fun replayDerivedState(
        reductionsBySession: Map<Long, Map<Long, Float>> = emptyMap(),
    ) = replayMutex.withLock {
        derivedState.rebuild { scratch ->
            val snapshot = ReplaySnapshot.loadStaticFromDb(db)
            val controller = progressionControllerFactory()
            val initials = db.baselineOverrideDao().getInitials()
```

…and change the `applySessionProgression` call inside the session loop to:

```kotlin
                applySessionProgression(
                    session.id,
                    snapshot,
                    asOf = session.endTime!!,
                    controller = controller,
                    scratch = scratch,
                )
```

(`reductionsBySession` stays in the signature per the Global Constraints scope boundary, but is no longer read. Add `// reductionsBySession is retained for API compatibility; mid-set drops now flow through the set log as negative innovations.` above the loop, and a `CLAUDE_TODO.md` entry to remove the dead param.)

Remove now-unused imports (`BaselineNormalizationThreshold` usage is gone; `BaselineComputationInput`/proposal references are gone). Keep imports still used: `BaselineHistory`, `CoefficientHistory`, `MuscleGroupStrength`, `BaselineChangeReason`, `WorkoutSet`, `MuscleGroup`, `WeightUnit`.

- [ ] **Step 4: Trim `ReplaySnapshot` and delete its obsolete test**

The deleted repo methods were the only callers of `ReplaySnapshot.filteredCoefficientInput`/`filteredNormalizationInput`, which return the to-be-deleted `*ComputationInput` types. Remove both methods and the three fields only they used (`allSets`, `allSessionTimes`, `allExercises`), and simplify `loadStaticFromDb` (it no longer needs the wasteful `workoutSetDao().getAll()`). Replace `app/src/main/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshot.kt` with:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup

/**
 * Holds the evolving derived state for one full replay of [WorkoutRepository.replayDerivedState].
 * Static fields are loaded once from the DB; dynamic maps are mutated as each session's step writes
 * its derived rows. The progression controller reads [currentBaselines]/[currentCoefficients] and the
 * static [exerciseMuscle]/[seedCoefficients]; it does not consume per-session set windows, so the
 * former set/session caches and `filtered*Input` projections are gone.
 */
class ReplaySnapshot(
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val seedCoefficients: Map<Long, Float>,
) {
    val currentCoefficients: MutableMap<Long, Float> = seedCoefficients.toMutableMap()
    val currentBaselines: MutableMap<MuscleGroup, Float> = mutableMapOf()
    val progressionBaselines: MutableMap<Pair<Long, MuscleGroup>, Float> = mutableMapOf()
    val baselineHistoryByMuscle: MutableMap<MuscleGroup, MutableList<BaselineHistory>> = mutableMapOf()

    companion object {
        /** Reads static (input-only) data from the DB once for a full replay run. */
        suspend fun loadStaticFromDb(db: AppDatabase): ReplaySnapshot {
            val allExercises = db.exerciseDao().getAll()
            val activeExercises = db.exerciseDao().getActive()
            val exerciseMuscle = allExercises.associate { it.id to it.primaryMuscle }
            val seedCoefficients = activeExercises.associate { ex ->
                ex.id to (ExerciseCoefficients.get(ex) ?: 0f)
            }
            return ReplaySnapshot(exerciseMuscle = exerciseMuscle, seedCoefficients = seedCoefficients)
        }
    }
}
```

Then `git rm app/src/test/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshotTest.kt` (it asserts only `filteredCoefficientInput`; the per-session set read it covered now lives in `applySessionProgression` via `getSetsForSession`).

Note: `progressionBaselines` and `baselineHistoryByMuscle` are still written (by `writeBaselineUpdate` and the INITIAL/OVERRIDE bookkeeping in `replayDerivedState`) but no longer read by any controller. They are harmless in-replay scaffolding; leave them and add a `CLAUDE_TODO.md` line noting they are now write-only and removable in a later cleanup.

- [ ] **Step 5: Add the `CLAUDE_TODO.md` note**

Append to `CLAUDE_TODO.md` (create if missing):

```markdown
- Remove the now-dead `exerciseReductions`/`reductionsBySession` params from
  `WorkoutRepository.finishSession` / `replayDerivedState` and their UI callers
  (mid-set drops now flow through the set log as negative innovations; the reduction
  clamp was dropped with the PI controller).
```

- [ ] **Step 6: Update the 7 androidTest construction sites and delete the old fake**

In each listed androidTest, replace the `WorkoutRepository(...)` construction that passed `baselineHeuristic = FakeBaselineHeuristic()` (and any `heuristic = …`, `normalizer = …`) with the factory form, and delete `FakeBaselineHeuristic.kt`.

Pattern — replace:

```kotlin
        repository = WorkoutRepository(db, baselineHeuristic = FakeBaselineHeuristic())
```

with:

```kotlin
        repository = WorkoutRepository(db, progressionControllerFactory = { FakeProgressionController() })
```

For sites that passed real heuristics to exercise the real path (e.g. `FatigueNoDownwardBiasReplayTest`, which used real `LastSetAutoregulationHeuristic` + `EstCoefConsensusHeuristic` + `SeedNormalizer`), use the real controller:

```kotlin
        repository = WorkoutRepository(
            db,
            progressionControllerFactory = { RollingConservingProgressionController() },
        )
```

For `WorkoutRepositoryTest`'s normalizer-specific test (the one that built `WorkoutRepository(db, normalizer = normalizer, baselineHeuristic = Fakeln())` to assert normalization behavior): that test targets a component being deleted. Replace it with an assertion that the controller conserves the coefficient gauge across a replay — or, if that duplicates `ProgressionControllerTest.differential_conservesGeomean_acrossSequence`, delete the test method and note it in the commit. Do not leave a reference to `normalizer`.

Delete: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/FakeBaselineHeuristic.kt`.

- [ ] **Step 7: Build, then run the affected androidTests**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest" --tests "io.github.fowles.stochastic_strength.domain.ReplayDerivedStateTest" --tests "io.github.fowles.stochastic_strength.domain.FatigueNoDownwardBiasReplayTest" --tests "io.github.fowles.stochastic_strength.domain.LiveInputWritesTest" --tests "io.github.fowles.stochastic_strength.domain.DerivedStateBackfillTest" --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryDebugTest" --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: PASS. If `FatigueNoDownwardBiasReplayTest` asserts a specific downward-bias number tied to the old heuristic, re-derive the expectation under the controller (the property — no spurious downward drift from high-rep fatigue — still holds because innovation is symmetric in log space) and update the assertion.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: drive WorkoutRepository progression through ProgressionController"
```

---

### Task 4: Wire the production controller in `StochasticStrengthApp`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt`

**Interfaces:**
- Consumes: `RollingConservingProgressionController`, `ProgressionControllerConfig` (defaults are the locked production gains).

- [ ] **Step 1: Replace the repository construction**

In `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt`, replace the `workoutRepository` lazy (lines 35-49) with:

```kotlin
    val workoutRepository: WorkoutRepository by lazy {
        WorkoutRepository(
            database,
            derivedState = derivedStateStore,
            progressionControllerFactory = { RollingConservingProgressionController() },
        )
    }
```

Update imports: remove `LastSetAutoregulationHeuristic`, `EstCoefConsensusHeuristic`, `SeedNormalizer`; add `RollingConservingProgressionController`.

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt
git commit -m "feat: wire RollingConservingProgressionController into the app"
```

---

### Task 5: Port the simulation as a production param-lock test

Transform the exploration harness `ControllerReframeSimulationTest` into a focused lock on the production `RollingConservingProgressionController`: drop the rejected variants (current stack, within-session, plain-rolling, anchor), point signal extraction at `SessionSignalExtractor`, and assert the chosen gains hold the convergence/jitter/accuracy/gauge ceilings. Mirrors the `assertTrue`-on-chosen-config pattern in `CoefficientConvergenceSimulationTest`.

**Files:**
- Modify → rename: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ControllerReframeSimulationTest.kt` → `ProgressionControllerSimulationTest.kt` (class `ProgressionControllerSimulationTest`).

**Interfaces:**
- Consumes: `RollingConservingProgressionController`, `ProgressionStepInput`, `ProgressionObservation`, `SessionSignalExtractor`, `WorkoutGenerator`, `ExerciseLibrary`, `ExerciseCoefficients`, `DefaultProgressionEngine`.

- [ ] **Step 1: Rewrite the harness to drive the production controller**

Replace the file with a single realistic harness (keep the real-library / real-planner / mid-set-drop generator from `simulateRealistic`, and the strengthening-creep variant) that uses **only** `RollingConservingProgressionController`. The per-session step becomes:

```kotlin
        val observations = thisSessionSets.groupBy { it.exerciseId }.mapNotNull { (id, sets) ->
            SessionSignalExtractor.aggregateSession(sets)?.let {
                ProgressionObservation(id, exMuscle.getValue(id), it.est1RM, it.sessionConfidence)
            }
        }
        val hurtMuscles = thisSessionSets
            .filter { it.feedback == SetFeedback.HURT }
            .mapNotNull { exMuscle[it.exerciseId] }.toSet()
        val out = controller.step(
            ProgressionStepInput(
                now = t, observations = observations,
                baselines = baselines.toMap(), coefficients = coefs.toMap(),
                muscleExercises = muscleExercises, hurtMuscles = hurtMuscles, weightUnit = unit,
            ),
        )
        out.baselineUpdates.forEach { baselines[it.muscleGroup] = it.newBaseline }
        out.coefficientUpdates.forEach { coefs[it.exerciseId] = it.coefficient }
```

Keep the existing metric machinery (`RMetrics`, `trainedEndErr`, `jitter`, `coefInflation`, `baselineGaugeErr`, the seeds list, the report writer). Delete the `RStack` enum, `MultiMusclePiController`, `RollingWindowPiController`, the embedded `RollingConservingPiController`, `CommonDiffPiController`, the `currentStackStep` / `piStackStep` / `stacks` machinery, the `Profile` A/B in `reframe_abComparison_broadened`, and all references to `LastSetAutoregulationHeuristic` / `EstCoefConsensusHeuristic` / `SeedNormalizer` / `BaselineNormalizationThreshold`.

- [ ] **Step 2: Add the locked-config asserts**

Add a test method that runs the realistic harness at seed factor 0.8 (climbing from below) over 120+30 sessions, averages over the seeds, and asserts the ceilings the design doc's findings table establishes for the gauge-conserving form (with headroom):

```kotlin
    @Test
    fun production_gains_hold_convergence_and_gauge_ceilings() {
        val rows = seeds.map { simulateRealistic(0.8f, it, sessions = 120, tail = 30) }
        fun avg(sel: (RMetrics) -> Float) = rows.map(sel).average().toFloat()
        val convSess = rows.map { it.convSessions }.average()
        rows.forEach { assertTrue("non-finite metric: $it", metricsFinite(it)) }

        assertTrue("convergence ${convSess} > budget", convSess <= 8.0)            // doc: ~3
        assertTrue("trainedErr ${avg { it.trainedEndErr }} > ceiling", avg { it.trainedEndErr } <= 4.0f)  // doc: ~2.3
        assertTrue("jitter ${avg { it.jitter }} > ceiling", avg { it.jitter } <= 1.0f)                    // doc: ~0.5
    }

    @Test
    fun production_gains_conserve_gauge_under_strengthening() {
        for (growth in listOf(0.0f, 0.002f, 0.004f)) {
            val rows = seeds.map { simulateRealistic(1.0f, it, sessions = 120, tail = 30, growthPerSession = growth) }
            val infl = rows.map { it.coefInflation }.average()
            assertTrue("coefInflation $infl drifted at growth=$growth", infl in 0.97..1.03) // doc: ~1.00
        }
    }
```

(`metricsFinite` and `simulateRealistic` are retained from the original file, with the controller-driven step swapped in and the `stack` parameter removed.)

- [ ] **Step 3: Run the simulation lock**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerSimulationTest"`
Expected: PASS. If a ceiling is violated, do NOT loosen it blindly — confirm the production controller matches the validated prototype (gains, EMA, recency, clamps) before adjusting; a violation means the port diverged from what was validated.

- [ ] **Step 4: Commit**

```bash
git rm app/src/test/java/io/github/fowles/stochastic_strength/domain/ControllerReframeSimulationTest.kt
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerSimulationTest.kt
git commit -m "test: lock production progression-controller gains via realistic simulation"
```

---

### Task 6: Remove the dead three-component stack

Delete the replaced classes, their now-orphaned interfaces and input/proposal types, their unit tests, and the threshold helper. Mark the design doc's open decisions resolved. This task is complete only when the full unit + instrumented suites are green with zero references to the removed symbols.

**Files:**
- Delete: `LastSetAutoregulationHeuristic.kt`, `LastSetAutoregulationHeuristicTest.kt`
- Delete: `EstCoefConsensusHeuristic.kt`, `EstCoefConsensusHeuristicTest.kt`
- Delete: `SeedNormalizer.kt`, `SeedNormalizerTest.kt`
- Delete: `BaselineNormalizer.kt`, `CoefficientHeuristic.kt`, `BaselineHeuristic.kt`
- Delete: `BaselineNormalizationThreshold.kt`, `BaselineNormalizationThresholdTest.kt`
- Modify: `docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md`

- [ ] **Step 1: Confirm no remaining references**

Run:
```bash
rg -n "LastSetAutoregulationHeuristic|EstCoefConsensusHeuristic|SeedNormalizer|BaselineNormalizer|CoefficientHeuristic|BaselineHeuristic|BaselineComputationInput|CoefficientComputationInput|BaselineNormalizationInput|BaselineNormalizationProposal|BaselineProposal|CoefficientResult|ExerciseCoefficientSnapshot|BaselineNormalizationThreshold" app/src
```
Expected: only matches inside the files being deleted in this task. If any live file (e.g. `MuscleBaselineDetailViewModel.kt`, which references the `+1/+3/+7` offsets in a comment) still references a deleted symbol in code, update it first — for comment-only references, reword to point at `SessionSignalExtractor`.

- [ ] **Step 2: Delete the files**

```bash
git rm \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristic.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/LastSetAutoregulationHeuristicTest.kt \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/SeedNormalizer.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/SeedNormalizerTest.kt \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizer.kt \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientHeuristic.kt \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineHeuristic.kt \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThreshold.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThresholdTest.kt
```

Note: `BaselineHistory.changeReason = BaselineChangeReason.NORMALIZATION` is no longer produced. Leave the enum value in place (historical rows may exist / UI may switch on it); do not remove the enum constant in this plan.

- [ ] **Step 3: Mark the design doc decisions resolved**

In `docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md`, under "## Open decisions", update #2 and #3 from open to resolved:
- #2 → "RESOLVED: ship pure PI; progressive overload is intrinsic to the retained signal extractor's `+7/+3/+1` rep-offset map (successful sets yield positive innovations; the loop climbs to the failure edge and self-limits via TOO_HARD). No separate overload-policy layer; an explicit target-RIR / +x%/week layer is a documented future knob."
- #3 → "RESOLVED: dropped. Downward moves come from negative innovation, bounded by the log-step caps and EMA; the validated controller has no clamp."
Update the top-of-file Status line to "Design — implemented" (or similar).

- [ ] **Step 4: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 5: Run the full instrumented suite**

Run: `./gradlew :app:connectedAndroidTest`
Expected: all green. (If no device is attached, report that the instrumented run was skipped rather than claiming it passed.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: remove the three-component progression stack (replaced by ProgressionController)"
```

---

## Self-Review Notes

- **Spec coverage:** Migration-path steps 1-5 map to Tasks 1, 2-4, 5, (replay-equivalence handled via the simulation lock + repo-mechanics androidTests rather than a permanent dual-path flag — see below), 6. The design's "Testing" bullets (mode split, gauge conservation incl. under rising baseline, mid-set drop, HURT, single-exercise session, replay determinism, simulation locks, regression) are covered by `ProgressionControllerTest` (Task 2) + `ProgressionControllerSimulationTest` (Task 5) + the repo androidTests (Task 3) + the full suites (Task 6).
- **Dual-path A/B flag (migration step 2) — deliberately not built.** The doc floats keeping the old stack behind a flag for replay A/B. That permanent seam is heavier than warranted for a single-user app whose derived state is recomputed from the immutable log: the simulation already performs the current-vs-PI A/B (its job is done), and the repo-mechanics androidTests guard replay correctness. The old classes are kept live through Task 5 (so nothing breaks mid-stream) and removed only in Task 6 after both suites are green.
- **Determinism/replay:** no persisted controller state needed — `replayDerivedState` is a full left-fold from seed on every session finish, so the per-exercise EMA is reconstructed each replay (design doc "Determinism / replay").
