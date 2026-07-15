# Strength signal — a set becomes an implied ln(1RM) interval

Source: `domain/policy/SetIntervals.kt`, fatigue shift in `domain/belief/BeliefFold.kt`
Consumed by: the per-exercise [belief fold](03-exercise-estimates.md), via
`BeliefSessionStep.step`

Before anything can adapt, one logged set has to become a piece of evidence: "given how
that set actually went, what does it say about your fresh one-rep max for this lift right
now?" Unlike the old design, there is no per-session aggregation step — **every set is its
own piece of feedback**, folded into the belief individually and in set-id order.
`SetIntervals.impliedLn1RmInterval` is the shared translation, used identically by the
belief fold and by the held-out backtest metric that scores it, so a stack can't game its
own score with its own modeling assumptions.

## Each set implies an interval, not a point

The planner prescribes the weight you should be able to do for exactly `targetReps` reps.
Feedback is read as a bound (or a pair of bounds) on ln(1RM), using only the load-aware
rep-max formula and the reported bucket — no fatigue correction, no belief concepts yet:

| Feedback | Implied ln(1RM) interval at weight `w`, target reps `r` |
| --- | --- |
| **Too hard**, actual reps `a` known | `[1RM(w, a+0.5), 1RM(w, a+1)]` — narrow band around the failure |
| **Too hard**, no rep count | `(−∞, 1RM(w, r)]` — you're somewhere at or below the target-rep max |
| **RIR 0–1** ("almost nothing left") | `[1RM(w, r), 1RM(w, r+2)]` |
| **RIR 2–4** ("a couple left") | `[1RM(w, r+2), 1RM(w, r+5)]` |
| **RIR 5+** ("lots left") | `[1RM(w, r+5), ∞)` — unbounded above |
| **Hurt** / no feedback | no interval — carries no load information |

A belief that already sits inside the interval is **confirmed** by the set (unchanged
mean, tighter variance); a belief outside the interval gets pulled toward the boundary it
violates. See [the fold](03-exercise-estimates.md) for the mechanics. This is symmetric:
a strong, RIR-5+ set pulls the belief up exactly as hard as a failure pulls it down — there
is no down-snap or off-day damping in the estimator itself. (Weight *creeping back up* to a
just-failed number is prevented separately, by the policy cap — see
[muscle pooling](04-muscle-pooling.md).)

## The fatigue shift — later sets in a session mean less fresh capacity

A session's sets are not independent readings of the same fresh 1RM: set 3 of 3 is
performed more fatigued than set 1. Each exercise's rows are ranked 1-based by set id
within the session — **every row counts toward the rank, including HURT and
feedback-less rows** — and set *k*'s implied interval is shifted **up** before folding by

```
shift(k) = −ln(1 − phi·(k−1))
```

so a later, more-fatigued set is read as implying a *higher* fresh capacity than its raw
numbers alone would suggest (a heavier "true" 1RM is consistent with managing that weight
after prior fatigue). `phi` is the one fatigue constant — `fitted` on real history (see
`BeliefConfig.phi`, curve recorded in the phase-2 plan appendix). The shift is capped so it
stays finite (`phi·(k−1)` clamped below 0.9 before the log).

## What carries no signal

- **Pain (HURT)** implies no interval — `SetIntervals.impliedLn1RmInterval` returns `null`.
  It is handled separately and muscle-wide by the policy layer's HURT backoff, not by the
  belief itself.
- **Unloadable exercises** (zero/null seed coefficient — bodyweight, bands, wall-sits) are
  skipped by the caller (`BeliefSessionStep`) before this layer runs.
- A set with no weight (`targetWeight <= 0`) implies no interval either.

## Output

`LnInterval(lowerLn, upperLn)` — either bound may be `null` (unbounded on that side) — plus
the fatigue-shifted rank used to fold it. There is no separate aggregate step: each
interval is folded into the belief directly, one set at a time, in the order the sets were
logged.
