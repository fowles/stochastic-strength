# Reliability-Weighted Muscle Pooling (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the read-time muscle projector's hand-rolled `neff`/`kappa`/`siblingExcess` pooling with the clean precision-weighted Gaussian level + leave-one-out shrink from the design spec, using per-equipment-class transfer tightness τ.

**Architecture:** `MuscleStrengthProjector.project` computes a muscle level as a precision-weighted average of each exercise's seed-relative opinion (precision = `1/(evidenceVar + τ²)`, reading the adaptation-immune `evidenceVar`), then shrinks each exercise's mean toward a leave-one-out prediction. Reported σ stays the exercise's own live aged σ (deliberate divergence from spec §3). Pure read-time; no belief mutation, no Room migration.

**Tech Stack:** Kotlin, JVM unit tests (JUnit4), Gradle. All logic in `domain/progression/`.

**Design spec:** `docs/superpowers/specs/2026-07-09-reliability-weighted-pooling-design.md`

## Global Constraints

- **τ by equipment class (exact):** `BARBELL` → 0.08; `MACHINE`, `CABLE_MACHINE` → 0.20; every other loaded class (`DUMBBELL`, `KETTLEBELL`, `BODYWEIGHT`, `BAND`) → 0.25. Unknown/missing equipment → 0.25.
- **Borrowing/mean math reads `evidenceVar`**, never live `sigma2`. Level votes and the own-precision term both use `evidenceVar + τ²` / `evidenceVar`.
- **Reported σ (`pooledSigma`) = own live aged σ (`sqrt(sigma2)`), un-shrunk by siblings.** Do NOT feed spec §3's sibling-shrunk σ̃ to the policy.
- **`MuscleProjection` keeps its shape:** `(level, effectiveE1rm, derivedCoef, pooledSigma)`.
- **Safety gates that MUST hold at the end:** `ProdBssPrescriptionTest.policyPathSafetyBounds` pins BSS at 20 lb and strictly below 15.875 kg; `BeliefSimulationTest.calibration_*` coverage ∈ [0.60, 0.95]; the full-history backtest passes at its re-baselined BAND.
- **Build/test commands:** build `./gradlew :app:assembleDebug`; unit tests `./gradlew :app:testDebugUnitTest`; single class `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.<Class>"`.
- **Known-red window:** the math change (Task 2) and equipment threading (Task 3) shift the pinned gate values. `BeliefSimulationTest`, `ProdBssPrescriptionTest`, and the backtest are expected RED from Task 2 until re-pinned in Tasks 4–6. Do not "fix" them before then.

---

### Task 1: Add per-equipment τ config + `tauFor` helper (additive)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt` (the `EstimatorConfig` data class + a new top-level/extension helper)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/EstimatorConfigTauTest.kt` (create)

**Interfaces:**
- Produces: `EstimatorConfig.tauBarbell/tauMachineCable/tauOtherLoaded/levelAnchorPrecision: Float`; `fun EstimatorConfig.tauFor(equipment: Equipment?): Float`.
- Consumes: `io.github.fowles.stochastic_strength.data.model.Equipment`.
- **Leave the old `tauBridge`, `levelPrior`, `poolObsVar` fields in place for now** (removed in Task 7) so consumers keep compiling.

- [ ] **Step 1: Write the failing test**

Create `EstimatorConfigTauTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import org.junit.Assert.assertEquals
import org.junit.Test

class EstimatorConfigTauTest {
    private val config = EstimatorConfig()

    @Test
    fun tauForMapsEachEquipmentClass() {
        assertEquals(0.08f, config.tauFor(Equipment.BARBELL), 0f)
        assertEquals(0.20f, config.tauFor(Equipment.MACHINE), 0f)
        assertEquals(0.20f, config.tauFor(Equipment.CABLE_MACHINE), 0f)
        assertEquals(0.25f, config.tauFor(Equipment.DUMBBELL), 0f)
        assertEquals(0.25f, config.tauFor(Equipment.KETTLEBELL), 0f)
        assertEquals(0.25f, config.tauFor(Equipment.BODYWEIGHT), 0f)
        assertEquals(0.25f, config.tauFor(Equipment.BAND), 0f)
        assertEquals(0.25f, config.tauFor(null), 0f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.EstimatorConfigTauTest"`
Expected: FAIL — `tauFor` / `tauBarbell` unresolved.

