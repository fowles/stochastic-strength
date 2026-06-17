# Peer-consensus coefficient estimation

**Status:** Approved design, pending implementation plan
**Date:** 2026-06-17
**Component:** `domain/EstCoefConsensusHeuristic.kt` (a `CoefficientHeuristic`)
**Supersedes:** the Layer 4 "consensus guard" (group-drift veto + single-outlier
promotion + 5–10% dead band) and the per-exercise `minEvidenceWeight` gate.

## Problem

A coefficient is the per-exercise multiplier that bridges a muscle baseline to a
specific lift (`session weight = baseline × coefficient`, then scaled to the rep
target). The current `EstCoefConsensusHeuristic` learns coefficients by, per
exercise, dividing a recency-biased strength estimate by the *stored muscle
baseline*, then passing the result through a cross-exercise "consensus guard"
(`applyH2`). Three problems motivated this redesign:

1. **The consensus guard is fragile.** Its group-drift veto requires *unanimous
   sign*, and its single-outlier promotion requires *exactly one* >10% mover with
   *all* siblings calm (<5%). When two coefficients are wrong in the same
   direction they can trip the veto and suppress every exercise in the muscle —
   then renormalization mis-attributes the error to the baseline. A single sibling
   drifting in the 5–10% "dead band" silently blocks a genuine outlier's fast lane.

2. **The veto is redundant with renormalization.** `applyBaselineNormalization`
   (`SeedNormalizer`) runs on the very next step of `applySessionProgression` and
   already re-attributes systemic, whole-muscle coefficient drift back into the
   baseline (it removes the common scale `m = Σ(c·s)/Σ(c²)`, preserving coefficient
   *shape*). The veto second-guesses work renorm does cleanly.

3. **Per-exercise evidence is structurally starved.** `WorkoutGenerator` picks 6
   exercises per workout by uniform shuffle over a ~197-exercise library (max 2 per
   muscle), with no bias toward repeating prior exercises. A specific exercise
   recurs ~3% per workout, so the per-exercise `minEvidenceWeight = 1.5` gate
   (≈3 repeats of the *same* exercise within a ~1-month recency window) is almost
   never met. **The current engine is therefore already mostly dormant**, firing
   only via the single measured-failure bypass; coefficients sit at their seeds.

## Key insight

Per-exercise evidence is starved, but **cross-exercise** evidence is not. Over
~2–4 weeks a user accumulates one session each across several distinct exercises
per muscle (≈5–6 distinct chest exercises in a month, ≈11 glute, etc.). A
coefficient is an inherently *relative* quantity, so we should estimate it
*relative to its peers* rather than against the stored baseline. Robustness then
comes from a cross-exercise median plus heavy damping — not from per-exercise
repetition that never happens.

This reframes the variety planner from an obstacle into a feature: because we
cannot lean on repetition, we borrow strength across the muscle's exercises.

## Design

Replace the per-exercise "divide by stored baseline + consensus guard" with a
peer-consensus reference. The pipeline becomes:

```
setSignal → aggregateSession → per-exercise strength estimate E_i
          → peer-consensus reference B_others → damp
```

The stored baseline drops out of coefficient math entirely. It is still used to
prescribe weights (`baseline × coef`) and still maintained by the baseline
heuristic + renormalization.

### Layer 1 — `setSignal` (unchanged)

Each completed set becomes an est-1RM with a confidence and flags, per its
feedback (RIR_5_PLUS 0.40, RIR_2_4 0.70, RIR_0_1 0.85, TOO_HARD+reps 0.95
"definite", TOO_HARD no-reps 0.50 upper-bound, HURT discarded).

### Layer 2 — `aggregateSession` (unchanged)

Confidence-weight a session's set signals into one est-1RM, dropping upper-bound
signals when measured signals already point higher.

### Layer 3 — per-exercise strength estimate `E_i` (was `computeH1`)

Recency-weight (≈14-day half-life) the exercise's per-session est-1RMs into a
single `E_i` via a weighted median, with total evidence weight
`w_i = Σ recency·sessionConfidence`. **Changes from today:**

- **No `minEvidenceWeight` gate.** A single recent session yields a usable `E_i`.
- **No baseline division** — `E_i` is an absolute est-1RM, not a candidate
  coefficient.
- **No definite-bypass special-casing** (there is no gate to bypass; the measured
  failure's strength still flows through via its 0.95 set confidence).

### Layer 4 — peer-consensus reference (new; replaces `applyH2`)

Within each muscle, for each exercise *i*:

- **Peers** = the muscle's other exercises *j* with recent evidence
  (`w_j > peerWeightEpsilon`) and a positive current coefficient. Zero-coefficient
  (unloadable: bodyweight/banded/wall-sit) exercises are excluded — `E_j / c_j` is
  undefined for them — exactly as they are skipped today. An exercise with a
  zero coefficient also receives no proposal of its own.
- **Cold-start guard:** if fewer than `minPeers` (= 2) peers qualify, emit nothing
  — the coefficient stays at its current value. This is the *only* fallback; there
  is no stored-baseline reference.
- **Reference:** `B_others = weightedMedian over peers of (E_j / c_j)`, each peer
  weighted by `w_j`. `E_j / c_j` is "the muscle baseline implied by peer *j*"
  using its *current* coefficient.
- **Proposal:** `proposed c_i = E_i / B_others`.
- **Confidence:** `E_i`'s recency-weighted session confidence (today's
  `proposalConfidence`). Peer-support attenuation (scaling confidence down when
  total peer weight is thin) is a deferred refinement, added only if testing shows
  over-eager moves.

