# Per-exercise coefficient adaptation (the differential mode)

Source: `domain/ProgressionController.kt` (`RollingConservingProgressionController`), `domain/SessionSignalExtractor.kt`
Design note: `docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md`
Applied by: `WorkoutRepository.applySessionProgression`

A coefficient is the per-exercise multiplier that bridges the muscle baseline to a
specific lift. The baseline says "this muscle is worth roughly *this* much"; the
coefficient says "but for *this particular exercise* you're stronger or weaker than
that by some factor" — a barbell press and a dumbbell fly both draw on the same chest
baseline, but at very different absolute weights. This is the **differential mode** of
the progression controller — the half that answers "was this lift off *relative to its
siblings* in the same muscle?" — and it slowly learns each exercise's true coefficient
rather than trusting the seeded starting guess forever.

## Step 1 — turn each set into a strength estimate (`SessionSignalExtractor`)

Every completed set, based on how it felt, becomes a guess at your one-rep max for
that exercise, tagged with a confidence and a couple of flags:

- **"Lots left in the tank"** (RIR 5+) is a weak, low-confidence estimate — the engine
  has to extrapolate far.
- **"A couple reps left"** (RIR 2–4) is moderate confidence.
- **"Almost nothing left"** (RIR 0–1) is fairly high confidence.
- **"Too hard, and here's how many reps I actually got"** is the strongest, most
  trustworthy signal — a *measured* near-failure.
- **"Too hard but no rep count recorded"** is treated only as an *upper bound* — you're
  no stronger than this, but we don't know exactly how strong.
- **Pain** is discarded entirely.

Within a session those per-set estimates are confidence-weighted into one estimated
one-rep max per exercise (`aggregateSession`). The upper-bound signals are dropped if
the measured signals already point higher, so a fuzzy ceiling can't drag down a
concrete reading. (This whole signal layer is shared with the baseline's
[common mode](02-baseline-adaptation.md).)

## Step 2 — a smoothed estimate per exercise (the EMA filter)

Each exercise keeps a recency-decayed running average — an exponential moving average,
`emaBeta = 0.5` — of its log implied one-rep max, plus when it was last seen and with
what confidence. This is the measurement filter: it smooths out a single freak session
so one odd reading can't whip the coefficient around.

## Step 3 — the muscle's recent pool

When a muscle is trained, the controller gathers **every loaded exercise in that
muscle that has a recent measurement**, each weighted by recency (21-day half-life
since you last performed it) × the confidence of that measurement. Exercises you've
never performed — or done so long ago their weight has decayed below a tiny floor —
sit out. Unloadable (coefficient-0) exercises are excluded; they have no load
relationship to compare. This pool is the comparison set.

This pooling is what lets a **lone exercise still learn.** The planner favors variety,
so most muscles see only one exercise on a given day; comparing it against the muscle's
*recent pool* rather than only today's sets means there are always siblings to measure
against.

## Step 4 — the differential moves each coefficient

For each pooled exercise the innovation is `e_i = ln(emaImplied1RM_i / (baseline ×
coef_i))`. The controller subtracts the pool's weighted-average innovation — the same
**common mode** that drove the baseline — to get each exercise's **differential**:

```
differential_i = e_i − common
```

That differential is the part specific to the exercise: "easier or harder than its
siblings." Each coefficient moves a fraction of it:

```
Δ(log coef_i) = K_c × (w_i / w_max) × (e_i − common)      (K_c = 0.5)
```

The per-exercise factor `w_i / w_max` gives the freshest exercise full gain and staler
ones proportionally less. The move is capped at ~10% per session (`maxLogStepC =
ln(1.10)`), and moves smaller than 0.2% (`minRelativeChange`) are skipped to avoid
churn. Coefficients therefore ease toward their learned values over many sessions
rather than snapping.

Because the comparison is **relative**, a wrong baseline is invisible: if a muscle's
baseline is off, every innovation shifts by the same amount, the common mode absorbs
it, and the differentials are unchanged. The baseline's own level is handled by the
[common mode](02-baseline-adaptation.md); the coefficients only ever carry relative
shape.

## A note on which coefficients move

The differential is applied to **all** the pooled exercises — not just the one you
trained today — each scaled by its own weight. That is deliberate and load-bearing: it
is exactly what makes the coefficient scale conserved, so no separate renormalization
pass is needed. See [gauge conservation](04-gauge-conservation.md).

## Output

A set of updated coefficients (with notes on why), which feed into how every future
session's weights are computed for those exercises.