- [ ] **Step 3: Add the config fields**

In `ExerciseBelief.kt`, add these fields to the `EstimatorConfig` data class (near the existing `tauBridge` field; keep `tauBridge`/`levelPrior`/`poolObsVar` for now):

```kotlin
    /** Per-equipment-class transfer tightness τ (personal-offset std). Barbell lifts track the muscle
     *  level tightly; machines/cables medium; all other loaded classes loosest. Pinned by BeliefSimulationTest. */
    val tauBarbell: Float = 0.08f,
    val tauMachineCable: Float = 0.20f,
    val tauOtherLoaded: Float = 0.25f,
    /** λ₀: fixed precision of the seed anchor in the muscle-level pool (replaces levelPrior). A
     *  thinly-evidenced muscle leans on it. Pinned by BeliefSimulationTest. */
    val levelAnchorPrecision: Float = 1.0f,
```

- [ ] **Step 4: Add the `tauFor` helper**

At the bottom of `ExerciseBelief.kt` (top-level in the file, and add `import io.github.fowles.stochastic_strength.data.model.Equipment` to the imports):

```kotlin
/** τ for an exercise's equipment class; unknown/other-loaded → the loosest class. */
fun EstimatorConfig.tauFor(equipment: Equipment?): Float = when (equipment) {
    Equipment.BARBELL -> tauBarbell
    Equipment.MACHINE, Equipment.CABLE_MACHINE -> tauMachineCable
    else -> tauOtherLoaded
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.EstimatorConfigTauTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/EstimatorConfigTauTest.kt
git commit -m "config: per-equipment τ + levelAnchorPrecision + tauFor (phase 3)"
```

---

### Task 2: Rewrite `MuscleStrengthProjector` to reliability-weighted pooling

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjector.kt` (full rewrite of the class body; keep `MuscleProjection` shape)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/CrossTuning.kt` (swap `neff` → `poolPrecision`, add `equipment` param)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjectorTest.kt` (update)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ProjectorEvidenceGateTest.kt` (update)

**Interfaces:**
- Produces: `MuscleStrengthProjector.project(beliefs, seedCoef, muscleExerciseIds, now, muscleLastObs = null, equipment: Map<Long, Equipment> = emptyMap()): MuscleProjection`; `fun poolPrecision(aged: ExerciseBelief, tau: Float): Float`.
- Removes: `fun neff(aged): Float`.
- Consumes: `EstimatorConfig.tauFor`, `Equipment` (Task 1).

- [ ] **Step 1: Rewrite the projector**

Replace the whole class in `MuscleStrengthProjector.kt` (keep the `MuscleProjection` data class and its doc; add `import io.github.fowles.stochastic_strength.data.model.Equipment`):

