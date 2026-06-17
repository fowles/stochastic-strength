# Last-Set Autoregulation Baseline Controller

**Date:** 2026-06-17
**Status:** Design — pending implementation plan

## Problem

The current baseline adaptation heuristic (`EstBaselineConsensusHeuristic`) tries to
*estimate the user's true 1RM* per muscle and chase it. It does this by converting each
working set into an implied 1RM via a load-aware formula, weighting each by a
"confidence" score, and taking a confidence-weighted mean.

This has a systematic **downward bias in high-rep, multi-set sessions**:

- Under the app's "same weight for all working sets" rule, later sets are harder purely
  from accumulated fatigue. Their implied 1RM is lower — not because the user got weaker,
  but because they are fatigued.
- The `confidence` score conflates two distinct things: *measurement precision* (how
  tightly a set pins its own reps-to-failure) and *representativeness* (how much a set
  tells us about the **rested** 1RM). A fatigued set taken to failure has the **highest**
  confidence (0.95) because reps-to-failure is known exactly — but it is a precise
  measurement of the *wrong* quantity.
- Upward signals ("RIR 5+", "RIR 2-4") carry low confidence (0.4, 0.7) because reserve is
  genuinely vague, so they get swamped by the high-confidence fatigued-failure signals.

Net effect: normal within-session fatigue drags the baseline down, fighting the app's
core goal of increasing strength over time. The effect is worst exactly in the high-rep
regime, where fatigue accumulation is largest and failures most common.

## Reframe: a controller, not an estimator

The app never needs to *output* a 1RM. Given the core principles —

1. **Simple for the user:** same reps for every exercise in a workout, same weight for all
   working sets, no choices during the workout.
2. **Avoid injury:** don't jump weight too quickly; back off on pain signals.
3. **Increase strength (and weight) over time.**

— the only decision per muscle per session is: **should next session's weight go up,
stay, or down, and by how much?** That is a *control* problem.

Crucially, under "same weight for all working sets," **later sets being harder is the
correct shape of a well-chosen weight**, not evidence the weight is too heavy. A good
session is "early sets had reps in reserve, last set was near failure." The estimator
misreads that normal fatigue curve as weakness. The controller treats it as success.

This is standard **double-progression / RIR autoregulation**, keyed on the **last working
set** — the honest ceiling under fatigue.

### Scope of the change

- **Replaced:** the implied-1RM aggregation and confidence weighting inside the baseline
  heuristic, *and* its oscillation-based cap-scaling safety layer (that machinery existed
  to tame a noisy estimator the controller no longer uses).
- **Kept unchanged:**
  - The 1RM load formula for **rep-scaling** the baseline between rep targets (5/8/10 or
    a user range) when planning a session. Different job; stays.
  - The per-exercise **coefficient** system (`EstCoeff`). It handles the *differential*
    signal (which exercise is relatively harder); the baseline controller handles only the
    *common mode*.
  - The **mid-session weight-reduction** mechanism and its `minReductionFractions` clamp.
  - The **HURT** back-off (`hurtFactor = 0.85`).
  - The `BaselineHeuristic` interface and `BaselineComputationInput` (it already carries
    everything the controller needs: per-set rows, `exerciseMuscle`, `currentBaselines`,
    `minReductionFractions`, `sessionReps`, `weightUnit`, `asOf`).

The controller is **purely additive to what already works**: it adds disciplined upward
creep on clean sessions and leaves the (well-tuned) reduction path alone.

## Controller specification

Per muscle, the controller looks at the most recent session's working sets for each
exercise targeting that muscle, computes a per-exercise log-delta, averages them, then
applies the existing reduction clamp.

### 1. Identifying the governing set

For each exercise in the session targeting the muscle:

- **Warmup sets are excluded.** Only working sets count.
- The **full session weight** is the first working set's `targetWeight` (the originally
  planned load, before any mid-session drop).
- **Detect a mid-session reduction:** the exercise was reduced if any working set's
  `targetWeight` is below the full session weight (the drop lowered later sets).
- The **governing set** is the **last working set performed at the full session weight.**

### 2. Per-exercise signal → log-delta

If the exercise **was reduced mid-session**, it contributes **no up-signal**. Its entire
downward story is told by the `minReductionFractions` clamp (step 4). The reduced-weight
sets are ignored for the increase/hold decision.

