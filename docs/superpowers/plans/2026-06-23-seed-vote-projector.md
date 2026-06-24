# Seed-vote Muscle-Level Prior Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `MuscleStrengthProjector`'s hard `confidentThreshold` gate and separate cold-muscle fallback with a single fixed-weight seed prior, so confidence weighting alone governs cross-informing and a stale lone voter decays back to seed.

**Architecture:** The muscle level becomes one weighted log-space mean: every loaded exercise votes with its full decayed confidence against a prior anchored at the unweighted mean of seed-relative levels `ln(E_j/coef_j)` (which equals `ln(baseline)` for a cold muscle). The per-exercise shrink target becomes `ln(coef)+lnLevel` unconditionally. Change is confined to `MuscleStrengthProjector` and `EstimatorConfig`.

**Tech Stack:** Kotlin, JUnit4, JVM unit tests (`./gradlew :app:testDebugUnitTest`).

## Global Constraints

- Pure domain code; no Room/schema/persistence changes (derived state is rebuilt by replay).
- `EstimatorConfig` is the sole tuning surface and is pinned by `ExerciseEstimatorSimulationTest`.
- Do not touch `ExerciseEstimateUpdater` (the fold) or `SessionSignalExtractor` (signal extraction).
- Design from the caller's perspective; keep `MuscleProjection`'s public shape (`level`, `effectiveE1rm`, `derivedCoef`) unchanged.

---

## File Structure

- `app/src/main/.../domain/progression/ExerciseEstimate.kt` — `EstimatorConfig`: drop `confidentThreshold`, add `levelPrior`.
- `app/src/main/.../domain/progression/MuscleStrengthProjector.kt` — rewrite `project`; delete `fallbackLevel` and the nullable-level / `else e.lnE` branch.
- `app/src/test/.../domain/progression/MuscleStrengthProjectorTest.kt` — add the lone-voter corner test; rename the now-misnamed fallback test; widen one tolerance.
- `app/src/test/.../domain/progression/ExerciseEstimatorSimulationTest.kt` — decouple the "well-trained" metric from the deleted config field; re-pin `levelPrior`.

---

### Task 1: Seed-vote rewrite of `MuscleStrengthProjector`

