# Adaptation engines

The app personalizes itself over time. Two independent systems run on your logged
history: a **time estimator** that predicts how long a session will take, and a
**per-exercise progression system** that keeps each prescribed weight tracking your
real strength instead of a fixed plan.

| # | Engine | What it adapts | Source |
|---|--------|----------------|--------|
| 1 | [Time estimation](01-time-estimation.md) | How long each planned exercise (and the session) will take | `domain/ExercisePacingEstimator.kt`, `domain/DurationCalculator.kt` |
| 2 | [Strength signal](02-strength-signal.md) | Turns a session's set feedback into one implied 1RM per exercise | `domain/SessionSignalExtractor.kt` |
| 3 | [Per-exercise estimates](03-exercise-estimates.md) | The durable progression state — one log-space 1RM estimate per exercise | `domain/progression/ExerciseEstimate.kt`, `ExerciseEstimateUpdater.kt` |
| 4 | [Read-time muscle pooling](04-muscle-pooling.md) | How a muscle's exercises cross-inform at prescription time (level + shrink) | `domain/progression/MuscleStrengthProjector.kt` |

## The shape of the progression system

The older design carried two coupled numbers per muscle — a **baseline** and a set of
per-exercise **coefficients** — and adjusted them together with one gauge-conserving
controller. That has been replaced. The current system is **per-exercise and in log
space**:

- **The only durable state is one `ExerciseEstimate` per loaded exercise** — `lnE`
  (ln of estimated 1RM, in kg) plus a recency-decayed `confidence`. There is no stored
  baseline and no stored coefficient; the per-muscle display level and the derived
  coefficients are *projections* recomputed on demand, held in the in-memory
  `DerivedStateStore`, never persisted as truth.
- **Writing is local; cross-informing is read-time.** Folding a session updates only
  the exercises you trained ([#3](03-exercise-estimates.md)). A failure on one lift can
  never, by construction, move a sibling's stored estimate. Exercises only learn from
  each other when a weight is *read* ([#4](04-muscle-pooling.md)), where a confident
  sibling pool can shrink a cold or stale estimate toward what the muscle implies.

## How a session becomes next session's weight

1. **Signal** ([#2](02-strength-signal.md)). Each exercise's sets — based on how they
   felt (reps-in-reserve, too-hard with/without a measured rep count, pain) — collapse
   to a single implied 1RM for that exercise this session.
2. **Fold** ([#3](03-exercise-estimates.md)). That observation is folded into the
   exercise's stored estimate as a log-space EMA. Up-signals get a small weight (gentle
   progressive overload); down-signals get a larger one (a just-failed weight is not
   re-prescribed). Pain backs the estimate off multiplicatively. Confidence accumulates
   and decays with a ~21-day half-life.
3. **Project** ([#4](04-muscle-pooling.md)). When a weight is needed, the muscle's
   confident exercises vote a **level**, each exercise is predicted from that level via
   its seed coefficient, and its own estimate is shrunk toward that prediction by
   confidence. The result is the exercise's projected effective 1RM, which the planner
   scales to the session's rep target.

## Replay is the source of truth

The estimate map is rebuilt from scratch every time history changes:
`WorkoutRepository.replayDerivedState()` replays every completed session in order
through `applySessionProgression` (idempotent), then re-projects. Manual baseline edits
and detraining reductions are written as per-exercise `ExerciseStrengthOverride` rows
and folded in during the same replay.

## Design background

- Per-exercise estimate progression: `docs/superpowers/specs/2026-06-21-per-exercise-estimate-progression-design.md`
- Read-time seed-vote pooling: `docs/superpowers/specs/2026-06-23-seed-vote-projector-design.md`
- The earlier common/differential controller it replaced: `docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md` (historical)
