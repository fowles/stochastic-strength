# Fitted Coefficient Compression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Superseded later the same day (2026-07-31):** the runtime `compress()` / `LAMBDA` step this
> plan built was *baked away* — `ExerciseCoefficients` now ships the `guess^0.75` literals directly,
> and `CoefficientGuesses` + `CoefficientCompression` moved into the test tree (`BAKED_LAMBDA`) as a
> sweep-only tool. No runtime compression or exponent knob remains. This plan below is the record of
> the compression design as it was built; see CLAUDE.md for the current baked shape.

**Goal:** Turn `ExerciseCoefficients` into a fitted artifact — the shipped values become `guess^λ` compressed from a legible `CoefficientGuesses` prior, with a global λ fit by the held-out backtest and CI-guarded, so shipping new coefficients needs zero migration.

**Architecture:** `CoefficientGuesses.raw` holds the round-number priors (today's `ExerciseCoefficients.byName`, moved verbatim). `CoefficientCompression.compress(guess, λ)` maps `guess → guess^λ` preserving `0→0` and `1→1`. `ExerciseCoefficients.byName` is computed once at object init as `compressAll(CoefficientGuesses.raw, LAMBDA)`; `LAMBDA` is a shipped `fitted` constant. λ is fit by a 1-D held-out sweep over the existing belief backtest (`BeliefHeldOutScorer` on `BacktestData.withCoefLambda(λ)`); adoption + gate re-baseline is a human-gated checkpoint.

**Tech Stack:** Kotlin, JUnit4, Android/Gradle. JVM unit tests: `./gradlew :app:testDebugUnitTest`. Backtest tests self-skip when `app/src/test/resources/backtest/history.json` is absent (it is present here — personal, gitignored).

## Global Constraints

- This is **Part B** of the approved spec `docs/superpowers/specs/2026-07-31-fitted-coefficients-and-derived-state-cleanup-design.md`. Part A (Plans 1 & 2) shipped: live seed expansion, manual→ephemeral, detrain-by-inference, `exercise_strength_override` deleted. **No `coefExponent` runtime knob exists on main** — build the fit machinery fresh; usv's `withCompressedSeeds` was never merged.
- **Per-exercise refit is out of scope** (underdetermined — established). Only a single global structural λ is identifiable.
- **Per-muscle λ is out of scope** (global-only now; let per-muscle earn its place later).
- **Bodyweight (`coef == 0`) exercises stay 0** and **reference lifts (`coef == 1`) stay 1** under compression.
- **No migration.** Coefficients are not stored per user; changing them is a pure code change re-derived by idempotent `replayDerivedState`.
- **ONE fitness authority** (constitution rule 1): the held-out belief score on real history (`BeliefHeldOutScorer`). The forward-chaining cold-start RMSE (python scratchpad this session: RMSE 0.275→0.226 at λ≈0.75–0.80 under leave-one-exercise-out) is **already-recorded design evidence**, cited, not re-derived in Kotlin.
- **λ adoption and gate re-baseline are human decisions** — the fit test *reports*; a person adopts the printed value by hand (same ceremony as `fatiguePerSetEstimate`/`crossLiftIndependenceEstimate` in `BeliefConfig`).
- Constitution rule 2: every new tuning constant is labeled `semantic`/`fitted`/`flat` with provenance.

---

### Task 1: `CoefficientGuesses` prior + `CoefficientCompression` helper

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientGuesses.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientCompression.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/CoefficientCompressionTest.kt`

**Interfaces:**
- Consumes: nothing (leaf).
- Produces:
  - `object CoefficientGuesses { val raw: Map<String, Float> }` — the legible round-number priors.
  - `object CoefficientCompression { fun compress(guess: Float, lambda: Float): Float; fun compressAll(raw: Map<String, Float>, lambda: Float): Map<String, Float> }`.

- [x] **Step 1: Write the failing test**

`CoefficientCompressionTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class CoefficientCompressionTest {
    private val eps = 1e-6f

    @Test fun bodyweightStaysZero() {
        assertEquals(0f, CoefficientCompression.compress(0f, 0.75f), 0f)
    }

    @Test fun referenceStaysOne() {
        assertEquals(1f, CoefficientCompression.compress(1f, 0.75f), 0f)
    }

    @Test fun identityAtLambdaOne() {
        assertEquals(0.85f, CoefficientCompression.compress(0.85f, 1.0f), eps)
        assertEquals(2.50f, CoefficientCompression.compress(2.50f, 1.0f), eps)
    }

    @Test fun belowOneCompressesUpward() {
        // guess^λ moves fractional coefficients toward the reference (1.0) as λ<1.
        val c = CoefficientCompression.compress(0.5f, 0.75f)
        assertEquals(0.5f.pow(0.75f), c, eps)
        assertTrue("0.5^0.75 must sit between 0.5 and 1", c > 0.5f && c < 1f)
    }

    @Test fun aboveOneCompressesDownward() {
        // guess>1 (e.g. Leg Press 2.5) compresses down toward the reference as λ<1.
        val c = CoefficientCompression.compress(2.5f, 0.75f)
        assertEquals(2.5f.pow(0.75f), c, eps)
        assertTrue("2.5^0.75 must sit between 1 and 2.5", c > 1f && c < 2.5f)
    }

    @Test fun compressAllPreservesKeysAndAnchors() {
        val raw = mapOf("ref" to 1f, "bw" to 0f, "half" to 0.5f)
        val out = CoefficientCompression.compressAll(raw, 0.75f)
        assertEquals(raw.keys, out.keys)
        assertEquals(1f, out.getValue("ref"), 0f)
        assertEquals(0f, out.getValue("bw"), 0f)
        assertEquals(0.5f.pow(0.75f), out.getValue("half"), eps)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.CoefficientCompressionTest"`
Expected: FAIL — `CoefficientCompression` unresolved.

- [x] **Step 3: Write `CoefficientCompression`**

`CoefficientCompression.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import kotlin.math.pow

/**
 * Structural log-space compression of the coefficient guesses toward the per-muscle reference lift
 * (coef 1.0). `compress(guess, λ) = guess^λ`, so λ<1 shrinks the spread of log-coefficients by a
 * constant factor while pinning the anchors: bodyweight (0) and reference (1) exercises are
 * unchanged. λ is the single identifiable structural parameter (per-exercise refit is
 * underdetermined — see the Part B spec).
 */
object CoefficientCompression {
    fun compress(guess: Float, lambda: Float): Float = when {
        guess <= 0f -> 0f
        guess == 1f -> 1f
        else -> guess.pow(lambda)
    }

    fun compressAll(raw: Map<String, Float>, lambda: Float): Map<String, Float> =
        raw.mapValues { compress(it.value, lambda) }
}
```

- [x] **Step 4: Create `CoefficientGuesses` (move the current map verbatim)**

`CoefficientGuesses.kt` — copy the **entire** `val byName = mapOf(...)` body (all entries, all section comments) verbatim from the current `ExerciseCoefficients.kt` (lines 6–125) into `raw`:

```kotlin
package io.github.fowles.stochastic_strength.domain

/**
 * The legible round-number coefficient priors (per-exercise 1RM ratios vs. each muscle's reference
 * lift). These are hand-picked guesses and the input to [CoefficientCompression]; the shipped
 * runtime table [ExerciseCoefficients] is `compress(raw, LAMBDA)`. Keep values as round numbers —
 * the fitting lives entirely in the single global λ, not here.
 */
object CoefficientGuesses {
    val raw: Map<String, Float> = mapOf(
        // CHEST (reference: Barbell Bench Press)
        "Barbell Bench Press"          to 1.00f,
        "Incline Barbell Bench Press"  to 0.85f,
        // ... ALL remaining entries copied verbatim from ExerciseCoefficients.byName ...
        "Suitcase Carry"               to 0.45f,
    )
}
```

- [x] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.CoefficientCompressionTest"`
Expected: PASS (6 tests).

- [x] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientGuesses.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientCompression.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/CoefficientCompressionTest.kt
git commit -m "feat(coef): CoefficientGuesses prior + CoefficientCompression (guess^λ)"
```

---

### Task 2: Rewire `ExerciseCoefficients` to the computed compressed table (λ=1.0, zero behavior change)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseCoefficients.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseCoefficientsTest.kt` (add derivation assertions)

**Interfaces:**
- Consumes: `CoefficientGuesses.raw`, `CoefficientCompression.compressAll` (Task 1).
- Produces: `ExerciseCoefficients` unchanged public surface — `object ExerciseCoefficients : CoefficientSource { const val LAMBDA: Float; val byName: Map<String, Float>; fun get(exercise): Float? }`. **This task keeps `LAMBDA = 1.0f` so `byName` is bit-identical to today** — a pure refactor proven behavior-preserving before the number moves in Task 5.

- [x] **Step 1: Write the failing test**

Add to `ExerciseCoefficientsTest.kt`:

```kotlin
@Test
fun byNameIsCompressedGuesses() {
    assertEquals(
        CoefficientCompression.compressAll(CoefficientGuesses.raw, ExerciseCoefficients.LAMBDA),
        ExerciseCoefficients.byName,
    )
}

@Test
fun everyGuessSurvivesAsAKey() {
    // No exercise is dropped by the generator; anchors are preserved.
    assertEquals(CoefficientGuesses.raw.keys, ExerciseCoefficients.byName.keys)
    for ((name, g) in CoefficientGuesses.raw) {
        if (g == 0f) assertEquals(0f, ExerciseCoefficients.byName.getValue(name), 0f)
        if (g == 1f) assertEquals(1f, ExerciseCoefficients.byName.getValue(name), 0f)
    }
}
```

(Add `import org.junit.Assert.assertEquals` to the test file.)

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ExerciseCoefficientsTest"`
Expected: FAIL — `ExerciseCoefficients.LAMBDA` unresolved.

- [x] **Step 3: Rewrite `ExerciseCoefficients`**

Replace the whole file body with the computed table (the literal map now lives in `CoefficientGuesses`):

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise

/**
 * The shipped runtime coefficient table: [CoefficientGuesses] compressed by the global structural
 * exponent [LAMBDA] (`guess^λ`). A fitted artifact — [LAMBDA] is pinned by the held-out belief
 * backtest and CI-guarded by ExerciseCoefficientsTest. Shipping a new table (new guesses or a
 * re-fit λ) is a pure code change: nothing coefficient-derived is stored per user, so no migration.
 */
object ExerciseCoefficients : CoefficientSource {
    /**
     * `fitted` — global log-coefficient compression exponent. Adopted from the held-out λ sweep
     * (CoefExponentFitTest). Provenance curve recorded in Task 5.
     * TODO(Task 5): move off 1.0 to the fitted optimum after the sweep is reviewed.
     */
    const val LAMBDA: Float = 1.0f

    val byName: Map<String, Float> = CoefficientCompression.compressAll(CoefficientGuesses.raw, LAMBDA)

    override fun get(exercise: Exercise): Float? = byName[exercise.name]
}
```

- [x] **Step 4: Run the coefficient tests, then the full JVM suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ExerciseCoefficientsTest"`
Expected: PASS.

Run: `./gradlew :app:testDebugUnitTest`
Expected: **All green** — at λ=1.0 the table is identical, so no downstream test moves. (If anything fails here, the refactor is not behavior-preserving — stop and investigate; do not proceed to Task 5.)

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseCoefficients.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseCoefficientsTest.kt
git commit -m "refactor(coef): ExerciseCoefficients = compress(CoefficientGuesses, λ=1.0) (no behavior change)"
```

---

### Task 3: Backtest λ plumbing — `BacktestData.withCoefLambda`

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestData.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestDataTest.kt` (add λ cases)

**Interfaces:**
- Consumes: `CoefficientGuesses.raw`, `CoefficientCompression.compress` (Task 1); `ExerciseCoefficients` (shipped default).
- Produces: `BacktestData.coefById: Map<Long, Float>` (single coefficient source routed to both `newSnapshot().seedCoefficients` and the prebuilt seeds); `fun BacktestData.withCoefLambda(lambda: Float): BacktestData` — a copy whose coefficients are `CoefficientGuesses.raw[name]^lambda` per active exercise. `withCoefLambda(ExerciseCoefficients.LAMBDA)` reproduces the default.

- [x] **Step 1: Write the failing test**

Add to `BacktestDataTest.kt` (guard on history like the other backtest tests):

```kotlin
@Test
fun withCoefLambdaOneReproducesRawGuessSeeds() {
    val data = BacktestData.loadOrNull() ?: return
    val identity = data.withCoefLambda(1.0f)
    // λ=1 → coefById equals the raw guesses per active exercise.
    val expected = data.backup.exercises.filterNot { it.isDisliked }
        .associate { it.id to (CoefficientGuesses.raw[it.name] ?: 0f) }
    assertEquals(expected, identity.coefById)
}

@Test
fun withCoefLambdaCompressesCoefficients() {
    val data = BacktestData.loadOrNull() ?: return
    val compressed = data.withCoefLambda(0.5f)
    // Every positive, non-reference coefficient moves strictly toward 1.0.
    val moved = compressed.coefById.entries.count { (id, c) ->
        val g = data.backup.exercises.first { it.id == id }.let { CoefficientGuesses.raw[it.name] ?: 0f }
        g > 0f && g != 1f && c != g
    }
    assertTrue("λ=0.5 must change at least one coefficient", moved > 0)
}
```

(Imports: `CoefficientGuesses`, `org.junit.Assert.assertEquals`, `org.junit.Assert.assertTrue`.)

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BacktestDataTest"`
Expected: FAIL — `withCoefLambda` / `coefById` unresolved.

- [x] **Step 3: Route coefficients through one `coefById` and add `withCoefLambda`**

Rewrite `BacktestData.kt` so every coefficient consumer reads one `coefById`, and both factories funnel through a private constructor:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.CoefficientCompression
import io.github.fowles.stochastic_strength.domain.CoefficientGuesses
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonParser
import io.github.fowles.stochastic_strength.domain.backup.WorkoutBackup
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansion
import io.github.fowles.stochastic_strength.domain.progression.SeedBelief
import java.io.File

class BacktestData private constructor(
    val backup: WorkoutBackup,
    val weightUnit: WeightUnit,
    /** Active (non-disliked) exercise id → coefficient. THE single coefficient source for replay. */
    val coefById: Map<Long, Float>,
) {
    val sessions = backup.workoutSessions
        .filter { it.endTime != null }
        .sortedWith(compareBy({ it.endTime!! }, { it.id }))

    val setsBySession = backup.workoutSets.groupBy { it.sessionId }
        .mapValues { (_, s) -> s.sortedBy { it.id } }

    private val exerciseMuscle = backup.exercises.associate { it.id to it.primaryMuscle }

    /** Mirrors ReplaySnapshot.loadStaticFromDb: muscle map from all exercises; coefficients from
     *  active exercises only (exactly the DAO's getActive()). */
    fun newSnapshot(): ReplaySnapshot = ReplaySnapshot(
        exerciseMuscle = exerciseMuscle,
        seedCoefficients = coefById,
    )

    private val seeds = ExerciseSeedExpansion.buildSeeds(
        initialOverrides = backup.baselineOverrides.filter { it.sessionId == null },
        sessionOverrides = backup.baselineOverrides.filter { it.sessionId != null },
        sex = backup.userProfile.firstOrNull()?.sex ?: Sex.MALE,
        level = backup.userProfile.firstOrNull()?.strengthLevel ?: StrengthLevel.MEDIUM,
        exerciseMuscle = exerciseMuscle,
        coefById = coefById,
    )

    val initialSeeds: List<SeedBelief> = seeds.initial
    val sessionSeeds: Map<Long, List<SeedBelief>> = seeds.bySession

    /** A copy with coefficients recomputed as `CoefficientGuesses.raw^lambda` — for the λ fit sweep.
     *  Recompiles BOTH the snapshot coefficients and the prebuilt seeds from one map. */
    fun withCoefLambda(lambda: Float): BacktestData =
        BacktestData(backup, weightUnit, compressedCoef(backup, lambda))

    companion object {
        private val dir = File("src/test/resources/backtest")
        fun historyFile(): File = File(dir, "history.json")
        fun baselineFile(): File = File(dir, "phase0_baseline.json")

        /** Shipped table (already compressed at ExerciseCoefficients.LAMBDA). */
        private fun shippedCoef(backup: WorkoutBackup): Map<Long, Float> =
            backup.exercises.filterNot { it.isDisliked }
                .associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) }

        /** Raw guesses compressed by an arbitrary λ, keyed by active exercise id. */
        private fun compressedCoef(backup: WorkoutBackup, lambda: Float): Map<Long, Float> =
            backup.exercises.filterNot { it.isDisliked }
                .associate { it.id to CoefficientCompression.compress(CoefficientGuesses.raw[it.name] ?: 0f, lambda) }

        fun loadOrNull(): BacktestData? {
            val f = historyFile()
            if (!f.exists()) return null
            return from(BackupJsonParser.parse(f.readText()))
        }

        fun from(backup: WorkoutBackup): BacktestData =
            BacktestData(
                backup,
                backup.userProfile.firstOrNull()?.weightUnit ?: WeightUnit.KG,
                shippedCoef(backup),
            )
    }
}
```

Note: `backup` and `weightUnit` remain public (existing callers use them). `newSnapshot()` and the seeds now read `coefById` instead of recomputing from `ExerciseCoefficients`.

- [x] **Step 4: Run the backtest suite, fix any fallout**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.*"`
Expected: PASS. `BacktestData.from(...)` produces the shipped table (λ=1.0 here → identical to today), so `BeliefScoreTest`/`BeliefStackReplayTest`/`BeliefHeldOutScorerTest` are unaffected. If a test referenced a removed public constructor or the old `newSnapshot` recompute, update it to the private-constructor + `from()` shape.

- [x] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestData.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestDataTest.kt
git commit -m "test(backtest): route coefficients through coefById + BacktestData.withCoefLambda"
```

---

### Task 4: λ held-out fit test (reporting authority)

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/CoefExponentFitTest.kt`

**Interfaces:**
- Consumes: `BacktestData.loadOrNull`, `BacktestData.withCoefLambda` (Task 3); `BeliefHeldOutScorer.score` + `BeliefConfig` (existing).
- Produces: a reporting test (like `BeliefFitTest`) that prints the held-out total/per-set score for each λ in a grid and the argmin. **Not a gate** — it reports; a human adopts in Task 5.

- [x] **Step 1: Write the fit/report test**

`CoefExponentFitTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import org.junit.Assume
import org.junit.Test

/**
 * 1-D held-out sweep of the global coefficient-compression exponent λ (coef' = guess^λ) against the
 * ONE authority: held-out belief score on real history (BeliefHeldOutScorer). λ is not a
 * BeliefConfig field — it transforms fixed guessed constants into other fixed constants — so it is
 * swept via BacktestData.withCoefLambda, not the BeliefConfig coordinate descent. Human-gated:
 * the printed argmin + curve is adopted into ExerciseCoefficients.LAMBDA by hand (Task 5). Skips
 * without history.json.
 *
 * Design evidence (already gathered, this session): forward-chaining cold-start RMSE improved
 * 0.275→0.226 ln at λ≈0.75–0.80 under leave-one-exercise-out (scratchpad coldstart.py/compress.py).
 */
class CoefExponentFitTest {

    // Wide grid; an argmin on the EDGE means "widen the grid", not "adopt".
    private val grid = listOf(0.50f, 0.60f, 0.65f, 0.70f, 0.75f, 0.80f, 0.85f, 0.90f, 0.95f, 1.00f, 1.10f)

    @Test
    fun sweepCoefExponentHeldOut() {
        val data = BacktestData.loadOrNull()
        Assume.assumeTrue("backtest/history.json not present; skipping", data != null)
        data!!

        val config = BeliefConfig()
        val curve = grid.map { lambda ->
            val r = BeliefHeldOutScorer.score(data.withCoefLambda(lambda), config).report
            Triple(lambda, r.totalDistance, r.totalDistance / r.scoredSets)
        }
        val best = curve.minByOrNull { it.second }!!

        val sb = StringBuilder()
        sb.appendLine("=== Part B fit: coefficient compression λ, held-out belief score ===")
        sb.appendLine("  ${"lambda".padStart(6)}  ${"total".padStart(10)}  ${"per-set".padStart(9)}")
        for ((lambda, total, perSet) in curve) {
            val mark = when {
                lambda == best.first -> "  <-- best"
                lambda == 1.0f -> "  <-- current (identity)"
                else -> ""
            }
            sb.appendLine("  ${"%.2f".format(lambda).padStart(6)}  ${"%.4f".format(total).padStart(10)}  ${"%.5f".format(perSet).padStart(9)}$mark")
        }
        sb.appendLine("best λ = ${"%.2f".format(best.first)} (total ${"%.4f".format(best.second)} / per-set ${"%.5f".format(best.third)})")
        println(sb)
    }
}
```

- [x] **Step 2: Run it and confirm it reports (does not assert)**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.CoefExponentFitTest" -i`
Expected: PASS, with the λ curve printed to stdout. Capture the printed curve + best λ — Task 5 consumes them.

- [x] **Step 3: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/CoefExponentFitTest.kt
git commit -m "test(backtest): held-out λ sweep for coefficient compression (reporting)"
```

---

### Task 5: Adopt the fitted λ, re-baseline the gate (human-gated checkpoint)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseCoefficients.kt` (`LAMBDA` + provenance)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseLibraryTest.kt` (`:36` literal → compressed)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefScoreTest.kt` (recorded score numbers in the comment header)

**Interfaces:**
- Consumes: the printed λ curve from Task 4; the shipped-value path from Tasks 1–2.
- Produces: `ExerciseCoefficients.LAMBDA` set to the adopted optimum with the curve recorded; the gate green with updated recorded numbers.

**⚠️ This task is a review checkpoint. Present the Task 4 curve to the human before adopting. Adopt the argmin unless it sits on a grid edge (then widen the grid in Task 4 and re-run). Expected optimum ≈ 0.75 (usv fit + this session's cold-start LOO both landed 0.75–0.80).**

- [x] **Step 1: Adopt λ in `ExerciseCoefficients`**

Set `LAMBDA` to the adopted value and replace the `TODO(Task 5)` provenance with the recorded curve, e.g.:

```kotlin
    /**
     * `fitted` 2026-07-31 — global log-coefficient compression exponent (coef' = guess^λ), adopted
     * from the held-out λ sweep (CoefExponentFitTest). Curve (held-out per-set, ln-units):
     * 0.60→<x>  0.70→<x>  0.75→<x>  0.80→<x>  0.90→<x>  1.00→<x>. Independent cold-start LOO
     * (scratchpad) landed the same 0.75–0.80. Reference (1.0) and bodyweight (0.0) lifts unchanged.
     */
    const val LAMBDA: Float = 0.75f
```

Fill `<x>` from the actual Task 4 printout. Use the adopted argmin as the literal.

- [x] **Step 2: Update the one coefficient-literal test**

`ExerciseLibraryTest.kt:36` currently asserts `assertEquals(0.5f, ExerciseCoefficients.get(e))`. The shipped value is now compressed. Change it to assert the compressed value so intent (get() returns the shipped coef) is preserved:

```kotlin
import io.github.fowles.stochastic_strength.domain.CoefficientCompression
// ...
assertEquals(
    CoefficientCompression.compress(0.5f, ExerciseCoefficients.LAMBDA),
    ExerciseCoefficients.get(e)!!,
    1e-6f,
)
```

- [x] **Step 3: Run the safety-critical prescription pin FIRST**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProdBssPrescriptionTest"`
Expected: PASS. The BSS pin folds on demonstrated data, so the compressed QUADS seed should not move the prescription. **If it fails, STOP** — a moved safety pin is a human adjudication (trust this canonical test, per project memory), not a number to silently re-pin.

- [x] **Step 4: Re-baseline the belief gate (comment numbers only)**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefScoreTest" -i`
Expected: PASS — the gate is **relative** (belief per-set < phase0 baseline per-set); compression is expected to lower the per-set score, so it passes more comfortably. Read the printed `total` / `mean per set`, and update the recorded numbers in `BeliefScoreTest`'s KDoc header (the "Measured after wiring live seed expansion … total 37.6714 / per-set 0.11015 …" line) to the new post-compression values, noting "after adopting λ=<value>". **If the gate goes red** (per-set rose above the phase0 baseline), STOP and escalate — that means compression hurt on this history; do not adopt.

- [x] **Step 5: Full JVM + instrumented suites, fix fallout**

Run: `./gradlew :app:testDebugUnitTest`
Expected: green. Fix any test that hardcoded a now-compressed coefficient or seed weight (grep `ExerciseCoefficients` / seed-weight literals in the failing test; assert against `CoefficientCompression.compress(guess, ExerciseCoefficients.LAMBDA)` or `CoefficientGuesses.raw`).

Run: `./gradlew :app:connectedAndroidTest`
Expected: green (emulator typically running). The instrumented replay/derived-state tests re-derive through `replayDerivedState`; a compressed coefficient shifts cold-start seeds only, self-corrected by folds. Fix any hardcoded-coefficient assertion the same way.

- [x] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseCoefficients.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseLibraryTest.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BeliefScoreTest.kt
# plus any additional fallout tests fixed in Step 5
git commit -m "feat(coef): adopt fitted compression λ=<value>; re-baseline belief gate"
```

---

### Task 6: Docs — CLAUDE.md + spec closeout

**Files:**
- Modify: `CLAUDE.md` (progression section)
- Modify: `docs/superpowers/specs/2026-07-31-fitted-coefficients-and-derived-state-cleanup-design.md` (mark Part B / Plan 2-in-spec done)

**Interfaces:**
- Consumes: nothing (docs).
- Produces: living docs reflect the compressed-coefficient artifact.

- [x] **Step 1: Update CLAUDE.md**

In the progression section, the cold-start paragraph already says "shipping a new/refit coefficient table changes seeds automatically on next replay, no migration needed." Extend the coefficient description to record the new shape. Find the sentence describing `ExerciseCoefficients` seed coefficients (near "Seed coefficients come from `ExerciseCoefficients`") and append:

```
`ExerciseCoefficients.byName` is a fitted artifact: `CoefficientCompression.compress(CoefficientGuesses.raw, LAMBDA)` where `LAMBDA` (`fitted`, global) is pinned by the held-out λ sweep (`CoefExponentFitTest`) and CI-guarded by `ExerciseCoefficientsTest`. `CoefficientGuesses` holds the legible round-number priors; reference (1.0) and bodyweight (0.0) lifts are unchanged by compression. Re-fitting λ or editing a guess is a pure code change — nothing coefficient-derived is stored per user.
```

- [x] **Step 2: Mark the spec's Part B done**

At the top of the spec file, note Part B shipped (the plan split into three: detrain-by-inference, seed/override consolidation, and this compression plan). Add a one-line status under the header, e.g. `**Status:** Part A shipped (Plans 1–2); Part B (compression) shipped 2026-07-31.`

- [x] **Step 3: Commit**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-07-31-fitted-coefficients-and-derived-state-cleanup-design.md
git commit -m "docs: ExerciseCoefficients is a fitted compressed artifact; Part B closeout"
```

---

## Self-Review

**Spec coverage (Part B):**
- `CoefficientGuesses` (raw round-number priors) → Task 1. ✓
- `ExerciseCoefficients` = compressed values, `byName == compress(guesses, λ)`, anchors preserved → Tasks 2 (structure, λ=1) + 5 (adopt λ); assertion test in Task 2. ✓
- Global λ fit by held-out backtest, salvaging the compressed-seed sweep → Tasks 3 (`withCoefLambda`) + 4 (`CoefExponentFitTest`). ✓
- Cold-start forward-chaining RMSE as secondary readout → cited as recorded design evidence (Global Constraints + Task 4 KDoc), per the ONE-authority rule; not re-derived. ✓
- Re-baseline the gate (human decision) → Task 5 Step 4 (relative gate; update recorded numbers). ✓
- No migration → nothing user-stored changes (Global Constraints); confirmed by the whole plan touching only `domain/` constants + tests. ✓
- `f(gap)` decay + layoff threshold → **already shipped in Plan 1 (`DetrainingModel.kt`)**; explicitly out of scope. ✓

**Placeholder scan:** The only deferred value is the fitted λ itself (Task 5), which is a human-gated adoption of a *reported* number — the same ceremony as `BeliefConfig`'s fitted constants — not a forbidden placeholder. The expected value (0.75) and the adoption procedure are concrete. The verbatim-copy of the coefficient map in Task 1 is a mechanical move of existing content, flagged explicitly.

**Type consistency:** `compress(guess: Float, lambda: Float): Float` and `compressAll(raw: Map<String,Float>, lambda: Float): Map<String,Float>` used identically in Tasks 1–5. `ExerciseCoefficients.LAMBDA: Float` (const) referenced consistently in Tasks 2, 3, 5. `BacktestData.coefById: Map<Long,Float>` and `withCoefLambda(lambda: Float): BacktestData` used identically in Tasks 3–4. `BeliefHeldOutScorer.score(data, config).report.{totalDistance,scoredSets}` matches the existing signature read in Task 0 exploration.
