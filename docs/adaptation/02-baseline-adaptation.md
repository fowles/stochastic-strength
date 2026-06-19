# Per–muscle-group baseline adaptation (the common mode)

Source: `domain/ProgressionController.kt` (`RollingConservingProgressionController`), `domain/SessionSignalExtractor.kt`
Design note: `docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md`
Applied by: `WorkoutRepository.applySessionProgression`

Each muscle group carries a single "baseline" number that represents roughly how
strong you are for that muscle right now. Every exercise's working weight is derived
from its muscle's baseline. This is the **common mode** of the one progression
controller — the half that answers "did this whole muscle feel off in the same
direction?" — and its job is to nudge the baseline up or down after each session so
the app tracks your real strength instead of a fixed plan.

It runs once per session, considering each trained muscle independently.

## From feedback to an "innovation"

First, each exercise's sets are turned into an estimated one-rep max by
`SessionSignalExtractor` (the same signal layer described in
[coefficient adaptation](03-coefficient-estimation.md)). Comparing that estimate to
the weight we actually prescribed gives a per-exercise **innovation** — a small
positive number if the lift was easier than expected (we under-prescribed), negative
if it was harder. In log space:

```
innovation_i = ln( impliedOneRepMax_i / (baseline × coefficient_i) )
```

## The common mode moves the baseline

For each muscle the controller takes a **confidence- and recency-weighted average**
of its exercises' innovations — the part they all agree on. That shared average is
the common mode, and the baseline moves a fraction of it:

```
Δ(log baseline) = K_b × common          (K_b = 0.5)
```

The move is then capped (no more than ~15% in a single session, `maxLogStepB =
ln(1.15)`) and snapped to the equipment grid (2.5 kg / 5 lb). If everything felt
easy, the average is positive and the baseline rises; if everything felt hard, it
falls.

Crucially, the average is taken over the muscle's **recent window**, not only the
exercises you trained today — see [coefficient adaptation](03-coefficient-estimation.md)
for how that pool is built. Because the planner favors variety, most muscles see only
one exercise on a given day; pooling the recent window gives a stable, well-averaged
baseline signal even from a single-exercise session.

## Pain trumps everything

If any set for a muscle was flagged as having hurt, the controller ignores the normal
logic and simply knocks that muscle's baseline down by a fixed fraction (×0.85,
`hurtFactor`), and makes **no** coefficient changes for that muscle that session.
Pain is muscle-level and coefficient-independent: any painful set, even on an
unloaded movement, backs the baseline off. Safety first.

## Built-in progressive overload

A subtle but important property: the baseline keeps creeping up while you're
succeeding, with **no** separate "add x% per week" rule bolted on. The signal
extractor reads even an at-capacity set (reps-in-reserve 0–1) as implying about one
more rep in the tank, so a *successful* set still yields a small positive innovation.
The common mode nudges the baseline up until you genuinely start missing reps, at
which point the negative innovation pulls it back. The estimator therefore hovers
right at the edge of failure — progressive overload falls out of the math rather than
being a bolted-on policy.

## What feeds in, and what doesn't

- **Unloadable exercises stay silent.** Bodyweight, banded, or wall-sit movements
  (coefficient 0) have no load tie to the baseline, so they produce no innovation in
  either direction.
- **Mid-session weight drops need no special case.** If you reduced the weight partway
  through an exercise, those lighter sets simply read as a lower implied one-rep max →
  a negative innovation → a downward pull, handled organically. (The old design had a
  separate "reduction clamp"; it is gone — negative innovation, the per-session cap,
  and the recency smoothing do the job.)

## Output

A proposed new baseline per muscle (with a short note — `hurt`, or the common-mode
value and pool size), which the repository persists and logs as a `BaselineHistory`
row.
