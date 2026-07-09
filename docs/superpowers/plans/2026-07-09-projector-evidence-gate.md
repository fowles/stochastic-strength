# Projector Evidence Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple the muscle-pooling projector's `n_eff` from the belief's adaptation-inflated σ by giving each belief a clean "evidence variance" (`evidenceVar`), so a well-observed exercise whose σ was re-inflated by adaptive attention keeps a strong self-anchor and its confident siblings can no longer pull it toward the muscle level — closing the last 25→20 lb gap on prod BSS.

**Architecture:** Add one `Float` field `evidenceVar` to `ExerciseBelief` = the variance the belief would have without any adaptation inflation. Folds update it from the *un-inflated* prior (Kalman for Gaussian, truncated-Gaussian for censored); `age()` grows it like `sigma2`; `adaptPrior()` never touches it. `MuscleStrengthProjector.neff` reads `evidenceVar` instead of `sigma2` — the existing formula, one uniform notion of "how much do I know," used in all three consumers (level vote, `cSelf`, `siblingExcess`).

**Tech Stack:** Kotlin, JVM unit tests (JUnit4), Gradle. Pure domain code under `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/`.

**Design spec:** `docs/superpowers/specs/2026-07-09-projector-evidence-gate-design.md` (read it — it has the arithmetic and edge-case rationale).

## Global Constraints

- **No Room migration.** `ExerciseBelief` is in-memory derived state (rebuilt by replay); a new defaulted field is safe, exactly like `innovationRun`.
- **Replay determinism.** `evidenceVar` is a pure function of the folded observation sequence and timestamps.
- **`adaptPrior()` must NEVER read or write `evidenceVar`.** The evidence track is by definition the counterfactual where adaptation's variance inflation never happened.
- **The clean-variance update uses the UN-INFLATED prior** (`prior.evidenceVar`) and the **shared aged mean** (`aged.mu`) — never `sigma2`.
- **Exact fold math for `mu`/`sigma2` is unchanged.** `BeliefUpdaterFoldTest` must stay green — the new field is purely additive to the returned belief.
- **`neff` formula shape is unchanged** — it only swaps `sigma2` → `evidenceVar`: `((1/evidenceVar − 1/sigmaSeed²)·poolObsVar).coerceAtLeast(0f)`.
- **Sequencing:** land this plan BEFORE the adaptive-attention plan's Task 4/5/6 (the shared `BeliefSimulationTest`/`ProdBssPrescriptionTest`/`BacktestComparisonTest` re-baseline), so those gates measure corrected pooling. This plan intentionally leaves those three suites known-red; it re-pins nothing.
- **Commit convention (colocated jj repo, detached HEAD):** commit with `jj commit -m "<msg>"` (NOT `git add`/`git commit` — detached HEAD would diverge). One `jj commit` per task. Append `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` to messages. Capture the commit id with `jj log -r @- --no-graph -T 'commit_id.short()'`.
- **Test commands:**
  - Focused: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.<Class>"`
  - Full unit suite: `./gradlew :app:testDebugUnitTest`

## Known-red note (pre-existing, from the adaptive-attention plan)