Otherwise, map the governing set's feedback to a log-delta (`nearMiss = 1` rep default):

| Governing set | Meaning | Log-delta (default) |
|---|---|---|
| RIR 5+ | way too light | **+5.0%** (big) |
| RIR 2-4 | a bit light | **+2.5%** (moderate) |
| RIR 0-1 | just right | **+1.0%** (tiny creep) |
| Failed, `actualReps ≥ target − nearMiss` | close enough | **0** (hold) |
| Failed, `actualReps < target − nearMiss` | genuinely too heavy | **−5.0%** (small decrease) |
| Failed, `actualReps` unknown | ambiguous | **0** (hold — non-punitive; real drops handled by the clamp) |
| No usable feedback | — | exercise contributes no signal |

The percentages are the **primary tunable knob** and the main injury-vs-progress lever
(principles 2 and 3). They are applied in log space and the result is rounded to the
weight increment. Always-creep at RIR 0-1 keeps strength climbing but is self-limiting:
as weight creeps up, the last set eventually becomes a near-miss → hold → plateau until
the user genuinely gets stronger and clears the target again, then creep resumes.

### 3. Aggregation across exercises in the muscle

Average the contributing per-exercise log-deltas into one baseline log-delta (common
mode). **Exception:** a HURT on *any* contributing set forces the back-off
(`× hurtFactor`), overriding everything (pain is worst-wins — principle 2). When exercises
disagree on direction, that differential is `EstCoeff`'s job, not the baseline's.

If no exercise in the muscle produced a usable signal, the baseline is unchanged.

### 4. Reduction clamp (unchanged)

Apply the existing final clamp: `bNew ≤ bOld × (1 − minReductionFractions[muscle])`. In
any session where the muscle's load had to be dropped, this wins and the baseline cannot
creep up. The clamp is the authoritative downward mechanism for mid-session drops.

### 5. Rounding and no-op suppression

Round `bNew` to the weight increment. If `bNew == bOld`, emit no proposal (as today).

## Determinism / replay

The controller is a pure function of `(session working sets at full weight, current
baseline, minReductionFractions, weightUnit)`. No randomness, no wall-clock dependence
beyond `asOf` for history rows. It replays deterministically under
`replayDerivedState` / `recomputeDerivedState`. Existing `BaselineHistory` rows are
regenerated by replay; no migration of stored history is required (the heuristic output
is derived state, recomputed from the event log).

## Open risk to resolve during planning: `SeedNormalizer` interaction

`SeedNormalizer` re-attributes common-mode coefficient drift back into the baseline
(`m = Σ(c·s)/Σ(c²)`, 2 kg / 5 lbs threshold). `EstCoeff` still derives coefficients from
the fatigue-prone implied-1RM math. That fatigue bias is **common-mode** across an
exercise's sets, so it largely cancels in *relative* coefficients — but a uniform
downward bias in coefficients could be re-attributed by `SeedNormalizer` into a downward
baseline move, smuggling the old bias back in through the side door.

The implementation plan must verify this path and, if needed, gate `SeedNormalizer` so it
only rebalances coefficient-vs-baseline split without producing a net downward baseline
move from fatigue-driven common-mode drift. This is a known item, not yet decided.

## Testing

- **Signal table:** one test per row of the step-policy table (RIR 5+/2-4/0-1, near-miss
  hold, genuine-failure decrease, ambiguous-failure hold, no-feedback skip).
- **Self-limiting creep:** a sequence of clean RIR-0-1 sessions creeps up, then a
  near-miss holds — assert the plateau.
- **Fatigue no longer punishes:** the original failing scenario (e.g. target 10 →
  `13, 11, 9` across three sets, no drop) results in a **hold**, not a decrease.
- **Mid-session drop:** an exercise reduced mid-session yields a decrease bounded by the
  reduction fraction and **never** an increase, even when the reduced-weight last set hit
  target reps with reserve.
- **Aggregation:** multiple exercises in one muscle average; HURT on one forces back-off;
  disagreement leaves baseline near-flat (differential deferred to `EstCoeff`).
- **Replay determinism:** recompute over a fixed session log is stable and order-independent
  within a session.
- **Regression:** full unit suite (`./gradlew :app:testDebugUnitTest`); instrumented
  repository/replay tests.
