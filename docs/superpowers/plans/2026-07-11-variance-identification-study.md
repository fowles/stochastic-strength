# Variance/Covariance Identification Study — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an analysis-only, test-tree study that identifies, from the real training history, where session-to-session variance actually lives — by CV-scoring concrete candidate variance structures against baselines and reporting which the data prefers.

**Architecture:** One capture primitive replays the real history under a config and emits the per-set one-step-ahead prediction stream (`ScoredSet`). Every analysis — baseline/day-effect/Student-t scoring, obs-noise and τ sweeps, residual decomposition — consumes that one captured stream. Candidates that are pure config changes (obs-noise, τ, the `procNoise ×16` reference) reuse the baseline scorer with a different config; the two genuine model extensions (session day-effect, Student-t likelihood) live entirely as test-tree scorers. Zero production code is modified.

**Tech Stack:** Kotlin, JUnit4, JVM unit tests (`./gradlew :app:testDebugUnitTest`). Reuses existing `domain/progression` machinery (`BeliefUpdater`, `MuscleStrengthProjector`, `SessionProgressionStepper`, `SetObservation`, `PredictiveDensity`, `NormalCdf`) and `domain/backtest` harness (`BacktestHarness`, `RecalibrationHarness`).

## Global Constraints

- **Zero production code changes.** Every new file lives under `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/`. `EstimatorConfig` and everything in `domain/progression/` (main) is read-only. If any task feels like it needs to touch production, stop and flag it.
- **Package:** `io.github.fowles.stochastic_strength.domain.backtest` for all new files.
- **Real-history test must no-op without the fixture.** Use `org.junit.Assume.assumeTrue(...)` guarded on `BacktestHarness.load() != null`, exactly as `RecalibrationReportTest` does. The fixture is `app/src/test/resources/backtest/history.json` (gitignored, present on this machine).
- **Report output path:** `app/build/variance-identification-report.txt` (written via `File("build/variance-identification-report.txt")`, matching `RecalibrationReportTest`'s relative path from the `:app` module dir).
- **Verdict currency:** held-out one-step-ahead predictive log-score summed over the held-out tail (sessions after the first `minFold = 8`, by `endTime` order). Higher is better.
- **Decision gate (recorded in the report, not enforced as a change):** a structural candidate is "recommended" only if it (1) beats the B0 baseline held-out score by a clear margin AND (2) its swept parameter's optimum is interior (not at a grid bound).
- **Run the specific test after each task:** `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.<ClassName>"`. Run the full `:app:testDebugUnitTest` once at the end.
- **Commits:** end every commit message with the trailer:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

---

## File Structure

- `VarianceStudyStream.kt` — `ScoredSet`, `obsLocation()`, `captureStream()`. The prediction-capture primitive (Task 1).
- `VarianceStudyScoring.kt` — `SetScorer` interface, `BaselineScorer`, `DayEffectScorer`, `heldOutScore()`, `sweep()`, `InteriorVerdict` (Tasks 2–3).
- `StudentT.kt` — `StudentT.cdf()` (regularized incomplete beta) + `StudentTScorer` (Task 4).
- `VarianceStudyConfigs.kt` — config builders: `withProcNoise()`, `withObsNoise()`, `withTau()`, `withAnchorPrecision()` (Tasks 2, 5, 6).
- `VarianceStudyDiagnostics.kt` — residual variance decomposition, same-muscle pair correlation, light-lift swing (Tasks 6–7).
- `VarianceIdentificationStudy.kt` — candidate definitions, report assembly, `format()` (Task 8).
- `VarianceIdentificationTest.kt` — the real-history runnable test (Task 8).
- Unit tests: `VarianceStudyStreamTest.kt`, `VarianceStudyScoringTest.kt`, `StudentTTest.kt`, `VarianceStudyConfigsTest.kt`, `VarianceStudyDiagnosticsTest.kt` (Tasks 1–7).

---

### Task 1: Prediction-capture primitive (`ScoredSet` + `captureStream`)

Replays the history under a config and emits, for every load-bearing set, the one-step-ahead prediction that the estimator would have made before folding it. Replicates `SessionProgressionStepper.step`'s per-exercise interleave (predict from current beliefs → fold that exercise → next exercise) so later analyses inherit exact production semantics; Task 2 pins that parity.

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyStream.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyStreamTest.kt`

**Interfaces:**
- Consumes: `ReplayHistory`, `ReplaySnapshot`, `EstimatorConfig`, `BeliefUpdater`, `MuscleStrengthProjector`, `SetObservation`, `ExerciseBelief` (all existing).
- Produces:
  - `data class ScoredSet(sessionId: Long, exerciseId: Long, muscle: MuscleGroup?, endTime: Long, sessionRank: Int, setNumber: Int, obs: SetObservation, predMeanLn: Float, cleanVar: Float)`
  - `fun obsLocation(obs: SetObservation): Float` — the observation's point location: `gaussianLn` if set; else interval midpoint; if one bound null, the finite bound.
  - `fun captureStream(history: ReplayHistory, config: EstimatorConfig, newSnapshot: () -> ReplaySnapshot): List<ScoredSet>` — in `endTime`,`id` session order; `sessionRank` is 0-based rank among completed sessions.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ExerciseBelief
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import io.github.fowles.stochastic_strength.domain.progression.SetObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VarianceStudyStreamTest {

    private val DAY = 24L * 60 * 60 * 1000

    private fun set(session: Long, setNo: Int, reps: Int, fb: SetFeedback) = WorkoutSet(
        sessionId = session, exerciseId = 1L, setNumber = setNo, targetWeight = 80f,
        targetReps = reps, actualReps = null, feedback = fb,
    )

    private fun history(): ReplayHistory {
        val s1 = WorkoutSession(id = 1L, startTime = 0L, endTime = 10L * DAY)
        val s2 = WorkoutSession(id = 2L, startTime = 0L, endTime = 20L * DAY)
        return ReplayHistory(
            sessions = listOf(s2, s1), // deliberately unsorted; capture must sort by endTime
            setsBySession = mapOf(
                1L to listOf(set(1L, 1, 8, SetFeedback.RIR_2_4), set(1L, 2, 8, SetFeedback.RIR_2_4)),
                2L to listOf(set(2L, 1, 8, SetFeedback.RIR_0_1)),
            ),
            initialOverrides = emptyList(),
            sessionOverrides = emptyMap(),
        )
    }

    private fun newSnapshot(): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS),
            seedCoefficients = mapOf(1L to 1.0f),
            exerciseEquipment = mapOf(1L to Equipment.BARBELL),
        )
        snap.currentBeliefs[1L] = ExerciseBelief.seed(100f, at = 0L)
        return snap
    }

    @Test fun captureEmitsOneScoredSetPerLoadObservationInEndTimeOrder() {
        val stream = captureStream(history(), EstimatorConfig(), ::newSnapshot)
        // 3 load-bearing sets total, session 1 before session 2 by endTime.
        assertEquals(3, stream.size)
        assertEquals(listOf(1L, 1L, 2L), stream.map { it.sessionId })
        assertEquals(listOf(0, 0, 1), stream.map { it.sessionRank })
        stream.forEach { assertTrue(it.predMeanLn.isFinite() && it.cleanVar > 0f) }
    }

    @Test fun obsLocationMidpointForTwoSidedInterval() {
        val obs = SetObservation(lowerLn = 1.0f, upperLn = 3.0f, gaussianLn = null, noiseSd = 0.1f)
        assertEquals(2.0f, obsLocation(obs), 1e-6f)
    }

    @Test fun hurtSetsAreNotEmitted() {
        val h = ReplayHistory(
            sessions = listOf(WorkoutSession(id = 1L, startTime = 0L, endTime = 10L * DAY)),
            setsBySession = mapOf(1L to listOf(set(1L, 1, 8, SetFeedback.HURT))),
            initialOverrides = emptyList(), sessionOverrides = emptyMap(),
        )
        assertTrue(captureStream(h, EstimatorConfig(), ::newSnapshot).isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyStreamTest"`
Expected: FAIL — `captureStream` / `obsLocation` / `ScoredSet` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.progression.BeliefUpdater
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ExerciseBelief
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import io.github.fowles.stochastic_strength.domain.progression.SetObservation
import kotlin.math.ln

/** One load-bearing set with the one-step-ahead prediction the estimator held before folding it. */
data class ScoredSet(
    val sessionId: Long,
    val exerciseId: Long,
    val muscle: MuscleGroup?,
    val endTime: Long,
    val sessionRank: Int,
    val setNumber: Int,
    val obs: SetObservation,
    val predMeanLn: Float,
    val cleanVar: Float,
)

/** The observation's point location on ln(fresh-1RM): the counted point, else interval midpoint,
 *  else the one finite bound. Used for residual diagnostics and the day-offset learning step. */
fun obsLocation(obs: SetObservation): Float = when {
    obs.gaussianLn != null -> obs.gaussianLn
    obs.lowerLn != null && obs.upperLn != null -> (obs.lowerLn + obs.upperLn) / 2f
    obs.lowerLn != null -> obs.lowerLn
    obs.upperLn != null -> obs.upperLn
    else -> 0f
}

/**
 * Replays [history] under [config], emitting the per-set one-step-ahead prediction stream. Replicates
 * [SessionProgressionStepper.step]'s per-exercise interleave (predict from current beliefs, then fold
 * that exercise's sets before the next exercise) so the captured predictions equal production's; Task 2
 * pins that parity. Beliefs/muscle-clock evolve exactly as in the real replay.
 */
fun captureStream(
    history: ReplayHistory,
    config: EstimatorConfig,
    newSnapshot: () -> ReplaySnapshot,
): List<ScoredSet> {
    val updater = BeliefUpdater(config)
    val projector = MuscleStrengthProjector(config)
    val snapshot = newSnapshot()
    for (init in history.initialOverrides) {
        snapshot.currentBeliefs[init.exerciseId] = ExerciseBelief.seed(init.e1rm, at = init.asOf, config = config)
    }
    val out = mutableListOf<ScoredSet>()
    val ordered = history.sessions.filter { it.endTime != null }
        .sortedWith(compareBy({ it.endTime!! }, { it.id }))
    ordered.forEachIndexed { rank, session ->
        history.sessionOverrides[session.id]?.forEach { o ->
            snapshot.currentBeliefs[o.exerciseId] = ExerciseBelief.override(o.e1rm, o.asOf, config)
        }
        val sets = history.setsBySession[session.id].orEmpty()
        if (sets.isEmpty()) return@forEachIndexed
        val asOf = session.endTime!!
        val affected = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach exercise@{ (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@exercise
            var belief = snapshot.currentBeliefs[id] ?: return@exercise
            val muscle = snapshot.exerciseMuscle[id]
            val muscleLast = muscle?.let { snapshot.muscleLastObs[it] }
            // Pre-fold prediction, computed once per exercise from the CURRENT (partially within-session
            // updated) beliefs — exactly as the stepper does before folding this exercise's sets.
            var predMeanLn: Float? = null
            var cleanVar = 0f
            val ids = muscle?.let { snapshot.muscleExerciseIds[it] }
            if (ids != null) {
                val proj = projector.project(
                    beliefs = snapshot.currentBeliefs, seedCoef = snapshot.seedCoefficients,
                    muscleExerciseIds = ids, now = asOf,
                    muscleLastObs = snapshot.muscleLastObs[muscle], equipment = snapshot.exerciseEquipment,
                )
                predMeanLn = proj.effectiveE1rm[id]?.let { ln(it) }
                cleanVar = updater.age(belief, asOf, muscleLast).evidenceVar
            }
            var folded = false
            exSets.sortedBy { it.setNumber }.forEachIndexed { i, set ->
                val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
                if (predMeanLn != null) {
                    out += ScoredSet(
                        sessionId = session.id, exerciseId = id, muscle = muscle, endTime = asOf,
                        sessionRank = rank, setNumber = set.setNumber, obs = obs,
                        predMeanLn = predMeanLn, cleanVar = cleanVar,
                    )
                }
                belief = if (obs.gaussianLn != null) {
                    updater.foldGaussian(belief, obs.gaussianLn, obs.noiseSd, asOf, muscleLast)
                } else {
                    updater.foldCensored(belief, obs.lowerLn, obs.upperLn, obs.noiseSd, asOf, muscleLast)
                }
                folded = true
            }
            if (folded) {
                snapshot.currentBeliefs[id] = belief
                muscle?.let { affected.add(it) }
            }
        }
        for (m in affected) snapshot.muscleLastObs[m] = asOf
    }
    return out
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyStreamTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyStream.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyStreamTest.kt
git commit -m "test(variance-study): prediction-capture primitive (ScoredSet + captureStream)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Baseline scorer, held-out sum, and production parity

The baseline `SetScorer` scores one set's prediction with the Gaussian/censored predictive density (predVar = cleanVar + noiseSd²), identical to production `PredictiveScoreAccumulator`. `heldOutScore` sums per-session scores over the held-out tail. The parity test is the anchor: capturing + baseline-scoring **all** sessions must equal production `scoredReplayTotal` to tolerance — that pins Task 1's reimplementation. Also adds the `withProcNoise` config builder for the B1 reference and the `sweep` helper.

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoring.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyConfigs.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoringTest.kt`

**Interfaces:**
- Consumes: `ScoredSet`, `captureStream` (Task 1); `PredictiveDensity`, `EstimatorConfig`, `RecalibrationHarness.scoredReplayTotal`, `BacktestHarness` (existing).
- Produces:
  - `fun interface SetScorer { fun sessionScore(setsInSession: List<ScoredSet>): Double }`
  - `object BaselineScorer : SetScorer`
  - `fun heldOutScore(stream: List<ScoredSet>, scorer: SetScorer, minFold: Int = 8): Double` — groups the stream by session in order, sums `sessionScore` over sessions with `sessionRank >= minFold`.
  - `data class SweepPoint(val param: Double, val score: Double)`
  - `data class InteriorVerdict(val bestParam: Double, val bestScore: Double, val interior: Boolean)`
  - `fun sweep(params: List<Double>, score: (Double) -> Double): List<SweepPoint>`
  - `fun interiorVerdict(points: List<SweepPoint>): InteriorVerdict` — argmax; `interior = true` iff the argmax index is neither first nor last.
  - `VarianceStudyConfigs.withProcNoise(base: EstimatorConfig, mult: Double): EstimatorConfig`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class VarianceStudyScoringTest {

    @Test fun sweepFindsInteriorAndBoundaryOptima() {
        val interior = sweep(listOf(1.0, 2.0, 3.0)) { p -> -(p - 2.0) * (p - 2.0) } // peak at 2.0
        val v1 = interiorVerdict(interior)
        assertEquals(2.0, v1.bestParam, 1e-9)
        assertTrue(v1.interior)

        val boundary = sweep(listOf(1.0, 2.0, 3.0)) { p -> p } // peak at the top bound
        val v2 = interiorVerdict(boundary)
        assertEquals(3.0, v2.bestParam, 1e-9)
        assertFalse(v2.interior)
    }

    // Anchor: our independent capture+baseline scoring reproduces production's scored replay total.
    @Test fun baselineScoringMatchesProductionScoredReplayTotal() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!
        val cfg = io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig()
        val stream = captureStream(data.history, cfg, data::newSnapshot)
        val ours = heldOutScore(stream, BaselineScorer, minFold = 0)     // score ALL sessions
        val prod = RecalibrationHarness.scoredReplayTotal(data.history, cfg, data::newSnapshot)
        assertEquals(prod, ours, 1e-3)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyScoringTest"`
Expected: FAIL — `sweep` / `heldOutScore` / `BaselineScorer` unresolved.

- [ ] **Step 3: Write minimal implementation**

`VarianceStudyScoring.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.PredictiveDensity

/** Scores all load-bearing sets of ONE session, returning the summed predictive log-score. */
fun interface SetScorer {
    fun sessionScore(setsInSession: List<ScoredSet>): Double
}

/** Marginal Gaussian/censored predictive density with predVar = cleanVar + noiseSd² — the production rule. */
object BaselineScorer : SetScorer {
    override fun sessionScore(setsInSession: List<ScoredSet>): Double =
        setsInSession.sumOf { s ->
            val v = s.cleanVar + s.obs.noiseSd * s.obs.noiseSd
            if (s.obs.gaussianLn != null) {
                PredictiveDensity.gaussianLogDensity(s.obs.gaussianLn, s.predMeanLn, v).toDouble()
            } else {
                PredictiveDensity.censoredLogMass(s.obs.lowerLn, s.obs.upperLn, s.predMeanLn, v).toDouble()
            }
        }
}

/** Sum of per-session scores over the held-out tail (sessionRank >= [minFold]). One-step-ahead: each
 *  session's prediction already reflects only prior sessions, so the tail sum is the held-out score. */
fun heldOutScore(stream: List<ScoredSet>, scorer: SetScorer, minFold: Int = 8): Double =
    stream.filter { it.sessionRank >= minFold }
        .groupBy { it.sessionId }
        .values
        .sumOf { scorer.sessionScore(it) }

data class SweepPoint(val param: Double, val score: Double)
data class InteriorVerdict(val bestParam: Double, val bestScore: Double, val interior: Boolean)

fun sweep(params: List<Double>, score: (Double) -> Double): List<SweepPoint> =
    params.map { SweepPoint(it, score(it)) }

fun interiorVerdict(points: List<SweepPoint>): InteriorVerdict {
    val bestIdx = points.indices.maxByOrNull { points[it].score } ?: -1
    val best = points[bestIdx]
    val interior = bestIdx != 0 && bestIdx != points.lastIndex
    return InteriorVerdict(best.param, best.score, interior)
}
```

`VarianceStudyConfigs.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

/** Config builders for the study's candidate structures. Each returns a fresh copy; none mutate. */
object VarianceStudyConfigs {
    fun withProcNoise(base: EstimatorConfig, mult: Double): EstimatorConfig =
        base.copy(processNoisePerDay = (base.processNoisePerDay * mult).toFloat())
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyScoringTest"`
Expected: PASS. (The parity test runs only if the fixture is present; otherwise it is skipped via Assume.)

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoring.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyConfigs.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoringTest.kt
git commit -m "test(variance-study): baseline scorer + held-out sum + production parity anchor

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Session day-effect scorer

The candidate model extension. Within a session, a shared latent day offset `d ~ N(0, σ_day²)` is learned sequentially across the session's sets: each set is scored with the current marginal predictive variance (`cleanVar + noiseSd² + dVar`), then the day posterior is updated by a Gaussian Kalman step on the residual `obsLocation − predMean` (censored sets use `obsLocation`, a documented moment-match approximation for the *learning* step only; the *score* itself still uses the exact censored mass). At `σ_day = 0` the offset never moves and this reduces exactly to `BaselineScorer` — the spec §8 pin.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoring.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoringTest.kt`

**Interfaces:**
- Consumes: `ScoredSet`, `obsLocation`, `PredictiveDensity`.
- Produces: `class DayEffectScorer(private val sigmaDay: Float) : SetScorer`. Sets within a session are processed in `(setNumber, exerciseId)` order for the sequential day-learning.

- [ ] **Step 1: Write the failing test (append to `VarianceStudyScoringTest`)**

```kotlin
    @Test fun dayEffectAtZeroSigmaEqualsBaseline() {
        val cfg = io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig()
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!
        val stream = captureStream(data.history, cfg, data::newSnapshot)
        val base = heldOutScore(stream, BaselineScorer, minFold = 0)
        val day0 = heldOutScore(stream, DayEffectScorer(sigmaDay = 0f), minFold = 0)
        assertEquals(base, day0, 1e-6)
    }

    @Test fun dayEffectSharpensLaterSetsInSession() {
        // Two sets in one session, both far above prediction in the same direction: after learning a
        // positive day offset from set 1, set 2's score should be higher than the baseline (no-offset) score.
        val obs = io.github.fowles.stochastic_strength.domain.progression.SetObservation(
            lowerLn = null, upperLn = null, gaussianLn = 5.0f, noiseSd = 0.1f,
        )
        val s1 = ScoredSet(1L, 1L, null, 0L, 0, 1, obs, predMeanLn = 4.5f, cleanVar = 0.04f)
        val s2 = ScoredSet(1L, 2L, null, 0L, 0, 1, obs, predMeanLn = 4.5f, cleanVar = 0.04f)
        val baseline = BaselineScorer.sessionScore(listOf(s1, s2))
        val withDay = DayEffectScorer(sigmaDay = 0.15f).sessionScore(listOf(s1, s2))
        assertTrue(withDay > baseline)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyScoringTest"`
Expected: FAIL — `DayEffectScorer` unresolved.

- [ ] **Step 3: Write minimal implementation (append to `VarianceStudyScoring.kt`)**

```kotlin
/**
 * Session day-effect: a shared latent offset d ~ N(0, σ_day²) learned sequentially across the session.
 * Each set is scored with predVar = cleanVar + noiseSd² + dVar (day integrated out), then d is updated
 * by a Gaussian Kalman step on the residual (obsLocation − predMean) with obs variance cleanVar+noiseSd².
 * The LEARNING step uses obsLocation for censored sets (moment-match approximation); the SCORE uses the
 * exact censored mass. σ_day = 0 ⇒ dVar = 0 ⇒ the offset never moves ⇒ identical to BaselineScorer.
 */
class DayEffectScorer(private val sigmaDay: Float) : SetScorer {
    override fun sessionScore(setsInSession: List<ScoredSet>): Double {
        var dMean = 0f
        var dVar = sigmaDay * sigmaDay
        var total = 0.0
        val ordered = setsInSession.sortedWith(compareBy({ it.setNumber }, { it.exerciseId }))
        for (s in ordered) {
            val r = s.cleanVar + s.obs.noiseSd * s.obs.noiseSd
            val predMean = s.predMeanLn + dMean
            val predVar = r + dVar
            total += if (s.obs.gaussianLn != null) {
                PredictiveDensity.gaussianLogDensity(s.obs.gaussianLn, predMean, predVar).toDouble()
            } else {
                PredictiveDensity.censoredLogMass(s.obs.lowerLn, s.obs.upperLn, predMean, predVar).toDouble()
            }
            // Kalman update of the day offset from this set's residual about the (offset-free) prediction.
            val y = obsLocation(s.obs) - s.predMeanLn
            val k = dVar / (dVar + r)
            dMean += k * (y - dMean)
            dVar = (1f - k) * dVar
        }
        return total
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyScoringTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoring.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoringTest.kt
git commit -m "test(variance-study): session day-effect scorer (sequential within-session offset)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Student-t heavy-tailed scorer (+ t-CDF)

A Student-t observation likelihood with ν degrees of freedom and scale `sqrt(predVar)` (so ν→∞ recovers the Gaussian). Needs a Student-t CDF for the censored interval mass; implement it via the regularized incomplete beta function (continued fraction), the standard route. Pinned against known values (Cauchy at ν=1, symmetry at 0, Gaussian limit).

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/StudentT.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoring.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/StudentTTest.kt`

**Interfaces:**
- Produces:
  - `object StudentT { fun cdf(t: Double, nu: Double): Double; fun logPdf(t: Double, nu: Double): Double }`
  - `class StudentTScorer(private val nu: Double) : SetScorer` — for each set, standardize by `sqrt(predVar)`; Gaussian-point obs → `logPdf(z,ν) − 0.5·ln(predVar)`; censored → `ln(cdf(β,ν) − cdf(α,ν))` with `α,β` the standardized bounds (`±∞`→`0`/`1` via cdf at large magnitude), clamped away from `0` mass with a `1e-12` floor.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.PredictiveDensity
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class StudentTTest {
    @Test fun cdfKnownValues() {
        assertEquals(0.5, StudentT.cdf(0.0, 5.0), 1e-9)          // symmetry
        assertEquals(0.75, StudentT.cdf(1.0, 1.0), 1e-6)         // Cauchy: F(1)=0.75
        assertEquals(0.5 + 0.5, StudentT.cdf(50.0, 3.0), 1e-3)   // far right tail ≈ 1
    }

    @Test fun largeNuApproachesGaussianScore() {
        // A single Gaussian-point obs scored by Student-t at large ν ≈ Gaussian predictive log-density.
        val predVar = 0.05
        val z = 0.3
        val obs = (0.3 * sqrt(predVar)).toFloat() // obs at predMean + z·sd, predMean = 0
        val gaussian = PredictiveDensity.gaussianLogDensity(obs, 0f, predVar.toFloat()).toDouble()
        val t = StudentTScorer(nu = 1e6).sessionScore(
            listOf(
                ScoredSet(1L, 1L, null, 0L, 0, 1,
                    io.github.fowles.stochastic_strength.domain.progression.SetObservation(
                        null, null, obs, noiseSd = 0f),
                    predMeanLn = 0f, cleanVar = predVar.toFloat()),
            ),
        )
        assertEquals(gaussian, t, 1e-3)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.StudentTTest"`
Expected: FAIL — `StudentT` / `StudentTScorer` unresolved.

- [ ] **Step 3: Write minimal implementation**

`StudentT.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Student-t distribution: CDF via the regularized incomplete beta function, and standardized log-pdf. */
object StudentT {

    fun logPdf(t: Double, nu: Double): Double {
        val c = lgamma((nu + 1) / 2) - lgamma(nu / 2) - 0.5 * ln(nu * PI)
        return c - (nu + 1) / 2 * ln(1 + t * t / nu)
    }

    /** P(T <= t) for T ~ t_nu. Uses x = nu/(nu+t²) and the identity with I_x(nu/2, 1/2). */
    fun cdf(t: Double, nu: Double): Double {
        if (t == 0.0) return 0.5
        val x = nu / (nu + t * t)
        val ib = 0.5 * regularizedIncompleteBeta(x, nu / 2.0, 0.5)
        return if (t > 0) 1.0 - ib else ib
    }

    // Lanczos log-gamma.
    private fun lgamma(z: Double): Double {
        val g = doubleArrayOf(
            676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7,
        )
        if (z < 0.5) return ln(PI / kotlin.math.sin(PI * z)) - lgamma(1 - z)
        val zz = z - 1
        var a = 0.99999999999980993
        val tt = zz + 7.5
        for (i in g.indices) a += g[i] / (zz + i + 1)
        return 0.5 * ln(2 * PI) + (zz + 0.5) * ln(tt) - tt + ln(a)
    }

    /** Regularized incomplete beta I_x(a,b) via Lentz's continued fraction (Numerical Recipes). */
    private fun regularizedIncompleteBeta(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val lbeta = lgamma(a) + lgamma(b) - lgamma(a + b)
        val front = exp(a * ln(x) + b * ln(1 - x) - lbeta) / a
        // Continued fraction (converges fast for x < (a+1)/(a+b+2); else use symmetry).
        if (x < (a + 1) / (a + b + 2)) return front * betacf(x, a, b)
        return 1.0 - exp(b * ln(1 - x) + a * ln(x) - lbeta) / b * betacf(1 - x, b, a)
    }

    private fun betacf(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-30
        var c = 1.0
        var d = 1.0 - (a + b) * x / (a + 1)
        if (kotlin.math.abs(d) < tiny) d = tiny
        d = 1.0 / d
        var h = d
        for (m in 1..200) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2))
            d = 1.0 + aa * d; if (kotlin.math.abs(d) < tiny) d = tiny
            c = 1.0 + aa / c; if (kotlin.math.abs(c) < tiny) c = tiny
            d = 1.0 / d; h *= d * c
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1))
            d = 1.0 + aa * d; if (kotlin.math.abs(d) < tiny) d = tiny
            c = 1.0 + aa / c; if (kotlin.math.abs(c) < tiny) c = tiny
            d = 1.0 / d; val del = d * c; h *= del
            if (kotlin.math.abs(del - 1.0) < 1e-12) break
        }
        return h
    }
}
```

Append `StudentTScorer` to `VarianceStudyScoring.kt`:

```kotlin
/**
 * Heavy-tailed observation model: Student-t with [nu] dof and scale sqrt(predVar) (ν→∞ ⇒ Gaussian).
 * Gaussian-point obs use the standardized t log-pdf; censored intervals use the t-interval mass.
 */
class StudentTScorer(private val nu: Double) : SetScorer {
    override fun sessionScore(setsInSession: List<ScoredSet>): Double =
        setsInSession.sumOf { s ->
            val predVar = (s.cleanVar + s.obs.noiseSd * s.obs.noiseSd).toDouble()
            val sd = kotlin.math.sqrt(predVar)
            if (s.obs.gaussianLn != null) {
                val z = (s.obs.gaussianLn - s.predMeanLn) / sd
                StudentT.logPdf(z, nu) - kotlin.math.ln(sd)
            } else {
                val a = s.obs.lowerLn?.let { (it - s.predMeanLn) / sd }?.toDouble()
                val b = s.obs.upperLn?.let { (it - s.predMeanLn) / sd }?.toDouble()
                val loMass = a?.let { StudentT.cdf(it, nu) } ?: 0.0
                val hiMass = b?.let { StudentT.cdf(it, nu) } ?: 1.0
                kotlin.math.ln((hiMass - loMass).coerceAtLeast(1e-12))
            }
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.StudentTTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/StudentT.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyScoring.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/StudentTTest.kt
git commit -m "test(variance-study): Student-t heavy-tailed scorer + t-CDF via incomplete beta

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Obs-noise and τ / anchor config builders

The two config-only candidates: re-freed observation noise (scale all rep-noise bases and `obsModelSd` by a multiplier) and cross-exercise transfer (scale all three τ together, and separately scale `levelAnchorPrecision`). These are scored by re-capturing the stream under the modified config and applying `BaselineScorer` — the belief evolution changes, the scoring rule does not. `×1` must reproduce the default config exactly.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyConfigs.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyConfigsTest.kt`

**Interfaces:**
- Produces (added to `VarianceStudyConfigs`):
  - `fun withObsNoise(base: EstimatorConfig, mult: Double): EstimatorConfig` — scales `repNoiseBucket`, `repNoiseCounted`, `repNoiseRel`, `obsModelSd`.
  - `fun withTau(base: EstimatorConfig, mult: Double): EstimatorConfig` — scales `tauBarbell`, `tauMachineCable`, `tauOtherLoaded`.
  - `fun withAnchorPrecision(base: EstimatorConfig, mult: Double): EstimatorConfig` — scales `levelAnchorPrecision`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class VarianceStudyConfigsTest {
    private val base = EstimatorConfig()

    @Test fun obsNoiseScalesAllNoiseBases() {
        val c = VarianceStudyConfigs.withObsNoise(base, 2.0)
        assertEquals(base.repNoiseBucket * 2f, c.repNoiseBucket, 1e-7f)
        assertEquals(base.repNoiseCounted * 2f, c.repNoiseCounted, 1e-7f)
        assertEquals(base.repNoiseRel * 2f, c.repNoiseRel, 1e-7f)
        assertEquals(base.obsModelSd * 2f, c.obsModelSd, 1e-7f)
    }

    @Test fun tauScalesAllThreeClasses() {
        val c = VarianceStudyConfigs.withTau(base, 0.5)
        assertEquals(base.tauBarbell * 0.5f, c.tauBarbell, 1e-7f)
        assertEquals(base.tauMachineCable * 0.5f, c.tauMachineCable, 1e-7f)
        assertEquals(base.tauOtherLoaded * 0.5f, c.tauOtherLoaded, 1e-7f)
    }

    @Test fun unitMultiplierReproducesDefault() {
        assertEquals(base, VarianceStudyConfigs.withObsNoise(base, 1.0))
        assertEquals(base, VarianceStudyConfigs.withTau(base, 1.0))
        assertEquals(base, VarianceStudyConfigs.withAnchorPrecision(base, 1.0))
        assertEquals(base, VarianceStudyConfigs.withProcNoise(base, 1.0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyConfigsTest"`
Expected: FAIL — `withObsNoise` / `withTau` / `withAnchorPrecision` unresolved.

- [ ] **Step 3: Write minimal implementation (append to `VarianceStudyConfigs`)**

```kotlin
    fun withObsNoise(base: EstimatorConfig, mult: Double): EstimatorConfig = base.copy(
        repNoiseBucket = (base.repNoiseBucket * mult).toFloat(),
        repNoiseCounted = (base.repNoiseCounted * mult).toFloat(),
        repNoiseRel = (base.repNoiseRel * mult).toFloat(),
        obsModelSd = (base.obsModelSd * mult).toFloat(),
    )

    fun withTau(base: EstimatorConfig, mult: Double): EstimatorConfig = base.copy(
        tauBarbell = (base.tauBarbell * mult).toFloat(),
        tauMachineCable = (base.tauMachineCable * mult).toFloat(),
        tauOtherLoaded = (base.tauOtherLoaded * mult).toFloat(),
    )

    fun withAnchorPrecision(base: EstimatorConfig, mult: Double): EstimatorConfig =
        base.copy(levelAnchorPrecision = (base.levelAnchorPrecision * mult).toFloat())
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyConfigsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyConfigs.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyConfigsTest.kt
git commit -m "test(variance-study): obs-noise / tau / anchor-precision config builders

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Residual diagnostics — variance shares & same-muscle pair correlation

The descriptive "why" that rides along with the CV verdict. From a captured stream: (a) the variance of one-step-ahead residuals (`obsLocation − predMeanLn`) partitioned into a whole-session component (variance of per-session mean residuals) vs a within-session component (mean of within-session residual variances) vs a within-exercise/set component; (b) same-muscle exercise-pair residual correlation, aggregating co-occurring-session residual pairs per muscle. Strictly diagnostic — never a verdict.

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyDiagnostics.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyDiagnosticsTest.kt`

**Interfaces:**
- Produces:
  - `data class ResidualDecomposition(val totalVar: Double, val betweenSessionVar: Double, val withinSessionVar: Double, val n: Int)`
  - `fun decomposeResiduals(stream: List<ScoredSet>): ResidualDecomposition` — `betweenSessionVar` = variance of per-session mean residuals; `withinSessionVar` = mean of per-session residual variances.
  - `data class PairCorrelation(val muscle: MuscleGroup, val exerciseA: Long, val exerciseB: Long, val correlation: Double, val nSessions: Int)`
  - `fun sameMusclePairCorrelations(stream: List<ScoredSet>): List<PairCorrelation>` — for each muscle and each exercise pair sharing ≥ 3 co-occurring sessions, Pearson correlation of the two exercises' per-session mean residuals.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.progression.SetObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VarianceStudyDiagnosticsTest {

    private fun pointSet(session: Long, ex: Long, muscle: MuscleGroup, residual: Float) =
        ScoredSet(session, ex, muscle, session, 0, 1,
            SetObservation(null, null, gaussianLn = 1.0f + residual, noiseSd = 0.1f),
            predMeanLn = 1.0f, cleanVar = 0.04f)

    @Test fun betweenSessionVarianceCapturesAWholeSessionShift() {
        // Session 1 residuals all +0.2, session 2 all -0.2 → pure between-session, ~0 within.
        val stream = listOf(
            pointSet(1L, 1L, MuscleGroup.QUADS, 0.2f), pointSet(1L, 2L, MuscleGroup.QUADS, 0.2f),
            pointSet(2L, 1L, MuscleGroup.QUADS, -0.2f), pointSet(2L, 2L, MuscleGroup.QUADS, -0.2f),
        )
        val d = decomposeResiduals(stream)
        assertTrue(d.betweenSessionVar > 0.03)      // ~0.04
        assertEquals(0.0, d.withinSessionVar, 1e-6) // identical within each session
    }

    @Test fun perfectlyCorrelatedSiblingsReportCorrelationNearOne() {
        val stream = listOf(
            pointSet(1L, 1L, MuscleGroup.QUADS, 0.2f), pointSet(1L, 2L, MuscleGroup.QUADS, 0.2f),
            pointSet(2L, 1L, MuscleGroup.QUADS, -0.1f), pointSet(2L, 2L, MuscleGroup.QUADS, -0.1f),
            pointSet(3L, 1L, MuscleGroup.QUADS, 0.05f), pointSet(3L, 2L, MuscleGroup.QUADS, 0.05f),
        )
        val corrs = sameMusclePairCorrelations(stream)
        assertEquals(1, corrs.size)
        assertEquals(1.0, corrs[0].correlation, 1e-6)
        assertEquals(3, corrs[0].nSessions)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyDiagnosticsTest"`
Expected: FAIL — `decomposeResiduals` / `sameMusclePairCorrelations` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.MuscleGroup

data class ResidualDecomposition(
    val totalVar: Double, val betweenSessionVar: Double, val withinSessionVar: Double, val n: Int,
)

private fun variance(xs: List<Double>): Double {
    if (xs.size < 2) return 0.0
    val m = xs.average()
    return xs.sumOf { (it - m) * (it - m) } / xs.size
}

private fun ScoredSet.residual(): Double = (obsLocation(obs) - predMeanLn).toDouble()

/** Partition residual variance into a between-session (whole-session shift) and within-session component. */
fun decomposeResiduals(stream: List<ScoredSet>): ResidualDecomposition {
    val all = stream.map { it.residual() }
    val bySession = stream.groupBy { it.sessionId }
    val sessionMeans = bySession.values.map { rows -> rows.map { it.residual() }.average() }
    val withinVars = bySession.values.filter { it.size >= 2 }.map { rows -> variance(rows.map { it.residual() }) }
    return ResidualDecomposition(
        totalVar = variance(all),
        betweenSessionVar = variance(sessionMeans),
        withinSessionVar = if (withinVars.isEmpty()) 0.0 else withinVars.average(),
        n = all.size,
    )
}

data class PairCorrelation(
    val muscle: MuscleGroup, val exerciseA: Long, val exerciseB: Long,
    val correlation: Double, val nSessions: Int,
)

/** Pearson correlation of two same-muscle exercises' per-session mean residuals, over co-occurring sessions. */
fun sameMusclePairCorrelations(stream: List<ScoredSet>): List<PairCorrelation> {
    val out = mutableListOf<PairCorrelation>()
    val byMuscle = stream.filter { it.muscle != null }.groupBy { it.muscle!! }
    for ((muscle, rows) in byMuscle) {
        // per (exercise, session) mean residual
        val perExSession: Map<Long, Map<Long, Double>> = rows.groupBy { it.exerciseId }
            .mapValues { (_, exRows) -> exRows.groupBy { it.sessionId }.mapValues { (_, r) -> r.map { it.residual() }.average() } }
        val exercises = perExSession.keys.sorted()
        for (i in exercises.indices) for (j in i + 1 until exercises.size) {
            val a = perExSession[exercises[i]]!!
            val b = perExSession[exercises[j]]!!
            val shared = a.keys.intersect(b.keys).sorted()
            if (shared.size < 3) continue
            val xs = shared.map { a[it]!! }
            val ys = shared.map { b[it]!! }
            val mx = xs.average(); val my = ys.average()
            var cov = 0.0; var vx = 0.0; var vy = 0.0
            for (k in shared.indices) {
                cov += (xs[k] - mx) * (ys[k] - my); vx += (xs[k] - mx) * (xs[k] - mx); vy += (ys[k] - my) * (ys[k] - my)
            }
            val denom = kotlin.math.sqrt(vx * vy)
            val corr = if (denom == 0.0) 0.0 else cov / denom
            out += PairCorrelation(muscle, exercises[i], exercises[j], corr, shared.size)
        }
    }
    return out
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyDiagnosticsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyDiagnostics.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyDiagnosticsTest.kt
git commit -m "test(variance-study): residual variance decomposition + same-muscle pair correlation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Light-lift swing diagnostic (deferred-concern color)

Emits the lightest-accessory session-to-session prescription swing the problem statement flagged (5 lb → 15 lb under a reactive belief) so the follow-up phase starts informed. Reuses `BacktestHarness.replayPolicyPrescriptions`: under a config, find the exercise with the smallest median prescribed weight, and report its min/max prescribed weight and its largest session-to-session absolute change. Compares B0 vs B1. Explicitly color — the study does not act on it.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyDiagnostics.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyDiagnosticsTest.kt`

**Interfaces:**
- Consumes: `BacktestHarness.BacktestData`, `BacktestHarness.replayPolicyPrescriptions`, `BacktestHarness.Row`.
- Produces:
  - `data class LightLiftSwing(val exerciseId: Long, val minKg: Float, val maxKg: Float, val maxStepKg: Float, val sessions: Int)`
  - `fun lightestLiftSwing(rows: List<BacktestHarness.Row>): LightLiftSwing?` — pure over prescription rows; picks the exercise with the smallest median weight; `maxStepKg` = max |Δ| between consecutive sessions (rows ordered by sessionId).

- [ ] **Step 1: Write the failing test (append to `VarianceStudyDiagnosticsTest`)**

```kotlin
    @Test fun lightestLiftSwingPicksSmallestMedianAndMaxStep() {
        val rows = listOf(
            BacktestHarness.Row(1L, 10L, 100f), BacktestHarness.Row(2L, 10L, 100f), // heavy exercise, stable
            BacktestHarness.Row(1L, 20L, 5f), BacktestHarness.Row(2L, 20L, 15f), BacktestHarness.Row(3L, 20L, 10f),
        )
        val swing = lightestLiftSwing(rows)!!
        assertEquals(20L, swing.exerciseId)
        assertEquals(5f, swing.minKg, 1e-6f)
        assertEquals(15f, swing.maxKg, 1e-6f)
        assertEquals(10f, swing.maxStepKg, 1e-6f) // |15-5| across sessions 1->2
        assertEquals(3, swing.sessions)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyDiagnosticsTest"`
Expected: FAIL — `lightestLiftSwing` unresolved.

- [ ] **Step 3: Write minimal implementation (append to `VarianceStudyDiagnostics.kt`)**

```kotlin
data class LightLiftSwing(
    val exerciseId: Long, val minKg: Float, val maxKg: Float, val maxStepKg: Float, val sessions: Int,
)

/** The lightest accessory's prescription volatility: smallest-median exercise, its range and max step. */
fun lightestLiftSwing(rows: List<BacktestHarness.Row>): LightLiftSwing? {
    if (rows.isEmpty()) return null
    val byExercise = rows.groupBy { it.exerciseId }
    fun median(xs: List<Float>): Float { val s = xs.sorted(); return s[s.size / 2] }
    val lightest = byExercise.minByOrNull { median(it.value.map { r -> r.weightKg }) } ?: return null
    val ordered = lightest.value.sortedBy { it.sessionId }.map { it.weightKg }
    val maxStep = ordered.zipWithNext { a, b -> kotlin.math.abs(b - a) }.maxOrNull() ?: 0f
    return LightLiftSwing(lightest.key, ordered.min(), ordered.max(), maxStep, ordered.size)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceStudyDiagnosticsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyDiagnostics.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceStudyDiagnosticsTest.kt
git commit -m "test(variance-study): lightest-lift prescription swing diagnostic (deferred-concern color)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Study assembly, report, and real-history runnable test

Wires everything into `VarianceIdentificationStudy.run(data)`: computes B0/B1 references, sweeps each of the four candidates for its interior optimum and held-out delta vs B0 and B1, runs the diagnostics, and formats a report ending in a ranked recommendation section. The `VarianceIdentificationTest` no-ops without the fixture and writes `app/build/variance-identification-report.txt`, mirroring `RecalibrationReportTest`.

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceIdentificationStudy.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceIdentificationTest.kt`

**Interfaces:**
- Consumes: everything above; `BacktestHarness.load()/BacktestData`, `RecalibrationHarness` (unused here but same package).
- Produces:
  - `data class CandidateResult(val name: String, val bestParam: Double, val heldOut: Double, val deltaVsB0: Double, val deltaVsB1: Double, val interior: Boolean, val points: List<SweepPoint>)`
  - `data class StudyReport(val b0: Double, val b1: Double, val candidates: List<CandidateResult>, val decomposition: ResidualDecomposition, val pairCorrelations: List<PairCorrelation>, val b0Swing: LightLiftSwing?, val b1Swing: LightLiftSwing?)`
  - `object VarianceIdentificationStudy { fun run(data: BacktestHarness.BacktestData, minFold: Int = 8): StudyReport; fun format(r: StudyReport): String }`
  - Sweep grids (constants in the object): `SIGMA_DAY = [0.0,0.02,0.04,0.06,0.08,0.10,0.14,0.18,0.24]`; `OBS_NOISE = [0.5,0.75,1.0,1.5,2.0,3.0,4.0]`; `TAU = [0.25,0.5,1.0,1.5,2.0,3.0,4.0]`; `NU = [2.5,4.0,6.0,10.0,20.0,50.0,1e6]`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class VarianceIdentificationTest {

    @Test fun varianceStudy_onRealHistory_printsAndWrites() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!

        val report = VarianceIdentificationStudy.run(data)
        val text = VarianceIdentificationStudy.format(report)
        println(text)

        val out = File("build/variance-identification-report.txt")
        out.parentFile?.mkdirs()
        out.writeText(text)

        // Structural invariants: four candidates, each with a non-empty sweep, references finite.
        assertEquals(
            listOf("day-effect", "obs-noise", "student-t", "transfer-tau"),
            report.candidates.map { it.name },
        )
        assertTrue(report.b0.isFinite() && report.b1.isFinite())
        report.candidates.forEach { assertTrue(it.points.isNotEmpty() && it.heldOut.isFinite()) }
        // B1 (procNoise x16) is the known release-valve reference: it should beat B0 held-out.
        assertTrue(report.b1 > report.b0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceIdentificationTest"`
Expected: FAIL — `VarianceIdentificationStudy` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

data class CandidateResult(
    val name: String, val bestParam: Double, val heldOut: Double,
    val deltaVsB0: Double, val deltaVsB1: Double, val interior: Boolean, val points: List<SweepPoint>,
)

data class StudyReport(
    val b0: Double, val b1: Double, val candidates: List<CandidateResult>,
    val anchorSweep: List<SweepPoint>,
    val decomposition: ResidualDecomposition, val pairCorrelations: List<PairCorrelation>,
    val b0Swing: LightLiftSwing?, val b1Swing: LightLiftSwing?,
)

/**
 * Phase-6 variance-identification study: CV-scores the four candidate variance structures against the
 * B0 (default) and B1 (procNoise x16) references on real history, reports interior-optimum status and
 * diagnostics, and ranks a recommendation. Analysis-only; changes no production constant.
 */
object VarianceIdentificationStudy {
    private val SIGMA_DAY = listOf(0.0, 0.02, 0.04, 0.06, 0.08, 0.10, 0.14, 0.18, 0.24)
    private val OBS_NOISE = listOf(0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0)
    private val TAU = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
    private val NU = listOf(2.5, 4.0, 6.0, 10.0, 20.0, 50.0, 1e6)
    private val ANCHOR = listOf(0.25, 0.5, 1.0, 2.0, 4.0)

    fun run(data: BacktestHarness.BacktestData, minFold: Int = 8): StudyReport {
        val base = EstimatorConfig()
        val baseStream = captureStream(data.history, base, data::newSnapshot)
        val b0 = heldOutScore(baseStream, BaselineScorer, minFold)
        val b1 = heldOutScore(
            captureStream(data.history, VarianceStudyConfigs.withProcNoise(base, 16.0), data::newSnapshot),
            BaselineScorer, minFold,
        )

        fun candidate(name: String, points: List<SweepPoint>): CandidateResult {
            val v = interiorVerdict(points)
            return CandidateResult(name, v.bestParam, v.bestScore, v.bestScore - b0, v.bestScore - b1, v.interior, points)
        }

        // Day-effect: default belief evolution, day-effect scoring, sweep sigma_day (scorer-only).
        val dayEffect = candidate("day-effect", sweep(SIGMA_DAY) { sd ->
            heldOutScore(baseStream, DayEffectScorer(sd.toFloat()), minFold)
        })
        // Obs-noise: config change, baseline scoring — re-capture per multiplier.
        val obsNoise = candidate("obs-noise", sweep(OBS_NOISE) { m ->
            heldOutScore(captureStream(data.history, VarianceStudyConfigs.withObsNoise(base, m), data::newSnapshot), BaselineScorer, minFold)
        })
        // Student-t: default belief evolution, t-scoring, sweep nu (scorer-only).
        val studentT = candidate("student-t", sweep(NU) { nu ->
            heldOutScore(baseStream, StudentTScorer(nu), minFold)
        })
        // Transfer: config change (tau), baseline scoring — re-capture per multiplier.
        val transfer = candidate("transfer-tau", sweep(TAU) { m ->
            heldOutScore(captureStream(data.history, VarianceStudyConfigs.withTau(base, m), data::newSnapshot), BaselineScorer, minFold)
        })

        // Anchor-precision mini-sweep (spec §5.4): config change, baseline scoring — diagnostic, not a named candidate.
        val anchorSweep = sweep(ANCHOR) { m ->
            heldOutScore(captureStream(data.history, VarianceStudyConfigs.withAnchorPrecision(base, m), data::newSnapshot), BaselineScorer, minFold)
        }

        val b0Rows = BacktestHarness.replayPolicyPrescriptions(data, base)
        val b1Rows = BacktestHarness.replayPolicyPrescriptions(data, VarianceStudyConfigs.withProcNoise(base, 16.0))

        return StudyReport(
            b0 = b0, b1 = b1,
            candidates = listOf(dayEffect, obsNoise, studentT, transfer),
            anchorSweep = anchorSweep,
            decomposition = decomposeResiduals(baseStream),
            pairCorrelations = sameMusclePairCorrelations(baseStream),
            b0Swing = lightestLiftSwing(b0Rows),
            b1Swing = lightestLiftSwing(b1Rows),
        )
    }

    fun format(r: StudyReport): String {
        val sb = StringBuilder()
        sb.appendLine("Variance-identification study")
        sb.appendLine("references: B0(default)=%.3f  B1(procNoise x16)=%.3f  (B1-B0=%.3f)".format(r.b0, r.b1, r.b1 - r.b0))
        sb.appendLine()
        sb.appendLine("candidate      best@     heldOut   dVsB0    dVsB1    interior")
        for (c in r.candidates) {
            sb.appendLine("%-14s %-9s %-9s %-8s %-8s %s".format(
                c.name, "%.3f".format(c.bestParam), "%.3f".format(c.heldOut),
                "%+.3f".format(c.deltaVsB0), "%+.3f".format(c.deltaVsB1), if (c.interior) "INTERIOR" else "pins-bound"))
        }
        sb.appendLine()
        for (c in r.candidates) {
            sb.appendLine("  ${c.name} sweep: " + c.points.joinToString(" ") { "%.3f=%.2f".format(it.param, it.score) })
        }
        sb.appendLine("  anchor-precision sweep: " + r.anchorSweep.joinToString(" ") { "%.3f=%.2f".format(it.param, it.score) })
        sb.appendLine()
        val d = r.decomposition
        sb.appendLine("residuals: n=%d totalVar=%.4f betweenSession=%.4f withinSession=%.4f (between share=%.1f%%)".format(
            d.n, d.totalVar, d.betweenSessionVar, d.withinSessionVar,
            if (d.totalVar > 0) 100.0 * d.betweenSessionVar / d.totalVar else 0.0))
        sb.appendLine("same-muscle pair correlations:")
        for (p in r.pairCorrelations.sortedByDescending { it.correlation }) {
            sb.appendLine("  %s ex%d~ex%d  r=%.2f (n=%d)".format(p.muscle, p.exerciseA, p.exerciseB, p.correlation, p.nSessions))
        }
        sb.appendLine()
        sb.appendLine("light-lift swing (deferred color): B0=${r.b0Swing}  B1=${r.b1Swing}")
        sb.appendLine()
        sb.appendLine("RECOMMENDATION (CV gain + interior optimum; see spec decision gate):")
        val ranked = r.candidates.sortedWith(compareByDescending<CandidateResult> { it.interior }.thenByDescending { it.deltaVsB0 })
        for (c in ranked) {
            val verdict = when {
                c.deltaVsB0 > 0 && c.interior -> "RECOMMENDED (beats B0 with interior optimum)"
                c.deltaVsB0 > 0 -> "gains but pins bound (release-valve-like, treat with suspicion)"
                else -> "no CV gain over B0"
            }
            sb.appendLine("  %-14s dVsB0=%+.3f dVsB1=%+.3f -> %s".format(c.name, c.deltaVsB0, c.deltaVsB1, verdict))
        }
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.VarianceIdentificationTest"`
Expected: PASS (fixture present) — report written to `app/build/variance-identification-report.txt`. Read the report; the assertions only check well-formedness, the *content* is the deliverable.

- [ ] **Step 5: Run the full suite for regressions**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — no production behavior changed, so the existing suite is unaffected; the new tests are additive.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceIdentificationStudy.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceIdentificationTest.kt
git commit -m "test(variance-study): study assembly + report + real-history runnable test

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Post-implementation: read the report

The report file (`app/build/variance-identification-report.txt`) is the actual deliverable — the code just produces it. After Task 8, read it and summarize for the human:
- Which candidate(s) beat B0 held-out **with an interior optimum** (the recommended structure).
- Whether the day-effect matches/beats the B1 release-valve gain without pinning.
- What the residual decomposition and same-muscle correlations say about *why*.
- The light-lift swing numbers (color for the deferred prescription-stability work).

This summary — not a code change — feeds the follow-up implementation phase's brainstorm. **No production constant is adopted in this sub-project.**
