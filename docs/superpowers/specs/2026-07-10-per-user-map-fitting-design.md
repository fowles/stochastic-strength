# Per-user MAP fitting (Phase 4) — design

**Date:** 2026-07-10
**Status:** approved design, pre-implementation
**Parent design:** `2026-07-06-belief-policy-reframe-design.md` §5 (this reconciles
that section with the code as it actually landed after phases 2–3:
`2026-07-09-projector-evidence-gate-design.md` and
`2026-07-09-reliability-weighted-pooling-design.md`).

## Motivation

The belief estimator carries a set of tuning constants (`EstimatorConfig`) pinned
to a synthetic model-matched lifter. Phase 4 lets those constants adapt to the
individual user: replay each user's real history under different settings and keep
the settings that best predicted what actually happened, regularized toward the
global defaults so a thin history stays at defaults. This is the last phase of the
belief/policy reframe; it changes no schema and no observable behavior for a user
with little history.

Phase 4 was specified in the parent design §5, but phases 2–3 evolved the belief
and pooling in ways §5 did not foresee. This document records the reconciled
decisions and is the authoritative Phase 4 spec.

## What changed since §5 (and how this design reconciles it)

1. **The pooled predictive variance `σ̃` is gone.** Phase 3 reports `pooledSigma`
   as the own live aged σ, *un-shrunk*; the leave-one-out shrink now produces only
   the mean `μ̃`. §5's objective literally read `N(μ̃, σ̃²+s²)`. **Reconciliation:**
   the scorer predicts from the half-blended belief — pooled/LOO-shrunk mean `μ̃`
   with the lift's *own* variance — not a reconstructed `σ̃`.
2. **Adaptive attention now exists.** The belief carries `innovationRun` and
   re-inflates `sigma2` on a consistent surprise run, while pooling votes on the
   adaptation-immune `evidenceVar`. **Reconciliation:** the scorer uses the
   **clean** own variance (`evidenceVar`), the same quantity pooling already
   trusts — not the working `sigma2`. Fitting is a measurement activity; it wants
   the sharpest signal about which settings genuinely predict the user's body, and
   the clean variance denies a bad setting the chance to soften its own penalty
   through adaptive widening. (The working `sigma2` remains what the live
   prescription acts on — this divergence is deliberate and mirrors the
   clean-for-judging / working-for-acting line phase 3 already drew.)
3. **τ is three classes plus `levelAnchorPrecision`.** §5 fits one `τScale`
   multiplier on all τ classes. Because the scorer uses the own (not shrunk)
   variance, τ enters the objective only through `μ̃` (via `predPrec`), so `τScale`
   is *weakly* identifiable. That is accepted: `τScale` moves slowly and is held
   near its default by the prior unless the data speaks clearly. `λ₀`
   (`levelAnchorPrecision`) is **not** fitted.

## Decisions log (this brainstorming cycle)

| Question | Decision |
|---|---|
| Predictive distribution the scorer uses | Half-blended: pooled/LOO-shrunk mean `μ̃`, own variance |
| Which own variance | Clean (`evidenceVar`), adaptation-immune — not working `sigma2` |
| Fitted parameter set | §5's five, unchanged (see §1) |
| When the fit runs | Background; summary uses cached θ, new θ applies on the next rebuild |
| θ persistence | In-memory only (`DerivedStateStore`); never written to Room |
| Fitted-θ visibility | Debug-only panel; no user-facing surface |

## 1. Fitted parameters

Five personal scalars, each a bounded adjustment applied to an `EstimatorConfig`
default. Fitting produces a single derived `EstimatorConfig` = defaults with θ
applied.

| θ component | Applies to | Form |
|---|---|---|
| drift rate | `detrainRatePerWeek` | direct value |
| rep-noise scale | `repNoiseBucket` **and** `repNoiseCounted` | one multiplier on both |
| fatigue | `fatiguePerSet` | direct value |
| variance growth | `processNoisePerDay` | direct value |
| τ scale | `tauBarbell`, `tauMachineCable`, `tauOtherLoaded` | one multiplier on all three |

`uncertaintyZ` and `overloadDelta` are **not** fitted — training preferences, not
properties of the user's body. `levelAnchorPrecision` is not fitted.

## 2. The objective

One-step-ahead predictive log-likelihood over the whole history, plus log-priors,
maximized (MAP).

For each folded working-set observation, in replay order:

1. Form the exercise's **pooled pre-fold prediction**: the LOO-shrunk mean `μ̃_i`
   from the projector, paired with the exercise's own **clean** variance
   (`evidenceVar_i`, aged to the observation time). Predictive variance =
   clean own variance + observation noise variance `s²` (§ parent design §2).
2. Score the observation under that prediction:
   - **Counted / Gaussian** observation → Gaussian log-density.
   - **Interval or one-sided (RIR bucket / uncounted failure)** → log of the
     probability mass in the censored region (the log `Z` already computed by the
     censored update). A shared predictive-density helper is used by both the
     scorer and the censored fold so the two never diverge.
3. Sets that carry no load observation (weight ≤ 0, zero/null-coefficient
   exercises, HURT) contribute nothing, exactly as they fold to nothing.

Add a lognormal log-prior on each parameter, centered on its default with standard
deviation `priorSd` in log space. Sum of predictive log-scores + sum of log-priors
= the MAP objective.

