# Phase 3: Swap, Trace, Delete — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Phase-2 belief stack (`domain/belief/`) the live production estimator — replay drives belief folds, prescriptions come from `BeliefPrescriber` + policy (nudge ON), charts/debug read μ/σ with an uncertainty band, a "why this weight" trace explains every prescription — then delete the old estimator entirely.

**Architecture:** Per the binding constitution `docs/superpowers/specs/2026-07-14-estimator-rebuild-design.md` (Phase 3 section). The one non-obvious structural move: extract the per-session belief step (pre-fold pooling → cold-prior fold → post-fold projection) out of the test-tree `BeliefStackReplay` into a prod `BeliefSessionStep`, and have BOTH the prod `ReplayEngine` and the backtest delegate to it. That turns the "KEEP IN SYNC with MainStackReplay" comment into a compile-time guarantee: the backtest scores exactly the stack that runs in prod. The swap is staged so every commit compiles and is testable: (1) measure the flagged bind magnitude, (2) extract the shared step (score must stay bit-identical), (3) thread beliefs through the prod replay in parallel with the old stack (no behavior change), (4) flip derived-state writes + planner prescriptions to beliefs (the behavior change, gated by invariants), (5) swap charts, (6) add the trace, (7) delete the old estimator, (8) ship-gate ceremony (score gate, bind report, constant census, docs, full suites).

**Tech Stack:** Kotlin, JUnit4 JVM tests (`src/test`), instrumented Room tests (`src/androidTest`), Vico charts, jj for VCS.

## Global Constraints

