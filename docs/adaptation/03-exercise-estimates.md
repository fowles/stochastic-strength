# Per-exercise beliefs — the durable progression state

Source: `domain/belief/Belief.kt`, `domain/belief/BeliefFold.kt`
Shared step: `domain/belief/BeliefSessionStep.kt` (drives both the production replay and
the backtest — the backtest scores exactly the stack that runs in prod)
Applied by: `ReplayEngine` → `WorkoutRepository.replayDerivedState`

The **only durable progression state** is one `Belief` per loaded exercise:

```
Belief(mu, sigma2, updatedAt)
```

`mu` is the natural log of the exercise's believed fresh 1RM (kg); `sigma2` is the
variance of that belief, in ln-units². Everything lives in log space, so a fixed
percentage move is a fixed additive step. There is no separate confidence score — `sigma2`
*is* the confidence: tight (small `sigma2`) means well-evidenced, wide means uncertain.
There is no stored baseline and no stored coefficient.

A brand-new exercise has no belief at all until it is either seeded (an initial
`ExerciseStrengthOverride` row) or folded for the first time, in which case it borrows its
sibling-pooled prediction as a cold prior (see [muscle pooling](04-muscle-pooling.md)).

## Aging — uncertainty grows with idle time

Before any fold, and at read time, a belief is aged to "now":

```
sigma2 ← (sigma2 + qPerDay · idleDays), clamped to [sigma2Floor, sigma2Cap]
```

`mu` does not drift — an exercise you haven't trained in months keeps its last known
capacity as its best guess, but the growing variance means [pooling](04-muscle-pooling.md)
increasingly defers to its siblings' opinion instead. `qPerDay` is `fitted` on real history
(`BeliefConfig.qPerDay`; curve in the phase-2 plan appendix — main's old equivalent decay
rate was found to be roughly 16× too slow). `sigma2Floor` (±2%) and `sigma2Cap` (±50%) are
`flat` guards: sigma2 never fully collapses to zero certainty, and never inflates past a
ceiling once fully stale.

## Folding a set — a boundary-pull Gaussian update

Each set gives one exercise a (fatigue-shifted) implied ln(1RM) interval from
[the signal layer](02-strength-signal.md). `BeliefFold.fold` applies one Kalman update per
set, in set-id order, aging the belief to "now" first:

- **Inside the interval** (`mu` already lies within the shifted bounds): the set
  *confirms* what's already believed. `mu` is unchanged; `sigma2` shrinks exactly as a
  Gaussian fold at the nearer boundary would shrink it — confirmation still sharpens
  confidence even though it doesn't move the estimate.
- **Outside the interval**: `mu` is pulled toward the violated boundary by the standard
  Kalman gain `sigma2 / (sigma2 + obsSigma2)`, and `sigma2` shrinks by the same
  precision-weighted blend.

This is deliberately **symmetric up and down** — a strong RIR-5+ set pulls the belief up
exactly as hard as a failure pulls it down. There is no down-snap, no off-day damping, no
asymmetric weighting baked into the estimator. Immediate protection against re-prescribing
a weight you just failed is the policy layer's cap, not fold asymmetry (see
[muscle pooling](04-muscle-pooling.md)). The one observation-noise constant, `sigmaObs`, is
shared across all feedback buckets — re-confirmed `edge-pinned`/`saturated` at 0.005 on the
current history (the fitting sweep found the score flat and monotonically improving toward
the low edge of the tested range, not an interior optimum, so 0.005 is kept as the least
extreme point on that saturated edge rather than pushed further; see `BeliefConfig.sigmaObs`
kdoc and the phase-2 plan appendix for the sensitivity curve).

Every fold should be explainable in one gym sentence: "you left 2–4 reps in reserve at
20 kg for 5, so your ceiling is at least ~24 kg; we were below that, so we moved up toward
it."

## Pain is handled by policy, not the fold

HURT sets carry no implied interval (`SetIntervals.impliedLn1RmInterval` returns `null`
for them), so `BeliefFold.foldSession` simply skips them — a HURT row still counts toward
fatigue rank, but folds nothing. The muscle-wide backoff that pain used to apply directly
to the estimate now lives entirely in the policy layer's `hurtMultiplier`
(`PrescriptionPolicy.kt`) — semantic, prescription-time, and separate from belief
inference (see [muscle pooling](04-muscle-pooling.md)).

## Folding is local

`BeliefSessionStep.step` folds exercise *i*'s sets into exercise *i*'s belief only. A
failure on the barbell bench never reaches into the dumbbell fly's stored belief. That is
structural, not something the math has to be careful about — cross-informing between
exercises happens only at read time, as a non-destructive shrink toward the pooled level
([#4](04-muscle-pooling.md)).

## Override seeding

Manual baseline edits and detraining reductions write `ExerciseStrengthOverride` rows, and
those seed or reset the belief directly rather than folding through the set-log path:

- **Initial rows** (`sessionId == null`, e.g. a starting-weight seed): `Belief(ln(e1rm),
  sigmaSeed², asOf)`. `sigmaSeed` (±15%) is `semantic` — a seed is trusted as a rough
  starting guess, not hard evidence.
- **Per-session override rows** (a deliberate user edit, or a detraining-dialog
  reduction): `Belief(ln(e1rm), sigmaOverride², asOf)`, applied before that session's fold
  runs. `sigmaOverride` (±10%) is `semantic` — a little tighter than a cold seed, since the
  user is actively asserting a number.

Both constants are just priors: as soon as real sets are folded against them, the usual
boundary-pull update takes over.

## Output

An updated `Belief` per exercise the session touched, held in `ReplaySnapshot.currentBeliefs`
and — after a full replay — in `DerivedStateStore`'s `exerciseBeliefs()`. Everything
downstream (muscle level, prescriptions, charts, the "why this weight" trace) reads from
this one map; nothing else is durable.
