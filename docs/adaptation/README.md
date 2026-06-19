# Adaptation engines

The app personalizes itself over time. Two engines run on your logged history: a
**time estimator** that predicts how long a session will take, and a single
**progression controller** that keeps the prescribed weights tracking your real
strength instead of a fixed plan. The progression controller replaced an older
three-part stack — separate baseline, coefficient, and renormalization passes — and
now does all of that in one gauge-conserving loop.

| # | Engine | What it adapts | Source |
|---|--------|----------------|--------|
| 1 | [Time estimation](01-time-estimation.md) | How long each planned exercise (and the session) will take | `domain/ExercisePacingEstimator.kt`, `domain/DurationCalculator.kt` |
| 2 | [Baseline adaptation](02-baseline-adaptation.md) | The per–muscle-group baseline weight — the controller's **common mode** | `domain/ProgressionController.kt`, `domain/SessionSignalExtractor.kt` |
| 3 | [Coefficient adaptation](03-coefficient-estimation.md) | The per-exercise multiplier — the controller's **differential mode** | `domain/ProgressionController.kt`, `domain/SessionSignalExtractor.kt` |
| 4 | [Gauge conservation](04-gauge-conservation.md) | Why the coefficient scale never drifts — so no separate renormalization is needed | `domain/ProgressionController.kt` |

Engines #2, #3, and #4 are three views of **one** thing — `RollingConservingProgressionController`, invoked once per session by
`WorkoutRepository.applySessionProgression`. They are documented separately only because they answer different questions.

## How they relate

- **Baseline × coefficient** is the heart of the system: a muscle's baseline says
  "this muscle is worth roughly *this* much," and an exercise's coefficient says
  "but for *this particular* lift you're stronger/weaker by some factor." Their
  product, scaled to the session's rep target, is the weight you're prescribed.
- **One loop, two modes.** At session end the controller runs once per muscle. It
  turns each exercise's feedback into an **innovation** — the gap, in log space,
  between the weight we prescribed and the weight your performance implies — then
  splits that gap into two orthogonal parts:
  - the part *all* of the muscle's exercises agree on (the **common mode**) moves
    the **baseline** (#2);
  - the part specific to each exercise (the **differential mode**) moves that
    exercise's **coefficient** (#3).
- **The split conserves the gauge for free** (#4). Because the differential is
  "each exercise's gap minus the shared average," the coefficient changes always
  cancel to zero — the overall coefficient scale cannot drift. That is why there is
  no longer a separate renormalization step: it is built into the math.
- **They share one signal.** Both modes read the same per-set feedback
  (reps-in-reserve, too-hard with/without a rep count, pain), turned into an implied
  one-rep max by `SessionSignalExtractor`.
- **Time estimation (#1)** is independent of the strength loop — it learns pace from
  the timestamps between sets, not from feedback.

## Design background

The control-theory reframe and the rationale for collapsing three engines into one
are written up in
`docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md`.
