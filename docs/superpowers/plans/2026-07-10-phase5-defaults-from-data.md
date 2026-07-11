# Phase 5 — Defaults From Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an offline, re-runnable forward-chaining cross-validation harness that proposes new global defaults for the four estimator hyperparameters from real user histories, honestly under n=1, for human adoption.

**Architecture:** A pure-JVM, test-tree component (`RecalibrationHarness`) reuses the existing `HyperparameterFitter`, `ReplayEngine`, `SessionProgressionStepper`, and `PredictiveScoreAccumulator`. For each user history it walks folds `k`, fits θ on `sessions[1..k]`, and scores the held-out one-step-ahead prediction of session `k+1` by differencing two scored replays. It aggregates folds into per-parameter verdicts (trajectory, proposed multiplier, out-of-sample CV delta, robustness flag). A separate integration test runs it on the personal fixture and writes a report. No `app/src/main` / runtime changes; no Room migration.

**Tech Stack:** Kotlin, JUnit4, JVM unit tests (`./gradlew :app:testDebugUnitTest`). Existing domain classes in `io.github.fowles.stochastic_strength.domain.progression` and `...domain.backtest`.

## Global Constraints

- Package for all new files: `io.github.fowles.stochastic_strength.domain.backtest`.
- All new code lives under `app/src/test/java/...` (test tree). No production/runtime code changes. No `EstimatorConfig` constant edits in this plan (adoption is a later, human-gated step).
- The four fitted parameters and defaults (order used by `HyperparameterFitter.applyTheta`): `detrainRatePerWeek` (0.01), `fatiguePerSet` (0.03), `processNoisePerDay` (8.0e-5), τ-scale (shared over `tauBarbell` 0.08 / `tauMachineCable` 0.20 / `tauOtherLoaded` 0.25).
- Harness `FitConfig` (distinct from production): `boundMultiplierLo = 1.0/16.0`, `boundMultiplierHi = 16.0`, `priorSd = 1.5`, `minFitSessions = 8`, `maxIterations = 200`.
- `minFoldSessions = 8`. Folds run `k = 8 .. N−1` where `N` = completed session count.
- `RecalibrationReportTest` must no-op when `app/src/test/resources/backtest/history.json` is absent (personal, gitignored), exactly like the existing backtest tests.
- Run the specific test after each change; run `./gradlew :app:testDebugUnitTest` at the end.

---

### Task 1: Sub-history truncation helper

Truncate a `ReplayHistory` to its first `k` completed sessions (ordered by `endTime`), keeping only the sets and session-overrides for those sessions. Initial (session-null) overrides are always retained.

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarness.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarnessTest.kt`

**Interfaces:**
- Consumes: `ReplayHistory(sessions: List<WorkoutSession>, setsBySession: Map<Long, List<WorkoutSet>>, initialOverrides: List<ExerciseStrengthOverride>, sessionOverrides: Map<Long, List<ExerciseStrengthOverride>>)` from `domain.progression.ReplayHistory`. `WorkoutSession` has `id: Long` and `endTime: Long?`.
- Produces: `RecalibrationHarness.truncateTo(history: ReplayHistory, k: Int): ReplayHistory`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.entity.WorkoutSession
import io.github.fowles.stochastic_strength.data.entity.WorkoutSet
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class RecalibrationHarnessTest {

    private fun session(id: Long, end: Long) =
        WorkoutSession(id = id, startTime = end - 1000, endTime = end)

    private fun set(id: Long, sessionId: Long) =
        WorkoutSet(id = id, sessionId = sessionId, exerciseId = 1L, weightKg = 20f, reps = 10)

    private fun history(n: Int): ReplayHistory {
        val sessions = (1..n).map { session(it.toLong(), end = it * 1000L) }
        val sets = (1..n).associate { it.toLong() to listOf(set(it.toLong(), it.toLong())) }
        return ReplayHistory(
            sessions = sessions,
            setsBySession = sets,
            initialOverrides = emptyList(),
            sessionOverrides = emptyMap(),
        )
    }

    @Test
    fun truncateTo_keepsFirstKSessionsAndTheirSets() {
        val h = history(5)
        val t = RecalibrationHarness.truncateTo(h, 3)
        assertEquals(listOf(1L, 2L, 3L), t.sessions.map { it.id })
        assertEquals(setOf(1L, 2L, 3L), t.setsBySession.keys)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.RecalibrationHarnessTest"`