- Constitution: authority = forward-chaining held-out CV on local `app/src/test/resources/backtest/history.json` (gitignored; tests `Assume`-skip without it). Constants only `fitted`/`flat`/`semantic`. Safety = untuned prescription-time clamps invisible to the fitness function. Policy = log-fact arithmetic only.
- **Ship gate (spec):** belief stack held-out ≥ main's baseline (recorded: belief **24.3274** total / 237 scored + 9 skipped vs main re-baselined **28.4451** / 0.12002 / 237 + 9); failure invariants green; clamp-bind report reviewed; constant census matches the ledger; full JVM + instrumented suites green.
- Belief constants (adopted Phase 2, do NOT retune in this phase): σ_seed 0.15, σ_override 0.10, φ 0.01, qPerDay 3e-6, σ_obs 0.005, τ 0.2, σ² floor 4e-4 / cap 0.25, z 0.5244, nudge = one grid increment.
- Phase-2 flag to resolve FIRST (memory + phase-2 plan appendix): bind rate 7.4% (125 binds) vs main 3.4% (58) on the same history; nudge exonerated by ablation (nudge-off identical); hypothesis = z-target crosses demonstrated caps; **measure bind magnitude before wiring live** (Task 1).
- `PolicyFacts` stays built at plan time from the set log in `buildPlanner` (deliberate deviation from the spec's "DerivedStateStore holds PolicyFacts" sentence): the replay iterates only completed sessions (`endTime != null`), so replay-built facts would DROP failure caps demonstrated in an abandoned (never-finished) session that the current set-log query still sees. Facts are still "rebuilt from the set log alone" — the constitutional point — just at plan time. Record this in the swap commit message.
- VCS is jj (not git). Commit with `jj commit -m "..."`. **Run `jj commit` exactly once per task; if jj reports "divergent", STOP and report — do not retry.**
- Subagent report/brief files in `.superpowers/sdd/` must be prefixed `phase3-` (cross-run collisions happened in phase 2).
- Instrumented tests: run `./gradlew :app:connectedAndroidTest` directly (emulator is typically running) — Tasks 4 and 8.
- After deleting code, remove now-unused imports/deps. No new external dependencies anywhere in this plan.

## File Structure (end state)

```
main/domain/belief/
  Belief.kt              (unchanged: Belief + BeliefConfig ledger)
  BeliefFold.kt          (obsSigma loses its unused param — carry-forward)
  BeliefPooling.kt       (unchanged)
  BeliefPrescriber.kt    (unchanged)
  BeliefSessionStep.kt   (NEW: shared per-session step, prod + backtest)
  SetObservation.kt      (NEW: per-set fresh-capacity observation point for charts)
  PrescriptionTrace.kt   (NEW: "why this weight" trace builder)
main/domain/progression/
  ReplayEngine.kt        (drives BeliefSessionStep; seeds beliefs from override rows)
  CrossTuning.kt         (rewritten on beliefs/pooling)
  ExerciseProgressionSeriesBuilder.kt (rewritten: μ/σ lines + band + per-set dots)
  ObservedSet.kt         (owns its reserve constants)
  ExerciseSeedExpansion.kt (untouched)
  — DELETED: ExerciseEstimate.kt (incl EstimatorConfig), ExerciseEstimateUpdater.kt,
    MuscleStrengthProjector.kt, SessionProgressionStepper.kt
main/domain/
  ReplaySnapshot.kt      (currentBeliefs replaces currentEstimates)
  WorkoutRepository.kt   (belief pooling → BeliefPrescriber → planner; nudge ON; trace accessor)
  — DELETED: SessionSignalExtractor.kt
main/domain/derived/DerivedStateStore.kt (exerciseBeliefs replaces exerciseEstimates)
main/ui/debug/  (trace section on ExerciseCoefficientDetailScreen; band series on the chart)
main/ui/exercises/ExerciseDetailViewModel.kt (per-set dots via SetObservation)
test/domain/backtest/
  BeliefStackReplay.kt   (thin adapter over BeliefSessionStep)
  ScoreReport.kt         (NEW: SessionScore/ScoreReport moved out of HeldOutScorer.kt)
  — DELETED: MainStackReplay(+Test), HeldOutScorer(+Test), BaselineReportTest,
    CapViolationDiagnostic(+Test)
```

---

### Task 1: Measure the flagged bind magnitude (rule-4 review item)

The Phase-2 report flagged 125 cap binds (7.4%) with the z-target crossing demonstrated caps. Before wiring the stack live, measure HOW FAR prescriptions cross: if binds are one grid increment (designed creep against a ceiling), the rate is cosmetic; if they are multiple increments, that is a genuine estimator bug to surface before the swap.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicy.kt` (add `uncappedWeightKg` to `Prescription`)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefPolicyBacktestTest.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicyTest.kt`

**Interfaces:**
- Produces: `data class Prescription(val weightKg: Float, val capBound: Boolean, val hurtMultiplier: Float, val uncappedWeightKg: Float)` — `uncappedWeightKg` is the rounded, nudged, pre-cap weight (== `weightKg` when the cap doesn't bind). Later tasks (trace, planner) rely on this exact shape.

- [x] **Step 1: Write the failing test** — add to `PrescriptionPolicyTest.kt`:

```kotlin
@Test
fun prescriptionReportsUncappedWeightWhenCapBinds() {
    // Cap demonstrated well below the raw target: raw 100 kg e1rm at 5 reps vs a cap of ln(80).
    val facts = PolicyFacts(
        capByExercise = mapOf(7L to ExerciseCapFact(capLn = ln(80f), demonstratedAt = 1_000L)),
    )
    val p = PrescriptionPolicy.prescribe(
        rawE1rm = 100f, sessionReps = 5, exerciseId = 7L, muscle = MuscleGroup.QUADS,
        facts = facts, now = 2_000L, weightUnit = WeightUnit.KG, engine = DefaultProgressionEngine,
    )
    assertTrue(p.capBound)
    // The uncapped weight is what the engine would have prescribed with no cap.
    val free = PrescriptionPolicy.prescribe(
        rawE1rm = 100f, sessionReps = 5, exerciseId = 99L, muscle = MuscleGroup.QUADS,
        facts = PolicyFacts.EMPTY, now = 2_000L, weightUnit = WeightUnit.KG, engine = DefaultProgressionEngine,
    )
    assertEquals(free.weightKg, p.uncappedWeightKg, 1e-4f)
    assertEquals(free.weightKg, free.uncappedWeightKg, 1e-4f)
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicyTest"`
Expected: FAIL — no `uncappedWeightKg` parameter.

- [x] **Step 3: Implement** — in `PrescriptionPolicy.kt`:

```kotlin
/** One clamped prescription. [capBound]/[hurtMultiplier]/[uncappedWeightKg] feed the clamp-bind health report. */
data class Prescription(
    val weightKg: Float,
    val capBound: Boolean,
    val hurtMultiplier: Float,
    /** The rounded (and nudged) weight the engine wanted BEFORE the cap — == [weightKg] unless the cap bound. */
    val uncappedWeightKg: Float,
)
```

and thread it through `prescribe` (three return sites):

```kotlin
if (capLn == null) return Prescription(uncapped, capBound = false, hurtMultiplier = mult, uncappedWeightKg = uncapped)
...
if (uncapped <= capWeight + WeightFormatter.GRID_EPSILON) return Prescription(uncapped, capBound = false, hurtMultiplier = mult, uncappedWeightKg = uncapped)
return Prescription(WeightFormatter.roundDown(capWeight, weightUnit), capBound = true, hurtMultiplier = mult, uncappedWeightKg = uncapped)
```

- [x] **Step 4: Run the policy tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicyTest"`
Expected: PASS (all, including existing tests — the added field has no behavior).

- [x] **Step 5: Extend the bind report with magnitude** — in `BeliefPolicyBacktestTest.kt`, inside the `if (p.capBound)` branch collect the overshoot, and report a distribution. Add next to the existing counters:

```kotlin
val bindOvershoots = mutableListOf<Float>() // kg the engine wanted above the capped weight
```

in the bind branch:

```kotlin
if (p.capBound) {
    capBinds++
    bindsByExercise[exerciseId] = (bindsByExercise[exerciseId] ?: 0) + 1
    bindOvershoots += p.uncappedWeightKg - p.weightKg
}
```

and in the report (after the per-exercise line), measuring in grid increments (`WeightFormatter.minIncrement(data.weightUnit)`):

```kotlin
val inc = WeightFormatter.minIncrement(data.weightUnit)
val inIncrements = bindOvershoots.map { it / inc }
appendLine("bind magnitude (grid increments of %.2f kg):".format(inc))
appendLine("  ≤1: ${inIncrements.count { it <= 1f + 1e-3f }}  " +
    "≤2: ${inIncrements.count { it <= 2f + 1e-3f }}  " +
    ">2: ${inIncrements.count { it > 2f + 1e-3f }}")
appendLine("  mean %.2f  max %.2f".format(inIncrements.average(), inIncrements.max()))
```

- [x] **Step 6: Run and record**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefPolicyBacktestTest" --info | grep -A 25 "clamp-bind"`
Expected: PASS (0 violations, 125 binds unchanged) + the new magnitude lines. **Record the full report in this plan's Results appendix.** Review rule: if the mean overshoot is ≤ ~1 increment, note "designed creep against the ceiling — accepted" and proceed; if materially larger, STOP and surface to the user before Task 4 (the behavior flip) — do not silently continue.

- [x] **Step 7: Run the full unit suite** (the `Prescription` shape change touches prod)

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [x] **Step 8: Commit**

```bash
jj commit -m "test(backtest): bind-magnitude distribution in the clamp-bind report (phase-3 rule-4 review)"
```

---

### Task 2: Extract `BeliefSessionStep` (shared prod/backtest per-session step)

Move the per-session belief logic out of the test tree into prod, and make `BeliefStackReplay` a thin adapter. The held-out score must stay **bit-identical** (24.3274) — this task changes structure, not math.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/belief/BeliefSessionStep.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefStackReplay.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/belief/BeliefSessionStepTest.kt`

**Interfaces:**
- Consumes: `BeliefFold`, `BeliefPooling`, `EffectiveBelief`, `Belief`, `BeliefConfig` (existing, unchanged).
- Produces (later tasks call exactly this):

```kotlin
class BeliefSessionStep(private val config: BeliefConfig) {
    data class MuscleStep(
        val muscle: MuscleGroup,
        /** exp(levelLn): the muscle level for MuscleGroupStrength/baseline_history (0 if no voters). */
        val level: Float,
        /** exp(mu_eff) per exercise, post-fold pooling at asOf. */
        val effectiveE1rm: Map<Long, Float>,
        /** effectiveE1rm / level, so level × coef == effectiveE1rm (parity with the old projector). */
        val derivedCoef: Map<Long, Float>,
    )
    data class Result(
        /** Pre-fold effective beliefs for ALL muscles at asOf — the held-out state; also the cold prior. */
        val preFoldEffective: Map<Long, EffectiveBelief>,
        /** Post-fold projections for the muscles this session touched. */
        val steps: List<MuscleStep>,
    )
    fun step(
        beliefs: MutableMap<Long, Belief>,
        sets: List<WorkoutSet>,
        seedCoef: Map<Long, Float>,
        exerciseMuscle: Map<Long, MuscleGroup>,
        muscleExerciseIds: Map<MuscleGroup, List<Long>>,
        asOf: Long,
    ): Result
}
```

- [x] **Step 1: Write the failing tests** — `BeliefSessionStepTest.kt`. Key behaviors, all hand-computable (reuse the numeric style of `BeliefFoldTest`/`BeliefPoolingTest`):

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class BeliefSessionStepTest {
    private val config = BeliefConfig()
    private val step = BeliefSessionStep(config)

    private fun set(id: Long, exerciseId: Long, weight: Float, reps: Int, feedback: SetFeedback?) = WorkoutSet(
        id = id, sessionId = 1L, exerciseId = exerciseId, setNumber = id.toInt(),
        targetWeight = weight, targetReps = reps, feedback = feedback, completedAt = 1_000L,
    )

    @Test
    fun foldMatchesBeliefFoldForAnExistingBelief() {
        val beliefs = mutableMapOf(7L to Belief(ln(100f), 0.01f, 0L))
        val expected = BeliefFold(config).foldSession(
            beliefs.getValue(7L), listOf(set(1, 7L, 90f, 5, SetFeedback.RIR_2_4)), asOf = 86_400_000L)
        step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 7L, 90f, 5, SetFeedback.RIR_2_4)),
            seedCoef = mapOf(7L to 1f),
            exerciseMuscle = mapOf(7L to MuscleGroup.QUADS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to listOf(7L)),
            asOf = 86_400_000L,
        )
        assertEquals(expected.mu, beliefs.getValue(7L).mu, 1e-6f)
        assertEquals(expected.sigma2, beliefs.getValue(7L).sigma2, 1e-6f)
    }

    @Test
    fun coldExerciseFoldsAgainstTheSiblingPredictionAsPrior() {
        // Trained sibling 1 (coef 1.0) at ln(100); cold target 2 (coef 0.5) has no belief.
        val beliefs = mutableMapOf(1L to Belief(ln(100f), 0.01f, 1_000L))
        val result = step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 2L, 40f, 5, SetFeedback.RIR_2_4)),
            seedCoef = mapOf(1L to 1f, 2L to 0.5f),
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS, 2L to MuscleGroup.QUADS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to listOf(1L, 2L)),
            asOf = 1_000L,
        )
        // The pre-fold effective for the cold exercise is the sibling prediction…
        val pre = result.preFoldEffective.getValue(2L)
        assertEquals(ln(0.5f) + ln(100f), pre.mu, 1e-4f)
        // …and after the step the cold exercise HAS a belief (folded from that prior).
        assertTrue(2L in beliefs)
    }

    @Test
    fun untouchedMusclesGetNoPostFoldStepButDoGetPreFoldEffective() {
        val beliefs = mutableMapOf(
            1L to Belief(ln(100f), 0.01f, 1_000L), // QUADS, trained this session
            9L to Belief(ln(50f), 0.01f, 1_000L),  // BICEPS, not in this session
        )
        val result = step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 1L, 90f, 5, SetFeedback.RIR_0_1)),
            seedCoef = mapOf(1L to 1f, 9L to 0.6f),
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS, 9L to MuscleGroup.BICEPS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to listOf(1L), MuscleGroup.BICEPS to listOf(9L)),
            asOf = 1_000L,
        )
        assertEquals(listOf(MuscleGroup.QUADS), result.steps.map { it.muscle })
        assertTrue(9L in result.preFoldEffective)
    }

    @Test
    fun postFoldStepReportsLevelEffectiveAndDerivedCoef() {
        val beliefs = mutableMapOf(1L to Belief(ln(100f), 0.01f, 1_000L))
        val result = step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 1L, 90f, 5, SetFeedback.RIR_2_4)),
            seedCoef = mapOf(1L to 1f),
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to listOf(1L)),
            asOf = 1_000L,
        )
        val quads = result.steps.single()
        val eff = quads.effectiveE1rm.getValue(1L)
        // Single voter, coef 1: level == effective e1rm, derived coef == 1.
        assertEquals(eff, quads.level, 1e-3f)
        assertEquals(1f, quads.derivedCoef.getValue(1L), 1e-4f)
        // Post-fold: the RIR_2_4 fold ran before this projection.
        assertEquals(exp(beliefs.getValue(1L).mu), eff, 1e-3f)
    }

    @Test
    fun zeroCoefExercisesAreSkippedEntirely() {
        val beliefs = mutableMapOf<Long, Belief>()
        val result = step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 3L, 40f, 5, SetFeedback.RIR_2_4)),
            seedCoef = mapOf(3L to 0f),
            exerciseMuscle = mapOf(3L to MuscleGroup.QUADS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to emptyList()),
            asOf = 1_000L,
        )
        assertTrue(beliefs.isEmpty())
        assertTrue(result.steps.isEmpty())
    }
}
```

(Adjust the `WorkoutSet` constructor call to the real entity's parameter list — copy from `BeliefFoldTest`'s existing helper.)

- [x] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefSessionStepTest"`
Expected: FAIL — `BeliefSessionStep` unresolved.

- [x] **Step 3: Implement `BeliefSessionStep.kt`** — lift the loop body of `BeliefStackReplay.run` verbatim (same order of operations):

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.exp

/**
 * One session's belief step, shared by the production replay and the backtest replay (spec Phase 3
 * "replay drives the new fold"). Order of operations is the Phase-2 contract and must not change:
 * (1) pre-fold pooling over ALL muscles at asOf — the held-out state and the cold prior;
 * (2) per-exercise foldSession, existing belief or the sibling prediction as the cold prior;
 * (3) post-fold pooling for the touched muscles — the derived-state projection.
 */
class BeliefSessionStep(private val config: BeliefConfig) {
    private val fold = BeliefFold(config)
    private val pooling = BeliefPooling(config)

    data class MuscleStep(
        val muscle: MuscleGroup,
        val level: Float,
        val effectiveE1rm: Map<Long, Float>,
        val derivedCoef: Map<Long, Float>,
    )

    data class Result(
        val preFoldEffective: Map<Long, EffectiveBelief>,
        val steps: List<MuscleStep>,
    )

    fun step(
        beliefs: MutableMap<Long, Belief>,
        sets: List<WorkoutSet>,
        seedCoef: Map<Long, Float>,
        exerciseMuscle: Map<Long, MuscleGroup>,
        muscleExerciseIds: Map<MuscleGroup, List<Long>>,
        asOf: Long,
    ): Result {
        val preFold = mutableMapOf<Long, EffectiveBelief>()
        for ((_, ids) in muscleExerciseIds) {
            preFold.putAll(pooling.effective(beliefs, seedCoef, ids, asOf).effective)
        }

        val touched = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((seedCoef[id] ?: 0f) <= 0f) return@forEach
            val prior = beliefs[id]
                ?: preFold[id]?.let { Belief(it.mu, it.sigma2, asOf) }
                ?: return@forEach
            beliefs[id] = fold.foldSession(prior, exSets, asOf)
            exerciseMuscle[id]?.let { touched.add(it) }
        }

        val steps = touched.mapNotNull { muscle ->
            val ids = muscleExerciseIds[muscle] ?: return@mapNotNull null
            val pool = pooling.effective(beliefs, seedCoef, ids, asOf)
            val level = pool.levelLn?.let { exp(it) } ?: 0f
            val effective = pool.effective.mapValues { (_, e) -> exp(e.mu) }
            val coefs = effective.mapValues { (id, e1rm) ->
                if (level > 0f) e1rm / level else (seedCoef[id] ?: 0f)
            }
            MuscleStep(muscle = muscle, level = level, effectiveE1rm = effective, derivedCoef = coefs)
        }
        return Result(preFoldEffective = preFold, steps = steps)
    }
}
```

- [x] **Step 4: Run the new tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.BeliefSessionStepTest"`
Expected: PASS.

- [x] **Step 5: Rewire `BeliefStackReplay` to delegate** — replace its inner loop with a call to the step (predictions still come from the PRE-fold effective, unchanged semantics):

```kotlin
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
        val sessionStep = BeliefSessionStep(config)
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

            val result = sessionStep.step(
                beliefs = beliefs,
                sets = sets,
                seedCoef = snapshot.seedCoefficients,
                exerciseMuscle = data.exerciseMuscle,
                muscleExerciseIds = snapshot.muscleExerciseIds,
                asOf = asOf,
            )
            val predictions = sets.groupBy { it.exerciseId }.flatMap { (id, exSets) ->
                val eff = result.preFoldEffective[id]
                exSets.sortedBy { it.id }.mapIndexed { idx, s ->
                    SetPrediction(s, idx + 1, eff?.let { it.mu - fold.fatigueShift(idx + 1) })
                }
            }
            observer.onSession(session.id, asOf, predictions, result.preFoldEffective, beliefs)
        }
    }
}
```

Note: the step needs `exerciseMuscle`. If `BacktestData` doesn't already expose an id→muscle map, derive it in place from `data.backup.exercises.associate { it.id to it.primaryMuscle }` (check `BacktestData`/`newSnapshot()` — `ReplaySnapshot.exerciseMuscle` may already be on the snapshot; use whichever exists, don't add duplicate state). Update the class kdoc: drop "KEEP IN SYNC with MainStackReplay" in favor of "delegates to BeliefSessionStep — the same code the production replay runs".

