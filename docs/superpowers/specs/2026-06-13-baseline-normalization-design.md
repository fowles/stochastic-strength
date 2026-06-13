# Baseline Normalization

## Problem

Per-exercise coefficient drift can hide muscle-group baseline changes. Session weight is computed as `baseline × coefficient`, then scaled to the session's rep target. The decomposition is not unique: scaling baseline by `1/m` and every in-group coefficient by `m` yields the same prescribed weight. When `EstCoefConsensusHeuristic` (or any future heuristic) drifts coefficients in a consistent direction across a muscle group, that drift is observationally indistinguishable from a baseline change — and accumulates as a coefficient bias rather than landing where users (and debug screens) expect to see it: in the baseline.

The existing heuristic already detects this: when all proposals in a muscle group move the same direction by more than `ln(1.05)`, it *suppresses* them. This spec adds the natural complement — re-attribute the accumulated drift back to baseline.

## Algorithm

For a muscle group `g` with observed exercises `{e₁, …, eₙ}` (current coefficients `c_e`, seed coefficients `s_e`), find the scale `m` minimizing the uniform-weight RMSE between scaled coefficients and seeds:

```
m = argmin Σ_e (m · c_e − s_e)²
  = Σ_e (c_e · s_e) / Σ_e (c_e²)
```

Then:
- `newBaseline = oldBaseline / m`, rounded to unit precision via `WeightFormatter.round`.
- `mEffective = oldBaseline / newBaseline` — derived from the *rounded* baseline so that prescribed weights are preserved exactly. The raw `m` would leak rounding error into every coefficient.
- `newCoefficient_e = c_e · mEffective` for every exercise in the group.

Session weight is preserved exactly: `newBaseline × newCoefficient_e = oldBaseline × c_e`.

A proposal is only applied if the rounded baseline movement clears an absolute threshold (2 kg / 5 lb). The threshold prevents jitter from small noise; the absolute formulation matches what users see in the UI (kg/lb display precision) and the smallest plate increment.

## Exercise Sets

Two distinct sets:

**Input set** — exercises whose `(c_e, s_e)` pair feeds the regression. An exercise is in the input set for `g` iff:
1. Its `primaryMuscle` is `g`.
2. At least one `WorkoutSet` exists with `exerciseId = e.id` (i.e., the user has actually performed it).
3. Its `currentCoefficient > 0` (rules out pure-bodyweight exercises).

A group with fewer than two qualifying exercises emits no proposal: `n=1` makes `m = s/c` unbounded, and the absolute baseline threshold is not a safe guard there.