Expected: FAIL — `RecalibrationHarness` unresolved (compile error). (Confirm `WorkoutSession` / `WorkoutSet` constructor param names by opening `app/src/main/java/io/github/fowles/stochastic_strength/data/entity/WorkoutSession.kt` and `WorkoutSet.kt`; adjust the test helpers to the real required params — these entities may have more non-default fields.)

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory

/**
 * Phase-5 offline recalibration: forward-chaining cross-validation over real histories to
 * propose new global defaults for the four fitted estimator hyperparameters. Analysis-only,
 * test-tree; changes no production constant (adoption is a separate human-gated step).
 */
object RecalibrationHarness {

    /** First [k] completed sessions (ordered by endTime), with only their sets/overrides. */
    fun truncateTo(history: ReplayHistory, k: Int): ReplayHistory {
        val kept = history.sessions
            .sortedBy { it.endTime ?: Long.MAX_VALUE }
            .take(k)
        val keptIds = kept.map { it.id }.toSet()
        return history.copy(
            sessions = kept,
            setsBySession = history.setsBySession.filterKeys { it in keptIds },
            sessionOverrides = history.sessionOverrides.filterKeys { it in keptIds },
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.RecalibrationHarnessTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarness.kt app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarnessTest.kt
git commit -m "test(phase5): sub-history truncation helper for recalibration harness"
```

---

### Task 2: Scored-replay total + per-fold held-out scoring

Add a scored-replay helper and the forward-chaining fold loop. Fitting is injected as a function so the fold logic is unit-testable without invoking Nelder-Mead.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarness.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarnessTest.kt`

**Interfaces:**
- Consumes: `ReplayEngine(stepper, config).run(history, snapshot, observer)`; `SessionProgressionStepper(config = ..., scorer = acc)`; `PredictiveScoreAccumulator().total`; `EstimatorConfig()`; `ReplaySnapshot`.
- Produces:
  - `RecalibrationHarness.UserHistory(history: ReplayHistory, newSnapshot: () -> ReplaySnapshot)`.
  - `RecalibrationHarness.scoredReplayTotal(history: ReplayHistory, config: EstimatorConfig, newSnapshot: () -> ReplaySnapshot): Double`.
  - `RecalibrationHarness.FoldRow(k: Int, multipliers: DoubleArray, heldOutProposed: Double, heldOutDefault: Double)`.
  - `RecalibrationHarness.foldScores(user: UserHistory, minFoldSessions: Int = 8, fit: (ReplayHistory) -> EstimatorConfig): List<FoldRow>`.

- [ ] **Step 1: Write the failing test**

```kotlin
// Add to RecalibrationHarnessTest, plus these imports at the top of the file:
// import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
// import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

    private fun emptySnapshot() = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to "chest"),
        seedCoefficients = mapOf(1L to 1.0f),
        exerciseEquipment = emptyMap(),
    )

    @Test
    fun foldScores_enumeratesFoldsAndDefaultMatchesProposedForIdentityFit() {
        val user = RecalibrationHarness.UserHistory(history(6)) { emptySnapshot() }
        // Identity fit: always return defaults -> proposed == default per fold.
        val rows = RecalibrationHarness.foldScores(user, minFoldSessions = 3) { EstimatorConfig() }
        // Folds k = 3,4,5 (k .. N-1, N=6)
        assertEquals(listOf(3, 4, 5), rows.map { it.k })
        rows.forEach { assertEquals(it.heldOutDefault, it.heldOutProposed, 1e-9) }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.RecalibrationHarnessTest"`
Expected: FAIL — `UserHistory` / `foldScores` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add to `RecalibrationHarness` (new imports at top of file: `ReplaySnapshot`, `EstimatorConfig`, `ReplayEngine`, `SessionProgressionStepper`, `PredictiveScoreAccumulator`):

```kotlin
    data class UserHistory(
        val history: ReplayHistory,
        val newSnapshot: () -> io.github.fowles.stochastic_strength.domain.ReplaySnapshot,
    )

    data class FoldRow(
        val k: Int,
        val multipliers: DoubleArray,
        val heldOutProposed: Double,
        val heldOutDefault: Double,
    )

    private val DEFAULTS = io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig()

    /** Sum of one-step-ahead predictive log-scores over a replay of [history] under [config]. */
    fun scoredReplayTotal(
        history: ReplayHistory,
        config: io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig,
        newSnapshot: () -> io.github.fowles.stochastic_strength.domain.ReplaySnapshot,
    ): Double {
        val acc = io.github.fowles.stochastic_strength.domain.progression.PredictiveScoreAccumulator()
        val engine = io.github.fowles.stochastic_strength.domain.progression.ReplayEngine(
            io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepper(config = config, scorer = acc),
            config,
        )
        engine.run(history, newSnapshot()) { _, _, _, _, _ -> }
        return acc.total
    }

    /** Multipliers of a fitted config over the defaults, in applyTheta order (drift,fatigue,procNoise,tau). */
    private fun multipliersOf(
        c: io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig,
    ): DoubleArray = doubleArrayOf(
        (c.detrainRatePerWeek / DEFAULTS.detrainRatePerWeek).toDouble(),
        (c.fatiguePerSet / DEFAULTS.fatiguePerSet).toDouble(),
        (c.processNoisePerDay / DEFAULTS.processNoisePerDay).toDouble(),
        (c.tauBarbell / DEFAULTS.tauBarbell).toDouble(),
    )

    /**
     * Forward-chaining CV over one user history. For each fold k in [minFoldSessions, N-1],
     * fit θ on sessions[1..k] and score the held-out one-step-ahead prediction of session k+1
     * by differencing scored replays of [1..k+1] and [1..k] under the same θ. Default θ scored
     * the same way is the honest baseline.
     */
    fun foldScores(
        user: UserHistory,
        minFoldSessions: Int = 8,
        fit: (ReplayHistory) -> io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig,
    ): List<FoldRow> {
        val n = user.history.sessions.count { it.endTime != null }
        val rows = mutableListOf<FoldRow>()
        for (k in minFoldSessions..(n - 1)) {
            val train = truncateTo(user.history, k)
            val trainPlus = truncateTo(user.history, k + 1)
            val theta = fit(train)
            fun heldOut(config: io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig): Double =
                scoredReplayTotal(trainPlus, config, user.newSnapshot) -
                    scoredReplayTotal(train, config, user.newSnapshot)
            rows += FoldRow(
                k = k,
                multipliers = multipliersOf(theta),
                heldOutProposed = heldOut(theta),
                heldOutDefault = heldOut(DEFAULTS),
            )
        }
        return rows
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.RecalibrationHarnessTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarness.kt app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarnessTest.kt
git commit -m "test(phase5): forward-chaining fold held-out scoring via replay differencing"
```

---

### Task 3: Per-parameter aggregation, robustness flags, report assembly

Collapse the fold rows into one verdict per parameter: the multiplier trajectory, the proposed multiplier (median of mature folds), the out-of-sample CV delta (Σ proposed − Σ default), and a robustness flag.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarness.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarnessTest.kt`

**Interfaces:**
- Consumes: `FoldRow` (Task 2), harness `FitConfig` bounds (`boundMultiplierLo = 1/16`, `boundMultiplierHi = 16`).
- Produces:
  - `RecalibrationHarness.Flag { STABLE, PINS_BOUND, FRAGILE }`.
  - `RecalibrationHarness.ParamVerdict(name: String, trajectory: List<Double>, proposedMultiplier: Double, cvDelta: Double, flag: Flag)`.
  - `RecalibrationHarness.RecalibrationReport(sessionCount: Int, foldCount: Int, params: List<ParamVerdict>, cvTotalProposed: Double, cvTotalDefault: Double)`.
  - `RecalibrationHarness.assemble(user: UserHistory, rows: List<FoldRow>, loBound: Double, hiBound: Double): RecalibrationReport`.
  - `RecalibrationHarness.classify(trajectory: List<Double>, loBound: Double, hiBound: Double): Flag`.

Flag rules (over the *mature-half* of the trajectory — the later folds, which have the most data): let `med` = median, `spread` = (p75 − p25) / med (relative IQR). `PINS_BOUND` if ≥ half the mature folds sit within 1% of `loBound` or of `hiBound`. Else `STABLE` if `spread ≤ 0.25`. Else `FRAGILE`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun classify_flagsStablePinnedAndFragileTrajectories() {
        val lo = 1.0 / 16.0
        val hi = 16.0
        // Tight around 6.0 -> STABLE
        assertEquals(
            RecalibrationHarness.Flag.STABLE,
            RecalibrationHarness.classify(listOf(5.9, 6.0, 6.1, 6.0), lo, hi),
        )
        // Sitting at the upper bound -> PINS_BOUND
        assertEquals(
            RecalibrationHarness.Flag.PINS_BOUND,
            RecalibrationHarness.classify(listOf(16.0, 16.0, 15.99, 16.0), lo, hi),
        )
        // All over the place -> FRAGILE
        assertEquals(
            RecalibrationHarness.Flag.FRAGILE,
            RecalibrationHarness.classify(listOf(0.5, 3.0, 1.0, 9.0), lo, hi),
        )
    }

    @Test
    fun assemble_producesFourVerdictsAndCvTotals() {
        val user = RecalibrationHarness.UserHistory(history(6)) { emptySnapshot() }
        val rows = RecalibrationHarness.foldScores(user, minFoldSessions = 3) { EstimatorConfig() }
        val report = RecalibrationHarness.assemble(user, rows, 1.0 / 16.0, 16.0)
        assertEquals(4, report.params.size)
        assertEquals(listOf("drift", "fatigue", "procNoise", "tau"), report.params.map { it.name })
        // Identity fit -> proposed multipliers all 1.0, CV delta ~0.
        report.params.forEach { assertEquals(1.0, it.proposedMultiplier, 1e-9) }
        assertEquals(0.0, report.cvTotalProposed - report.cvTotalDefault, 1e-9)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.RecalibrationHarnessTest"`
Expected: FAIL — `Flag` / `classify` / `assemble` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add to `RecalibrationHarness`:

```kotlin
    enum class Flag { STABLE, PINS_BOUND, FRAGILE }

    data class ParamVerdict(
        val name: String,
        val trajectory: List<Double>,
        val proposedMultiplier: Double,
        val cvDelta: Double,
        val flag: Flag,
    )

    data class RecalibrationReport(
        val sessionCount: Int,
        val foldCount: Int,
        val params: List<ParamVerdict>,
        val cvTotalProposed: Double,
        val cvTotalDefault: Double,
    )

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val idx = (p * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
        val lo = idx.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = idx - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    private fun median(xs: List<Double>): Double = percentile(xs.sorted(), 0.5)

    /** Later half of the trajectory (most data); at least the last element. */
    private fun matureHalf(trajectory: List<Double>): List<Double> {
        if (trajectory.isEmpty()) return trajectory
        val from = trajectory.size / 2
        return trajectory.subList(from, trajectory.size)
    }

    fun classify(trajectory: List<Double>, loBound: Double, hiBound: Double): Flag {
        val mature = matureHalf(trajectory)
        if (mature.isEmpty()) return Flag.FRAGILE
        val atBound = mature.count { m ->
            kotlin.math.abs(m - loBound) <= 0.01 * loBound || kotlin.math.abs(m - hiBound) <= 0.01 * hiBound
        }
        if (atBound * 2 >= mature.size) return Flag.PINS_BOUND
        val sorted = mature.sorted()
        val med = median(mature)
        val spread = if (med == 0.0) Double.MAX_VALUE else (percentile(sorted, 0.75) - percentile(sorted, 0.25)) / med
        return if (spread <= 0.25) Flag.STABLE else Flag.FRAGILE
    }

    fun assemble(user: UserHistory, rows: List<FoldRow>, loBound: Double, hiBound: Double): RecalibrationReport {
        val names = listOf("drift", "fatigue", "procNoise", "tau")
        val verdicts = names.mapIndexed { i, name ->
            val trajectory = rows.map { it.multipliers[i] }
            ParamVerdict(
                name = name,
                trajectory = trajectory,
                proposedMultiplier = median(matureHalf(trajectory)),
                cvDelta = rows.sumOf { it.heldOutProposed } - rows.sumOf { it.heldOutDefault },
                flag = classify(trajectory, loBound, hiBound),
            )
        }
        return RecalibrationReport(
            sessionCount = user.history.sessions.count { it.endTime != null },
            foldCount = rows.size,
            params = verdicts,
            cvTotalProposed = rows.sumOf { it.heldOutProposed },
            cvTotalDefault = rows.sumOf { it.heldOutDefault },
        )
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.RecalibrationHarnessTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarness.kt app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarnessTest.kt
git commit -m "test(phase5): per-param verdicts, robustness flags, report assembly"
```

---

### Task 4: Real-fit wiring + integration report test

Wire the real `HyperparameterFitter` (with the widened harness `FitConfig`) as the fold fit function, add a top-level `run(...)`, and add the integration test that no-ops without the fixture, otherwise runs on the real history and prints/writes the report.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarness.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationReportTest.kt`

**Interfaces:**
- Consumes: `HyperparameterFitter(defaults, fitConfig).fit(history, newSnapshot).config`; `FitConfig(minFitSessions, boundMultiplierLo, boundMultiplierHi, priorSd, maxIterations)`; `BacktestHarness.load()` → `BacktestData` with `.history` and `.newSnapshot()`.
- Produces:
  - `RecalibrationHarness.harnessFitConfig(): FitConfig`.
  - `RecalibrationHarness.run(users: List<UserHistory>, minFoldSessions: Int = 8): RecalibrationReport` (aggregates fold rows across all users, then assembles).
  - `RecalibrationHarness.format(report: RecalibrationReport): String` (human-readable table).

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RecalibrationReportTest {

    @Test
    fun recalibrationReport_onRealHistory_printsAndWrites() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!

        val user = RecalibrationHarness.UserHistory(data.history) { data.newSnapshot() }
        val report = RecalibrationHarness.run(listOf(user))
        val text = RecalibrationHarness.format(report)
        println(text)

        val out = File("build/recalibration-report.txt")
        out.parentFile.mkdirs()
        out.writeText(text)
        // Evidence, not a gate: assert only that it ran to completion over some folds.
        assert(report.foldCount >= 0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.RecalibrationReportTest"`
Expected: FAIL — `run` / `format` / `harnessFitConfig` unresolved (compile error). (If the fixture is absent the test will still fail to compile until Step 3; after Step 3 it will be SKIPPED via the assumption when the fixture is absent.)

- [ ] **Step 3: Write minimal implementation**

Add to `RecalibrationHarness`:

```kotlin
    fun harnessFitConfig(): io.github.fowles.stochastic_strength.domain.progression.FitConfig =
        io.github.fowles.stochastic_strength.domain.progression.FitConfig(
            minFitSessions = 8,
            boundMultiplierLo = 1.0 / 16.0,
            boundMultiplierHi = 16.0,
            priorSd = 1.5,
            maxIterations = 200,
        )

    fun run(users: List<UserHistory>, minFoldSessions: Int = 8): RecalibrationReport {
        val fitConfig = harnessFitConfig()
        // Each fold fits θ on the training sub-history with this user's own snapshot factory.
        val allRows = users.flatMap { user ->
            foldScores(user, minFoldSessions) { train ->
                io.github.fowles.stochastic_strength.domain.progression.HyperparameterFitter(DEFAULTS, fitConfig)
                    .fit(train) { user.newSnapshot() }
                    .config
            }
        }
        val ref = users.first()
        return assemble(ref, allRows, fitConfig.boundMultiplierLo, fitConfig.boundMultiplierHi)
            .copy(sessionCount = users.sumOf { it.history.sessions.count { s -> s.endTime != null } })
    }

    fun format(report: RecalibrationReport): String {
        val sb = StringBuilder()
        sb.appendLine("Phase-5 recalibration report")
        sb.appendLine("sessions=${report.sessionCount} folds=${report.foldCount}")
        sb.appendLine("CV total: proposed=${"%.3f".format(report.cvTotalProposed)} default=${"%.3f".format(report.cvTotalDefault)} delta=${"%.3f".format(report.cvTotalProposed - report.cvTotalDefault)}")
        sb.appendLine("param      proposed×  flag        trajectory")
        for (p in report.params) {
            val traj = p.trajectory.joinToString(",") { "%.2f".format(it) }
            sb.appendLine("%-10s %-9s %-11s %s".format(p.name, "%.3f".format(p.proposedMultiplier), p.flag, traj))
        }
        return sb.toString()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.RecalibrationReportTest"`
Expected: PASS if the personal fixture is present (prints the report, writes `app/build/recalibration-report.txt`); otherwise SKIPPED via the JUnit assumption. Either outcome is green.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationHarness.kt app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/RecalibrationReportTest.kt
git commit -m "test(phase5): real-fit wiring + integration report over pooled histories"
```

---

### Task 5: Full-suite regression + report review

Run the whole JVM suite to confirm no regressions, then read the generated report and summarize the proposed defaults for the human adoption decision.

**Files:** none (verification only).

- [ ] **Step 1: Run the full JVM unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (no regressions; the new tests green, `RecalibrationReportTest` runs on the fixture or is skipped).

- [ ] **Step 2: Read and summarize the report**

Open `app/build/recalibration-report.txt` (if the fixture was present). For each of the four params, note: proposed multiplier, flag (STABLE / PINS_BOUND / FRAGILE), and the aggregate CV delta. Confirm the expectation from Phase 4 that `procNoise` pins high (now against the ÷16…×16 box) — either it lands interior (report the value) or still pins ×16 (report the direction).

- [ ] **Step 3: Present the adoption recommendation (no code change)**

Write a short summary for the human: which params are STABLE-and-CV-positive (recommend adopting the proposed multiplier × default as the new constant), which PINS_BOUND (recommend adopting cautiously in the indicated direction, and note whether the production ÷4…×4 fitter bound should widen), and which are FRAGILE (recommend leaving at the guessed default). Do NOT edit `EstimatorConfig` — the adoption ceremony (spec §4: constant edit, `BeliefSimulationTest` re-pin, backtest re-baseline, `ProdBssPrescriptionTest` check, version bump, docs) is a separate, explicitly human-gated follow-up.

- [ ] **Step 4: Commit (docs only, if a durable report is wanted)**

If the human wants the report kept in-repo (it contains only aggregate multipliers, no personal set data), copy it to `docs/adaptation/phase5-recalibration-report.txt` and commit; otherwise skip.

```bash
git add docs/adaptation/phase5-recalibration-report.txt
git commit -m "docs(phase5): recalibration CV report (aggregate multipliers only)"
```

---

## Self-Review Notes

- **Spec §2 (harness, forward-chaining, differencing):** Tasks 1–2. ✓
- **Spec §3 (per-param report, flags, bound-widening, weak prior, `minFitSessions`=8):** Task 3 (flags/report) + Task 4 (`harnessFitConfig` with 1/16…16, priorSd 1.5, minFitSessions 8). ✓
- **Spec §3 output (printed + written artifact):** Task 4 `format` + `RecalibrationReportTest` write; Task 5 optional durable copy. ✓
- **Spec §4 (adoption ceremony is a separate human-gated step):** Task 5 Step 3 explicitly stops before editing `EstimatorConfig`. ✓
- **Spec §5 (testing: unit on synthetic, no-op integration):** Tasks 1–3 synthetic unit tests; Task 4 `assumeTrue`-gated integration. ✓
- **Entity constructor caveat:** Task 1 Step 2 instructs verifying `WorkoutSession`/`WorkoutSet` real constructor params before relying on the test helpers, since these Room entities likely have more required fields than the minimal helper shows.