- [x] **Step 6: Verify the score is bit-identical (the refactor gate)**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefScoreTest" --info | grep -B2 -A8 "belief stack held-out"`
Expected: PASS with **exactly** total 24.3274 / 237 scored / 9 skipped (unchanged from Phase 2). Also run `BeliefStackReplayTest`, `BeliefHeldOutScorerTest`, `BeliefPolicyBacktestTest` — all PASS with unchanged numbers (125 binds).

- [x] **Step 7: Commit**

```bash
jj commit -m "refactor(belief): extract BeliefSessionStep; backtest replay delegates to the prod step (score bit-identical)"
```

---

### Task 3: Thread beliefs through the production replay (dark — no behavior change)

Run the belief stack inside `ReplayEngine` alongside the old estimator: seed beliefs from override rows, fold per session, store the final belief map in `DerivedStateStore`. Derived writes and prescriptions still come from the old stack — instrumented tests must stay green untouched.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshot.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngine.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStore.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt` (observer arity only)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStoreTest.kt`, `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngineTest.kt`

**Interfaces:**
- Produces: `ReplaySnapshot.currentBeliefs: MutableMap<Long, Belief>`; `ReplayEngine.SessionObserver.onSession(sessionId, asOf, sets, snapshot, result, beliefResult: BeliefSessionStep.Result)`; `DerivedStateStore.Snapshot.exerciseBeliefs(): Map<Long, Belief>` + `MutableDerivedState.putExerciseBeliefs(Map<Long, Belief>)`.
- Belief seeding contract (matches `BeliefStackReplay`): initial override rows (`sessionId == null`) → `Belief(ln(e1rm), sigmaSeed², asOf)`; per-session override rows → `Belief(ln(e1rm), sigmaOverride², asOf)` applied before that session's step.

