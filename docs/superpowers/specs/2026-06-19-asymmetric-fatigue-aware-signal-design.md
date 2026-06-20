# Asymmetric, fatigue-aware progression signal

**Date:** 2026-06-19
**Status:** Design approved, pending implementation plan

## Problem

The baseline progresses too quickly and frequently grows even when the last set
of an exercise had mixed results. Two structural causes:

1. **`RIR_0_1` is treated as an up-signal.** `SessionSignalExtractor` maps
   `RIR_0_1 → targetReps + 1` reps in reserve. The planner already prescribes the
   weight you can do for *exactly* `targetReps` reps, so completing a set at the
   intended target effort still implies a 1RM *above* the prescription and pushes
   the baseline up.
2. **All sets are averaged, so fresh easy sets inflate the estimate.**
   `aggregateSession` takes a confidence-weighted mean over *all* sets of an
   exercise. Early, un-fatigued sets (often `RIR_2_4` / `RIR_5_PLUS`) drag the
   implied 1RM upward even when the fatigued *last* set was a grind.

### Reframe

"One Rep Max" is a user-facing simplification. The estimator's real goal is to
find a baseline whose **prescribed full set is completable at `RIR_0_1` on the
last, fatigued set**. Progressive overload remains the explicit goal — the system
should still climb — but gently, and it must not grow on a session that contained
a failure.

## Decisions (from brainstorming)

1. **Asymmetric aggregation.** A failing set *dominates* the down-pull; non-failing
   sets in the same exercise may only *soften* that down-pull, never flip it into
   growth.
2. **`RIR_0_1` is a small up-signal (Option 2), not neutral.** Progressive overload
   is the goal, so a clean `RIR_0_1` nudges the baseline up gently (≈ half a rep of
   implied reserve) rather than holding perfectly still — but far less than today's
   full `+1` rep.
3. **Rep-scaled softening of the down-pull.** How much good sets soften a final-set
   failure scales with the rep target: low reps stay strict (a miss is real), high
   reps are forgiving (a near-miss is largely absorbed), while still targeting full
   completion.

## Scope

- **Change:** `SessionSignalExtractor` (signal semantics) and
  `ProgressionControllerSimulationTest` (validation harness, incl. its synthetic
  lifter) and `SessionSignalExtractorTest`.
- **Unchanged:** `RollingConservingProgressionController` — its common/differential
  split, gauge conservation, gain damping, and HURT backoff are untouched. We are
  reshaping the *signal* it consumes, not the control dynamics. Its locked
  gauge-conservation guarantees remain in force.

The rep target spans **`[1, 20]`** (`RepRangePicker` picks from round values
1,2,3,5,8,10,12,15,18,20 plus the chosen extrema). Every formula below is defined
over that full range, not just the {5,8,10} the planner historically used.

## Design

### Per-set signed rep-deviation

Each **full-weight** set collapses to a signed deviation `d` from the target — reps
of reserve (positive) or shortfall (negative) — plus a confidence:

| Feedback                | deviation `d`               | confidence | note                                   |
|-------------------------|-----------------------------|------------|----------------------------------------|
| `RIR_5_PLUS`            | `+6`                        | 0.40       | clearly easy                           |
| `RIR_2_4`               | `+3`                        | 0.70       | bucket midpoint                        |
| `RIR_0_1`               | `+0.5`                      | 0.85       | **was `+1`** — the gentle Option-2 nudge |
| `TOO_HARD` (reps known) | `actualReps − targetReps`   | 0.95       | real shortfall (negative)              |
| `TOO_HARD` (reps unknown) | `−targetReps / 2`         | 0.95       | conservative shortfall                 |
| `HURT`                  | — (no load signal)          | —          | handled muscle-level by controller     |

Offsets are absolute reps, so they work unchanged at any target in `[1, 20]`.

**Dropped / reduced-weight sets are ignored** for this aggregation. The failure
that triggered a mid-session weight drop is itself a full-weight `TOO_HARD` set and
is already captured; the subsequent lighter sets carry no additional up-signal
(consistent with the existing "dropped sets contribute no up-signal" rule).

