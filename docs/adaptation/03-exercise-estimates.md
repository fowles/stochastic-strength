# Per-exercise estimates — the durable progression state

Source: `domain/progression/ExerciseEstimate.kt`, `domain/progression/ExerciseEstimateUpdater.kt`
Tuning: `EstimatorConfig` (pinned by `ExerciseEstimatorSimulationTest`)
Applied by: `WorkoutRepository.applySessionProgression`

The **only durable progression state** is one `ExerciseEstimate` per loaded exercise:

```
ExerciseEstimate(lnE, confidence, updatedAt)
```

`lnE` is the natural log of the estimated 1RM in kg (everything lives in log space, so a
fixed percentage move is a fixed additive step). `confidence` is a recency-decayed
effective sample size — it grows as sessions are folded in and decays with staleness, so
a long-unseen exercise quietly hands authority to its siblings at read time
([#4](04-muscle-pooling.md)). There is no stored baseline and no stored coefficient.

A brand-new exercise is `seed`ed at its starting 1RM with `confidence = 0`.

## Folding a session into the estimate

Each session gives one exercise an observed implied 1RM and a bracket confidence (from
[the signal layer](02-strength-signal.md)). `ExerciseEstimateUpdater.fold` blends that
observation into the stored estimate as a **confidence-weighted log-space average**:

```
c   = decayedConfidence(prior, now)          // prior confidence, aged to now
lnE = (c · prior.lnE + w · ln(obsE1rm)) / (c + w)
confidence = min(c + w, confidenceCap)
```

The observation weight `w` is **asymmetric**, and this asymmetry is the heart of the
controller:

- **Up-signal** (observation above the current estimate): small weight `wUp = 1.48`.
  Success nudges the estimate up gently — progressive overload that creeps rather than
  jumps.
- **Down-signal** (observation below the current estimate): larger weight, interpolated
  by bracket confidence from `wDown = 3.0` toward `wDownSnap = 8.0`. A failure pulls the
  estimate down fast, and a *demonstrated* drop-cascade (high bracket confidence) snaps
  it down nearly all the way — so a weight you just failed is not prescribed again next
  session.

Because `confidence` is capped (`confidenceCap = 6`), a long-trained exercise keeps a
floor learning rate: the fold behaves like an EMA that can always still move, rather than
freezing once it has seen many sessions.

### Decay

Confidence decays on a **~21-day half-life** (`halfLifeMs`). `decayedConfidence` ages the
prior confidence to "now" before every fold and every read, so evidence from months ago
counts for little and a stale exercise naturally defers to fresher siblings.

## Pain never touches the estimate

HURT carries no load signal and — since the belief+policy reframe (phase 1) — no longer
alters any estimate. It is recorded as a muscle-level policy event during replay and applied
at prescription time as a decaying caution multiplier (×(1 − 0.15) immediately, healing with
a ~2-week half-life, floored). See `domain/policy/PrescriptionPolicy.kt` and
`docs/superpowers/specs/2026-07-06-belief-policy-reframe-design.md` §4.

## Why folding is local

A fold for exercise *i* touches only *i*'s estimate. A failure on the
barbell bench never reaches into the dumbbell fly's stored number. This makes the
"a failure must not corrupt siblings" guarantee **structural** rather than something the
math has to be careful about. Sharing strength between an exercise and its siblings is
deferred entirely to read time, where it is a non-destructive shrink
([#4](04-muscle-pooling.md)).

## Output

An updated `ExerciseEstimate` per trained exercise, written back into the estimate map
that `replayDerivedState` rebuilds and `MuscleStrengthProjector` reads.