- [x] **Step 1: Write the failing tests.** In `DerivedStateStoreTest.kt` add:

```kotlin
@Test
fun beliefsSurviveRebuildAndDefaultEmpty() = runBlocking {
    val store = DerivedStateStore()
    assertTrue(store.snapshot().exerciseBeliefs().isEmpty())
    store.rebuild { it.putExerciseBeliefs(mapOf(3L to Belief(4.6f, 0.01f, 99L))) }
    assertEquals(4.6f, store.snapshot().exerciseBeliefs().getValue(3L).mu, 1e-6f)
}
```

In `ReplayEngineTest.kt` update the compile-shape guard to the six-arg observer (append `beliefResult = BeliefSessionStep.Result(emptyMap(), emptyList())` to the direct `onSession` call and a `_ ->` lambda slot).

- [x] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.derived.DerivedStateStoreTest" --tests "io.github.fowles.stochastic_strength.domain.progression.ReplayEngineTest"`
Expected: FAIL to compile.

- [x] **Step 3: Implement.**

`ReplaySnapshot.kt` — add alongside `currentEstimates`:

```kotlin
/** Per-exercise beliefs (the Phase-2 stack), updated as each session is folded in. */
val currentBeliefs: MutableMap<Long, Belief> = mutableMapOf()
```

`ReplayEngine.kt` — construct `BeliefSessionStep(BeliefConfig())` (add a `beliefConfig: BeliefConfig = BeliefConfig()` constructor parameter next to the stepper), seed both stacks, run both steps:

```kotlin
class ReplayEngine(
    private val stepper: SessionProgressionStepper = SessionProgressionStepper(),
    private val beliefConfig: BeliefConfig = BeliefConfig(),
) {
    private val beliefStep = BeliefSessionStep(beliefConfig)

    fun interface SessionObserver {
        fun onSession(
            sessionId: Long, asOf: Long, sets: List<WorkoutSet>, snapshot: ReplaySnapshot,
            result: SessionProgressionStepper.StepResult,
            beliefResult: BeliefSessionStep.Result,
        )
    }

    suspend fun run(db: AppDatabase, snapshot: ReplaySnapshot, observer: SessionObserver) {
        val sigmaSeed2 = beliefConfig.sigmaSeed * beliefConfig.sigmaSeed
        val sigmaOverride2 = beliefConfig.sigmaOverride * beliefConfig.sigmaOverride
        val initials = db.exerciseStrengthOverrideDao().getInitials()
        for (init in initials) {
            snapshot.currentEstimates[init.exerciseId] = ExerciseEstimate.seed(init.e1rm, at = init.asOf)
            snapshot.currentBeliefs[init.exerciseId] = Belief(ln(init.e1rm), sigmaSeed2, init.asOf)
        }
        val exerciseOverridesBySession = db.exerciseStrengthOverrideDao().getNonInitials().groupBy { it.sessionId!! }
        val sessions = db.workoutSessionDao().getAll()
            .filter { it.endTime != null }
            .sortedWith(compareBy({ it.endTime!! }, { it.id }))
        for (session in sessions) {
            exerciseOverridesBySession[session.id]?.forEach { o ->
                snapshot.currentEstimates[o.exerciseId] = ExerciseEstimate(ln(o.e1rm), 1.0f, o.asOf)
                snapshot.currentBeliefs[o.exerciseId] = Belief(ln(o.e1rm), sigmaOverride2, o.asOf)
            }
            val sets = db.workoutSetDao().getSetsForSession(session.id)
            if (sets.isEmpty()) continue
            val result = stepper.step(sets, snapshot, session.endTime!!)
            val beliefResult = beliefStep.step(
                beliefs = snapshot.currentBeliefs, sets = sets,
                seedCoef = snapshot.seedCoefficients, exerciseMuscle = snapshot.exerciseMuscle,
                muscleExerciseIds = snapshot.muscleExerciseIds, asOf = session.endTime!!,
            )
            observer.onSession(session.id, session.endTime!!, sets, snapshot, result, beliefResult)
        }
    }
}
```

`DerivedStateStore.kt` — mirror `exerciseEstimates` exactly: private `exerciseBeliefs` map on both `Snapshot` and `MutableDerivedState`, `putExerciseBeliefs`, `exerciseBeliefs()`, threaded through `toSnapshot()` and `Snapshot.empty()`.

`WorkoutRepository.replayDerivedState` — accept the extra observer arg (`_, beliefResult ->` unused for now is fine, name it) and after the replay add `scratch.putExerciseBeliefs(snapshot.currentBeliefs.toMap())`. `ExerciseProgressionSeriesBuilder`'s observer lambda gains the unused sixth parameter.

- [x] **Step 4: Run the JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — no behavior change anywhere (derived rows, planner, charts all still old-stack).

- [x] **Step 5: Commit**

```bash
jj commit -m "feat(belief): thread beliefs through the prod replay in parallel (dark; derived writes unchanged)"
```

---

### Task 4: Flip prescriptions + derived state to the belief stack (THE swap)

Derived rows (muscle level, coefficients) now come from `BeliefSessionStep.Result`; planner targets come from live belief pooling → `BeliefPrescriber` → policy with `overloadNudge = true`. The old estimator still computes in parallel (charts still read it) but no longer drives anything the planner or History sees. Rewrite `ProdBssPrescriptionTest` through the belief stack; update instrumented expectations.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt` (nudge flag)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProdBssPrescriptionTest.kt`
- Modify (as needed): `app/src/androidTest/.../ReplayDerivedStateTest.kt`, `FatigueNoDownwardBiasReplayTest.kt`, `WorkoutRepositoryTest.kt`, `WorkoutSessionControllerTest.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ReplayProjectionTest.kt` (rewrite to belief expectations)

**Interfaces:**
- Consumes: `BeliefSessionStep.Result.steps` (Task 2), `DerivedStateStore.exerciseBeliefs()` (Task 3), `BeliefPooling`/`BeliefPrescriber` (Phase 2), `Prescription.uncappedWeightKg` (Task 1, unused here but present).
- Produces: `WorkoutRepository` private `val beliefConfig = BeliefConfig()` + `val beliefPooling = BeliefPooling(beliefConfig)` — Task 6's trace accessor reuses these.

- [x] **Step 1: Rewrite `ProdBssPrescriptionTest` as the failing test.** Keep the fixture data (seed coefs, initials, the session history with the 24.95/15.88 kg failures) and both invariant assertions, but drive it through the belief stack: seed `Belief(ln(e1rm), sigmaSeed², 0)` from the initials, apply each session via `BeliefSessionStep.step(...)`, then for the final prescription use `BeliefPooling(...).effective(...)` at `EXPORTED_AT` → `BeliefPrescriber.targetE1rm` → `PrescriptionPolicy.prescribe(..., overloadNudge = true)` with `PolicyFacts` built from all fixture sets. Assertions unchanged in spirit: the prescribed BSS weight is strictly below both failed weights at their rep targets. Delete the old-stack imports (`ExerciseEstimate`, `MuscleStrengthProjector`, `SessionProgressionStepper`).

- [x] **Step 2: Run it**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProdBssPrescriptionTest"`
Expected: PASS already if the wiring code below existed — it doesn't yet, so the test compiles against Phase-2 pieces and should PASS on its own (it exercises domain classes directly, not the repository). That is fine: this test pins the invariant across the swap; the repository flip is verified by the instrumented tests in Step 5.

