# Adaptation engines

The app personalizes itself over time. Two independent systems run on your logged
history: a **time estimator** that predicts how long a session will take, and an
**estimate-based progression system** that keeps each prescribed weight tracking your real
strength instead of a fixed plan. Everything below tracks *estimates* — a best guess plus
how sure we are of it — not measured truths.

| # | Engine | What it adapts | Source |
|---|--------|----------------|--------|
| 1 | [Time estimation](01-time-estimation.md) | How long each planned exercise (and the session) will take | `domain/ExercisePacingEstimator.kt`, `domain/DurationCalculator.kt` |
| 2 | [Strength signal](02-strength-signal.md) | Turns one logged set into an implied ln(1RM) interval plus a fatigue shift | `domain/policy/SetIntervals.kt`, `domain/belief/BeliefFold.kt` |
| 3 | [Per-exercise estimates](03-exercise-estimates.md) | The durable progression state — one `Belief(mu, sigma2)` per exercise (a best guess plus its uncertainty), folded set by set | `domain/belief/Belief.kt`, `BeliefFold.kt`, `BeliefSessionStep.kt` |
| 4 | [Muscle pooling + prescription + policy](04-muscle-pooling.md) | How a muscle's exercises cross-inform at read time, the success-chance prescription target, and the safety caps around it | `domain/belief/BeliefPooling.kt`, `BeliefPrescriber.kt`, `domain/policy/PrescriptionPolicy.kt` |

## The shape of the progression system

The progression system is **per-exercise, per-set, and in log space**:

- **The only durable state is one `Belief` per loaded exercise** — `mu` (the current best
  guess at ln fresh 1RM, kg) plus `sigma2` (how unsure we are of that guess, in ln-units²:
  tight means well-evidenced, wide means uncertain). There is no stored baseline and no
  stored coefficient; the per-muscle display level and the derived coefficients are
  *projections* recomputed on demand, held in the in-memory `DerivedStateStore`, never
  persisted as truth.
- **Every set is its own piece of feedback.** There is no per-session aggregation: each
  set folds into its exercise's estimate individually, in set-id order, with a fatigue
  shift that accounts for its position in the session.
- **Folding is local; cross-informing is read-time.** Folding a set updates only the
  exercise you trained ([#3](03-exercise-estimates.md)). A failure on one lift can never,
  by construction, move a sibling's stored estimate. Exercises only learn from each other
  when a weight is *read* ([#4](04-muscle-pooling.md)), where confidence-weighted pooling
  can shrink a cold or stale estimate toward what the muscle implies.
- **Estimation and safety are separate layers.** The estimate stack is scored, and folds,
  with no notion of caps or floors — it is free to move up or down symmetrically on the
  evidence. Safety (never re-prescribing a just-failed weight, backing off after pain,
  rest cooldowns) is a separate policy layer of plain arithmetic over the raw set log,
  applied after the belief produces its raw target.

## How a session becomes next session's weight

1. **Signal** ([#2](02-strength-signal.md)). Each set — based on how it felt (reps in
   reserve, too-hard with/without a measured rep count, pain) — becomes an implied
   ln(1RM) interval (or no interval, for pain), fatigue-shifted for how deep into the
   session it fell.
2. **Fold** ([#3](03-exercise-estimates.md)). Each set's interval is folded into the
   exercise's estimate as a censored (boundary-pull) update: confirmed sets sharpen
   confidence without moving the best guess; violated sets pull the best guess toward the
   boundary, symmetrically up or down. Uncertainty grows with idle time between sessions.
3. **Pool** ([#4](04-muscle-pooling.md)). When a weight is needed, the muscle's exercises
   vote a confidence-weighted **level**, each exercise is predicted from that level via its
   seed coefficient (leave-one-out), and its own estimate is blended toward that prediction
   by confidence. The result is the exercise's **effective estimate**, which the
   success-chance prescriber turns into a raw target.
4. **Cap** ([#4](04-muscle-pooling.md)). The raw target passes through the policy layer:
   a demonstrated-capacity cap (never re-prescribe above what the most recent session
   proved), a HURT backoff, and an overload nudge for clean sessions — all plain
   restatements of the set log, none of them tuned.

## Replay is the source of truth

The estimate map is rebuilt from scratch every time history changes:
`WorkoutRepository.replayDerivedState()` replays every completed session in order through
`BeliefSessionStep.step` (idempotent), then re-derives the muscle-level projections.
Manual baseline edits and detraining reductions are written as per-exercise
`ExerciseStrengthOverride` rows, which seed or reset an estimate directly and are folded in
during the same replay.

## Why this weight

Every prescription can be explained: `PrescriptionTraceBuilder` walks the same pipeline —
own estimate, sibling pull, effective estimate, success target, HURT backoff, overload
nudge, capacity cap, rounding — one plain gym sentence per stage, citing the sets or
numbers behind it, on the debug exercise-detail screen. (The on-screen stage labels still
read "Own belief"/"Effective belief" in code; the "Success target" line was renamed.)

## Design background

- Estimate-core design and the constant ledger (fitted/flat/semantic labeling discipline):
  `docs/superpowers/specs/2026-07-14-estimator-rebuild-design.md`
- The earlier per-exercise estimate + seed-vote projector design it replaced:
  `docs/superpowers/specs/2026-06-21-per-exercise-estimate-progression-design.md`,
  `docs/superpowers/specs/2026-06-23-seed-vote-projector-design.md` (historical)
- The common/differential PI controller before that:
  `docs/superpowers/specs/2026-06-18-common-differential-pi-controller-design.md`
  (historical)