```kotlin
class MuscleStrengthProjector(private val config: EstimatorConfig = EstimatorConfig()) {
    private val updater = BeliefUpdater(config)

    /**
     * Pooling precision of an aged belief given its transfer tightness τ: 1/(evidenceVar + τ²).
     * Reads the ADAPTATION-IMMUNE evidenceVar (not live sigma2), so a surprise-inflated σ is not
     * misread as "uninformed" and dragged back by confident siblings (the prod-BSS regression).
     */
    fun poolPrecision(aged: ExerciseBelief, tau: Float): Float = 1f / (aged.evidenceVar + tau * tau)

    private data class Loaded(
        val id: Long, val belief: ExerciseBelief, val coef: Float,
        val tau: Float, val opinion: Float, val votePrec: Float,
    )

    fun project(
        beliefs: Map<Long, ExerciseBelief>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
        muscleLastObs: Long? = null,
        equipment: Map<Long, Equipment> = emptyMap(),
    ): MuscleProjection {
        val loaded = muscleExerciseIds.mapNotNull { id ->
            val b0 = beliefs[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) return@mapNotNull null
            val aged = updater.age(b0, now, muscleLastObs)
            val tau = config.tauFor(equipment[id])
            Loaded(id, aged, coef, tau, aged.mu - ln(coef), poolPrecision(aged, tau))
        }
        if (loaded.isEmpty()) return MuscleProjection(0f, emptyMap(), emptyMap(), emptyMap())

        // Bayesian posterior on the muscle level ℓ over an included set. Prior mean = unweighted mean
        // of the included opinions (seed level for a cold muscle); fixed prior precision λ₀. Returns
        // (lnLevel, σ_ℓ²). Excluding an id gives that exercise's leave-one-out prediction.
        fun posterior(exclude: Long?): Pair<Float, Float> {
            val incl = loaded.filter { it.id != exclude }
            val prior = if (incl.isEmpty())
                loaded.first { it.id == exclude }.opinion   // lone exercise: LOO prior = its own opinion ⇒ pred == own μ
            else incl.map { it.opinion }.average().toFloat()
            var p = config.levelAnchorPrecision
            var num = config.levelAnchorPrecision * prior
            for (l in incl) { p += l.votePrec; num += l.votePrec * l.opinion }
            return (num / p) to (1f / p)
        }

        val (lnLevel, _) = posterior(null)
        val level = exp(lnLevel)

        val effective = mutableMapOf<Long, Float>()
        val coefs = mutableMapOf<Long, Float>()
        val sigmas = mutableMapOf<Long, Float>()
        for (l in loaded) {
            val (lnLevelLoo, sigmaL2Loo) = posterior(l.id)
            val lnPred = ln(l.coef) + lnLevelLoo
            val predPrec = 1f / (sigmaL2Loo + l.tau * l.tau)
            val ownPrec = 1f / l.belief.evidenceVar          // borrow weight from the immune track
            val lnUsed = (ownPrec * l.belief.mu + predPrec * lnPred) / (ownPrec + predPrec)
            effective[l.id] = exp(lnUsed)
            coefs[l.id] = if (level > 0f) exp(lnUsed) / level else l.coef
            sigmas[l.id] = l.belief.sigma                    // own live aged σ, un-shrunk (spec §3 divergence)
        }
        return MuscleProjection(level, effective, coefs, sigmas)
    }
}
```

Update the `MuscleProjection.pooledSigma` doc comment to: `/** Own live aged belief std per exercise (un-shrunk) — the z-shading input for the policy. */`.

- [ ] **Step 2: Update `CrossTuning.kt`**

Add `equipment: Map<Long, Equipment> = emptyMap()` param (and `import ...Equipment`). Replace the `neffById`/`totalNeff`/`contribution` computation:

```kotlin
    val precById = muscleExerciseIds.associateWith { id ->
        val b = beliefs[id] ?: return@associateWith 0f
        projector.poolPrecision(updater.age(b, now, muscleLastObs), config.tauFor(equipment[id]))
    }
    val totalPrec = precById.values.sum()
```

Update the `contribution` line to `if (totalPrec > 0f) precById.getValue(id) / totalPrec else 0f`, pass `equipment` into the `projector.project(...)` LOO call, and update `CrossTuningRow.contribution`'s doc to `/** This exercise's pooling precision as a share of the muscle's total (0..1). */`.

- [ ] **Step 3: Update `MuscleStrengthProjectorTest.kt`**

Replace `neffScalesFromZeroAtSeedToTrainedRange` with:

```kotlin
    @Test
    fun poolPrecisionRisesWithEvidenceAndTightness() {
        val cold = cold(100f)                                   // evidenceVar = σ_seed² = 0.0625
        assertEquals(1f / (0.0625f + 0.25f * 0.25f), projector.poolPrecision(cold, 0.25f), 1e-3f)
        val trainedOther = ExerciseBelief(4f, 0.0004f, 0L, evidenceVar = 0.0004f)
        assertTrue("trained beats cold", projector.poolPrecision(trainedOther, 0.25f) > projector.poolPrecision(cold, 0.25f))
        assertTrue("barbell τ gives higher precision than other-loaded",
            projector.poolPrecision(trainedOther, 0.08f) > projector.poolPrecision(trainedOther, 0.25f))
    }
```

Replace `coldExerciseWithTrainedSiblingsIsPredictedFromTheirLevel` with an equipment-aware version (barbell cold strongly adopts; other-loaded cold adopts only partially):