`BeliefSimulationTest`, `ProdBssPrescriptionTest`, and `BacktestComparisonTest` are already red on this branch (the adaptive-attention changes moved belief trajectories; their re-baseline is the adaptive-attention plan's Task 4/5/6). This evidence-gate plan will further shift those trajectories — expected. Each task below confirms it introduces **no new failing test class** beyond those three. Do not re-pin them here.

## Reference: current code shapes

`ExerciseBelief` (in `ExerciseBelief.kt`) is a data class `(mu, sigma2, updatedAt, innovationRun=0f)` with `e1rm`/`sigma` getters and `seed()`/`override()` factories. `EstimatorConfig` is defined in the SAME file. `BeliefUpdater` has `age()`, private `kalmanStep()`, private `adaptPrior()`, `foldGaussian()`, `foldCensored()`, `internal clampVar()`, and a companion with `CLAMP`, `MIN_MASS`, `DAY_MS`, `WEEK_MS`. `MuscleStrengthProjector` has `neff(aged)` and `project(...)`.

---

## Task 1: `evidenceVar` field, initialization, and aging

Add the field and make `age()` grow it. No fold/projector wiring yet.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/BeliefUpdater.kt` (`age()`)
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/EvidenceVarTest.kt`

**Interfaces:**
- Produces: `ExerciseBelief.evidenceVar: Float` (default `0.0625f`); `seed()` sets it to `sigmaSeed²`, `override()` to `sigmaOverride²`; `age()` grows it by `processNoisePerDay·idleDays` (clamped).

- [ ] **Step 1: Write the failing test** — create `EvidenceVarTest.kt`

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceVarTest {
    private val config = EstimatorConfig()
    private val updater = BeliefUpdater(config)
    private val DAY = 24L * 60 * 60 * 1000

    @Test
    fun seedInitializesEvidenceVarToSeedVariance() {
        val b = ExerciseBelief.seed(e1rm = 38f, at = 0L, config = config)
        assertEquals(config.sigmaSeed * config.sigmaSeed, b.evidenceVar, 1e-9f)
    }

    @Test
    fun overrideInitializesEvidenceVarToOverrideVariance() {
        val b = ExerciseBelief.override(e1rm = 38f, at = 0L, config = config)
        assertEquals(config.sigmaOverride * config.sigmaOverride, b.evidenceVar, 1e-9f)
    }

    @Test
    fun ageGrowsEvidenceVar() {
        // A belief whose evidenceVar is below the cap must grow with idle time, like sigma2.
        val b = ExerciseBelief(mu = 3.6f, sigma2 = 0.01f, updatedAt = 0L, evidenceVar = 0.01f)
        val aged = updater.age(b, now = 30 * DAY, muscleLastObs = null)
        assertTrue("evidenceVar must grow with idle time (${aged.evidenceVar})", aged.evidenceVar > b.evidenceVar)
        assertEquals("evidenceVar ages exactly like sigma2 (same q)", aged.sigma2, aged.evidenceVar, 1e-9f)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.EvidenceVarTest"`
Expected: FAIL — `evidenceVar` unresolved (compile error).

- [ ] **Step 3: Add the field to `ExerciseBelief`**

Replace the data class declaration (keep the getters/companion below it):

```kotlin
data class ExerciseBelief(
    val mu: Float,
    val sigma2: Float,
    val updatedAt: Long,
    val innovationRun: Float = 0f,
    /**
     * Clean variance (projector-evidence-gate): the variance this belief WOULD have if adaptation had
     * never inflated it. Drives pooling n_eff so adaptive σ-inflation isn't misread as "uninformed".
     * Folds update it from the UN-inflated prior; age() grows it like sigma2; adaptPrior never touches
     * it. Default = EstimatorConfig().sigmaSeed² (0.0625) for the default config; seed()/override() set
     * it precisely from config. Only ever the raw default on non-pooled constructions (chart broad-prior,
     * unit tests). Not persisted (in-memory derived, rebuilt by replay).
     */
    val evidenceVar: Float = 0.0625f,
) {
```

- [ ] **Step 4: Set `evidenceVar` in `seed()` and `override()`**

In the companion object, update both factories:

```kotlin
        fun seed(e1rm: Float, at: Long, config: EstimatorConfig = EstimatorConfig()): ExerciseBelief =
            ExerciseBelief(mu = ln(e1rm), sigma2 = config.sigmaSeed * config.sigmaSeed, updatedAt = at,
                evidenceVar = config.sigmaSeed * config.sigmaSeed)

        fun override(e1rm: Float, at: Long, config: EstimatorConfig = EstimatorConfig()): ExerciseBelief =
            ExerciseBelief(mu = ln(e1rm), sigma2 = config.sigmaOverride * config.sigmaOverride, updatedAt = at,
                evidenceVar = config.sigmaOverride * config.sigmaOverride)
```

- [ ] **Step 5: Age `evidenceVar` in `BeliefUpdater.age()`**

Replace the body of `age()` (currently returns `ExerciseBelief(mu, sigma2, updatedAt=now, innovationRun=belief.innovationRun)`):

```kotlin
    fun age(belief: ExerciseBelief, now: Long, muscleLastObs: Long?): ExerciseBelief {
        if (now <= belief.updatedAt) return belief
        val idleDays = (now - belief.updatedAt).toFloat() / DAY_MS
        val sigma2 = clampVar(belief.sigma2 + config.processNoisePerDay * idleDays)
        val evidenceVar = clampVar(belief.evidenceVar + config.processNoisePerDay * idleDays)
        var mu = belief.mu
        if (muscleLastObs != null) {
            mu -= detrainDrift(maxOf(belief.updatedAt, muscleLastObs + config.detrainGraceMs), now)
        }
        // Carry innovationRun forward (aging is time passing, not a filter reset). evidenceVar ages
        // exactly like sigma2 so a stale exercise's evidence fades and it re-borrows from siblings.
        return ExerciseBelief(mu = mu, sigma2 = sigma2, updatedAt = now,
            innovationRun = belief.innovationRun, evidenceVar = evidenceVar)
    }
```

- [ ] **Step 6: Run the test + the exact-math fold test**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.EvidenceVarTest" --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefUpdaterFoldTest"`
Expected: `EvidenceVarTest` PASS; `BeliefUpdaterFoldTest` PASS (folds don't touch `evidenceVar` yet; `age()`'s `mu`/`sigma2` arms are unchanged).

- [ ] **Step 7: Commit**

```
jj commit -m "belief: add evidenceVar clean-variance field + age it like sigma2 (projector evidence gate, part 1)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
Record the commit id (`jj log -r @- --no-graph -T 'commit_id.short()'`).

---

## Task 2: Folds update `evidenceVar` from the un-inflated prior

Wire both fold paths to update `evidenceVar`, and add a `censoredPosteriorVar` helper for the censored case. `adaptPrior` still leaves `evidenceVar` untouched (its `.copy(sigma2 = ...)` preserves the field automatically — the key correctness point).

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/BeliefUpdater.kt` (`kalmanStep`, `foldCensored`, new `censoredPosteriorVar`)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/EvidenceVarTest.kt`

**Interfaces:**
- Consumes: `ExerciseBelief.evidenceVar`.
- Produces: after any fold, the returned belief's `evidenceVar` = the clean-track posterior variance computed from the pre-fold `evidenceVar` (never from the adaptation-inflated `sigma2`). A private `censoredPosteriorVar(mu, priorVar, lowerLn, upperLn, s): Float`.

- [ ] **Step 1: Write the failing tests** — append to `EvidenceVarTest.kt`

```kotlin
    @Test
    fun gaussianFoldReducesEvidenceVar() {
        val seed = ExerciseBelief.seed(e1rm = 38f, at = 0L, config = config)
        val after = updater.foldGaussian(seed, obsLnE1rm = 3.4f, noiseSd = 0.08f, at = 0L, muscleLastObs = null)
        assertTrue("a fold must add evidence (lower evidenceVar): ${after.evidenceVar}",
            after.evidenceVar < seed.evidenceVar)
    }

    @Test
    fun censoredFoldReducesEvidenceVar() {
        val seed = ExerciseBelief.seed(e1rm = 38f, at = 0L, config = config)
        val after = updater.foldCensored(seed, lowerLn = 3.3f, upperLn = 3.5f, noiseSd = 0.08f, at = 0L, muscleLastObs = null)
        assertTrue("a censored fold must add evidence: ${after.evidenceVar}",
            after.evidenceVar < seed.evidenceVar)
    }

    @Test
    fun adaptationDoesNotContaminateEvidenceVar() {
        // Fold a consistent down-run so adaptation fires and inflates sigma2. evidenceVar must be
        // essentially identical to the run WITHOUT adaptation (threshold huge) — proving it tracks
        // accumulated evidence, not the adaptation-inflated variance.
        val noAdapt = EstimatorConfig(adaptRunThreshold = 1e6f)
        val u2 = BeliefUpdater(noAdapt)
        var withAdapt = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L, evidenceVar = 0.02f * 0.02f)
        var without = withAdapt
        repeat(5) {
            withAdapt = updater.foldGaussian(withAdapt, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
            without = u2.foldGaussian(without, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
        }
        assertTrue("adaptation must inflate sigma2", withAdapt.sigma2 > without.sigma2)
        assertEquals("but evidenceVar must be untouched by adaptation",
            without.evidenceVar, withAdapt.evidenceVar, 1e-6f)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.EvidenceVarTest"`
Expected: FAIL — `evidenceVar` unchanged by folds (still seed value), so `gaussianFoldReducesEvidenceVar`/`censoredFoldReducesEvidenceVar` fail.

- [ ] **Step 3: Update `kalmanStep` to advance the clean track**

Replace `kalmanStep`:

```kotlin
    /** Pure Kalman measurement update — no aging, no adaptation; carries [run] and advances the clean
     *  evidence track from [prior]'s UN-inflated evidenceVar (never sigma2). */
    private fun kalmanStep(prior: ExerciseBelief, obsLnE1rm: Float, s2: Float, run: Float, at: Long): ExerciseBelief {
        val k = prior.sigma2 / (prior.sigma2 + s2)
        val kc = prior.evidenceVar / (prior.evidenceVar + s2)
        return ExerciseBelief(
            mu = prior.mu + k * (obsLnE1rm - prior.mu),
            sigma2 = clampVar((1f - k) * prior.sigma2),
            updatedAt = at,
            innovationRun = run,
            evidenceVar = clampVar((1f - kc) * prior.evidenceVar),
        )
    }
```

> Why this is correct: `foldGaussian` calls `kalmanStep(prior1, ...)` where `prior1 = adaptPrior(aged0, ...)`. `adaptPrior` does `aged.copy(sigma2 = ...)`, so `prior1.evidenceVar == aged0.evidenceVar` (un-inflated). The censored `z < MIN_MASS` fallback calls `kalmanStep(aged, ...)` where `aged` is likewise the adaptPrior result — same guarantee.

- [ ] **Step 4: Add the `censoredPosteriorVar` helper**

Insert just above `internal fun clampVar` in `BeliefUpdater`:

```kotlin
    /**
     * Posterior variance of a censored fold (truncated-Gaussian moment match, spec §2 shape) given a
     * prior (mu, priorVar) and obs noise s — the exact information credit for the interval. Used to
     * advance the clean evidence track; the real sigma2 track keeps its inline computation because it
     * also needs the posterior mean mz.
     */
    private fun censoredPosteriorVar(mu: Float, priorVar: Float, lowerLn: Float?, upperLn: Float?, s: Float): Float {
        val st2 = priorVar + s * s
        val st = sqrt(st2)
        val alpha = (if (lowerLn != null) (lowerLn - mu) / st else -CLAMP).coerceIn(-CLAMP, CLAMP)
        val beta = (if (upperLn != null) (upperLn - mu) / st else CLAMP).coerceIn(-CLAMP, CLAMP)
        val z = NormalCdf.cdf(beta) - NormalCdf.cdf(alpha)
        if (z < MIN_MASS) {
            val k = priorVar / (priorVar + s * s)
            return clampVar((1f - k) * priorVar)
        }
        val phiA = NormalCdf.pdf(alpha)
        val phiB = NormalCdf.pdf(beta)
        val vz = st2 * (1f + (alpha * phiA - beta * phiB) / z - ((phiA - phiB) / z).let { it * it })
        val k = priorVar / st2
        return clampVar(priorVar - k * k * (st2 - vz))
    }
```

- [ ] **Step 5: Advance the clean track in `foldCensored`'s main-path return**

Replace the final `return ExerciseBelief(...)` in `foldCensored` (the one after `val k = aged.sigma2 / st2`):

```kotlin
        return ExerciseBelief(
            mu = aged.mu + k * (mz - aged.mu),
            sigma2 = clampVar(aged.sigma2 - k * k * (st2 - vz)),
            updatedAt = at,
            innovationRun = run,
            evidenceVar = censoredPosteriorVar(aged.mu, aged.evidenceVar, lowerLn, upperLn, noiseSd),
        )
```

> The `z < MIN_MASS` branch already returns via `kalmanStep`, which now advances `evidenceVar` correctly — no change needed there.

- [ ] **Step 6: Run the evidence tests + exact-math fold tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.EvidenceVarTest" --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefUpdaterFoldTest" --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefAdaptationTest"`
Expected: all PASS. `BeliefUpdaterFoldTest` unaffected (its `mu`/`sigma2` assertions are unchanged; the new `evidenceVar` field isn't asserted there). If any `BeliefUpdaterFoldTest` exact-math case fails, STOP and report BLOCKED — the `sigma2`/`mu` math must not have changed.

- [ ] **Step 7: Commit**

```
jj commit -m "belief: folds advance evidenceVar from the un-inflated prior (projector evidence gate, part 2)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
Record the commit id.

---

## Task 3: Projector reads `evidenceVar`

Point `neff` at `evidenceVar` and prove the regression: an adaptation-inflated-σ but well-evidenced belief is NOT overridden by confident siblings.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjector.kt` (`neff`)
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ProjectorEvidenceGateTest.kt`

**Interfaces:**
- Consumes: `ExerciseBelief.evidenceVar`.
- Produces: `MuscleStrengthProjector.neff(aged)` computed from `aged.evidenceVar`; `project(...)` unchanged in shape, its three `neff` consumers now evidence-based.

- [ ] **Step 1: Write the failing test** — create `ProjectorEvidenceGateTest.kt`

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class ProjectorEvidenceGateTest {
    private val config = EstimatorConfig()
    private val projector = MuscleStrengthProjector(config)

    @Test
    fun neffReadsEvidenceVarNotSigma() {
        // Inflated sigma2 (adaptation) but tight evidenceVar (well observed) => HIGH n_eff.
        val inflated = ExerciseBelief(mu = 3.0f, sigma2 = 0.09f * 0.09f, updatedAt = 0L, evidenceVar = 0.03f * 0.03f)
        val seedFloorVar = config.sigmaSeed * config.sigmaSeed
        val expected = ((1f / inflated.evidenceVar - 1f / seedFloorVar) * config.poolObsVar).coerceAtLeast(0f)
        assertEquals(expected, projector.neff(inflated), 1e-6f)
        assertTrue("n_eff must be well above the inflated-sigma value", projector.neff(inflated) > 1.0f)
    }

    @Test
    fun wellEvidencedBeliefResistsConfidentSiblings() {
        // Self (id 55, coef 0.30): mean ~19 kg fresh, sigma2 INFLATED by a surprise, but well-evidenced
        // (evidenceVar 0.03²). Sibling (id 48, coef 1.00): strong ~120 kg, tight, and MORE-evidenced than
        // self (evidenceVar 0.02², like the real BSS case where squat has more folds) — so siblingExcess
        // is strictly positive and the capped bridge pull IS active. The evidence gate must still keep
        // self near its own 19 kg via a strong absolute self-anchor, NOT let the sibling pull it toward
        // the muscle level's prediction for self (~30+ kg).
        val beliefs = mapOf(
            55L to ExerciseBelief(mu = ln(19f), sigma2 = 0.09f * 0.09f, updatedAt = 0L, evidenceVar = 0.03f * 0.03f),
            48L to ExerciseBelief(mu = ln(120f), sigma2 = 0.02f * 0.02f, updatedAt = 0L, evidenceVar = 0.02f * 0.02f),
        )
        val seedCoef = mapOf(55L to 0.30f, 48L to 1.00f)
        val proj = projector.project(beliefs, seedCoef, listOf(55L, 48L), now = 0L)
        val self = proj.effectiveE1rm.getValue(55L)
        assertTrue("self must stay near its own 19 kg belief, not be pulled toward ~36 kg (got $self)",
            self < 20f)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ProjectorEvidenceGateTest"`
Expected: FAIL — `neff` still reads `sigma2`, so `neffReadsEvidenceVarNotSigma` mismatches and `wellEvidencedBeliefResistsConfidentSiblings` shows self pulled above 20 kg (the current bug).

- [ ] **Step 3: Point `neff` at `evidenceVar`**

In `MuscleStrengthProjector`, replace `neff`:

```kotlin
    /**
     * Bridge vote weight (phase 2): the belief's effective sample size in poolObsVar units — precision
     * above the seed floor, computed from the ADAPTATION-IMMUNE evidenceVar (not the live sigma2, which
     * adaptive attention inflates to move the mean). Seed-fresh → 0; well-observed → ≈2–5; stale
     * (evidenceVar grown past σ_seed²) → 0, so a stale lone voter decays to the seed-anchored prior.
     */
    fun neff(aged: ExerciseBelief): Float {
        val seedVar = config.sigmaSeed * config.sigmaSeed
        return ((1f / aged.evidenceVar - 1f / seedVar) * config.poolObsVar).coerceAtLeast(0f)
    }
```

- [ ] **Step 4: Run the projector tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ProjectorEvidenceGateTest"`
Expected: PASS both.

- [ ] **Step 5: Full suite — confirm no NEW red beyond the known three**

Run: `./gradlew :app:testDebugUnitTest`
Expected: failures limited to `BeliefSimulationTest`, `ProdBssPrescriptionTest`, `BacktestComparisonTest` (the pre-existing known-red set that the adaptive-attention plan's Task 4/5/6 re-baselines). Report the exact failing-class list. If any OTHER class fails, STOP and use `superpowers:systematic-debugging` — that's a real regression from this change (e.g. a chart/projection test that consumed σ-based n_eff). Do NOT re-pin the known-red three here.

- [ ] **Step 6: Commit**

```
jj commit -m "projector: n_eff reads adaptation-immune evidenceVar (closes the sibling re-nudge; BSS own belief holds)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
Record the commit id.

---

## Self-Review Notes (for the executor)

- **Spec coverage:** field + init (T1), aging (T1), fold updates Gaussian + censored from un-inflated prior (T2), `adaptPrior` untouched-by-construction (T2 `adaptationDoesNotContaminateEvidenceVar`), projector reads it everywhere (T3), regression that a well-evidenced inflated-σ belief holds (T3). The shared re-baseline (`BeliefSimulationTest`/`ProdBss`/backtest) belongs to the adaptive-attention plan's Task 4/5/6 and is intentionally out of scope here.
- **Type consistency:** `evidenceVar: Float` defined T1, consumed T2/T3. `censoredPosteriorVar(mu, priorVar, lowerLn, upperLn, s): Float` defined and used in T2. `neff(aged): Float` signature unchanged (T3), only its body.
- **The `adaptPrior` guarantee** (it preserves `evidenceVar` via `.copy`) is the linchpin — T2's `adaptationDoesNotContaminateEvidenceVar` locks it. If a future change makes `adaptPrior` construct a belief explicitly instead of `.copy`, that test catches the regression.
- **Non-goals:** no per-exercise φ, no τ-pooling rework, no Room migration, no re-pinning the known-red three.
