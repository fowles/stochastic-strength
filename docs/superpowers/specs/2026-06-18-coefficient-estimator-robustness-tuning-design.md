# Coefficient estimator robustness & tuning

**Status:** Approved design, pending implementation plan
**Date:** 2026-06-18
**Component:** `domain/EstCoefConsensusHeuristic.kt` (a `CoefficientHeuristic`)

## Problem

A split-exercise pressure test of the peer-consensus coefficient estimator surfaced
two real weaknesses (the design's headline properties — relative-domain framing and
orthogonal division of labor with renormalization — held up and are *not* changed here):

1. **The peer reference degenerates at the floor of its operating range.** The
   reference is `B_others = weightedMedian over peers j of (E_j / c_j)`
   (`EstCoefConsensusHeuristic.kt:184`). `weightedMedian` (`:202-212`) returns the
   *value of the higher-weight peer* when there are exactly two peers — a hard
   selection, not a blend — and can flip discontinuously between sessions as peer
   weights cross. With `minPeers = 2` the engine is allowed to operate in exactly
   this regime, and the variety planner makes small peer sets common for
   sparsely-trained muscles and at cold start. In the two-peer case the "median
   shrugs off a polluted peer" robustness claim does not hold.

2. **Convergence is slow relative to how badly a seed can be wrong.** With
   `alpha = 0.2`, the `ln(1.05)` per-session cap, and the `minRelativeChange = 0.005`
   deadband, correcting a 2×-wrong seed (e.g. 0.15 that should be 0.30) takes ~23
   *effective* sessions (trainings of that exercise) even with an oracle proposal,
   and then stalls ~3% short at the deadband. Realistic RIR feedback makes it slower
   still: a grossly-too-light set reports RIR_5_PLUS, whose `+7`-rep inference and
   0.40 confidence (`:95-98`) cap how much strength a single session can reveal, so
   the proposal target itself lags upward. (The *calendar* bottleneck — a given
   exercise recurs only ~once every two months under the variety planner — is **out
   of scope**; see below.)

## Key insight

These are two independent levers with independent jobs:

- **Robustness of the reference** is an *estimator + peer-count* problem. With two
  points there is no robust center (any estimator either picks one or blends two,
  0% breakdown); robustness requires a majority, i.e. ≥3 peers. So the fix is an
  estimator that degrades *gracefully* at small n (a blend, not a coin-flip) **plus**
  getting off n=2 (a higher `minPeers` floor and a longer recency window that keeps
  more peers alive between revisits).
- **Convergence speed vs. wildness** is a *damper* problem, and the two are
  decoupled: `alpha` sets sustained learning rate (and steady-state jitter, which
  scales only as `√alpha`), while `maxLogStep` independently bounds any single
  session. So we can raise responsiveness without raising single-session risk.

Rather than guess the parameter values, we **measure** them with a deterministic
simulation harness and lock the chosen values with assertions.

## Scope

**In scope** (all in `EstCoefConsensusHeuristic.kt` + a new test):
- Interpolated weighted median for the peer-consensus reference.
- Optional peer-support confidence attenuation (implemented; on/off decided by the
  harness).
- Re-tuning `alpha`, `tauHalfMs`, `minRelativeChange`, `minPeers` from candidate
  ranges, with final values chosen by the harness.
- A JUnit simulation harness that sweeps + prints metrics, then asserts bounds at
  the chosen values.

**Out of scope:**
- `WorkoutGenerator` / the variety planner. Biasing exercise selection toward
  under-converged exercises would require threading a new per-exercise priority
  signal into the generator *and* trading away workout variety (a product
  decision). The calendar-recurrence bottleneck is therefore accepted; we mitigate
  it indirectly by lengthening `tauHalfMs` so each scarce update pools more
  evidence.
- `LastSetAutoregulationHeuristic` (baseline), `SeedNormalizer` (renormalization),
  and any DB schema. The tuning knobs are code constants — no migration.

## Design

### Component 1 — interpolated weighted median (peer reference only)

Add a new function:

```
internal fun interpolatedWeightedMedian(valueWeights: List<Pair<Float, Float>>): Float
```

- Sort by value (ascending).
- Walk the sorted list accumulating weight; the target is `total / 2`.
- Instead of returning the first value whose cumulative weight reaches the target,
  **linearly interpolate between the two straddling points** at the exact
  half-weight crossing.
- Edge cases: empty → caller guards (returns nothing); `n == 1` → that value;
  all-equal values → that value; total weight `0` → caller already guards via
  `peerWeightEpsilon`.