```kotlin
    @Test
    fun coldBarbellAdoptsSiblingsWhileColdDumbbellPartiallyAdopts() {
        val seed = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f)
        val beliefs = mapOf(
            1L to trained(130f, days(30)),
            2L to trained(104f, days(30)),
            3L to cold(60f),                                   // sibling-implied ≈ 130 × 0.6 = 78
        )
        val barbell = projector.project(beliefs, seed, listOf(1L, 2L, 3L), now = days(30),
            equipment = mapOf(1L to Equipment.BARBELL, 2L to Equipment.BARBELL, 3L to Equipment.BARBELL))
        assertTrue("cold barbell should approach 78", abs(barbell.effectiveE1rm[3L]!! - 78f) / 78f <= 0.12f)

        val dumbbell = projector.project(beliefs, seed, listOf(1L, 2L, 3L), now = days(30),
            equipment = mapOf(1L to Equipment.BARBELL, 2L to Equipment.BARBELL, 3L to Equipment.DUMBBELL))
        val own3 = 60f
        assertTrue("cold dumbbell pulls up from own toward 78 but not all the way",
            dumbbell.effectiveE1rm[3L]!! in (own3 + 1f)..(78f - 1f))
    }
```

Add `import io.github.fowles.stochastic_strength.data.model.Equipment` to the test file. Leave `coldMuscleProjectsTheSeedLevel`, `staleOrSameAgeSiblingsDoNotLiftAFreshBelief`, `staleLoneVoterDecaysTowardTheSeedAnchor`, `pooledSigmaExposesTheOwnAgedUncertainty`, `driftLowersProjectionAfterAMuscleWideLayoff` unchanged (they hold under the new math: lone/stale exercises fall out via empty-LOO ⇒ prediction == own μ; a fresh tight belief has ownPrec ≫ predPrec).

- [ ] **Step 4: Update `ProjectorEvidenceGateTest.kt`**

Read the file first. Rewrite its two assertions to the new API: (a) an adaptation-inflated `sigma2` with small `evidenceVar` yields a HIGH `poolPrecision` (uses `poolPrecision(inflated, config.tauFor(Equipment.DUMBBELL))` instead of the `neff`/`poolObsVar` formula); (b) in a `project(...)` with confident siblings, the inflated-σ / small-evidenceVar exercise's `effectiveE1rm` stays ≈ its own `exp(mu)` (not pulled up). Keep the existing belief fixtures; only swap the removed `neff`/`poolObsVar` references for `poolPrecision`/`effectiveE1rm`.

- [ ] **Step 5: Run the projector tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjectorTest" --tests "io.github.fowles.stochastic_strength.domain.progression.ProjectorEvidenceGateTest"`
Expected: PASS. If the dumbbell partial-adoption bounds are tight, adjust the asserted band to the emitted value (the property — strictly between own and full — is the gate, not the exact number).

- [ ] **Step 6: Verify main still compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Existing `project(...)` callers still compile via the `equipment` default; they pass real equipment in Task 3.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjector.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/CrossTuning.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjectorTest.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ProjectorEvidenceGateTest.kt
git commit -m "projector: reliability-weighted pooling (per-equipment τ + LOO shrink)"
```

---

### Task 3: Thread real equipment into every `project(...)` call site

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt:76` and `:234`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepper.kt:50`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt:78,81`
- Modify: the `computeCrossTuning(...)` caller (find with grep below)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestHarness.kt:74`

**Interfaces:** Consumes `project(..., equipment=...)` and `ReplaySnapshot.exerciseEquipment` (already DB-populated).

- [ ] **Step 1: Thread equipment in main-source call sites**

- `SessionProgressionStepper.kt` line ~50 `projector.project(...)`: add `equipment = snapshot.exerciseEquipment,`.
- `ExerciseProgressionSeriesBuilder.kt` lines 78 and 81 `projector.project(...)`: add `equipment = snapshot.exerciseEquipment,` to each.
- `WorkoutRepository.kt` line ~234 display projector: add `equipment = snapshot.exerciseEquipment,`.
- `WorkoutRepository.kt` line ~76 `buildPlanner`: add `equipment = available.associate { it.id to it.equipment },` (`available` is the `List<Exercise>` already in scope at line 66).

- [ ] **Step 2: Thread equipment into the CrossTuning caller**

Run: `grep -rn "computeCrossTuning(" app/src/main/java`
For each caller (a debug ViewModel), pass `equipment = <the exercises' id→equipment map available there>` (build from the loaded `Exercise` list, or from a `ReplaySnapshot`/DerivedState the caller already holds). If a caller has no equipment source at hand, pass the DAO-loaded exercises' `associate { it.id to it.equipment }`.

- [ ] **Step 3: Thread equipment in the backtest harness**

`BacktestHarness.kt` line ~74 `projector.project(...)`: add `equipment = snap.exerciseEquipment,`.

