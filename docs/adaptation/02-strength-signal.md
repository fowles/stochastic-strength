# Strength signal — per-set observations of ln(fresh 1RM)

Source: `domain/progression/SetObservation.kt`
Consumed by: `domain/progression/SessionProgressionStepper` → `BeliefUpdater`, via
`WorkoutRepository.applySessionProgression`

Before anything can adapt, each working set must become a probabilistic observation of how
strong you are. `SetObservation.from(set, fatigueRank)` converts one logged set into an
observation of **ln(fresh 1RM)** — the log of your first-set, pre-fatigue one-rep max in
kg. The belief updater then folds these observations into the exercise's belief one by one,
in set order ([#3](03-exercise-estimates.md)).

## Fresh-basis shift

Beliefs are defined on **fresh capacity** — your 1RM on the very first set of the
exercise. Set k (1-indexed) happens under cumulative fatigue: capacity at set k equals
fresh × (1 − φ·(k−1)), where φ = 0.03 per set. Before folding, each set's bounds or
value are divided by (1 − φ·(k−1)) — in log space, shifted by −ln(1 − φ·(k−1)) — so
the observation speaks to fresh capacity rather than fatigued capacity. The φ·(k−1)
term is capped at 0.5 (`coerceAtMost(0.5f)`) so the divisor stays positive on very
long sets.

## Feedback → censored observation

Each feedback bucket constrains the set capacity to an interval on the log-1RM scale.
The rep→1RM formula `f(weight, reps)` converts those rep counts to log-1RM bounds:

| Feedback | Constraint on set capacity | Update type |
|---|---|---|
| TOO_HARD, actualReps = a | ln f(w, a+½) — tight point | Gaussian |
| TOO_HARD, no rep count | < ln f(w, r) — failed before target | one-sided upper bound |
| RIR_0_1 | ∈ [ln f(w, r), ln f(w, r+2)) | interval |
| RIR_2_4 | ∈ [ln f(w, r+2), ln f(w, r+5)) | interval |
| RIR_5_PLUS | ≥ ln f(w, r+5) | one-sided lower bound |
| HURT | none (policy event only) | skipped |

Sets with weight ≤ 0 or on zero/null-coefficient (unloadable) exercises carry no load
observation and are skipped.

**RIR_0_1** still carries a small lower bound (you completed the target reps) rather than
being a point observation — the uncertainty in how close to failure you actually were is
real, and the Tobit model handles it correctly.

## Observation noise

Noise is specified in rep units, converted through the local slope λ = ∂ln f(w, ρ)/∂ρ at
ρ = r (central difference, coerced to at least 1e-4). Rep-space standard deviation:

```
s_reps = √(base² + (ρ_rel · r)²)
```

where `base = repNoiseCounted = 0.5` for counted failures and `base = repNoiseBucket = 0.75`
for the RIR buckets and for an uncounted `TOO_HARD` (a one-sided upper bound); `ρ_rel = 0.06`
adds a rep-magnitude term. The log-1RM noise is then
`s = λ · s_reps`. At light absolute loads the slope λ is steeper, so accessory-lift
observations are automatically noisier — no special case needed.

## What carries no signal

- **HURT** sets return `null` from `SetObservation.from` — they carry no load
  information. The HURT event is collected separately as a muscle-level policy event and
  handled at prescription time ([#5](05-prescription-policy.md)).
- Mid-session drops (lighter sets after a failure) are ordinary observations at their own
  weights — there is no special bracket path. A failed set at the top weight produces a
  tight downward observation; the lighter sets that follow produce their own observations.
  All are folded sequentially.
- Zero/null-coefficient exercises are skipped by the caller; the projector's prescription
  for bodyweight or band exercises is undefined.

## What replaced the old signal layer

`SessionSignalExtractor.aggregateSession` (the recency EMA, the any-failure-caps-at-zero
rule, `bracketAggregate`, and `SessionAggregate`) is deleted. Per-set observations replace
the single per-session implied 1RM; the any-failure-caps-at-zero rule is now emergent —
a counted failure is a tight downward Gaussian observation and immediately pulls the
belief mean below the failed weight.

## Chart dots — impliedSessionE1rm

For display, `impliedSessionE1rm` computes "what did this session say" by folding the
session's observations into a broad-prior belief (σ² = 1 ≈ uninformative), independent
of history. This is the number plotted as chart dots on the progression chart; it shows
what the session itself said, independent of the post-fold posterior belief mean (which is
the own-estimate line). Phase 3/4 note: noise shape (repNoiseBucket/repNoiseCounted) is
a candidate fit parameter in phase 4.
