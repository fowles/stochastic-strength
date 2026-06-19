# Unrounded baselines — design

**Date:** 2026-06-19
**Status:** Approved (design); pending implementation plan

## Problem

Per-muscle baselines are currently rounded to the user's weight grid (5 lb / 2.5 kg)
at the moment the progression controller writes them
(`RollingConservingProgressionController`, `ProgressionController.kt:122` for the
progression update and `:102` for the HURT back-off). The persistence layer stores
that already-rounded value verbatim.

Because the controller is a pure proportional (P) loop and recomputes its update from
the *rounded* baseline every session, a real positive signal that is smaller than half
a grid step is rounded away and lost every session. The baseline plateaus in a rounding
deadband even though the signal says "go up."

Observed case (emulator DB, QUADS, 2026-06-06 / session 30):

```
b = 127.006 kg (280.00 lb)   common = +0.0116   dLogB = +0.0058
raw new = 127.74 kg (281.63 lb)  ──round 5 lb──►  280.00 lb   (no change)
```

The QUADS baseline reached 280 lb on 2026-06-01 and then sat there for six sessions, with
the common-mode innovation staying positive (and even growing: +0.0116, +0.0143, +0.0165)
because the push was never integrated past the grid boundary.

## Goal

Store baselines at full precision in the progression math, and round only at the single
point where a weight becomes a real plate selection. This both fixes the stall (sub-grid
pushes accumulate until they cross a grid boundary) and cleanly separates progression
state from display/selection concerns.

## Key existing fact

`WorkoutPlanner` **already rounds at selection time**:
`WeightFormatter.round(progressionEngine.fromOneRepMax(baseline × coef, reps), unit)`
at `WorkoutPlanner.kt:92` and `:167`. So the rounding the user actually experiences does
not depend on the baseline being pre-rounded. Today we round twice; this change removes
the first (internal) rounding and keeps the second (selection) rounding.

## Design (Approach A — remove rounding only in the controller)

### Change surface — `ProgressionController.kt` only

- **Progression update (`:122`):** emit `BaselineUpdate(m, b * exp(dLogB), …)` without
  `WeightFormatter.round`. Keep the existing `bNew != b && bNew > 0f` guard so an exact
  no-op (`common == 0` ⇒ `dLogB == 0`) emits nothing.
- **HURT back-off (`:102`):** emit `BaselineUpdate(m, b * config.hurtFactor, "hurt")`
  unrounded, for consistency.
- **`BaselineUpdate` KDoc:** currently states the value is "already rounded to the weight
  grid by the controller; the persistence layer stores it verbatim." Update it to state
  the baseline is stored at full precision and that grid rounding happens only at weight
  selection in `WorkoutPlanner`.

### Update churn

Per decision: **write every change.** No new deadband for baseline writes. Every trained
session that yields a non-zero `dLogB` writes a `BaselineHistory` row (existing behavior,
just finer values). The coefficient `minRelativeChange` gate is unrelated and unchanged.

### Explicitly unchanged

- `WorkoutPlanner` selection rounding (`:92`, `:167`) and warmup rounding (`:113`).
- `WeightFormatter` itself.
- DB schema and migrations — none. Same `Float` column; values simply stop being grid
  multiples. The in-memory replay (`WorkoutRepository.replayDerivedState`) recomputes all
  baselines from scratch on next launch, so existing users' baselines shift smoothly to
  their unrounded equilibrium. A selected session weight may move by one increment — expected.
- Seed / initial `baseline_override` rows and manual overrides — stored verbatim, as today.
- Display surfaces (`StrengthGrid` via `WeightFormatter.format`, debug baseline chart,
  exercise detail) — already round for display; they will now show finer values such as
  "282 lbs" instead of always a multiple of 5.

### Data flow after the change

```
controller: raw baseline (kg, full precision)
   └─► stored verbatim in DerivedStateStore / BaselineHistory
        └─► WorkoutPlanner: fromOneRepMax(baseline × coef, reps)
             └─► WeightFormatter.round(…, unit)  ─►  real plate weight
```

The controller remains a pure P-loop — no integral term is added. It simply is no longer
quantized, so it settles at its true steady state instead of being trapped on a grid point.

## Testing

- **`ProgressionControllerSimulationTest`** applies `newBaseline` verbatim and rounds only
  at *session-weight* selection (mirroring `WorkoutPlanner`). Its locked ceilings have
  headroom (trainedErr ~2.3 vs ceiling 4.0; jitter ~0.5 vs ceiling 1.0; coefInflation in
  0.97–1.03; convergence ≤ 8 sessions). Removing quantization should keep it green and
  likely *reduce* jitter. Plan: run it; if metrics shift, update the `// doc: ~X` comments.
  Only touch a ceiling if genuinely warranted, and flag it explicitly.
- **`ProgressionControllerTest`** (new focused case): one step whose `common` implies a
  sub-grid move asserts `newBaseline == b * exp(dLogB)` exactly (not a grid multiple).
- **Full `:app:testDebugUnitTest`** at the end for regressions.

## Out of scope / non-goals

- No integral or anchor term (the PI-reframe memo records integral/anchor as dead ends).
- No change to how weights are rounded for the user, no display-precision changes.
- No baseline deadband / threshold tuning.
