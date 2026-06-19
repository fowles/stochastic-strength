# Common/Differential PI Controller — unifying baseline + coefficient estimation

**Date:** 2026-06-18
**Status:** Design — pending implementation plan. Decision #1 resolved (gauge-conserving
rolling-window differential); two open decisions (#2 estimation-vs-policy, #3 reduction
clamp) remain.

## Problem

Today the per-session progression runs as **three coupled components** (in
`WorkoutRepository.applySessionProgression`):

1. `LastSetAutoregulationHeuristic` (a `BaselineHeuristic`) — moves the per-muscle
   **baseline** from last-set RIR.
2. `EstCoefConsensusHeuristic` (a `CoefficientHeuristic`) — moves each exercise's
   **coefficient** via a recency-weighted, peer-consensus implied-1RM estimate.
3. `SeedNormalizer` (a `BaselineNormalizer`) — re-attributes common-mode coefficient drift
   back into the baseline (`m = Σ(c·s)/Σ(c²)`, 2 kg / 5 lb threshold) to fix the gauge.

These three exist because the underlying model is a **rank-1 factorization per muscle**:

```
estimated_1RM[exercise] ≈ baseline[muscle] · coefficient[exercise]
```

and that factorization has one unavoidable **gauge freedom** per muscle: scaling every
coefficient by `k` and dividing the baseline by `k` leaves every prescribed weight
identical. The data can never split a systematic change between "the muscle got stronger"
and "all these coefficients drifted." Component 3 exists solely to *choose* a convention
after components 1 and 2 have each, separately, let the split slip.

Two structural costs follow:

- **Coupling lag.** The baseline moves first, then coefficients react to the new baseline
  *next* session — a built-in one-session delay.
- **Gauge slippage between coordinate systems.** Component 1 works in RIR-percent space,
  component 2 in implied-1RM-ratio space; they disagree about where the shared scale went,
  so component 3 has to keep cleaning up. `SeedNormalizer`'s `c²`-weighted least-squares
  anchor lets a single badly-seeded heavy-coefficient exercise contaminate the whole
  muscle's gauge.

## Reframe: one common/differential PI loop per muscle

Each session produces, per trained exercise, a single **innovation** — the log gap between
what we prescribed and what the feedback says we *should* have prescribed:

```
e_i = ln( observed_1RM_i / (baseline · coefficient_i) )
```

`observed_1RM_i` is extracted from the session's sets exactly as today (reuse the existing
`EstCoefConsensusHeuristic.aggregateSession` signal: RIR-bucket / too-hard → implied 1RM +
confidence). The innovation is then **split into two orthogonal modes per muscle**:

- **Common mode** `ē` = confidence-weighted mean of the muscle's innovations → drives the
  **baseline**. "Everything agreed it was easy/hard" ⇒ the muscle moved.
- **Differential mode** `e_i − ē` (sums to zero by construction) → drives each
  **coefficient**. "This lift was easy *relative to its siblings*" ⇒ its coefficient is off.

```
Δlog baseline       = K_b · ē
Δlog coefficient_i  = K_c · (e_i − ē)
```

with a per-session log-step clamp and the baseline snapped to the weight grid.

### Pooling: the split is over the muscle's recent window, not just this session