**Use it only for the peer-consensus reference** in `applyPeerConsensus`
(`B_others`, `:184`). **Keep the existing data-point `weightedMedian` for the inner
within-exercise estimate** in `computeEstimate` (`:148`): that median is over a
single exercise's own sparse sessions, where rejecting a freak session outright is
the desired behavior.

Both estimators are scale-equivariant (`f(k·x) = k·f(x)`), so the systemic-drift
cancellation property (`E_i / B_others` cancels a common factor `k`) is preserved.

### Component 2 — peer-support confidence attenuation (behind a knob)

Add a constructor parameter that, when enabled, scales a proposal's confidence down
when the total peer evidence weight backing its reference is thin — so a reference
built from only two or three lightly-evidenced peers moves the coefficient weakly
even if the reference itself is a poor pick. Implemented unconditionally in code but
**gated by a parameter whose default is set by the harness** (kept only if it
measurably reduces over-eager moves on thin peer sets).

The exact attenuation form (e.g. `confidence × min(1, totalPeerWeight / W₀)` for some
support threshold `W₀`) is a harness-tuned detail; the implementation plan will fix
the specific shape and the plan's tests will pin it.

### Component 3 — parameter re-tuning (values chosen by the harness)

Candidate ranges to sweep (the spec deliberately does **not** pin final values):

| param | current | candidate range |
|---|---|---|
| `alpha` | 0.2 | 0.2 – 0.4 |
| `tauHalfMs` | 14 d | 14 – 28 d |
| `minRelativeChange` | 0.005 | 0.002 – 0.005 |
| `minPeers` | 2 | 2 – 3 |
| `maxLogStep` | `ln(1.05)` | held fixed (single-session guard) |
| peer-support attenuation | n/a | on / off |

`maxLogStep` is intentionally held: it is the hard guarantee against a single wild
session and must not be traded for speed.

### Component 4 — simulation harness (JUnit, prints + asserts)

A deterministic JVM test (fixed RNG seed) in `src/test`:

- **Setup:** a synthetic muscle of N exercises with known *true* coefficients,
  seeded with deliberate errors — at least one 2× outlier and a couple of 20–40%
  errors.
- **Per simulated session:** prescribe `weight = baseline × currentCoef` (scaled to
  a rep target), then **derive realistic feedback from the gap** between the
  prescribed weight and the exercise's true strength — mapping that gap to a
  `SetFeedback` category (RIR_5_PLUS / RIR_2_4 / RIR_0_1 / TOO_HARD) so the
  RIR-ceiling proposal-lag is modeled rather than assumed away. Feed the resulting
  `WorkoutSet`s through `compute`, advance the clock, and let recency apply.
- **Metrics per run:** effective-sessions-to-90% of the true coefficient,
  steady-state jitter (std of the coefficient over a noisy tail once converged), and
  max single-session step.
- **Sweep** the candidate ranges and **print** a comparison table for inspection.
- Once values are chosen from the table, **assert bounds at those values**
  (convergence ≤ chosen session budget, jitter ≤ chosen ceiling, single step ≤
  `exp(maxLogStep) − 1`) so the choices are locked against regression.

The harness doubles as living documentation of why the chosen values were chosen.

## Testing

- All existing 27 `EstCoefConsensusHeuristic` unit tests must stay green.
  Interpolation touches only the peer reference; the equilibrium and systemic-drift
  regression tests are expected to be unaffected — verify explicitly.
- New unit tests: `interpolatedWeightedMedian` math + edge cases (n=1, n=2 blend,
  all-equal, scale-equivariance).
- The harness's locked-value assertions (Component 4).
- If peer-support attenuation is kept, a unit test pinning its behavior on a thin
  peer set.

## Rollout

No DB migration — the changes are code constants and pure functions. Changing
`tauHalfMs` / `alpha` alters the *next derived-state recompute* for existing users
(coefficient history is replayed in `replayDerivedState`), which is a recompute, not
a migration; coefficients will re-settle over subsequent sessions. State this plainly
so it is not mistaken for a breaking change.

## Docs

After implementation, update `docs/adaptation/03-coefficient-estimation.md`:
- Layer 4 reference is now an interpolated weighted median (graceful at small peer
  counts) rather than a data-point median.
- Note the `minPeers` floor and, if kept, peer-support attenuation.
- Layer 5 damp parameters reflect the harness-chosen values.
