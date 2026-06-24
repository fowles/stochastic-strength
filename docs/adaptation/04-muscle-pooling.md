# Read-time muscle pooling — how exercises cross-inform at prescription time

Source: `domain/progression/MuscleStrengthProjector.kt`
(cross-tuning view: `domain/progression/CrossTuning.kt`)
Design note: `docs/superpowers/specs/2026-06-23-seed-vote-projector-design.md`

Folding is local: each [exercise estimate](03-exercise-estimates.md) moves on its own
evidence only. That keeps failures from corrupting siblings, but on its own it would
leave a cold or long-unseen exercise stuck at a stale guess. The fix is to let exercises
borrow strength from each other **at read time**, without ever mutating the stored
estimates. That is `MuscleStrengthProjector.project`, and it runs every time the app
needs a weight (and after every session, to record the derived projections).

It does two things: compute a muscle **level**, then **shrink** each exercise toward what
that level predicts.

## Step 1 — the muscle level (a seed-anchored vote)

Each loaded exercise (positive seed coefficient) offers a seed-relative opinion of how
strong the muscle is: `lnE − ln(seedCoef)`. If every exercise matched its seed exactly,
these would all agree on `ln(baseline)`.

The level is a **confidence-weighted average of those opinions, anchored to a fixed-weight
seed prior**:

```
lnPrior = mean over loaded exercises of (lnE − ln(seedCoef))      // the cold-muscle anchor
lnLevel = (levelPrior · lnPrior + Σ confᵢ · (lnEᵢ − ln(seedCoefᵢ))) / (levelPrior + Σ confᵢ)
```

- Every exercise votes with its **full decayed confidence** — there is no confidence
  threshold or cold/confident gate. Low-confidence exercises simply contribute little.
- `levelPrior = 0.5` is the effective sample size of the seed anchor. A thinly-evidenced
  muscle leans on the seed; a stale lone voter decays back toward it instead of defining
  the level by itself. For a genuinely cold muscle, `lnLevel == lnPrior == ln(baseline)`,
  so a fresh exercise is prescribed exactly its seed weight.

## Step 2 — shrink each exercise toward its prediction

For each exercise, the level predicts a target via that exercise's seed coefficient, and
the exercise's own estimate is blended toward it by confidence:

```
lnPred = ln(seedCoef) + lnLevel
lnUsed = (confSelf · lnE + priorStrength · lnPred) / (confSelf + priorStrength)
```

`priorStrength = 1.0` is how many confidence units the sibling prediction is worth.

- A **confident** exercise (high `confSelf`) trusts its own estimate; the prediction
  barely moves it.
- A **cold or stale** exercise (low `confSelf`) leans on the prediction — its sibling
  pool carries it until it earns its own evidence.

`exp(lnUsed)` is the exercise's **projected effective 1RM** (`effectiveE1rm`). The derived
coefficient is just `effectiveE1rm / level`, kept so that `level × coef == effectiveE1rm`
for display and history.

This shrink is **non-destructive**: it reads the estimate map but never writes it. The
durable state stays purely per-exercise; pooling is a lens applied on the way out.

## How the prescription uses it

`MuscleStrengthProjector.project(...).effectiveE1rm[exerciseId]` is passed to the planner
as `prescribedE1rm`, which `DefaultProgressionEngine` scales to the session's chosen rep
target via the load-aware 1RM formula. The per-muscle level is also written to
`MuscleGroupStrength` + a `baseline_history` row, and the derived coefficients to
`coefficient_history` — all projections in the in-memory `DerivedStateStore`, rebuilt by
replay, never a stored source of truth.

## The cross-tuning view (debug)

`computeCrossTuning` is a read-only diagnostic built on the same projector. For each
exercise it reports **agreement** — how far its own estimate sits from a *leave-one-out*
sibling prediction (`project` run without that exercise) — and **contribution**, its
share of the muscle's total decayed confidence. It powers the debug exercise-detail
screen and changes no state.

## What replaced the old gauge problem

The previous baseline×coefficient design had a *gauge*: you could scale a whole muscle's
coefficients up and divide its baseline down for identical weights, so the scale could
silently drift and a separate renormalization pass (`SeedNormalizer`) had to sweep it
back. That ambiguity is **gone**: there is no stored coefficient to drift. The durable
state is one estimate per exercise; the level and coefficients are derived freshly from a
fixed seed anchor on every read, so there is nothing to renormalize.