Replace the gate + fallback with the single weighted prior. Drive it with a `levelPrior`-agnostic corner test, and keep every existing `MuscleStrengthProjectorTest` case green.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimate.kt` (the `EstimatorConfig` data class, ~lines 30-47)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjector.kt` (the `project` fun + `fallbackLevel`)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjectorTest.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimatorSimulationTest.kt` (compile fix only this task)

**Interfaces:**
- Consumes: `ExerciseEstimate(lnE, confidence, updatedAt)`, `EstimatorConfig.halfLifeMs/priorStrength`.
- Produces: `MuscleStrengthProjector.project(estimates, seedCoef, muscleExerciseIds, now): MuscleProjection` (signature unchanged); `EstimatorConfig.levelPrior: Float`.

- [ ] **Step 1: Write the failing corner test**

Add to `MuscleStrengthProjectorTest.kt` (the `est` helper already exists):

```kotlin
@Test
fun loneModeratelyConfidentVoterDoesNotFullyDefineLevel() {
    // Seed-vote prior regression: a single moderately-confident exercise must NOT fully own the
    // muscle level. ex1 is trained up to 150 (conf 2); its two siblings are cold at their seed of
    // 100. Under the old threshold gate ex1 voted alone and the level snapped to 150 (normalization
    // cancels its confidence magnitude). With the fixed-weight seed prior the level is pulled toward
    // the seed-relative mean (~114), so it must land strictly between that prior and ex1's implied
    // 150 — never at 150. Bounds hold for any levelPrior in [0.3, 2.0] (level lands ~131..145).
    val estimates = mapOf(
        1L to est(150f, conf = 2f),
        2L to est(100f, conf = 0f),
        3L to est(100f, conf = 0f),
    )
    val seed = mapOf(1L to 1.0f, 2L to 1.0f, 3L to 1.0f)
    val p = projector.project(estimates, seed, muscleExerciseIds = listOf(1L, 2L, 3L), now = 0L)
    val priorLevel = Math.cbrt((150f * 100f * 100f).toDouble()).toFloat() // geomean ≈ 114.47
    assertTrue("level ${p.level} must be below the lone voter's implied 150", p.level < 149f)
    assertTrue("level ${p.level} must be above the seed prior $priorLevel", p.level > priorLevel)
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjectorTest.loneModeratelyConfidentVoterDoesNotFullyDefineLevel"`
Expected: FAIL — current code returns `level == 150.0` (lone voter defines the level), so `level < 149f` fails.

- [ ] **Step 3: Swap `confidentThreshold` for `levelPrior` in `EstimatorConfig`**

In `ExerciseEstimate.kt`, replace these two fields:

```kotlin
    /** Sibling-prior strength (kappa) in the read-time shrink: how many confidence units the pool is worth. */
    val priorStrength: Float = 1.0f,
    /** Minimum decayed confidence for an exercise to vote in the muscle level / be trusted as its own estimate. */
    val confidentThreshold: Float = 1.0f,
```

with:

```kotlin
    /** Sibling-prior strength (kappa) in the read-time shrink: how many confidence units the pool is worth. */
    val priorStrength: Float = 1.0f,
    /**
     * Effective sample size of the seed prior in the muscle-level pool. Every exercise votes with its
     * full decayed confidence against this fixed-weight prior, so a thinly-evidenced muscle leans on
     * the seed and a stale lone voter decays back toward it instead of defining the level. Pinned by
     * ExerciseEstimatorSimulationTest.
     */
    val levelPrior: Float = 1.0f,
```

- [ ] **Step 4: Rewrite `project` in `MuscleStrengthProjector.kt`**

Replace the entire body of `project` (lines 23-62 in the current file — from `val votes = ...` through `return MuscleProjection(...)`) and delete `fallbackLevel`. Keep the `conf` local fun and the private `Float.pow` helper. The new body:

```kotlin
    fun project(
        estimates: Map<Long, ExerciseEstimate>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
    ): MuscleProjection {
        fun conf(e: ExerciseEstimate): Float {
            val age = (now - e.updatedAt).coerceAtLeast(0L)
            return e.confidence * 0.5f.pow(age.toFloat() / config.halfLifeMs)
        }

        // Loaded exercises with a positive seed coefficient.
        val loaded: List<Triple<Long, ExerciseEstimate, Float>> = muscleExerciseIds.mapNotNull { id ->
            val e = estimates[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) null else Triple(id, e, coef)
        }
        if (loaded.isEmpty()) return MuscleProjection(level = 0f, effectiveE1rm = emptyMap(), derivedCoef = emptyMap())

        // Seed prior anchor: unweighted mean of seed-relative levels ln(E_j / coef_j). Equals
        // ln(baseline) for a cold muscle (untrained siblings sit at seed) and drifts only as
        // exercises are genuinely trained.
        val lnPrior = loaded.map { (_, e, coef) -> e.lnE - ln(coef) }.average().toFloat()

        // Pooled level: every exercise votes with its full decayed confidence against the
        // fixed-weight prior. No threshold — low confidence simply contributes little.
        var num = config.levelPrior * lnPrior
        var den = config.levelPrior
        for ((_, e, coef) in loaded) {
            val c = conf(e)
            num += c * (e.lnE - ln(coef))
            den += c
        }
        val lnLevel = num / den
        val level = exp(lnLevel)

        val effective = mutableMapOf<Long, Float>()
        val coefs = mutableMapOf<Long, Float>()
        for ((id, e, coef) in loaded) {
            val cSelf = conf(e)
            val lnPred = ln(coef) + lnLevel // always defined; cold muscle -> ln(coef)+ln(baseline) == seed
            val lnUsed = (cSelf * e.lnE + config.priorStrength * lnPred) / (cSelf + config.priorStrength)
            val used = exp(lnUsed)
            effective[id] = used
            coefs[id] = if (level > 0f) used / level else coef
        }
        return MuscleProjection(level = level, effectiveE1rm = effective, derivedCoef = coefs)
    }
```

Then delete the whole `fallbackLevel` private function (current lines 64-76). Leave the `private fun Float.pow(...)` helper.

- [ ] **Step 5: Decouple the simulation's "well-trained" metric from the deleted field**

`ExerciseEstimatorSimulationTest.kt` references `config.confidentThreshold` (around line 251), which no longer exists. Add a class-level constant near the other tuning fields (after `private val behavioralGrowth = 0.002f`):

```kotlin
    /** Decayed-confidence cutoff for the test's own "I know this exercise" tracking metric. */
    private val wellTrainedConf = 1.0f
```

Then in the tail-metric block, change the cutoff line from:

```kotlin
                    decayedConf >= config.confidentThreshold
```

to:

```kotlin
                    decayedConf >= wellTrainedConf
```

(Keep `val config = EstimatorConfig()` just above it — it is still used for `config.halfLifeMs`.)

- [ ] **Step 6: Widen one existing-test tolerance and rename the fallback test**

The softer (prior-tempered) cross-inform moves `coldExerciseBorrowsFromConfidentSiblings`'s result a touch lower (~57-59 vs the old ~60). Widen its delta so it stays robust across the `levelPrior` sweep range. Change:

```kotlin
        assertEquals("cold exercise pulled toward sibling prediction", 60f, p.effectiveE1rm.getValue(2L), 3f)
```

to:

```kotlin
        assertEquals("cold exercise pulled toward sibling prediction", 60f, p.effectiveE1rm.getValue(2L), 5f)
```

Rename `fallbackLevelIsGeomeanOfImpliedLevelsAcrossColdSiblings` (there is no longer a separate fallback path — the all-cold case now flows through the prior, which equals the geomean) to `coldMuscleLevelEqualsSeedRelativeGeomean`, and update its leading comment's "falls back to" wording to "resolves to". Behavior and asserted value (`sqrt(100*120)`) are unchanged.

- [ ] **Step 7: Run the full projector unit test class**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjectorTest"`
Expected: PASS (all cases, including the new `loneModeratelyConfidentVoterDoesNotFullyDefineLevel`).

If `coldExerciseBorrowsFromConfidentSiblings` still fails at the `5f` tolerance, the cause is a `levelPrior` far outside [0.3, 2.0]; do not loosen further here — it is re-pinned in Task 2.

- [ ] **Step 8: Commit**

```bash
jj commit -m "feat: seed-vote muscle-level prior in MuscleStrengthProjector

Replace the confidentThreshold gate + cold-muscle fallback with a single
fixed-weight seed prior so confidence weighting alone governs cross-informing
and a stale lone voter decays back to seed. levelPrior provisional at 1.0;
re-pinned in the simulation next.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimate.kt \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjector.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjectorTest.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimatorSimulationTest.kt
```

---

### Task 2: Re-pin `levelPrior` against the simulation

The simulation's behavioral assertions (convergence ≤ 12 sessions, jitter ≤ 6%, failRate ≤ 0.40, lastSetRir ∈ [0,2], tail prescribed error ≤ 8%, cold-sibling error ≤ 12%) are the spec. Sweep `levelPrior`, report the metrics, and lock the value that keeps every assertion passing with the best margins.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimate.kt` (final `levelPrior` value)

**Interfaces:**
- Consumes: `EstimatorConfig.levelPrior` (from Task 1); `ExerciseEstimatorSimulationTest` harness (unchanged from Task 1).
- Produces: locked `levelPrior` default.

- [ ] **Step 1: Run the simulation at the provisional value**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimatorSimulationTest"`
Expected: likely PASS at `levelPrior = 1.0`. Record `convSessions`, `trainedEndErr`, `jitter`, `lastSetRir`, `failRate` if any assertion message prints; otherwise note PASS/FAIL per test.

- [ ] **Step 2: Sweep `levelPrior`**

For each value in {0.3, 0.5, 1.0, 1.5, 2.0}: set `levelPrior`'s default in `ExerciseEstimate.kt` to that value, re-run the simulation test class, and record pass/fail plus any reported metric margins.

- [ ] **Step 3: Report the sweep and get sign-off on the chosen value**

Present the sweep table (value → pass/fail + key margins) to the user. Recommend the value that passes all assertions with the most headroom on the tightest metric (typically jitter and failRate). **Pause for the user to confirm the `levelPrior` value before locking.**

- [ ] **Step 4: Lock the chosen value**

Set `levelPrior`'s default in `ExerciseEstimate.kt` to the confirmed value (update the provisional `1.0f` if different).

- [ ] **Step 5: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (regression check across the whole module, including `MuscleStrengthProjectorTest` and the re-pinned simulation).

- [ ] **Step 6: Commit**

```bash
jj commit -m "test: re-pin levelPrior=<chosen> in ExerciseEstimatorSimulationTest

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimate.kt
```

---

## Self-Review

**Spec coverage:**
- Remove `confidentThreshold`, add `levelPrior` → Task 1 Step 3. ✓
- `lnPrior` = unweighted mean over all loaded → Task 1 Step 4. ✓
- Single weighted level with prior; no threshold → Task 1 Step 4. ✓
- `lnPred = ln(coef)+lnLevel` always; delete `else e.lnE` → Task 1 Step 4. ✓
- Delete `fallbackLevel`; remove `lnLevel: Float?` nullability → Task 1 Step 4. ✓
- Empty-muscle zero-projection guard → Task 1 Step 4. ✓
- Corner test: stale/lone voter does not define level → Task 1 Step 1. ✓
- Corner test: cold muscle reduces to seed → covered by the renamed `coldMuscleLevelEqualsSeedRelativeGeomean` + existing `noConfidentSiblingsFallsBackToOwnSeedEstimate` (both survive unchanged) → Task 1 Steps 6-7. ✓
- Simulation re-pin with sign-off → Task 2. ✓
- Decouple sim "well-trained" metric from deleted field → Task 1 Step 5. ✓

**Placeholder scan:** None — all code shown verbatim; `<chosen>` in Task 2's commit is a deliberate post-sweep value the executor fills from Step 3's sign-off.

**Type consistency:** `project` signature and `MuscleProjection(level, effectiveE1rm, derivedCoef)` unchanged; `levelPrior: Float` referenced consistently; `wellTrainedConf: Float` local to the sim test.
