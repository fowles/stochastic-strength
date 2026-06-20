# Time estimation — how long a workout will take

Source: `domain/ExercisePacingEstimator.kt`, `domain/DurationCalculator.kt`
Wired in: `domain/WorkoutRepository.kt` (builds the estimator), `domain/WorkoutPlanner.kt` (stamps the estimate)

The goal is to predict, before you start, how many minutes each planned exercise
(and therefore the whole session) will take, and to make that prediction personal
to you rather than generic. It is split into two pieces: a **learner**
(`ExercisePacingEstimator`) that figures out *your* pace from history, and a
**calculator** (`DurationCalculator`) that turns that pace into a time estimate
for a specific planned exercise.

## The learner — `ExercisePacingEstimator`

It answers one question per exercise: "how many seconds does this person actually
spend per rep?" It builds this by walking back through your recent sessions,
newest first, and for each exercise it looks at the gaps between consecutive sets.
The time from finishing one set to finishing the next, minus the standard rest
period, is treated as the working time of that set; dividing by the reps performed
gives a seconds-per-rep figure. It does this within each appearance of the
exercise, averages those, and then averages across appearances.

A fair amount of hygiene keeps the estimate from being polluted:

- It only trusts the most recent handful of appearances of each exercise (capped
  at ten), so your current pace dominates over ancient history.
- It skips timed exercises entirely (planks and the like — reps aren't the right
  unit there).
- For single-limb (unilateral) exercises it doubles the effective rep count, since
  you do both sides.
- It throws away any per-rep figure that's implausibly fast or slow (outside
  roughly 1–30 seconds per rep) — those usually mean you got distracted, took a
  phone call, or the timestamps are junk.
- It ignores any set pair where either set was flagged as painful, since those
  aren't representative of normal pacing.

If it has never seen an exercise, it simply has no opinion and returns nothing.

## The calculator — `DurationCalculator`

It takes a single planned exercise and assembles a total time from parts:

- the working time of all the sets (per-rep pace × reps × number of sides × sets),
- plus the rest periods between them,
- plus the warmup sets (their own work and shorter rest periods),
- plus a fixed allowance for changing the weights — which depends on the
  equipment, since loading a barbell takes much longer than nudging a cable pin or
  grabbing dumbbells.

If the learner had no personal pace for this exercise, it falls back to a default
assumption of three seconds per rep. Timed exercises are handled separately, where
the "reps" are really seconds of holding.

## Wiring

The repository builds a fresh pacing estimator from recent history whenever it
constructs a planner. The planner then asks the estimator for each exercise's pace
and feeds it to the calculator to stamp an estimated duration onto every planned
exercise.
