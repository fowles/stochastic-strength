# Prescription policy — from belief to weight on the bar

Source: `domain/policy/PrescriptionPolicy.kt`
Inputs: `PooledBelief` (μ̃, σ̃) from `MuscleStrengthProjector`, `PolicyState` from replay
Design note: `docs/superpowers/specs/2026-07-06-belief-policy-reframe-design.md` §4

The [pooled belief](04-muscle-pooling.md) (μ̃, σ̃) is an honest statistical estimate of
fresh 1RM. Every training decision on top of that estimate lives here:
`PrescriptionPolicy.prescribe(exercise, sessionReps) → weight (kg)`.

Policy state (per-exercise failure ceilings, HURT events, muscle stress logs) is
assembled during `replayDerivedState` and held in `DerivedStateStore`. No policy state is
persisted; it is deterministically rebuilt from the set log on every replay.

## Base target: shading + overload + fatigue

Starting from the pooled mean μ̃ and pooled sigma σ̃ (own aged belief std), the base
target in log space is:

```
t = μ̃ − z·σ̃ + δ + ln(1 − φ·(S−1))
```

- **z·σ̃** shades the target below the belief mean by a fixed multiple of the
  uncertainty. With `uncertaintyZ = 0.4`, a belief at σ_seed = 0.25 prescribes about
  10% below the mean — conservative on a cold or uncertain estimate. As σ shrinks with
  training (toward σ_min = 0.02), the shading becomes negligible.
- **δ = overloadDelta = 0.02** is a small upward push in log space (~2%) — the
  progressive overload nudge. Together with z·σ canceling in steady state (when σ → σ_min),
  the net effect matches the feel of the previous estimator.
- **ln(1 − φ·(S−1))** is the fatigue discount: beliefs are defined on fresh (first-set)
  capacity, but the target is the **last set** (the one where RIR 0–1 is expected).
  With φ = fatiguePerSet = 0.03 and S = DEFAULT_SETS = 3, the discount is
  ln(1 − 0.03·2) ≈ −0.06 (about −6% of e1rm). In steady state z·σ ≈ δ and the
  fatigue discount effectively converts the fresh-capacity belief into the
  practical last-set weight.

The target e1rm is `exp(t)`.

## Failure ceiling

Applied **before** HURT (spec §4 order, so HURT compounds under the ceiling rather than
being floored by it). From the most recent completed session containing this exercise:

- **Clear miss** (shortfall ≥ 2 reps, or uncounted TOO_HARD): ceiling = `ceilingFactorClear = 0.97`
  × the failed-weight implied 1RM. If the computed target exceeds the cap it is clamped.
  Additionally, when nearest-rounding would land at or above the failed weight's rep-
  equivalent (possible on coarse grids where the 3% haircut is less than half a grid
  step), **round-down** is used instead of round-nearest, guaranteeing the prescribed
  weight is strictly below what you just failed.
- **Marginal miss** (shortfall ≤ 1 rep): ceiling at 1.0× — re-prescribing the same grid
  weight is allowed (preserves the "hold the weight on a marginal miss" behavior).
- Ceilings **expire** after `ceilingExpiryMs = 28 days`, or when superseded by any
  newer completed session on the exercise.

## HURT caution

After the failure ceiling, the target is multiplied by the combined HURT decay:

```
multiplier = max(hurtFloor, Π over hurt events on this muscle of (1 − hurtDepth · 2^(−Δt/hurtHalfLife)))
```

with `hurtDepth = 0.15`, `hurtHalfLifeMs = 14 days`, `hurtFloor = 0.6`. Each HURT
event reduces the prescription by ×(1 − 0.15) = ×0.85 immediately and decays with a
~2-week half-life. Multiple events in quick succession multiply; the floor prevents the
multiplier from dropping below 0.6 no matter how many events have occurred.

## Grid rounding

`WeightFormatter.round(raw, weightUnit)` rounds to the nearest grid step. Clear-ceiling
cases use `WeightFormatter.roundDown` when nearest-rounding would violate the strictly-
below guarantee (see failure ceiling above).

## Sore-muscle cooldown

`muscleRested(muscle)`: a muscle is **not rested** (excluded from workout generation)
when, within the past `restCooldownMs = 2 days`, any loaded exercise on that muscle had
a TOO_HARD set, or more than one RIR_0_1 set within a single exercise. This is a
verbatim port of the old planner rule (`WorkoutPlanner.recentlyFailedMuscles`) into the
policy.

## Layoff notice

If the detraining drift applied to any planned muscle since its previous session exceeds
`noticeThresholdFraction = 3%`, `PlanPreview` shows a passive one-line notice ("eased
~X% after the break"). No dialog, no user decision. This replaces the old
detraining dialog; the drift itself is applied automatically during replay
([#3](03-exercise-estimates.md)).

## Policy state provenance

All policy inputs (`state.ceilings`, `state.hurtEvents`, `state.muscleStress`) are
assembled by `PolicyStateBuilder` during `replayDerivedState`, one session at a time.
`PolicyState` is held in `DerivedStateStore` and rebuilt from scratch every replay —
never persisted to Room. Manual per-exercise weight overrides (`ExerciseStrengthOverride`)
bypass the policy entirely and are folded into the belief during replay.