- [ ] **Step 4: Build + run the fast projection/replay tests**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ReplayProjectionTest" --tests "io.github.fowles.stochastic_strength.domain.progression.ReplayHistoryTest"`
Expected: PASS (these don't pin tuned constants). If any assert a specific projected number that moved, update it to the emitted value — the structural property is the gate.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepper.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/CrossTuning.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestHarness.kt \
        <the-crosstuning-caller-file>
git commit -m "projector: pass real equipment class into pooling at every call site"
```

---

### Task 4: Re-pin `BeliefSimulationTest` (calibration + trained-selection)

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefSimulationTest.kt`

**Interfaces:** Consumes the final pooling math (Tasks 2–3). Sets `EstimatorConfig.levelAnchorPrecision`.

- [ ] **Step 1: Replace the poolObsVar coverage machinery**

In `calibration_eightyPercentIntervalRoughlyCovers` (around lines 445–483): the current test computes coverage as a function of the deleted `poolObsVar` using the old `n_eff` gate. Rewrite it to the new interval: a sample is "trained/counted" when its `poolPrecision` exceeds the seed-floor precision `1/(σ_seed² + τ²)` for its τ class, and coverage checks `absDiff <= 1.2816 · sqrt(sigma2)` (the own live σ is what the policy actually shades with — no `+ p` term). Keep the `assertTrue(coverage in 0.60f..0.95f)` gate. Print the coverage so it can be read.

- [ ] **Step 2: Replace the `neff >= 1` trained selector**

At line ~320 (`projector.neff(aged) >= 1f`) replace with an evidence-based selector, e.g. `updater.age(...).evidenceVar < config.sigmaSeed * config.sigmaSeed` (an exercise that has learned something about itself). Update the line-188 / line-47 doc comments that mention `neff`/`poolObsVar`.

- [ ] **Step 3: Run and tune `levelAnchorPrecision`**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefSimulationTest"`
If `calibration` coverage is outside [0.60, 0.95] or a scenario pin (`badDay_*`, growth pins) fails, adjust `EstimatorConfig.levelAnchorPrecision` (and only if necessary the τ constants) and re-run. Record the final coverage value in the test comment. Expected end state: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefSimulationTest.kt
git commit -m "test: re-pin BeliefSimulationTest to reliability-weighted pooling (levelAnchorPrecision)"
```

---

### Task 5: Confirm `ProdBssPrescriptionTest` (20 lb safety gate)

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProdBssPrescriptionTest.kt`

**Interfaces:** Consumes final pooling math + `levelAnchorPrecision` (Task 4).

- [ ] **Step 1: Pass real equipment into the two `project(...)` calls**

Add an equipment map matching the QUADS fixture ids (confirm each id's `equipment` from `ExerciseLibrary` seed data — expected: 48 Barbell Squat=BARBELL, 49 Front Squat=BARBELL, 50 Leg Press=MACHINE, 51 Leg Extension=MACHINE, 52 Hack Squat=MACHINE, 54 Goblet Squat=DUMBBELL/KETTLEBELL, 55 Bulgarian Split Squat=DUMBBELL, 56 Step-Up=DUMBBELL, 100 Dumbbell Lunge=DUMBBELL):

```kotlin
    private val equipment: Map<Long, Equipment> = mapOf(
        48L to Equipment.BARBELL, 49L to Equipment.BARBELL, 50L to Equipment.MACHINE,
        51L to Equipment.MACHINE, 52L to Equipment.MACHINE, 54L to Equipment.DUMBBELL,
        55L to Equipment.DUMBBELL, 56L to Equipment.DUMBBELL, 100L to Equipment.DUMBBELL,
    )
```

Add `equipment = equipment,` to both `MuscleStrengthProjector().project(...)` calls (lines ~96 and ~130).

- [ ] **Step 2: Run and re-pin the pre-policy figure if it moved**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProdBssPrescriptionTest"`
`policyPathSafetyBounds` (the real gate: `weightKg < 15.875` AND `== 20 lb`) MUST pass — BSS is DUMBBELL (τ 0.25, loosest) and its `evidenceVar` is tight after the surprise, so `ownPrec ≫ predPrec` and the belief holds its own mean, exactly as designed. If `reportBssPrescription`'s pre-policy pin (currently 25 lb) shifts, update that `assertEquals` to the emitted grid value and its comment. **If `policyPathSafetyBounds` does NOT hold, stop — that is a design regression, not a re-pin;** investigate before changing the assertion.
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/ProdBssPrescriptionTest.kt
git commit -m "test: ProdBss holds 20 lb under reliability-weighted pooling (real equipment)"
```

---

### Task 6: Re-baseline the full-history backtest

**Files:**
- Modify: the backtest JVM test (find with grep) and/or its BAND constant.

**Interfaces:** Consumes final pooling math.

- [ ] **Step 1: Locate the backtest and run it**

Run: `grep -rln "BAND\|Backtest\|backtest" app/src/test/java`
Run the full backtest test class: `./gradlew :app:testDebugUnitTest --tests "*Backtest*"`
Read the emitted per-exercise / systemic reprice diff.

- [ ] **Step 2: Attribute and re-baseline**

Confirm the reprice is attributable to the pooling swap (per-equipment τ tightening barbell coupling, LOO shrink). Update the baseline snapshot / expected values to the new output and keep BAND at its current tolerance (0.05 unless the emitted spread demands documenting a wider band). Record a one-line attribution in the test comment referencing this plan.
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add <backtest-test-file>
git commit -m "test: re-baseline full backtest to reliability-weighted pooling output"
```

---

### Task 7: Remove dead config, run full suite, update docs

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt` (delete `tauBridge`, `levelPrior`, `poolObsVar`)
- Modify: `docs/adaptation/04-muscle-pooling.md`
- Modify: `CLAUDE.md` (progression section)

- [ ] **Step 1: Delete the dead fields**

Remove `tauBridge`, `levelPrior`, `poolObsVar` from `EstimatorConfig`. Run: `grep -rn "tauBridge\|levelPrior\|poolObsVar" app/src` — expect ZERO hits. Fix any stragglers (should all be gone after Tasks 2–5).

- [ ] **Step 2: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all green. Fix any remaining compile/reference breaks.

- [ ] **Step 3: Rewrite `docs/adaptation/04-muscle-pooling.md`**

Replace Step 1 / Step 2 math with the precision-weighted level (`v_i = evidenceVar + τ²`) and LOO shrink (`ownPrec = 1/evidenceVar`, `predPrec = 1/(σ²_ℓLOO + τ²)`); remove the `poolObsVar`/`neff`/`kappa`/`siblingExcess` language and the "Phase 3 note"; add the per-equipment τ table and the "reported σ = own live, un-shrunk" divergence paragraph.

- [ ] **Step 4: Update `CLAUDE.md`**

In the Progression section, update the pooling bullet (item 3) to describe per-equipment τ (0.08/0.20/0.25), precision-weighted level from `evidenceVar`, and LOO shrink; note `poolObsVar`/`tauBridge`/`levelPrior` are gone and `levelAnchorPrecision`/`tauBarbell`/`tauMachineCable`/`tauOtherLoaded` are the new constants pinned by `BeliefSimulationTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt \
        docs/adaptation/04-muscle-pooling.md CLAUDE.md
git commit -m "cleanup+docs: drop poolObsVar/tauBridge/levelPrior; document phase-3 pooling"
```

---

## Self-Review

- **Spec coverage:** τ classes → Task 1; precision-weighted level + LOO shrink reading `evidenceVar` → Task 2; own-live-σ divergence → Task 2 (`sigmas[l.id] = l.belief.sigma`); deletions (`poolObsVar`/`neff`/`kappa`/`siblingExcess`/evidence gate) → Tasks 2 + 7; equipment threading → Task 3; `BeliefSimulationTest` re-pin → Task 4; ProdBss 20 lb → Task 5; backtest re-baseline → Task 6; docs (`04`, `CLAUDE.md`) → Task 7. All spec sections covered.
- **Placeholder scan:** the tuning steps (Task 4 `levelAnchorPrecision`, Task 5 pre-policy pin, Task 6 BAND) are procedures (run → read emitted value → set constant → confirm the named safety property), not placeholders — the safety assertions (`coverage ∈ [0.60,0.95]`, `20 lb & < 15.875`, BAND) are concrete and are the real gates.
- **Type consistency:** `project(..., equipment: Map<Long, Equipment> = emptyMap())`, `poolPrecision(aged, tau)`, `tauFor(equipment)`, `MuscleProjection(level, effectiveE1rm, derivedCoef, pooledSigma)` used identically across tasks. `ReplaySnapshot.exerciseEquipment` already exists.