### Within-exercise aggregation (asymmetric)

Over the full-weight sets of one exercise:

- Weight each set by `confidence × position` (later sets weigh more) so the **last,
  most-fatigued set dominates**. This is what prevents a fresh easy early set from
  inflating the baseline.
- `upAgg` = the `confidence × position`-weighted mean of the **non-failing**
  deviations (`d ≥ 0`); `0` if there are no non-failing full-weight sets.
- Let `worstFail = min(d)` over failing sets (`d < 0`); the most negative shortfall.

Then:

```
if no failing set:
    aggOffset = upAgg                                  # mild up — progressive overload
else:
    aggOffset = min(0, worstFail + σ(reps) · upAgg)    # failure dominates, softened, capped at neutral
```

The `min(0, …)` cap means **a session containing a failure can never grow the
weight** — at most it holds steady. Good earlier sets soften the down-pull (more so
at high reps), but cannot turn a failure into growth.

Finally:

```
est1RM = toOneRepMax(w0, targetReps + aggOffset)       # w0 = the exercise's full session weight
```

`est1RM` and a session confidence (the max contributing confidence) are handed to
the controller exactly as today via `ProgressionObservation`.

### Rep-scaled softening `σ(reps)`

```
σ(reps) = 0.10 + 0.70 · (clamp(reps, 1, 20) − 1) / 19
```

| reps | 1    | 3    | 5    | 8    | 10   | 12   | 15   | 18   | 20   |
|------|------|------|------|------|------|------|------|------|------|
| σ    | 0.10 | 0.17 | 0.25 | 0.36 | 0.43 | 0.51 | 0.62 | 0.73 | 0.80 |

Low reps (strength/power): a final-set miss is almost fully a down-signal.
High reps (endurance): a final-set miss is largely absorbed by good earlier sets.
These constants are a starting point; the simulation harness tunes and locks the
final values.

## Validation: simulation harness changes

`ProgressionControllerSimulationTest` currently cannot validate any of this. Three
changes:

1. **Cross-set fatigue in the synthetic lifter.** Today every set draws the same
   `true1RM`, so "the last set is the hard one" does not exist. Add a per-set
   fatigue penalty (later sets achieve fewer reps for the same weight) so set 3 is
   genuinely harder than set 1 — the phenomenon being tuned for.

2. **Redefine the primary success metric to the behavioral spec.** With fatigue the
   steady-state baseline *should* settle **below** the fresh `true1RM` (it settles
   where the *last, fatigued* set lands at ≈`RIR_0_1`), so "baseline×coef ==
   true1RM" is no longer the correct target. Replace the primary metric with:

   > At steady state, the prescribed weight produces **last sets centered on
   > `RIR_0_1`** — rarely failing, rarely landing at `RIR_2_4`+.

   Keep the existing guardrails: gauge conservation (`coefInflation ∈ [0.97,
   1.03]`) and bounded jitter / non-divergence.

3. **Exercise the full rep range.** Draw session rep targets from `[1, 20]` via
   `RepRangePicker` rather than only `{5, 8, 10}`, so `σ(reps)` is validated
   end-to-end.

## Out of scope

- No changes to the controller's control dynamics, gauge conservation, or HURT
  handling.
- No changes to the planner, weight selection, or rounding.
- The pre-existing concern that a backfilled/imported set logged well below the
  current baseline reads as a down-signal is not addressed here (existing behavior).

## Risks / open questions

- Final `σ(reps)` constants, the `confidence × position` weighting curve, and the
  per-set fatigue penalty in the synthetic lifter are all set by tuning against the
  redefined simulation metric; the values above are starting points.
- The behavioral steady-state metric needs a concrete tolerance (e.g. ≥ X% of
  tail-session last sets at `RIR_0_1`, ≤ Y% failing); the exact thresholds are
  chosen during implementation against multi-seed runs.