### Layer 5 — `damp` (unchanged)

Nudge the coefficient a fraction (`alpha`) of the way toward the proposal, scaled
by confidence, capped at `maxLogStep` per session, ignoring moves below
`minRelativeChange`.

## Why each robustness property holds — structurally

- **Systemic drift is invisible.** If the true baseline shifts, every `E_j` moves
  together, `B_others` moves with them, and the ratio `E_i / B_others` cancels the
  shared factor — coefficients don't budge. This replaces the group-drift veto with
  algebra. (The stored baseline's level is corrected separately by the baseline
  heuristic + renorm.)
- **Two wrong coefficients converge gracefully.** `B_others` excludes *i*; a median
  over ≥2 peers shrugs off one polluted peer. Both wrong exercises walk home; no
  freeze, no mis-attribution. (The original concern that motivated this work.)
- **No dead band, no outlier promotion** — those concepts no longer exist.
- **Per-session noise is damped.** A single noisy `E_i` produces only a ~1%/session
  step against a stable peer-median reference; noise averages out over the
  (roughly monthly) revisits.

## Division of labor with renormalization

The two engines are orthogonal and run back-to-back in `applySessionProgression`:

- **Peer-consensus engine** sets the *relative shape* of a muscle's coefficient
  family from evidence.
- **`SeedNormalizer`** applies a single *uniform scale* per muscle, re-attributing
  the global scale into the baseline while preserving coefficient shape.

Because renorm only touches global scale and peer-consensus only touches relative
shape, they do not fight. A fixed-point test will confirm that iterating
coefficient-pass → renorm converges rather than chasing.

## Code changes (`EstCoefConsensusHeuristic.kt`)

- **Delete:** `applyH2`; `EmitProposal` consensus metadata; the
  `H1Proposal.hasDefinite` field and definite-bypass logic; the unused
  cross-exercise grouping that `applyH2` performed.
- **Drop constructor params:** `minEvidenceWeight`, `minOutlierSessions`,
  `tauConsensusThreshold`, `tauOutlierThreshold`, and the `LN_110` constant.
- **Add constructor params:** `minPeers = 2`, `peerWeightEpsilon` (small floor).
- **Keep:** `tauHalfMs`, `alpha`, `maxLogStep`, `minRelativeChange`, and all of
  `setSignal`, `aggregateSession`, `damp`, `weightedMedian`.
- **Reshape:** `computeH1` → produce `E_i` + `w_i` (no gate, no baseline division);
  add the peer-consensus layer; `compute` orchestrates per muscle (it still needs
  `exerciseMuscle` for grouping and `currentCoefficients` for `E_j / c_j`).
- **Metadata:** replace `consensus_outlier` / `consensus_mixed` notes with a
  lightweight per-emit note, e.g. `peer_consensus:peers=<n>` for the debug history
  screens.
- **Class name** stays `EstCoefConsensusHeuristic` — "consensus" now accurately
  means *peer* consensus.

## Tests

**Delete** (obsolete):
- The 5 `applyH2_*` tests.
- `computeH1_belowMinEvidenceAndNoDefinite_returnsNull`.
- `computeH1_singleDefinitePointBypassesMinEvidence`.

**Keep** (unchanged): all `setSignal`, `aggregateSession`, and `damp` tests.

**Adapt:** the remaining `computeH1` median/recency tests to the new `E_i` output
(absolute est-1RM, no gate).

**Add:**
- Peer-consensus proposal math: `proposed c_i = E_i / weightedMedian(E_j/c_j)`.
- `< 2 peers → no proposal` (cold-start guard).
- **Two wrong coefficients in one muscle both converge toward truth** (the
  regression this work targets).
- **Systemic drift → zero coefficient movement** (the headline property): scale all
  `E_j` by a common factor, assert proposals are unchanged.
- A single recent session counts a peer as usable.
- Fixed-point: coefficient-pass → renorm iterated to convergence (no chase).

## Docs

Rewrite `docs/adaptation/03-coefficient-estimation.md` around peer-consensus:
the layer list becomes signal → session aggregate → per-exercise estimate →
peer-consensus reference → damp, with an explicit note that systemic drift cancels
structurally and that renormalization owns the global scale.

## Out of scope

- Changing `WorkoutGenerator`'s variety sampling (a larger product decision).
- Peer-support confidence attenuation (deferred refinement).
- Any change to the baseline heuristic or `SeedNormalizer` themselves.