All input-set exercises are weighted uniformly. (Weighted least squares is a future option; uniform is simple and aligns with the current heuristic's design.)

**Update set** — exercises whose coefficients are scaled by `m`. The update set is *all* exercises in `g` with a defined coefficient (`currentCoefficient > 0`), including unobserved ones. This preserves session-weight invariance for the planner: an unobserved exercise's prescribed weight is unchanged because both its baseline factor and its coefficient factor cancel.

## Architecture

### Interface

`domain/BaselineNormalizer.kt`:

```kotlin
data class ExerciseCoefficientSnapshot(
    val exercise: Exercise,
    val seedCoefficient: Float,        // 0f if absent from ExerciseCoefficients
    val currentCoefficient: Float,     // latest from coefficient_change_log, else seed
)

data class BaselineNormalizationInput(
    val sets: List<WorkoutSet>,                       // full ledger; normalizer determines "observed"
    val exercises: List<ExerciseCoefficientSnapshot>, // full table with per-exercise coefficients
    val baselines: Map<MuscleGroup, Float>,           // current baseline per muscle group
)

data class BaselineNormalizationProposal(
    val muscleGroup: MuscleGroup,
    val scale: Float,           // m: baseline → baseline/m, in-group coefficients → c_e · m
    val metadata: String? = null,
)

interface BaselineNormalizer {
    val name: String
    fun compute(input: BaselineNormalizationInput): List<BaselineNormalizationProposal>
}
```

The input mirrors `CoefficientComputationInput` in shape — a flat ledger handed to the algorithm — but consolidates per-exercise data into a single list of snapshots rather than three parallel maps. The normalizer does its own grouping and filtering.

### Implementation

`domain/SeedNormalizer.kt` — the single implementation:

```kotlin
class SeedNormalizer : BaselineNormalizer {
    override val name = "seed-normalizer"

    override fun compute(input: BaselineNormalizationInput): List<BaselineNormalizationProposal> {
        val observed = input.sets.mapTo(mutableSetOf()) { it.exerciseId }
        val byMuscle = input.exercises.groupBy { it.exercise.primaryMuscle }
        return byMuscle.mapNotNull { (muscle, snaps) ->
            val qualifying = snaps.filter { it.exercise.id in observed && it.currentCoefficient > 0f }
            if (qualifying.size < 2) return@mapNotNull null
            val num = qualifying.sumOf { (it.currentCoefficient * it.seedCoefficient).toDouble() }
            val den = qualifying.sumOf { (it.currentCoefficient * it.currentCoefficient).toDouble() }
            if (den <= 0.0) return@mapNotNull null
            val m = (num / den).toFloat()
            val rmseBefore = rmse(qualifying) { c, s -> c - s }
            val rmseAfter = rmse(qualifying) { c, s -> m * c - s }
            BaselineNormalizationProposal(
                muscleGroup = muscle,
                scale = m,
                metadata = "n=${qualifying.size}, m=${"%.4f".format(m)}, " +
                           "rmse_before=${"%.4f".format(rmseBefore)}, " +
                           "rmse_after=${"%.4f".format(rmseAfter)}",
            )
        }
    }

    private inline fun rmse(qs: List<ExerciseCoefficientSnapshot>, residual: (Float, Float) -> Float): Float {
        val sq = qs.sumOf { val r = residual(it.currentCoefficient, it.seedCoefficient); (r * r).toDouble() }
        return kotlin.math.sqrt(sq / qs.size).toFloat()
    }
}
```

### Runner

`WorkoutRepository` gains a constructor parameter `normalizers: List<BaselineNormalizer> = emptyList()`, mirroring `heuristics`. A new private method:

```kotlin
internal suspend fun applyBaselineNormalization(asOf: Long, sessionId: Long) {
    if (normalizers.isEmpty()) return
    val input = buildNormalizationInput()
    val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
    val threshold = BaselineNormalizationThreshold.forUnit(weightUnit)

    val proposals = normalizers.flatMap { it.compute(input) }
    if (proposals.isEmpty()) return

    db.withTransaction {
        val latestCoefByExercise = db.coefficientChangeLogDao().getLatestPerExercise()
            .associateBy { it.exerciseId }
        for (proposal in proposals) {
            val oldBaseline = input.baselines[proposal.muscleGroup] ?: continue
            if (oldBaseline <= 0f || proposal.scale <= 0f) continue
            val rawNew = oldBaseline / proposal.scale
            val newBaseline = WeightFormatter.round(rawNew, weightUnit)
            if (kotlin.math.abs(newBaseline - oldBaseline) < threshold) continue
            if (newBaseline <= 0f) continue
            val mEffective = oldBaseline / newBaseline   // derived from rounded baseline to keep invariance exact

            db.muscleGroupStrengthDao().upsert(
                MuscleGroupStrength(muscleGroup = proposal.muscleGroup, baselineWeight = newBaseline)
            )
            db.baselineChangeLogDao().insert(
                BaselineChangeLog(
                    sessionId = sessionId,
                    muscleGroup = proposal.muscleGroup,
                    previousBaseline = oldBaseline,
                    newBaseline = newBaseline,
                    changeReason = BaselineChangeReason.NORMALIZATION,
                    timestamp = asOf,
                )
            )
            val inGroup = input.exercises.filter {
                it.exercise.primaryMuscle == proposal.muscleGroup && it.currentCoefficient > 0f
            }
            for (snap in inGroup) {
                val newCoef = snap.currentCoefficient * mEffective
                db.coefficientChangeLogDao().insert(
                    CoefficientChangeLog(
                        exerciseId = snap.exercise.id,
                        previousCoefficient = latestCoefByExercise[snap.exercise.id]?.coefficient
                            ?: snap.currentCoefficient,
                        coefficient = newCoef,
                        heuristicName = "baseline_normalization",
                        heuristicMetadata = proposal.metadata,
                        computedAt = asOf,
                    )
                )
            }
        }
    }
}

private suspend fun buildNormalizationInput(): BaselineNormalizationInput {
    val allExercises = db.exerciseDao().getAll()
    val sets = db.workoutSetDao().getAll()
    val baselines = db.muscleGroupStrengthDao().getAll()
        .associate { it.muscleGroup to it.baselineWeight }
    val latestCoefs = db.coefficientChangeLogDao().getLatestPerExercise()
        .associate { it.exerciseId to it.coefficient }
    val snapshots = allExercises.map { ex ->
        val seed = coefficientSource.get(ex) ?: 0f
        val current = latestCoefs[ex.id] ?: seed
        ExerciseCoefficientSnapshot(ex, seed, current)
    }
    return BaselineNormalizationInput(sets, snapshots, baselines)
}
```

`BaselineChangeLog.sessionId` is non-nullable in the schema. `applySessionProgression` passes the in-flight `sessionId`; the backfill path resolves it to the most recent session's ID via `workoutSessionDao().getAll().maxByOrNull { it.endTime ?: it.startTime }`. If there are no sessions on disk, normalization has no observed exercises anyway (the input set is empty), so the runner exits before reaching the log-write path and the resolved-id question is moot. `recomputeDerivedState` performs this lookup once and passes the resolved id through.

### Threshold constant

`domain/BaselineNormalizationThreshold.kt`:

```kotlin
object BaselineNormalizationThreshold {
    fun forUnit(unit: WeightUnit): Float = when (unit) {
        WeightUnit.KG -> 2f
        WeightUnit.LBS -> 5f
    }
}
```

The lb threshold is the symmetric "smallest common plate increment" rather than a unit conversion of 2 kg.

### Schema changes

Add `BaselineChangeReason.NORMALIZATION`. Stored as `enum.name` via the existing converter — no Room migration required.

## Integration

### `applySessionProgression`

Replace the trailing `recomputeCoefficients(asOf = triggerTime)` with a single combined call:

```kotlin
suspend fun applySessionProgression(sessionId: Long, exerciseReductions: Map<Long, Float> = emptyMap()) {
    // ... existing per-muscle baseline progression and hurt-flag logic unchanged ...
    recomputeDerivedState(asOf = triggerTime, sessionId = sessionId)
}
```

### `recomputeDerivedState` — single production entry point

```kotlin
suspend fun recomputeDerivedState(asOf: Long? = null, sessionId: Long? = null) {
    recomputeCoefficients(asOf = asOf)
    val resolvedSessionId = sessionId
        ?: db.workoutSessionDao().getAll()
            .maxByOrNull { it.endTime ?: it.startTime }?.id
        ?: return   // nothing to normalize against
    applyBaselineNormalization(
        asOf = asOf ?: System.currentTimeMillis(),
        sessionId = resolvedSessionId,
    )
}
```

Both production callers go through this method:
- `applySessionProgression` passes its in-flight `sessionId` so the lookup is skipped.
- `StochasticStrengthApp.onCreate` backfill path — changes `workoutRepository.recomputeCoefficients()` to `workoutRepository.recomputeDerivedState()` and lets the method resolve the most recent session.

`recomputeCoefficients` stays public for the existing test surface; `applyBaselineNormalization` is `internal` for symmetry. Production code does not call them directly.

### Manual baseline overrides

`applyManualBaselineOverrides` does not invoke normalization. Manual override is the user's explicit intent; normalization would re-process it on the next session anyway, by which point the baseline is the established baseline and the math reflects observed reality.

### Order rationale

1. Per-muscle baseline progression (existing).
2. `recomputeCoefficients` — heuristics emit drift relative to the just-updated baseline.
3. `applyBaselineNormalization` — re-attributes accumulated drift back into baseline when threshold clears.

Running normalization before coefficient recomputation would have heuristics fire against stale state. After is correct.

## Logging & Introspection

- `BaselineChangeLog` rows with `changeReason = NORMALIZATION`. `MuscleBaselineDetailScreen` renders `reason.name`, so the new value appears automatically as "NORMALIZATION" without UI code changes.
- `CoefficientChangeLog` rows with `heuristicName = "baseline_normalization"`. `ExerciseCoefficientDetailScreen` already iterates rows by `heuristicName` and renders these alongside `est-coef-consensus` rows.
- Metadata format on both kinds of rows:

  ```
  n=4, m=1.0823, rmse_before=0.0612, rmse_after=0.0148
  ```

  An inspector can verify that normalization improved the fit (`rmse_after < rmse_before`).

No dedicated UI work in this scope. Pretty labels for the new reason and heuristic name are deferred until the strings look awkward in use.

## Testing

### JVM unit tests — `SeedNormalizerTest`

Modeled on `EstCoefConsensusHeuristicTest`:

- `compute_returns_empty_when_no_sets`.
- `compute_skips_group_with_fewer_than_two_observed_exercises`.
- `compute_skips_group_when_all_observed_have_zero_current_coefficient`.
- `compute_returns_m_near_one_when_coefficients_match_seeds`.
- `compute_returns_m_less_than_one_when_coefficients_drifted_above_seed`.
- `compute_returns_m_greater_than_one_when_coefficients_drifted_below_seed`.
- `compute_solves_least_squares_optimally` — for a hand-computed case, verify `m = Σ(c·s)/Σ(c²)` within tolerance.
- `compute_handles_muscle_groups_independently`.
- `compute_metadata_contains_n_m_rmse_before_after` — string format is stable enough to parse.

### Instrumented tests — additions to `WorkoutRepositoryTest`

Drive the runner with fake `BaselineNormalizer` instances so scenarios are deterministic:

- `applyBaselineNormalization_writesNothingWhenNoNormalizersRegistered` — back-compat: empty `normalizers` list is a no-op.
- `applyBaselineNormalization_writesNothingWhenBelowThreshold` — fake emits an `m` such that `|new − old| < 2 kg` → no rows.
- `applyBaselineNormalization_writesBaselineAndCoefficientLogsWhenAboveThreshold` — fake clears threshold; one `NORMALIZATION` baseline row, one `baseline_normalization` coefficient row per in-group exercise.
- `applyBaselineNormalization_preservesSessionWeightAcrossInGroupExercises` — for each in-group exercise, `newBaseline · newCoef ≈ oldBaseline · oldCoef` within rounding tolerance.
- `applyBaselineNormalization_scalesUnobservedExercisesInGroup` — exercise with a defined coefficient but no `WorkoutSet` still gets scaled.
- `applyBaselineNormalization_runsAfterRecomputeCoefficients` — order check: a `CoefficientHeuristic` plus a `BaselineNormalizer` registered together; normalizer sees heuristic-written coefficients.
- `applyManualBaselineOverrides_doesNotTriggerNormalization` — manual override path unaffected.

### Backfill regression guard

`recomputeDerivedState_runsBothCoefficientsAndNormalization`:

- Seed the DB with sessions whose sets demonstrate consistent same-direction drift across a muscle group.
- Construct a `WorkoutRepository` with both an `EstCoefConsensusHeuristic` and a `SeedNormalizer` registered.
- Call `repository.recomputeDerivedState()` once.
- Assert: at least one `CoefficientChangeLog` row from `est-coef-consensus` exists, AND at least one `BaselineChangeLog` row with `NORMALIZATION` reason exists.

A companion sub-assertion runs the same fixture with only one of the two registered, confirming each pass fires independently when the other is absent. Any future post-progression pass added inside `recomputeDerivedState` should append a corresponding assertion to this test.

### End-to-end smoke

A single test that constructs a session sequence (no fakes; real `SeedNormalizer` + `EstCoefConsensusHeuristic`) where coefficients drift uniformly across a muscle group over several sessions, and asserts that a `NORMALIZATION` baseline log row eventually appears. Provides convergence evidence on the full pipeline.

## Out of Scope

- Weighted least squares (per-exercise observation weights).
- Multi-objective normalizers (e.g., targeting different reference points than seed).
- UI relabeling of the new reason / heuristic name.
- Backfill-on-upgrade replaying historical sessions through normalization beyond the existing `recomputeDerivedState` call. The current backfill path replays `ActualRepsBackfill` and then recomputes derived state once over the resulting ledger.
