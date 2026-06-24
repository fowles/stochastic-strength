# Strength signal — feedback to one implied 1RM per exercise

Source: `domain/SessionSignalExtractor.kt`
Consumed by: the per-exercise [estimate fold](03-exercise-estimates.md), via
`WorkoutRepository.applySessionProgression`

Before anything can adapt, a session's raw set logs have to become a single number per
exercise: "given how those sets actually went, what does your one-rep max for this lift
look like right now?" That is the job of `SessionSignalExtractor.aggregateSession` — it
turns one exercise's sets into a `SessionAggregate` (an implied 1RM plus a confidence,
and occasionally a *bracket confidence* flag). This signal layer is shared by every
exercise; the progression system never reads raw feedback directly.

## Each set becomes a signed rep deviation

The planner prescribes the weight you should be able to do for exactly `targetReps`
reps, so the feedback is read as a deviation from that target — how many reps you had in
reserve (positive) or fell short by (negative):

| Feedback | Reserve / shortfall | Confidence | Failure? |
| --- | --- | --- | --- |
| **RIR 5+** ("lots left") | +6 reps | 0.40 | no |
| **RIR 2–4** ("a couple left") | +3 reps | 0.70 | no |
| **RIR 0–1** ("almost nothing left") | +0.5 reps | 0.85 | no |
| **Too hard** (with measured reps) | actual − target (signed) | 0.95 | yes |
| **Too hard** (no rep count) | −target/2 (a guess) | 0.95 | yes |
| **Hurt** | — discarded — | — | — |

Note that hitting the target effort exactly (**RIR 0–1**) still reads as a small *up*
signal (+0.5 reps). That is deliberate: it is the mechanism behind gentle progressive
overload — a clean target-effort set nudges the estimate up by about half a rep's worth,
not zero. (See [per-exercise estimates](03-exercise-estimates.md).)

## Aggregating a session's sets

Only the **full-weight** sets carry the capacity signal (the heaviest weight used that
exercise; lighter drop sets are handled by the bracket path below). Across those sets:

- They are combined with a **recency EMA** (`RECENCY_BETA = 0.88`) ordered by set number,
  so the **last, most-fatigued set dominates** — the estimate tracks last-set capacity
  rather than a flattering multi-set average.
- **Any full-weight failure caps the session at zero deviation.** A session that
  contains a missed rep at the top weight can never *grow* the estimate, only hold or
  shrink it.

The resulting rep deviation is added to `targetReps` and run through
`DefaultProgressionEngine.rawToOneRepMax(weight, effectiveReps)` to get `est1RM`. Session
confidence is the max confidence of the contributing sets.

## The bracket path — when a top-weight failure forced a drop

If you failed at the top weight **and** then completed lighter sets, `aggregateSession`
switches to `bracketAggregate`, which estimates capacity from the heaviest *completed*
set (capacity you actually demonstrated), capped from above by the failed weight's
target-rep 1RM (a failed weight proves you are below it). This case carries a high
`bracketConfidence` (0.95) — a demonstrated drop-cascade is strong evidence — which the
fold uses to snap the estimate down harder (see [#3](03-exercise-estimates.md)). If every
set failed, it estimates from the lightest failed set's achieved reps.

## What carries no signal

- **Pain (HURT)** is removed here — it carries no load information. It is handled
  separately and muscle-wide by the estimate fold.
- **Reduced-weight drop sets** outside the bracket case contribute nothing; the failure
  that triggered the drop is itself a full-weight `TOO_HARD` set and is already captured.
- **Unloadable exercises** (zero/null seed coefficient — bodyweight, bands, wall-sits)
  are skipped by the caller before this layer runs.

## Output

One `SessionAggregate(est1RM, sessionConfidence, bracketConfidence)` per exercise, or
`null` if the session produced no usable full-weight signal.
