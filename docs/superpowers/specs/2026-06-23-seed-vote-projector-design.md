# Seed-vote muscle-level prior: collapse `MuscleStrengthProjector` regimes

**Date:** 2026-06-23
**Status:** Approved (design)

## Problem

`MuscleStrengthProjector.project` computes a per-muscle strength **level** as a
confidence-weighted geometric mean of each confident exercise's seed-relative
estimate `ln(E_j / coef_j)`. Two coupled mechanisms guard it:

1. A hard gate — only exercises with decayed confidence `>= confidentThreshold`
   (1.0) vote in the level.
2. A separate cold-muscle fallback — when no exercise crosses the threshold,
   `lnLevel` is `null`, the level comes from `fallbackLevel`, and the
   per-exercise shrink target switches from the pooled level to each exercise's
   own seed (`else e.lnE`).

The gate is not redundant with confidence weighting, because the level is a
**weight-normalized** mean: `lnLevel = Σ(vote_j · c_j) / Σ(c_j)`. Normalization
cancels the absolute magnitude of confidence, so a single stale voter — however
low its confidence — still fully determines the level forever. The threshold is
what actually retires stale voters; without it, low confidence does **not**
"decay away" for the level.

We want low confidence to bleed back toward the seed prior continuously, the way
it already does for the per-exercise shrink (which uses an *absolute* prior
weight `priorStrength` and therefore needs no threshold). That requires giving
the level an absolute-weight prior too.

## Key enabling fact

`ExerciseSeedExpansion` builds a seeded exercise as `e1rm = baseline * coef`.
Therefore at seed, `ln(E / coef) = ln(baseline)` — identical for every exercise
in the muscle. An untrained sibling's seed-relative level *is* the muscle
baseline, so the prior anchor can be recovered from current estimates without
persisting the baseline separately.

## Design

Change is confined to `MuscleStrengthProjector.project`. The estimate fold
(`ExerciseEstimateUpdater`) and signal extraction (`SessionSignalExtractor`) are
untouched.

### `EstimatorConfig`

- **Remove** `confidentThreshold`.
- **Add** `levelPrior: Float` — effective sample size of the seed prior in the
  muscle-level pool. Initial value chosen during the simulation re-pin (see
  Testing).

### `project`

1. Gather loaded exercises (`coef > 0`). If none, return the zero projection
   (`MuscleProjection(0f, emptyMap(), emptyMap())`) — replaces the old
   null-fallback path.
2. `lnPrior` = unweighted mean of `ln(E_j / coef_j)` over all loaded exercises.
   Equals `ln(baseline)` for a cold muscle; drifts only as exercises are
   genuinely trained. Trained exercises double-count slightly (prior share
   `levelPrior / N` plus their own vote `c_j`); the bias is second-order and
   vanishes for cold muscles. Accepted.
3. Pooled level — every exercise votes with its full decayed confidence, no
   threshold, against the fixed-weight prior:

   ```
   lnLevel = (Σ c_j·(lnE_j − ln coef_j) + levelPrior·lnPrior) / (Σ c_j + levelPrior)
   ```

4. Per-exercise shrink uses `lnPred = ln(coef) + lnLevel` **always**. The
   `else e.lnE` branch is deleted: as `c → 0` for every exercise, `lnLevel →
   lnPrior → ln(baseline)`, so `lnPred → ln(coef) + ln(baseline) = ` the seed
   estimate. The cold-muscle behavior is now reached continuously instead of via
   a branch.

### Deletions

- `confidentThreshold` (config).
- `fun fallbackLevel`.
- The `lnLevel: Float?` nullability and the `else e.lnE` shrink branch.

## Behavior deltas (vs. current)

- **Cold muscle (all c ≈ 0):** `lnLevel → ln(baseline)`, shrink toward own seed.
  Equivalent to the old fallback.
- **Stale lone voter (one exercise, c ≈ 0.2):** old code lets it fully define
  the level (normalization); new code blends `(0.2·v + levelPrior·prior) /
  (0.2 + levelPrior)` — mostly prior. This is the fix.
- **Single fresh session (c ≈ 1.48):** authority depends on `levelPrior`
  (≈ 83% at 0.3, ≈ 60% at 1.0). A genuine behavior change — one session no
  longer fully redefines a muscle. `levelPrior` is the continuous knob the
  binary threshold was crudely approximating.

## Testing

- **`ExerciseEstimatorSimulationTest`** pins `EstimatorConfig` and trajectories.
  Re-pin: sweep `levelPrior`, propose a value, get sign-off on trajectory
  deltas, update golden numbers.
- **`MuscleStrengthProjectorTest`** gains corner cases:
  - cold muscle reduces to seed (level == baseline, prescriptions == seeds);
  - a stale lone voter bleeds toward the prior rather than defining the level.
- TDD throughout: failing test first, then the projector change.

## Out of scope

- The estimate fold, signal extraction, and weight prescription engine.
- The `conf < ε` cold-only prior variant (rejected in favor of mean-over-all).
- Any persistence/schema change — derived state only, rebuilt by replay.