- [x] **Step 3: Flip the repository.** In `WorkoutRepository`:

(a) fields:

```kotlin
private val beliefConfig = BeliefConfig()
private val beliefPooling = BeliefPooling(beliefConfig)
```

(b) `replayDerivedState` observer body — write derived rows from `beliefResult` instead of `result` (same helpers, same epsilon-dedupe):

```kotlin
replayEngine.run(db, snapshot) { sessionId, asOf, _, _, _, beliefResult ->
    for (stepResult in beliefResult.steps) {
        writeLevelUpdate(stepResult.muscle, stepResult.level, sessionId, asOf, scratch)
        val exerciseIds = snapshot.muscleExerciseIds[stepResult.muscle] ?: continue
        writeDerivedCoefficients(
            muscleExerciseIds = exerciseIds,
            derivedCoef = stepResult.derivedCoef,
            snapshot = snapshot, asOf = asOf, scratch = scratch,
        )
    }
}
```

(c) keep `scratch.putExerciseEstimates(...)` AND `scratch.putExerciseBeliefs(...)` (charts still read estimates until Task 5).

(d) cold-start muscle fill — pool beliefs instead of projecting estimates:

```kotlin
val displayNow = snapshot.currentBeliefs.values.maxOfOrNull { it.updatedAt } ?: 0L
for ((muscle, exerciseIds) in snapshot.muscleExerciseIds) {
    if (scratch.muscleGroupStrength(muscle) != null) continue
    val levelLn = beliefPooling.effective(snapshot.currentBeliefs, snapshot.seedCoefficients, exerciseIds, displayNow).levelLn ?: continue
    val level = exp(levelLn)
    if (level > 0f) scratch.upsertMuscleGroupStrength(MuscleGroupStrength(muscleGroup = muscle, baselineWeight = level))
}
```

(e) `buildPlanner` — prescribed targets from live belief pooling:

```kotlin
val beliefs = derivedState.snapshot().exerciseBeliefs()
val prescribedE1rm = muscleIds.flatMap { (_, ids) ->
    beliefPooling.effective(beliefs, seedCoef, ids, now).effective.entries
        .map { it.key to BeliefPrescriber.targetE1rm(it.value) }
}.toMap()
```

(the `MuscleStrengthProjector` local and its import go away; `EstimatorConfig` import likewise if now unused).

(f) `WorkoutPlanner.weightForExercise` — flip the nudge at the single `PrescriptionPolicy.prescribe` call site: add `overloadNudge = true` with the comment `// Phase-3 swap: the belief stack's in-band feedback legitimately leaves mu unmoved, so the smallest-plate nudge covers the steady-state stall (spec Phase 2).`

- [x] **Step 4: Rewrite `ReplayProjectionTest`** — it pins the replay's derived writes; re-derive its expectations from the belief stack (level = `exp(levelLn)`, coef = effective/level). Follow the existing test's scenario structure; compute expected numbers with the same `BeliefFold`/`BeliefPooling` calls in the test (self-consistent, not magic constants), asserting the repository's `DerivedStateStore` rows match a hand-driven `BeliefSessionStep` over the same sessions.

- [x] **Step 5: Run the JVM suite, then instrumented**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS except possibly old-stack pins that read derived rows — fix any such test by re-deriving its expectation through the belief stack (do NOT weaken assertions; if a test's premise died with the old stack, note it for Task 7's deletion list instead).

Run: `./gradlew :app:connectedAndroidTest`
Expected: failures ONLY in tests asserting old-stack derived numbers (`ReplayDerivedStateTest`, `FatigueNoDownwardBiasReplayTest`, possibly `WorkoutRepositoryTest`/`WorkoutSessionControllerTest`). Update each to belief-stack expectations the same way: behavioral assertions (e.g. "baseline never drops below seed after clean sessions") stay as-is and must PASS — they are invariants, not pins; only numeric pins move. If a behavioral invariant FAILS under the belief stack, STOP and surface it — that is a real regression, not a test to update.

- [x] **Step 6: Commit**

```bash
jj commit -m "feat(belief): SWAP — derived state + planner prescriptions from the belief stack, nudge ON (PolicyFacts stay plan-time-built: replay misses abandoned-session caps)"
```

---

### Task 5: Charts read μ/σ — uncertainty band, per-set dots, belief cross-tuning

Rewrite the chart data path on beliefs: the three lines become own μ / sibling prediction / effective μ with a ±σ band; observation dots become per-set implied points ("every set is its own piece of feedback"); cross-tuning bars compute from pooling precisions. The user-facing exercise chart keeps parity with the debug chart via a shared per-set helper.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/belief/SetObservation.kt`
- Rewrite: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt`
- Rewrite: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/CrossTuning.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/ExerciseProgressionChart.kt` (BAND color role), `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt` (band series), `app/src/main/java/io/github/fowles/stochastic_strength/ui/components/ChartRange.kt` (include band in shared Y range), `app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailViewModel.kt` (per-set dots)
- Tests: `app/src/test/.../domain/belief/SetObservationTest.kt` (new), `domain/progression/CrossTuningTest.kt` + `ExerciseProgressionSeriesBuilderTest.kt` (rewrites), `ui/exercises/ExerciseDetailViewModelTest.kt`, `ui/debug/ExerciseCoefficientDetailViewModelTest.kt`, `ui/components/ChartRangeTest.kt` (updates)

**Interfaces:**
- Produces `SetObservation.kt`:

```kotlin
/**
 * The fresh-capacity observation one set implies for charts: the midpoint of its finite implied
 * ln-1RM interval (or the single finite bound), fatigue-corrected back to fresh capacity by
 * +fatigueShift(rank). Null when the set carries no interval (HURT / no feedback / no weight).
 */
fun setObservationLn(set: WorkoutSet, rank: Int, config: BeliefConfig): Float?
```

- Produces on `ExerciseProgressionSeries`: two new point lists `bandUpper`/`bandLower` (μ_eff ± σ_eff, exp'd); `ProgressionFrame` unchanged except `merged` header shows "value ±σ" (formatting lives in the ViewModel).
- Produces `computeCrossTuning(beliefs: Map<Long, Belief>, seedCoef, namesById, muscleExerciseIds, now, config): List<CrossTuningRow>` — `CrossTuningRow` shape unchanged (agreement = own aged e1rm / LOO prediction − 1; contribution = pooling precision share `w_i/Σw`, `w_i = 1/(agedσ_i² + τ²)`).
- Consumes: `ReplayEngine.SessionObserver`'s `beliefResult` (Task 3), `snapshot.currentBeliefs`.

- [ ] **Step 1: Write `SetObservationTest`** (failing):

```kotlin
class SetObservationTest {
    private val config = BeliefConfig()

    @Test
    fun rirSetYieldsFiniteIntervalMidpoint() {
        val s = set(weight = 100f, reps = 5, feedback = SetFeedback.RIR_0_1) // interval [1RM(w,5), 1RM(w,7)]
        val i = SetIntervals.impliedLn1RmInterval(s)!!
        assertEquals((i.lowerLn!! + i.upperLn!!) / 2f, setObservationLn(s, rank = 1, config)!!, 1e-6f)
    }

    @Test
    fun unboundedIntervalsUseTheirFiniteBound() {
        val easy = set(weight = 100f, reps = 5, feedback = SetFeedback.RIR_5_PLUS)   // [b, ∞)
        assertEquals(SetIntervals.impliedLn1RmInterval(easy)!!.lowerLn!!, setObservationLn(easy, 1, config)!!, 1e-6f)
        val fail = set(weight = 100f, reps = 5, feedback = SetFeedback.TOO_HARD, actualReps = null) // (−∞, b]
        assertEquals(SetIntervals.impliedLn1RmInterval(fail)!!.upperLn!!, setObservationLn(fail, 1, config)!!, 1e-6f)
    }