The prior is what keeps a user near defaults until the data earns a move; `priorSd`
is calibrated (by the simulation harness) so under ~20 sessions the fitted θ stays
≈ defaults.

## 3. The fitter

- **Search:** Nelder-Mead over the five parameters in **log space** (keeps every
  value positive; makes the ÷4/×4 bounds symmetric). Fixed initial simplex around
  the defaults, iteration cap ~200.
- **Each evaluation** is one in-memory replay over history loaded **once** — no DB
  access inside the loop. The replay reuses the existing `ReplayEngine` /
  `ReplayHistory.loadFromDb` path, threading the candidate `EstimatorConfig` and
  accumulating the objective through a scoring observer.
- **Bounds:** each parameter hard-clamped to `[default÷4, default×4]`.
- **Floor:** the whole fit is skipped below `minFitSessions` (~15 completed
  sessions); θ = defaults until then.
- **Fallback:** if the fitted point does not beat the defaults on the same MAP
  objective, use defaults. (Nelder-Mead can terminate at a worse point; the
  defaults are the prior mode, so a fitted point wins only when the data likelihood
  gain outweighs the prior penalty.)

Deterministic and idempotent: same history + same defaults ⇒ same θ.

## 4. Execution model — background, self-warming

θ lives only in `DerivedStateStore`, in memory, and is **never persisted** to Room.

- `replayDerivedState` reads the currently-cached θ (defaults if none) and rebuilds
  derived state with it — instant, no fitting on this path.
- After the rebuild, if the **fit-key** (completed-session count + latest session
  end time) differs from the key the cached θ was fit under, it launches a
  background fit on the application `CoroutineScope`.
- When the background fit completes, it stores the new θ (and its fit-key) in
  `DerivedStateStore` and triggers **one** more `replayDerivedState`. Because θ is
  not part of the fit-key, that follow-up rebuild sees an unchanged key and does
  **not** re-trigger the fit — no loop.
- **Cold start** warms naturally: in-memory θ starts empty, so the first rebuild's
  key differs from "none" and a background fit runs once. The very next
  workout after that fit lands uses fitted θ.

**Concurrency** is confined to a thread-safe θ slot in `DerivedStateStore` (the
`replayMutex` already serializes rebuilds). The repository gains a `CoroutineScope`
(injected; the app already owns `applicationScope`) to launch the fit.

## 5. Reshaping: single source of the active config

Today `EstimatorConfig()` is constructed fresh (defaults) in ~4 sites:
`WorkoutRepository.buildPlanner`, `replayDerivedState`, the display projector, and
`PrescriptionPolicy`'s default arg. For fitted θ to reach both prescription and
pooling, **`DerivedStateStore` becomes the single source** of the active
`EstimatorConfig`, and every one of those sites reads it from there instead of
constructing defaults. This is the backbone change; the fitter is inert until it is
in place.

## 6. New code (pure, `domain/progression/`)

- `HyperparameterFitter` — Nelder-Mead in log space, bounds, floor, fallback;
  takes preloaded history + defaults, returns θ (as a derived `EstimatorConfig`)
  and its objective value.
- A predictive-density helper (Gaussian + censored `Z`) shared by the fitter's
  scorer and the censored fold, so the fold and the score never disagree.
- A scoring `SessionObserver` for `ReplayEngine` that accumulates the objective.

## 7. Debug panel

A read-only debug section: for each of the five parameters, fitted value vs
default; the MAP score gain over defaults; and the completed-session count (so the
`minFitSessions` floor is legible). No user-facing surface.

## 8. Defaults / guardrail constants (initial; sim tunes before pinning)

| Constant | Initial | Meaning |
|---|---|---|
| fit bounds | ÷4 … ×4 | hard clamp per parameter |
| `priorSd` | 0.5 (log space) | prior width; <~20 sessions ≈ defaults |
| `minFitSessions` | 15 | fit skipped below this many completed sessions |
| Nelder-Mead iters | ~200 | evaluation cap |

## 9. Testing & verification

**Unit:** fitter recovers planted parameters from synthetic histories within
tolerance; respects bounds; skips below the floor; falls back to defaults when
fitting does not beat them; the shared predictive-density helper agrees with the
censored fold's `Z` and with numerical integration.

**Simulation harness** (`BeliefSimulationTest`): re-pinned with fitting active —
the existing match-feel and calibration pins hold; add a pin that a planted
per-user parameter is recovered over a long synthetic history.

**Real-history backtest (key gate):** the exported prod history replays through the
full stack **with fitting on**; assert fitted θ in-bounds, fitted score ≥ defaults,
no NaN/degenerate beliefs, and per-session prescription deltas vs the frozen
current baseline within the pinned band. Re-baseline only with explicit approval,
as in prior phases.

**Instrumented:** existing `connectedAndroidTest` suite stays green.

## 10. Rollout

One release, folded into the existing replay. No Room migration; θ is never
persisted, so nothing new is stored. `docs/adaptation/` gains the fitting page
(the parent design's planned `06-fitting`). Version bump at the end per convention.

## Non-goals

Persisting θ; user-facing display or control of fitted θ; fitting `z`/`δ`/`λ₀`;
per-parameter priors beyond the single `priorSd`; any change to the belief, pooling,
or policy math beyond threading the active config and adding the scorer.