The split is **not** taken over only the exercises trained this session. Each exercise
keeps a recency-decayed EMA of its `log(observed_1RM)` (the measurement filter that replaces
the coefficient estimator's recency-weighted median), plus its last-seen time and
confidence. On a muscle trained this session:

For each muscle trained this session, let `e_i = emaLogEst_i − ln(baseline·coefficient_i)`
over **every loaded exercise in the muscle with a recent measurement**, with weight
`w_i = recency(age_i) · confidence_i` (recency = exponential half-life decay), and the
weighted common `ē = Σ w_i·e_i / Σ w_i`:

- the **baseline** moves on the pooled common mode: `Δlog baseline = K_b · ē` — a
  well-averaged, smoothed signal even when only one exercise trained today;
- **every pooled coefficient** moves (not just this session's), scaled by its own weight:
  `Δlog coefficient_i = K_c · ŵ_i · (e_i − ē)`, where `ŵ_i = w_i / max_j w_j` (freshest
  gets full gain, staler proportionally less).

This matters because the real planner caps a muscle at 2 exercises/session, so most muscles
see only one exercise per workout. Within-session-only pooling gives a lone exercise a zero
differential (nothing to compare against) and a single-exercise common mode; pooling the
recent window restores the cross-session pooling `EstCoefConsensus` does today — a
lone-exercise session still yields a real differential and a stable baseline signal. On the
evidence below this is a ~3× accuracy and ~10× jitter improvement over within-session-only.

The per-exercise state (recency-decayed EMA of `log(observed_1RM)`, last-seen time,
confidence) is **exactly the information `EstCoefConsensus` already keeps** — the same data
reorganized into the unified loop, not new conceptual baggage.

### Why this deletes the normalizer (and why the differential must be gauge-conserving)

Because `ē` is the **weighted** mean, the weighted residuals sum to zero —
`Σ w_i·(e_i − ē) = 0` — so applying the differential to **all** pooled exercises (scaled by
`w_i`) makes `Σ Δlog(coefficient_i) = 0` every session (pre-clamp). The coefficient
**geomean — i.e. the gauge — is conserved, continuously, for free.** The baseline owns the
common mode (scale); the coefficients own the sum-zero subspace (shape); they run in separate
lanes that cannot bleed into each other, so there is nothing left for a normalizer to fix.

This sum-zero property is **load-bearing**, and it is why the differential updates *all*
pooled exercises rather than only this session's. A tempting simpler variant — pool the
common mode but update only the freshly-trained coefficient — **breaks conservation**
(`Σ Δlog c ≠ 0`) and, under a strengthening lifter, **systematically ratchets the
coefficient gauge upward**: each fresh workout slightly beats the staler pool, and that gap
is repeatedly attributed to the trained exercise's coefficient. Simulation confirmed this
creep (coefficient geomean inflating ~9% at vigorous gains, baseline correspondingly
under-tracking). The gauge-conserving form pins it at 1.00 across all growth rates. **Build
the conserving form.**

### The mid-set weight drop needs no special case

A failed set is logged at full weight with low `actualReps` (`TOO_HARD`), and remaining
sets drop via `scaleReps`. In the innovation framing this is just an `observed_1RM_i` below
the prescription → a **negative innovation** → a downward push on baseline/coefficient.
The controller handles drops organically; no separate clamp is *required* (keeping the
existing reduction clamp as an extra downward guard is an option — see open decisions).

## Empirical case

A simulation A/B lives in
`app/src/test/.../domain/ControllerReframeSimulationTest.kt` (exploration-only; prints
tables to `build/reports/`). It drives the **joint** loop (the existing
`CoefficientConvergenceSimulationTest` holds baseline fixed, so it never exercised the
gauge). Three test methods:

- `reframe_abComparison_broadened` — 4 seed-error profiles × {static, thin, rising} ×
  8 seeds, with a never-trained held-out exercise whose seed = truth, so its prescription
  error isolates **gauge drift**.
- `reframe_realisticVariant` — real `ExerciseLibrary` (bands removed, all else incl.
  bodyweight), real `WorkoutGenerator` picking 5/workout across 10 muscles, coefficients
  perturbed off seed (~8% lognormal + 4 outliers), faithful mid-set drops, seed baselines
  ±20%.
- `reframe_strengtheningCreep` — the realistic harness with a *rising* true baseline (true
  baseline compounds per session), measuring `coefInflation = geomean(coef/seedCoef)` (gauge
  creep) and `baselineGaugeErr` (cold-start error a seed-accurate new exercise would see).

Findings:

- **PI matches or beats the three-component stack on prescription accuracy, convergence,
  and gauge stability** under idiosyncratic per-person seed error — often dramatically
  (held-out gauge drift 0.7% vs 10.4% on the "outlier" profile).
- **Realistic regime** (real library/planner, bands removed, 5/workout, 10 muscles, mid-set
  drops, seeds ±20%), tail prescription error / jitter:

  | stack | trainedErr% | jitter% | convSess (from −20%) |
  |---|--:|--:|--:|
  | current (3-component) | ~16.5 | ~6.3 | ~41 |
  | PI, within-session split | ~7.2 | ~4.1 | ~3 |
  | PI, rolling-window (plain) | ~2.2 | ~0.45 | ~3 |
  | **PI, rolling-window (gauge-conserving)** | **~2.3** | **~0.5** | **~3** |

  The current stack's coarse, floored `+5/10/15%` RIR steps crawl up from a low seed (~41
  sessions); all PI variants converge in ≤3. The rolling-window split reaches
  single-muscle-clean-conditions accuracy (~2%) under realistic thin-per-muscle training and
  cuts session-to-session weight wobble ~14× versus the current stack. The gauge-conserving
  form matches that steady-state accuracy exactly.
- **Strengthening creep (why conserving is the chosen form).** Under a rising true baseline,
  the *plain* rolling split ratchets the coefficient gauge upward (`coefInflation` 1.01 → 1.05
  → 1.09 as growth goes 0 → 0.2% → 0.4%/session), the baseline correspondingly under-tracks,
  and its steady-state advantage evaporates. The **gauge-conserving** form holds
  `coefInflation` at ~1.00 across all growth rates (within-session PI also holds it, at 1.005)
  — the sum-zero differential does its job. A *separate*, benign baseline velocity-lag remains
  for both pooled forms (pooling the common mode means the baseline is driven partly by staler
  evidence, so it lags a fast ramp ~8–13% and prescribes slightly conservatively during rapid
  gains — safe and self-healing as gains slow). A user spends most of their training life near
  plateau, where conserving wins decisively; the ramp penalty is transient.
- **Both embellishments were tested and dropped.** A PI **integral** term was within noise
  everywhere (no scenario exercised the deadband stall it targets). A **seed anchor** (pull
  coefficients toward seed values) helped only uniform-bias cases but wrecked the
  idiosyncratic wins and **broke thin training** (never converged) — confirming a genuine
  no-free-lunch: anchor strength is just "how much you trust your seeds."
- **One inherent limit, not a regression:** when a *never-trained* exercise's seed is
  inconsistent with its muscle's other seeds, the absolute scale is mathematically
  unobservable; no method (current, PI, or anchored) recovers it. PI handles the realistic
  *consistent* version of uniform bias fine.

### Caveat: the accuracy gap is partly philosophical

The realistic `trainedErr%` gap (≈16% current vs ≈7% PI) is **not purely "PI is more
accurate."** `LastSetAutoregulation` is a *progressive-overload-to-failure* controller by
design: at RIR 0–1 (essentially at true capacity) it still adds +5%, deliberately
overshooting until failure. The "error vs true capacity" metric reads that intended
overshoot as error. The **clean** wins — convergence speed, jitter, drop handling — hold
regardless. This surfaces a real architectural point, deferred to an open decision below.

## Scope of the change

- **Replaced:** `LastSetAutoregulationHeuristic`, `EstCoefConsensusHeuristic`, and
  `SeedNormalizer` — all three collapse into one controller that emits both a per-muscle
  baseline proposal and per-exercise coefficient proposals from one innovation split.
- **Kept unchanged:**
  - The **signal extraction** (feedback → implied 1RM + confidence); lift it out of
    `EstCoefConsensusHeuristic` into a shared helper.
  - The **1RM load formula** (`DefaultProgressionEngine`) for rep-scaling between targets.
  - The **HURT** back-off (`×0.85`, worst-wins per muscle).
  - **Persistence:** baseline → `MuscleGroupStrength` + `BaselineHistory`; coefficient →
    `CoefficientHistory`. The controller writes both, with new `heuristicName`.
  - The `BaselineComputationInput` fields (sets, `exerciseMuscle`, `currentBaselines`,
    `currentCoefficients`, `minReductionFractions`, `weightUnit`, `asOf`) — the controller
    needs the same inputs plus the prior EMA state.

### Interface

`BaselineHeuristic` + `CoefficientHeuristic` + `BaselineNormalizer` unify into one
`ProgressionController` invoked once per session, returning both baseline and coefficient
proposals. `applySessionProgression`'s three-step body becomes a single call.

## Determinism / replay

The controller is a **left-fold over the ordered session sequence** (its per-exercise state —
recency-decayed EMA of `log(observed_1RM)`, last-seen time, confidence — carries forward),
not a pure function of a window. It is deterministic given the session order, and
`recomputeDerivedState` already replays sessions in order from the seed state — so that state
is reconstructed exactly on every full recompute; **it need not be persisted** for replay
correctness. *Incremental* single-session application needs the prior state: either persist
the per-exercise triple as derived state, or full-recompute. **Planning item:** confirm
which path the derived-state pipeline uses today and wire accordingly. (This is the same
per-exercise history information `EstCoefConsensus` already reconstructs from the set log on
each recompute.)

## Open decisions

1. **Differential form — RESOLVED: gauge-conserving rolling window.** Within-session-only
   pooling corrects outlier coefficients slowly (most of its ~7% realistic residual). Plain
   rolling-window fixes that (~2.2% error, ~0.45% jitter) but **creeps the coefficient gauge
   under a strengthening lifter** (the worry that surfaced this — confirmed in simulation).
   The **gauge-conserving** rolling form (differential applied to all pooled exercises,
   weighted so `Σ Δlog c = 0`) keeps the full steady-state win (~2.3% / ~0.5%) *and* pins
   `coefInflation` at ~1.00 across all growth rates. Build the gauge-conserving form; plain
   rolling and within-session are not shipped. A benign baseline velocity-lag under rapid
   strengthening remains (see findings) and is accepted as safe/transient; an untested knob
   (shorter half-life for the common mode than the differential) could erase it later.

2. **Estimation vs progression policy (architectural).** PI *estimates true capacity*; the
   current baseline controller *also drives overload* (the +5%-at-RIR-0–1 creep). If
   deliberate overload is desired, it should become an **explicit thin policy layer** on top
   of an accurate estimate (e.g. prescribe at a target RIR / +x%/week), rather than being
   baked into the estimator. Decision: keep overload behavior, and if so, where it lives.

3. **Reduction clamp (minor).** PI produces downward moves organically via negative
   innovation. Decide whether to also retain the explicit `minReductionFractions` clamp as a
   belt-and-suspenders downward guard, or drop it as redundant.

## Migration path

1. Extract the feedback→(implied-1RM, confidence) signal helper from
   `EstCoefConsensusHeuristic`.
2. Add `ProgressionController` (gauge-conserving rolling-window common/differential P — see
   `RollingConservingPiController` in the sim — recency-decayed per-exercise EMA, weighted
   sum-zero differential, clamps) behind the new unified seam; keep the old three-component
   path available behind a flag for A/B in replay.
3. Port `ControllerReframeSimulationTest`'s `RollingConservingPiController` to production; lock
   chosen gains (`K_b`, `K_c`, EMA β, recency half-life, log-step caps) with asserts as
   `CoefficientConvergenceSimulationTest` already does for `EstCoef` params.
4. Replay-equivalence check: recompute derived state on real session logs old vs new;
   compare prescribed-weight trajectories.
5. Remove `SeedNormalizer`, `LastSetAutoregulationHeuristic`, `EstCoefConsensusHeuristic`
   and their wiring once the new path is confirmed.

## Testing

- **Mode split:** all-easy session ⇒ baseline up, coefficients ≈ unchanged (differential
  near zero); one lift easy + one hard at the same average ⇒ baseline ≈ flat, coefficients
  diverge.
- **Gauge conservation:** coefficient geomean per muscle is invariant under the differential
  update across an arbitrary session sequence (the property that replaces the normalizer),
  **including under a rising baseline** (the strengthening-creep probe: `coefInflation` ≈ 1.00
  at every growth rate).
- **Mid-set drop:** a reduced exercise yields a downward move bounded sensibly and never an
  increase.
- **HURT:** worst-wins back-off per muscle.
- **Single-exercise session:** baseline moves from the muscle's pooled recent window, the
  trained exercise's coefficient corrects against that pooled reference, and untrained
  exercises' coefficients are untouched that session.
- **Replay determinism:** recompute over a fixed log is stable; per-exercise EMA/recency
  state reconstructed identically.
- **Simulation locks:** chosen gains hold the convergence/jitter ceilings from
  `ControllerReframeSimulationTest`.
- **Regression:** full unit suite + instrumented repository/replay tests.
```
