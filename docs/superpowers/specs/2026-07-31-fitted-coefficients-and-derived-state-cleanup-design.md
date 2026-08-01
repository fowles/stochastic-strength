# Fitted cold-start coefficients + derived-state cleanup

**Date:** 2026-07-31
**Status:** Part A shipped (Plans 1–2); Part B (compression) shipped 2026-07-31

## Motivation

`ExerciseCoefficients` (the per-exercise 1RM ratios) are hand-picked guesses. A prior
investigation ([memory `project_coef_refit_underdetermined`], scratchpad scripts this session)
established two things from the real `history.json`:

1. The **individual** coefficients cannot be re-fit from history — the two-way `ln1RM = session +
   γ_exercise` model is saturated (params ≈ observations), same-muscle co-training is rare (24 of
   152 muscle-sessions train ≥2 same-muscle lifts), and held-out CV is flat across regularization
   strength. Per-exercise refit is underdetermined and would bake one user's idiosyncrasies into a
   global prior.
2. A single **structural compression** parameter *is* identifiable and helps. Forward-chaining
   cold-start (predict a lift's first-appearance 1RM from sibling beliefs) improves from RMSE
   0.275 ln (current) to 0.226 ln under leave-one-exercise-out with `coef' = coef^λ`, λ≈0.75–0.80.
   The abandoned `usv` branch independently fit the same λ=0.75 against the belief gate (per-set
   score 0.126 → 0.080).

`usv` shipped λ as a **runtime** parameter (`BeliefConfig.coefExponent`) applied in `BeliefPooling`
and `StartingWeights`, plus a v18→v19 backfill migration to compress existing users' materialized
seed rows. That is the wrong shape: λ only ever transforms fixed guessed constants into other fixed
constants — it has no runtime dependence, so its output can be precomputed. It is not the same kind
of thing as `fatiguePerSetEstimate`/`crossLiftIndependenceEstimate`/`confidenceDecayEstimate`, which govern live estimator dynamics.

**This work** replaces the runtime knob with a fitted, precomputed coefficient table, and collapses
the durable state that forced coefficient changes to migrate user data.

## Goals

- `ExerciseCoefficients` becomes a **fitted artifact** of the backtest framework (like the other
  belief constants), shipped as plain numbers with a reproducible, CI-guarded derivation.
- Shipping a new coefficient table requires **no migration** — it touches nothing user-stored.
- Build on the existing held-out backtest gate so we can tell whether new coefficients are better.
- Leave the door open for more data to refine the fit (coarse now, re-runnable later).

## Non-goals

- Per-exercise coefficient refitting (underdetermined — established).
- Per-muscle λ *now*. Start global-only; let per-muscle earn its place via the gate later.
- Bodyweight (`coef==0`) exercises — skipped by the fold, unaddressable by compression; separate
  problem, out of scope.

## Design

### Part A — Derived-state cleanup

Today the coefficient is baked into durable per-user rows: `exercise_strength_override`
(`sessionId==null`, `e1rm = perMuscleBaseline × coef`), which is why a coefficient change needs a
migration. Writers of that table split cleanly by `sessionId`:

| rows | writer | coefficient-derived? | disposition |
|---|---|---|---|
| `sessionId IS NULL` | `seedInitialWeights`, legacy backfill | yes (pure projection) | retire → live expansion |
| `sessionId != null`, `OVERRIDE` | `applyManualExerciseOverrides` (session start) | no | drop (redundant with set log) |
| `sessionId != null`, `DETRAIN` | `applyDetrainingReduction` (session start) | no | drop (replaced by inference) |

**A1 — Live seed expansion.** Replay's init phase (`ReplayEngine.runCore`, currently lines 73–76)
synthesizes cold-start seed beliefs on the fly instead of reading materialized rows:
`ExerciseSeedExpansion.expand(perMuscleBaselines, exercises, ExerciseCoefficients)` seeded with
`sigmaSeed`. Per-muscle baseline = `baseline_override` rows if present, else the
`StartingWeights(sex, level)` default from `UserProfile`. Coefficient-independent durable input;
the coefficient half is applied live from the current table.

**A2 — Manual override → ephemeral.** Keep the plan-time weight edit (`WorkoutPlanner.
exerciseE1rmOverrides`) that drives what the user performs this session. Drop the durable
`applyManualExerciseOverrides` belief-reset write. Once the user performs at the intended weight and
gives feedback, the set fold converges the belief there within the session (gain ≈ 0.9/set). The
set log is the record.

- *Behavior change:* a deliberate *downward* manual override that the sets then contradict (e.g.
  override to 70, then do 70 at RIR 5+) no longer sticks — the one-sided lower-bound set can't pull
  mu down. Defensible: the set demonstrates the override was too conservative. "Keep me light
  despite feeling strong" is the HURT-backoff policy's job, not a belief edit.

**A3 — Detrain → inferred from the session timeline.** Remove stored detrain rows and the
`DetrainingDialog`. During replay, a large gap between consecutive sessions applies a strength decay
`f(gap)` to **all** current beliefs at that boundary. Signal is the **inter-session** gap (the
existing `weeksOff(lastCompleted, now)` notion), not per-exercise idle — someone who trains 4×/week
but benches every 10 days must not detrain. Applied globally, so exercises not performed at the gap
(next session's lifts) come back reduced too — fixing the current plan-scoped gap where next
session's different lifts return at full pre-layoff strength. Self-correcting: whatever `f(gap)`
gets wrong, the first set back reveals true capacity and folds the belief there, so `f(gap)` only
needs to make the comeback set *safe*, not exact.

- *Notification:* a lightweight notice at workout start after a qualifying layoff gap
  ("you've been away — starting lighter"). No stored state, no slider; the adjustment is automatic.
  Replaces the interactive dialog. `WorkoutSessionController.maybeOfferDetraining` already computes
  the gap — it drives a notice instead of a dialog.

**A4 — Delete `exercise_strength_override`.** No writers remain after A1–A3. One trivial migration
(drop the table / delete its rows; retire the defunct `perExerciseSeedsBackfilled` flag and the
backfill path). Backup import/export drops the table accordingly.

**Runtime simplification.** No `coefExponent` in `BeliefConfig`; no exponent in `BeliefPooling` or
`StartingWeights`. Runtime returns to the clean `level + ln(coef)`, one coefficient space
everywhere.

**Durable state after Part A:** `workoutSessions` + `workoutSets` + per-muscle `baseline_override`
+ `UserProfile`. Beliefs and all per-muscle/per-exercise projections remain derived (rebuilt by
replay).

### Part B — The coefficient generator + gate

- **`CoefficientGuesses`** — new object holding the legible raw round numbers (the current
  `ExerciseCoefficients.byName` values), the generator's input/prior.
- **`ExerciseCoefficients`** — holds the shipped **compressed** values (`guess^λ`), read at runtime
  as plain numbers. A test asserts `ExerciseCoefficients.byName == compress(CoefficientGuesses, λ)`
  so the derivation is reproducible and CI-guarded. `guess==0 → 0` and `guess==1 → 1` are preserved
  (reference lifts and bodyweight lifts unchanged).
- **λ is global, fit by held-out backtest score.** Salvage from `usv`: the `coefExponent` axis in
  the fit harness and `BacktestData.withCompressedSeeds`. Fitness = the general `BeliefScoreTest`
  held-out score (sensitive: usv moved per-set 0.126→0.080), with the forward-chaining cold-start
  RMSE (scratchpad `coldstart.py`/`compress.py` this session) as a targeted secondary readout.
  Re-baselining the gate is a human decision.
- **`f(gap)` decay rate + layoff threshold** — new constants for A3, labeled `fitted`/`semantic`,
  pinned the same way (fit against real layoff gaps in history if present, else literature-based
  semantic values). Because sets self-correct, precision is low-stakes.

### Fitness / "are they better?"

Held-out backtest score via the existing framework (`BeliefScoreTest`/`BeliefHeldOutScorer`), plus
the cold-start forward-chaining RMSE built this session as a focused check on the coefficient half.

## Migration

- **One-time:** drop `exercise_strength_override` (and retire `perExerciseSeedsBackfilled`). Simple
  SQL. This is the *last* coefficient-related migration.
- **Thereafter:** shipping a new `ExerciseCoefficients` (or a re-fit λ) is a pure code change —
  zero migration, since nothing coefficient-derived is stored per user.

## Risks / notes

- Live expansion for **existing** users: seeds now come from `baseline_override × current table` at
  replay time. Per-*muscle* baseline edits are preserved (they live in `baseline_override`); only
  the coefficient half updates. New users (no `baseline_override`) use `StartingWeights` defaults.
- Detrain-by-inference **re-times historical gaps** — replay applies decay at every past layoff,
  shifting the pinned gate. Expected; re-baseline as part of B.
- The whole change re-derives derived state through `replayDerivedState`, which is idempotent;
  correctness is gated by keeping the belief gate green (re-baselined deliberately, not silently).

## Phasing (for the plan)

Part A (cleanup) and Part B (fit) are separable — A is behavior/architecture, B is the numbers. A
natural split into two plans:

- **Plan 1 (Part A):** live seeds, manual→ephemeral, detrain-by-inference + notice, delete the
  table, remove runtime λ. Gate stays green (or is re-baselined for the detrain model).
- **Plan 2 (Part B):** `CoefficientGuesses` + generator + assertion test, fit global λ, re-baseline
  the gate, ship the compressed table.

Sequencing decided at writing-plans time.