    @Test
    fun laterRanksAreFatigueCorrectedUpward() {
        val s = set(weight = 100f, reps = 5, feedback = SetFeedback.RIR_0_1)
        val fresh = setObservationLn(s, rank = 1, config)!!
        val third = setObservationLn(s, rank = 3, config)!!
        assertEquals(fresh + BeliefFold(config).fatigueShift(3), third, 1e-6f)
    }

    @Test
    fun hurtAndFeedbacklessSetsYieldNull() {
        assertNull(setObservationLn(set(100f, 5, SetFeedback.HURT), 1, config))
        assertNull(setObservationLn(set(100f, 5, feedback = null), 1, config))
    }
}
```

(reuse the `WorkoutSet` helper pattern from `BeliefFoldTest`.)

- [ ] **Step 2: Run to verify failure**, then implement `SetObservation.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals

fun setObservationLn(set: WorkoutSet, rank: Int, config: BeliefConfig): Float? {
    val interval = SetIntervals.impliedLn1RmInterval(set) ?: return null
    val base = when {
        interval.lowerLn != null && interval.upperLn != null -> (interval.lowerLn + interval.upperLn) / 2f
        else -> interval.lowerLn ?: interval.upperLn ?: return null
    }
    return base + BeliefFold(config).fatigueShift(rank)
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.SetObservationTest"` — PASS.

- [ ] **Step 3: Rewrite `CrossTuning.kt` on beliefs (TDD: update `CrossTuningTest` first).** Test expectations: with beliefs {1: ln100 σ²=0.01 coef 1, 2: ln30 σ²=0.01 coef 0.3}, exercise 2's LOO prediction = 0.3·100 = 30 → agreement 0; make one belief tighter and assert the contribution share follows `w_i = 1/(σ_i²+τ²)`. Implementation:

```kotlin
fun computeCrossTuning(
    beliefs: Map<Long, Belief>,
    seedCoef: Map<Long, Float>,
    namesById: Map<Long, String>,
    muscleExerciseIds: List<Long>,
    now: Long,
    config: BeliefConfig = BeliefConfig(),
): List<CrossTuningRow> {
    val fold = BeliefFold(config)
    val pooling = BeliefPooling(config)
    val tau2 = config.tau * config.tau
    val weights = muscleExerciseIds.associateWith { id ->
        val coef = seedCoef[id] ?: return@associateWith 0f
        if (coef <= 0f) return@associateWith 0f
        beliefs[id]?.let { 1f / (fold.aged(it, now).sigma2 + tau2) } ?: 0f
    }
    val totalW = weights.values.sum()

    return muscleExerciseIds.mapNotNull { id ->
        val belief = beliefs[id] ?: return@mapNotNull null
        val coef = seedCoef[id] ?: return@mapNotNull null
        if (coef <= 0f) return@mapNotNull null
        val name = namesById[id] ?: return@mapNotNull null
        val looLevelLn = pooling.effective(beliefs, seedCoef, muscleExerciseIds.filter { it != id }, now).levelLn
        val prediction = looLevelLn?.let { exp(ln(coef) + it) } ?: 0f
        val ownE1rm = exp(fold.aged(belief, now).mu)
        CrossTuningRow(
            exerciseId = id, name = name,
            agreement = if (prediction > 0f) ownE1rm / prediction - 1f else 0f,
            contribution = if (totalW > 0f) (weights[id] ?: 0f) / totalW else 0f,
        )
    }.sortedByDescending { it.agreement }
}
```

`WorkoutRepository.getCrossTuning` switches to `derivedState.snapshot().exerciseBeliefs()`.

- [ ] **Step 4: Rewrite `ExerciseProgressionSeriesBuilder.kt` (TDD via its test).** Semantics per session (target's muscle touched only), all from the observer's post-step state:
  - `ownEstimate`: `exp(snapshot.currentBeliefs[targetId].mu)` (post-fold).
  - `siblingsEstimate`: `exp(ln(targetCoef) + looLevelLn)` where `looLevelLn = BeliefPooling.effective(currentBeliefs, seedCoef, muscleIds.filter { it != targetId }, asOf).levelLn`.
  - `merged`: `exp(mu_eff)` from `BeliefPooling.effective(..., muscleIds, asOf).effective[targetId]`.
  - `bandUpper`/`bandLower` (new lists on `ExerciseProgressionSeries`, same length as `merged`): `exp(mu_eff ± sqrt(sigma2_eff))`.
  - `ownObservations`: one dot PER SET — `exp(setObservationLn(set, rank, config))` over the target's session sets sorted by id, rank = 1-based index (all rows count, matching the fold's rank rule).
  - `siblingObservations`: same per-set dots for same-muscle siblings, scaled by `targetCoef / sibCoef`.
  - `buildFrame`: same values; `crossTuning` from the new signature; `observations` (tooltip) unchanged via `impliedObservedSet`.
  - `SessionSignalExtractor` import goes away. `sampleSession`'s `projector` parameter is replaced by `config: BeliefConfig`/`pooling` — adjust both call sites and the test helper.
  - Frame gating "muscle touched": use `beliefResult.steps.any { it.muscle == muscle }` from the six-arg observer.
  Update `ExerciseProgressionSeriesBuilderTest` expectations by computing them through `BeliefFold`/`BeliefPooling` in the test (mirror the existing test's structure; it already builds tiny synthetic histories).

- [ ] **Step 5: UI band + shared range + user-chart dots.**
  - `ExerciseProgressionChart.kt`: add `ProgressionColorRole.BAND`; in `progressionColors()` map it to `MaterialTheme.colorScheme.error.copy(alpha = 0.35f)` (the merged line's color, faded). Band series use style `LINE`.
  - `ExerciseCoefficientDetailViewModel`: append two series `ProgressionChartSeries("+σ", pts(series.bandUpper), LINE, BAND)` and `("−σ", pts(series.bandLower), LINE, BAND)`; `headerMerged` becomes `"$merged ±σ"` only if trivially available — keep headers as-is otherwise (band is visible on the chart; don't over-format).
  - `ChartRange.kt` / `sharedProgressionYRange`: include `bandUpper`/`bandLower` values so the band never clips; update `ChartRangeTest`.
  - `ExerciseDetailViewModel`: replace `observedSessionPoints`' aggregate with per-set points — for each `ObservedSession`, sets sorted by id, `exp(setObservationLn(set, rank, config)) * scale`; keep one `ChartPoint` per set. Update `ExerciseDetailViewModelTest` (chart parity: the same helper feeds both charts, which is the point).

- [ ] **Step 6: Run the JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat(charts): belief mu/sigma lines + uncertainty band, per-set observation dots, precision-share cross-tuning"
```

Note for the final summary: dynamic-color caveat — chart colors must be eyeballed on-device (memory: tertiary/alpha tokens can render invisible); flag as a deferred on-device check.

---

### Task 6: "Why this weight" prescription trace

A pure, unit-tested trace builder plus a debug-screen section. Every line is one gym sentence citing the data that produced it (spec Phase 3).

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/belief/PrescriptionTrace.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (accessor), `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt` + `ExerciseCoefficientDetailScreen.kt` (render)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/belief/PrescriptionTraceTest.kt`

**Interfaces:**

```kotlin
data class TraceLine(val label: String, val detail: String)
data class PrescriptionTrace(val lines: List<TraceLine>, val finalWeightKg: Float)

object PrescriptionTraceBuilder {
    fun build(
        exerciseId: Long,
        muscle: MuscleGroup,
        beliefs: Map<Long, Belief>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        facts: PolicyFacts,
        capSessionSets: List<WorkoutSet>,   // sets of the cap fact's session, for citation (empty if none)
        sessionReps: Int,
        now: Long,
        weightUnit: WeightUnit,
        config: BeliefConfig = BeliefConfig(),
        engine: ProgressionEngine = DefaultProgressionEngine,
    ): PrescriptionTrace?   // null when the exercise has no effective belief (unloadable/cold muscle)
}
```

- Consumes: `Prescription.uncappedWeightKg` (Task 1) to phrase the cap line; `BeliefPooling` internals via public API only.

- [ ] **Step 1: Write the failing test** — `PrescriptionTraceTest.kt`. Scenario: two QUADS exercises, target coef 0.3 with own belief ln(30) σ²=0.01, sibling coef 1.0 belief ln(100) σ²=0.01; a cap fact from a failed session; assert:

```kotlin
@Test
fun traceListsEveryStageWithCitations() {
    // ... build beliefs/facts as above; capSessionSets = one TOO_HARD set 35 kg × 10 (actual 6) ...
    val trace = PrescriptionTraceBuilder.build(/* ... */)!!
    val labels = trace.lines.map { it.label }
    assertEquals(
        listOf("Own belief", "Sibling pull", "Effective belief", "Risk percentile", "HURT backoff", "Overload nudge", "Capacity cap", "Rounding"),
        labels,
    )
    // The cap line cites the failed set.
    val cap = trace.lines.first { it.label == "Capacity cap" }
    assertTrue(cap.detail.contains("35"))   // failed weight appears
    // Final weight equals the policy's prescription for the same inputs.
    val expected = PrescriptionPolicy.prescribe(
        rawE1rm = /* z-target computed the same way */, sessionReps = 10, exerciseId = targetId,
        muscle = MuscleGroup.QUADS, facts = facts, now = now, weightUnit = WeightUnit.KG,
        engine = DefaultProgressionEngine, overloadNudge = true,
    )
    assertEquals(expected.weightKg, trace.finalWeightKg, 1e-4f)
}

@Test
fun traceIsNullForAnExerciseWithNoEffectiveBelief() { /* zero coef → null */ }

@Test
fun uncappedTraceSaysNoCap() { /* facts EMPTY → "Capacity cap" detail contains "no cap" */ }
```

- [ ] **Step 2: Run to verify failure**, then implement. Line construction (each `detail` a plain sentence; weights formatted with `WeightFormatter.format(v, weightUnit)`; σ shown as ±% via `(exp(sqrt(sigma2)) − 1) × 100`):
  1. **Own belief** — aged `Belief`: `"~X (±Y%), last updated <date>"`, or `"none — cold exercise, leaning on siblings"`.
  2. **Sibling pull** — LOO prediction `exp(ln coef + L₋ᵢ)` and blend weight `pSib/(pOwn+pSib)` as a percent: `"siblings imply ~X; blended at Z%"` (recompute `pOwn`/`pSib` exactly as `BeliefPooling.effective` does: `pOwn = 1/σ²_own,aged`, `pSib = 1/(1/looW + τ²)`); or `"no siblings with evidence"`.
  3. **Effective belief** — `"~X (±Y%)"` from the pooled `EffectiveBelief`.
  4. **Risk percentile** — `"prescribing at the 30th percentile: ~X"` = `BeliefPrescriber.targetE1rm`.
  5. **HURT backoff** — from `PrescriptionPolicy.hurtMultiplier(facts.hurtEventsByMuscle[muscle], now)`: `"none"` or `"×M after N recent HURT set(s)"`.
  6. **Overload nudge** — from `facts.capByExercise[exerciseId]?.allEasy` + expiry window: `"last session all easy → +one increment"` or `"not applied"`.
  7. **Capacity cap** — call `PrescriptionPolicy.prescribe(..., overloadNudge = true)` once; if `capBound`: `"capped at X (wanted Y): <cited sets>"` where cited sets render each `capSessionSets` row as `"35 kg × 10 → failed at 6"` / `"20 kg × 10 → RIR 2–4"`; else `"no cap"` or `"cap ~X, not binding"`.
  8. **Rounding** — `"final: X"` (the prescription's `weightKg`).
  `finalWeightKg` = the same prescription's `weightKg` — the trace never re-implements policy math, it CALLS it (one source of truth).

- [ ] **Step 3: Repository accessor** — in `WorkoutRepository`:

```kotlin
suspend fun getPrescriptionTrace(exerciseId: Long): PrescriptionTrace? {
    val exercise = db.exerciseDao().getById(exerciseId) ?: return null
    val snapshot = ReplaySnapshot.loadStaticFromDb(db)
    val muscleIds = snapshot.muscleExerciseIds[exercise.primaryMuscle] ?: return null
    val allSets = db.workoutSetDao().getAllForExercise(exerciseId)  // + the same recent-history query buildPlanner uses for facts
    // Facts exactly as the planner builds them (same query, same build call).
    val history = db.workoutSetDao().getRecentSetsForExercises(listOf(exerciseId) + muscleIds, limit = 200)
    val facts = PolicyFacts.build(history, mapOf(exerciseId to exercise.primaryMuscle) /* extend to muscleIds */)
    val capFact = facts.capByExercise[exerciseId]
    val capSessionSets = capFact?.let { f -> allSets.filter { s -> s.completedAt != null }
        .groupBy { it.sessionId }.values.firstOrNull { s -> s.maxOf { it.completedAt!! } == f.demonstratedAt } }.orEmpty()
    val reps = allSets.filter { it.completedAt != null }.maxByOrNull { it.completedAt!! }?.targetReps ?: 10
    val unit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
    return PrescriptionTraceBuilder.build(
        exerciseId = exerciseId, muscle = exercise.primaryMuscle,
        beliefs = derivedState.snapshot().exerciseBeliefs(), seedCoef = snapshot.seedCoefficients,
        muscleExerciseIds = muscleIds, facts = facts, capSessionSets = capSessionSets,
        sessionReps = reps, now = System.currentTimeMillis(), weightUnit = unit,
        config = beliefConfig, engine = progressionEngine,
    )
}
```

(Implementer: match the facts build to `buildPlanner`'s exactly — same muscle map construction — so the trace explains the weight the planner would actually pick.)

- [ ] **Step 4: Debug UI** — in `ExerciseCoefficientDetailViewModel`, add `trace: PrescriptionTrace?` to the state, loaded alongside the progression data. In `ExerciseCoefficientDetailScreen`, render below the cross-tuning section: `SectionHeader("Why this weight")`, then one row per `TraceLine` (label in `labelMedium`, detail in `bodySmall`), final weight emphasized. Follow the screen's existing section composable patterns.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.belief.PrescriptionTraceTest" --tests "io.github.fowles.stochastic_strength.ui.debug.ExerciseCoefficientDetailViewModelTest"`
Expected: PASS. Then the full JVM suite: PASS.

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat(debug): 'why this weight' prescription trace — one cited gym sentence per stage"
```

---

### Task 7: Delete the old estimator (+ phase-2 carry-forwards)

Everything the old stack was is now unread. Delete it in one commit; the suite stays green.

**Files — delete (main):**
- `domain/progression/ExerciseEstimate.kt` (incl `EstimatorConfig`)
- `domain/progression/ExerciseEstimateUpdater.kt`
- `domain/progression/MuscleStrengthProjector.kt`
- `domain/progression/SessionProgressionStepper.kt`
- `domain/SessionSignalExtractor.kt`

**Files — delete (test):**
- `domain/progression/ExerciseEstimatorSimulationTest.kt`, `ExerciseEstimateUpdaterTest.kt`, `MuscleStrengthProjectorTest.kt`, `SessionProgressionStepperTest.kt`
- `domain/SessionSignalExtractorTest.kt`, `domain/BulgarianBracketCharacterizationTest.kt` (characterizes the deleted extractor)
- `domain/backtest/MainStackReplay.kt`, `MainStackReplayTest.kt`, `HeldOutScorerTest.kt`, `BaselineReportTest.kt`, `CapViolationDiagnostic.kt`, `CapViolationDiagnosticTest.kt` (phase-0 artifacts of the OLD stack; the baseline they measured is a recorded number — see appendix — and `BeliefHeldOutScorer` is the living scorer)

**Files — modify:**
- `domain/ReplaySnapshot.kt`: delete `currentEstimates`
- `domain/progression/ReplayEngine.kt`: drop the old stepper + `result` observer param → `onSession(sessionId, asOf, sets, snapshot, beliefResult)`; delete `ExerciseEstimate` seeding
- `domain/derived/DerivedStateStore.kt`: delete `exerciseEstimates`/`putExerciseEstimates`
- `domain/WorkoutRepository.kt`: delete `putExerciseEstimates` call, `stepper` field, dead imports
- `domain/progression/ExerciseProgressionSeriesBuilder.kt`, `ReplayEngineTest.kt`, and any other observer call sites: five-arg signature
- `domain/progression/ObservedSet.kt`: own the reserve constants (carry-forward):

```kotlin
private const val RESERVE_RIR_0_1 = 0.5f
private const val RESERVE_RIR_2_4 = 3f
private const val RESERVE_RIR_5_PLUS = 6f
```

(drop the `SessionSignalExtractor` import; kdoc: "display reserve offsets — midpoints of the SetIntervals feedback buckets".)
- `domain/belief/BeliefFold.kt`: carry-forward — drop `obsSigma(feedback)`'s unused parameter: replace the method with direct `config.sigmaObs` use in `foldSession` (and delete the `SetFeedback` import if now unused); keep the Task-10 collapse note on `BeliefConfig.sigmaObs`.
- `test/domain/backtest/HeldOutScorer.kt` → rename content: keep ONLY `SessionScore` + `ScoreReport` in a new `ScoreReport.kt`; delete the `HeldOutScorer` object.
- `test/domain/backtest/BeliefHeldOutScorer.kt`: carry-forward — add the missing skip-condition comment on the `pred == null` branch: `// skipped = the set implied an interval but no prediction existed (cold exercise before its first fold, zero-coef).`

- [ ] **Step 1: Delete + modify per the list above.** Use `grep -rn "ExerciseEstimate\|SessionSignalExtractor\|MuscleStrengthProjector\|EstimatorConfig\|SessionProgressionStepper\|MainStackReplay\|HeldOutScorer\b" app/src` after the edit — the only hits must be `BeliefHeldOutScorer` internals and historical docs (`docs/superpowers/`), which are historical documents and stay untouched.

- [ ] **Step 2: Full JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, and `BeliefScoreTest` still reports 24.3274 (nothing math-bearing was touched).

- [ ] **Step 3: Lint for dead code/imports**

Run: `./gradlew :app:lint`
Expected: no new errors (warnings triaged: fix unused-import/unused-parameter ones in touched files).

- [ ] **Step 4: Commit**

```bash
jj commit -m "chore(belief): delete the old estimator (estimate/updater/projector/extractor/stepper, simulation pins, main-stack backtest) + phase-2 carry-forwards"
```

---

### Task 8: Ship-gate ceremony — score gate, bind report, constant census, docs, full suites

**Files:**
- Modify: `docs/adaptation/02-strength-signal.md`, `03-exercise-estimates.md`, `04-muscle-pooling.md`, `README.md` (living docs → belief model)
- Modify: this plan (Results appendix)

- [ ] **Step 1: Gate + reports.** Run and capture:

```
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefScoreTest" --info | grep -A 10 "held-out"
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefPolicyBacktestTest" --info | grep -A 25 "clamp-bind"
```

Expected: gate PASS (24.3274 < 28.4451, unchanged), 0 invariant violations, bind report (with Task-1 magnitudes) recorded in the appendix.

- [ ] **Step 2: Constant census.** Grep every numeric constant in `domain/belief/`, `domain/policy/`, and confirm each carries a ledger label matching the spec (~7 estimator: σ_seed, σ_override, φ, q, σ_obs, floor/cap; policy semantics: cap expiry 28d, HURT 0.15/14d/0.6, cooldown 2d, z, nudge = one increment). Any constant without a label: label it from the Phase-2 record or delete it (constitution rule 2). Record the census table in the appendix.

- [ ] **Step 3: Docs.** Rewrite `docs/adaptation/02-strength-signal.md` (set → implied ln-1RM interval, fatigue shift), `03-exercise-estimates.md` (Belief μ/σ², boundary-pull fold, aging, override seeding), `04-muscle-pooling.md` (precision-weighted level, LOO blend, z-prescription, policy caps + nudge), and the README's flow diagram to the belief model. Keep the docs' existing voice and length; these are living docs (the superpowers specs/plans are historical and stay).

- [ ] **Step 4: Full suites.**

```
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedAndroidTest
```

Expected: both fully green. Record counts in the appendix.

- [ ] **Step 5: Commit**

```bash
jj commit -m "docs: phase-3 complete — belief stack live; gate/bind/census recorded; adaptation docs on the belief model"
```

---

## Self-review checklist (done at write time)

- Spec Phase-3 coverage: wiring/swap → Tasks 3–4; charts μ/σ + band → Task 5; trace → Task 6; deletions → Task 7; ship gate → Task 8; flagged bind magnitude → Task 1; "KEEP IN SYNC" debt → Task 2. `ExerciseSeedExpansion`/`StartingWeights`/detraining path confirmed untouched (they operate on override rows and planner weights, both stack-agnostic).
- Deviation recorded: PolicyFacts stay plan-time-built (abandoned-session caps) — Global Constraints + Task 4 commit message.
- Type consistency: `BeliefSessionStep.Result(preFoldEffective, steps)` used identically in Tasks 2/3/4/5; `Prescription.uncappedWeightKg` introduced Task 1, consumed Tasks 6 (trace) and 1 (report); observer arity 6 (Tasks 3–6) → 5 (Task 7).

## Results appendix (filled during execution)

- Task 1 bind-magnitude report:

```
=== Phase 2 clamp-bind report (policy over BELIEF prescriptions, nudge ON) ===
prescriptions checked : 1690
cap binds             : 125 (7.4%)
hurt binds            : 0
per-exercise cap binds: ex 20=17, ex 100=14, ex 21=14, ex 75=14, ex 77=14, ex 30=14, ex 55=12, ex 26=9, ex 33=7, ex 23=5, ex 24=5
bind magnitude (grid increments of 2.27 kg):
  ≤1: 111  ≤2: 125  >2: 0
  mean 1.11  max 2.00
post-policy failure-invariant violations: 0
```

**Review:** bind magnitude is designed creep against the ceiling (88.8% within 1 increment, 100% within 2 increments, max overshoot 2.00 increments = 4.54 kg). No estimator bug flagged; safe to proceed to Tasks 2–8.

- Task 8 gate + final bind report + constant census + suite counts: _pending_
- Deferred on-device check: chart band/dot colors under dynamic color.

## Recorded baselines (for reference after the old harness is deleted)

- Phase-0 main-stack baseline (original 24-session history): 26.7593 total / 0.12563 per set / 213 scored / 9 skipped / 49 cap violations.
- Re-baselined main (current 26-session history): **28.4451** total / 0.12002 per set / 237 scored + 9 skipped / 56 cap violations.
- Belief stack (Phase 2, adopted config): **24.3274** total / 237 scored + 9 skipped.
- Phase-1 policy-over-main bind report (current history): 1690 prescriptions, 58 cap binds (3.4%), 0 violations.
- Phase-2 policy-over-belief bind report: 1690 prescriptions, 125 cap binds (7.4%), 0 violations; nudge-off ablation identical; worst binders ex 20 (17), then 100/21/75/77/30 (14 each).
