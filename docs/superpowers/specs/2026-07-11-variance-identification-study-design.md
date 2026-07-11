# Design — Variance/Covariance Identification Study

**Date:** 2026-07-11
**Status:** Approved design. Sub-project 1 of the data-driven variance-budget redesign.
**Precursor:** `docs/superpowers/specs/2026-07-11-variance-budget-problem-definition.md` (problem statement).
**Binding principle:** §0 of the precursor — *trust the data, not the constants or the synthetic
simulation.* Every existing constant is a suspect to be re-justified against held-out real data, the
cross-exercise transfer constants (τ, `levelAnchorPrecision`) included.

---

## 1. Purpose

Determine **from the real training history** where session-to-session variance actually lives, so the
follow-up implementation phase fixes the model's **structure** rather than cranking one release-valve
knob (`processNoisePerDay`). The problem statement established that process noise has no interior
optimum on real data — it pins whatever bound it is given, soaking up all unexplained scatter — which
is a model-misspecification signal, not a measurement. This study identifies the correct home of that
scatter.

The verdict currency is the **held-out forward-chaining one-step-ahead predictive log-score** on the
real 24-session history — the only ground truth the problem statement trusts.

### Decision gate (what makes a candidate "the answer")

A structural candidate is recommended only if it clears **both**:

1. **Clear CV gain** — beats the B0 baseline held-out predictive score by a clear margin, ideally
   matching or beating the B1 release-valve reference (`processNoisePerDay ×16`).
2. **Interior optimum** — its key parameter's held-out CV curve peaks in the *interior* of a wide
   sweep, not at a bound. This is the explicit misspecification tell that `procNoise ×16` fails: B1
   wins on raw CV yet pins its bound, so raw CV gain alone is a proven-insufficient bar.

## 2. Non-goals (explicit hand-offs)

Written here so scope cannot silently expand:

- **No production constant changes.** `EstimatorConfig` is untouched; this is analysis-only, test-tree.
- **No light-lift / coarse-grid prescription fix.** Deferred to the implementation phase. The study
  *does* emit the lightest-accessory session-to-session swing (the 5 lb → 15 lb pathology the problem
  statement flagged) as a diagnostic number, so the next phase starts informed — but does not act on it.
- **No rebuild or demotion of the synthetic `BeliefSimulationTest` gate.** A separate downstream
  decision once the structure is known.
- **No actual-filter implementation of any candidate.** The day-effect term (candidate 1) is
  implemented only in the *predictive/scoring* path for measurement; wiring it into the live
  `BeliefUpdater` fold is the follow-up phase's job.

## 3. Where it lives

Analysis-only, test tree, mirroring the existing `RecalibrationHarness`:

- New `VarianceIdentificationStudy` object in
  `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/`.
- New `VarianceIdentificationTest` that **no-ops without the personal fixture**
  (`app/src/test/resources/backtest/history.json`, gitignored) and writes the report to
  `app/build/variance-identification-report.txt`.
- Reuses existing machinery: `ReplayEngine`, `SessionProgressionStepper`, `PredictiveScoreAccumulator`,
  `RecalibrationHarness.truncateTo`, `RecalibrationHarness.scoredReplayTotal`, and the telescoping
  `RecalibrationHarness.heldOutTailScore` (fixed-config candidates score in two replays).

**Zero production code is modified by this sub-project.** Any scoring-path model extension (the
day-effect predictive variance, the Student-t likelihood) lives in the test tree as a scorer variant,
not in `domain/progression/`.

## 4. Reference baselines (present in every run)

- **B0** — current model at defaults. The honest baseline.
- **B1** — current model with `processNoisePerDay ×16`. The known release-valve gain (+~31 held-out CV
  in the problem statement) that structural candidates must match or beat *while having an interior
  optimum* — which B1, by construction, does not.

## 5. Candidate structures

Each is scored by held-out CV against B0 and B1, and each is **swept across a wide parameter grid** so
the interior-vs-pinned status is measured, not assumed.

### 5.1 Session day-effect (the one model extension)

A per-session shared random intercept `ε_session ~ N(0, σ_day²)`. In the scoring path, every set in
session *s* has its predictive variance inflated by `σ_day²`, and sets within the same session share
the latent day offset. This absorbs whole-session "good day / bad day" multiplicative scatter **without
permanently moving μ** — the key structural difference from `processNoisePerDay`, which dumps the same
scatter into permanent belief drift. Implemented only in the predictive/scoring path here. Sweep
`σ_day` over a wide grid; the hypothesis is a clear interior optimum.

### 5.2 Re-freed observation noise

Sweep a multiplier on the report-noise bases (`repNoiseBucket`/`repNoiseCounted`/`repNoiseRel`) and
`obsModelSd`. The cheap null hypothesis: the scatter is simply per-set signal noise, and phase-4's
"the fitter learned to distrust the user" was the model *correctly* detecting large residuals through
the only knob it was allowed to move. Sweep for interior optimum and CV gain.

### 5.3 Heavy-tailed observations

A Student-t observation likelihood (degrees of freedom ν) replacing the Gaussian in the scorer. Sweep
ν. Distinguishes "a few large outliers" (low ν wins) from "uniformly larger scatter" (high ν, ≈
Gaussian, no gain).

### 5.4 Cross-exercise transfer

Two parts:

- **Descriptive:** same-muscle exercise-pair one-step-ahead residual correlation ("corresponding
  exercises near each other"), to see whether real siblings move together as tightly as the current τ
  assumes.
- **CV:** sweep the τ multiplier (all three equipment classes together, as the fitter does) and
  `levelAnchorPrecision` for interior optima and held-out CV gain. Reports whether the current
  0.08/0.20/0.25 values are data-supported at all, or are sim-pinned artifacts.

## 6. Diagnostics (the "why", never the verdict)

One-step-ahead residuals — the belief's predicted mean *before* folding each set, minus the observation
location (mid-interval for censored intervals, the point for Gaussian) — grouped by:

- **session** → day-effect share,
- **exercise-pair within muscle** → transfer correlation,
- **set-index within exercise** → fatigue / per-set share.

Reported as a descriptive variance-share table. Explicitly diagnostic color only: the problem statement
warns that an aggregate summary (the projector's "STABLE" τ flag) misled us before, so the *verdict*
comes from CV-comparison, never from these shares.

## 7. Output

`app/build/variance-identification-report.txt`, containing, per candidate:

- held-out CV delta vs **B0** and vs **B1**,
- the full parameter sweep curve,
- an **interior-optimum vs pins-bound** flag,
- the descriptive residual decomposition (variance shares, pair correlations),
- the deferred light-lift swing diagnostic (color for the next phase).

Ending with a ranked **recommendation section**: which structure(s) the data supports as the real home
of the variance, each with its interior-optimum status. This section is the hand-off into the
implementation phase's own brainstorm.

## 8. Testing the study itself

Because the follow-up phase will act on this study's verdict, the scoring machinery is pinned by
synthetic unit tests before we trust its real-history numbers:

- day-effect scorer at `σ_day = 0` reproduces the baseline predictive score exactly,
- Student-t scorer at large ν matches the Gaussian scorer to tolerance,
- obs-noise multiplier ×1 reproduces the baseline,
- τ multiplier ×1 reproduces the baseline projector behavior.

Trust the machinery, then trust its verdict.

## 9. Reproduction / state

- Requires the personal fixture `app/src/test/resources/backtest/history.json` (present, gitignored);
  the study test no-ops without it, like the existing recalibration tests.
- Adopts nothing. `EstimatorConfig` unchanged. The deliverable is a decision, not a code change.
