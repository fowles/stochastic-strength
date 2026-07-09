# Adaptation engines

The app personalizes itself over time. Two independent systems run on your logged
history: a **time estimator** that predicts how long a session will take, and a
**per-exercise progression system** that keeps each prescribed weight tracking your
real strength instead of a fixed plan.

| # | Engine | What it adapts | Source |
|---|--------|----------------|--------|
| 1 | [Time estimation](01-time-estimation.md) | How long each planned exercise (and the session) will take | `domain/ExercisePacingEstimator.kt`, `domain/DurationCalculator.kt` |
| 2 | [Strength signal](02-strength-signal.md) | Converts each set's feedback into a censored observation of ln(fresh 1RM) | `domain/progression/SetObservation.kt` |
| 3 | [Per-exercise beliefs](03-exercise-estimates.md) | The durable progression state — one Gaussian belief (μ, σ²) per exercise | `domain/progression/ExerciseBelief.kt`, `BeliefUpdater.kt` |
| 4 | [Read-time muscle pooling](04-muscle-pooling.md) | How a muscle's exercises cross-inform at prescription time (n_eff level + bridge shrink) | `domain/progression/MuscleStrengthProjector.kt` |
| 5 | [Prescription policy](05-prescription-policy.md) | Every training decision between the pooled belief and the weight on the bar | `domain/policy/PrescriptionPolicy.kt` |

## The shape of the progression system

The current system is **per-exercise, in log space, and Bayesian**:

- **The only durable state is one `ExerciseBelief` per loaded exercise** — a Gaussian
  belief `(mu, sigma2)` over ln(fresh 1RM, kg), where *fresh* means first-set
  pre-fatigue capacity. There is no stored baseline and no stored coefficient; the
  per-muscle display level and derived coefficients are *projections* recomputed on
  demand, held in the in-memory `DerivedStateStore`, never persisted as truth.
- **Writing is local; cross-informing is read-time.** Folding a session updates only
  the exercises you trained ([#3](03-exercise-estimates.md)). A failure on one lift can
  never, by construction, move a sibling's stored belief. Exercises only learn from
  each other when a weight is *read* ([#4](04-muscle-pooling.md)), where the sibling
  pool can shrink a cold or stale belief toward what the muscle implies.
- **Estimation and prescription are separate.** The belief is updated only by what the
  data says — no policy bias. Every training decision (shading, overload push, fatigue
  discount, failure ceiling, HURT caution) lives in `PrescriptionPolicy`
  ([#5](05-prescription-policy.md)).

## How a session becomes next session's weight

1. **Observe** ([#2](02-strength-signal.md)). Each working set becomes a censored
   observation of ln(fresh 1RM) — an interval, one-sided bound, or Gaussian point
   depending on feedback. HURT sets produce no load observation (they become policy
   events). Sets are processed in set order, with a fresh-basis shift for cumulative
   fatigue.
2. **Fold** ([#3](03-exercise-estimates.md)). Each observation is folded into the
   exercise's belief via a Bayesian update (Kalman step for counted failures; truncated-
   Gaussian moment match for the RIR buckets). The belief ages between sets: variance
   grows by process noise q, and the belief mean drifts down after the muscle's grace
   period for long inactivity (automatic detraining). Folding is local — siblings are
   untouched.
3. **Pool** ([#4](04-muscle-pooling.md)). At read time, the muscle's exercises vote a
   **level** weighted by n_eff (precision above the seed floor). Each exercise's belief
   mean is bridge-shrunk toward the level's prediction by kappa, capped so that fresh
   own evidence is barely moved. This yields the pooled effective 1RM and pooled σ̃.
4. **Prescribe** ([#5](05-prescription-policy.md)). `PrescriptionPolicy` applies:
   base target μ̃ − z·σ̃ + δ + ln(1 − φ·(S−1)); then failure ceiling with adjudicated
   round-down; then HURT decay multiplier; then grid rounding.

## Replay is the source of truth

The belief map is rebuilt from scratch every time history changes:
`WorkoutRepository.replayDerivedState()` replays every completed session in order
through `applySessionProgression` (idempotent), then re-projects. Manual weight overrides
are written as per-exercise `ExerciseStrengthOverride` rows and folded in during the same
replay as belief resets at `sigmaOverride = 0.10`. Policy state (ceilings, HURT events,
muscle stress) is also assembled during replay and held in `DerivedStateStore`.

## Design background

- Phase-2 belief + policy reframe (current living design): `docs/superpowers/specs/2026-07-06-belief-policy-reframe-design.md`
- Per-exercise estimate progression (historical): `docs/superpowers/specs/2026-06-21-per-exercise-estimate-progression-design.md`
- Read-time seed-vote pooling (historical): `docs/superpowers/specs/2026-06-23-seed-vote-projector-design.md`
- The earlier common/differential controller: `docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md` (historical)
