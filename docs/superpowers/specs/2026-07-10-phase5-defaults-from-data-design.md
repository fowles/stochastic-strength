# Phase 5 — Recalibrating global defaults from data (empirical Bayes)

**Date:** 2026-07-10
**Status:** Design approved, pending implementation plan
**Predecessor:** Phase 4 per-user MAP fitting (`docs/adaptation/06-fitting.md`, spec `2026-07-10-per-user-map-fitting.md`)

## 1. Purpose & honest scope

Phase 4 personalizes the four structural estimator parameters — `detrainRatePerWeek`,
`fatiguePerSet`, `processNoisePerDay`, and the τ-scale (`tauBarbell` / `tauMachineCable` /
`tauOtherLoaded`) — **per user**, starting from best-guess global defaults. Those defaults
came from n=0: they are educated guesses with unknown error. Phase 5 moves the *defaults
themselves* off their guesses using the real histories we have, so that everyone's cold
start and every fit's MAP prior center are grounded in data instead of guesses.

**Data situation:** n=1 (one real ~24-session history). This is deliberately not gated on
n>1. One real history is strictly more information than the zero that produced the current
defaults. The constraint is honesty, not abstinence: we extract only what out-of-sample
evidence supports and never claim population truth from a single person.

**The realistic win under n=1 is cold-start quality.** The defaults drive:
- every user's first ~`minFitSessions` (≈15) sessions, before per-user fitting engages; and
- the MAP prior center for every subsequent per-user fit.

For the one user we already have, per-user fitting overrides the defaults regardless, so
Phase 5 does not change *their* mature prescriptions — it improves the out-of-the-box model
that new users (and everyone's early sessions) start from.

**Non-goals:**
- No runtime auto-recalibration (see §6 approach B, rejected).
- No change to the production per-user fitter's ÷4…×4 bounds in this phase (noted in the
  report as a follow-up decision, §4).
- Feedback-trust (rep-noise) stays pinned and unfitted, exactly as in Phase 4.

## 2. The forward-chaining CV harness

New **test-tree** component `RecalibrationHarness` under
`app/src/test/java/.../domain/backtest/` (alongside the existing `BacktestHarness`), plus a
`RecalibrationReportTest` that runs it and prints/writes the report. Both **no-op when the
gitignored `history.json` fixture is absent**, exactly like the existing backtest tests.
This is analysis-only: no `app/src/main` / runtime code changes, no Room migration.

### Inputs

A **list** of user histories. Today the list has one element (Matt's, loaded via
`BacktestHarness.load()`), but the harness signature takes
`List<UserHistory>` where `UserHistory` bundles a `ReplayHistory` with its
`newSnapshot: () -> ReplaySnapshot`. User #2 slots in with no structural change.
Forward-chaining runs per user; held-out scores sum across users.

### Forward-chaining core (per history of N completed sessions)

For each fold `k` from `minFoldSessions` (default 8) to `N−1`:

1. Fit θ_k on the sub-history `sessions[1..k]` via the existing `HyperparameterFitter`,
   using the widened `FitConfig` from §3.
2. Held-out one-step-ahead score of session `k+1`:

   ```
   heldOut(k) = scoredReplay(sessions[1..k+1], θ_k).total
              − scoredReplay(sessions[1..k],   θ_k).total
   ```

   The difference isolates session `k+1`'s predictions because sessions `1..k` contribute
   identically under the same θ_k. This reuses `PredictiveScoreAccumulator` unchanged — no
   scorer plumbing changes.
3. Record for that fold: θ_k's four multipliers and `heldOut(k)`.

Also compute `heldOut(k)` fold-by-fold using the **default** θ (all multipliers = 1). That
is the honest baseline any proposal must beat.

### Data flow

`RecalibrationHarness.run(histories): RecalibrationReport`. Pure JVM, deterministic, reusing
`ReplayEngine` / `SessionProgressionStepper` / `PredictiveScoreAccumulator`. A sub-history is
formed by truncating `ReplayHistory.sessions` (and the corresponding `setsBySession` /
`sessionOverrides`) to the first `k` completed sessions.

## 3. Per-parameter report & `processNoise` bound-widening

The harness aggregates folds into a **per-parameter verdict**, because adoption is per-param.

For each of the four parameters the report shows:

- **Fitted-multiplier trajectory across folds** (k = 8 … N−1) — the honesty signal. A stable
  optimum across folds is trustworthy; one that jumps around is noise.
- **Robustness flag:**
  - `STABLE` — tight spread across folds, does not pin the box → trustworthy proposal.
  - `PINS-BOUND` — consistently saturates the widened box → the default is misspecified;
    adopt the widened direction (with the caveat that the true value is beyond what one
    history can pin exactly).
  - `FRAGILE` — high variance across folds → leave at the guessed default.
- **Aggregate proposed multiplier** — median of the mature folds (later folds, more data).
- **Out-of-sample CV delta** — total `heldOut` under the proposal minus under defaults.
  Positive-and-robust is the bar for a recommendation.

### `processNoisePerDay` bound-widening

Phase 4 observed `processNoisePerDay` saturating its ×4 cap on this history — a boundary
solution is a misspecification signal, not a noise fit. The harness's `FitConfig` widens the
box to **÷16 … ×16** so the fit can find where `processNoise` settles instead of pinning:

- Lands interior (e.g. ×6) → we have its real value; propose it.
- Still pins ×16 → the default is dramatically low; report the direction, adopt cautiously.

This widening lives **only in the harness `FitConfig`**. The production per-user fitter's
÷4…×4 bounds are a separate, later decision; the report flags whether they too should widen.

### Harness `FitConfig` (distinct from production)

The harness builds its own `FitConfig`, deliberately different from the production one:

- `boundMultiplierLo / Hi` widened to **1/16 … 16** (§ bound-widening above).
- `priorSd` widened to **1.5** (weak prior; the human is the shrinkage gate).
- `minFitSessions` **lowered to `minFoldSessions` (8)**. The production floor of 15 would
  make every fold with k < 15 silently return default θ, flattening the trajectory exactly
  in the early folds where we most want to see the fit move. Lowering it lets every fold
  from k=8 actually fit; the trajectory + robustness flag (not a session floor) are how the
  harness discounts thin-data noise.

### MAP prior in the harness

The production fitter's lognormal prior is centered on the defaults — exactly what we are
trying to move. For recalibration the harness widens `priorSd` (weak prior, default 1.5) so
the data speaks; the human review is the real shrinkage gate. The trajectory + robustness
flag guard against the thin-early-fold noise the strong prior used to suppress.

### Output

A printed table plus a written report artifact (proposed defaults, per-param trajectories,
CV deltas, robustness flags, session/fold counts). Location decided at adoption time
(`docs/adaptation/` for a durable record, or scratchpad for a transient run).

## 4. Human adoption ceremony (separate, later step)

The harness *proposes*; a human *adopts*, per parameter. The harness changes no production
constant. For each parameter accepted:

1. Edit the constant in `EstimatorConfig`.
2. Re-pin `BeliefSimulationTest` (sim assertions move when defaults move).
3. Re-baseline the backtest default-config baseline (`BacktestComparisonTest`) — expected and
   intended, since the defaults changed.
4. `ProdBssPrescriptionTest` sanity check (the demonstrated 20 lb BSS anchor holds).
5. Bump version; add a "Phase 5 — defaults from data" section to
   `docs/adaptation/06-fitting.md`; update `EstimatorConfig`'s pinning comment and the
   `BeliefSimulationTest` re-pin note.

A **follow-up decision, not part of the harness:** whether to widen the production per-user
fitter's ÷4…×4 bounds for any param the report shows pinning (most likely `processNoise`).

## 5. Testing

- `RecalibrationHarnessTest` — pure-logic unit tests on synthetic mini-histories: fold
  enumeration is correct, the sub-history truncation is exact, `heldOut(k)` differencing
  isolates session `k+1`, and the aggregate/flag logic classifies a planted STABLE vs
  FRAGILE vs PINS-BOUND signal correctly. Runs without the personal fixture.
- `RecalibrationReportTest` — the integration entry point; no-ops without `history.json`,
  otherwise runs the full harness on the real fixture and writes/prints the report. Not a
  pass/fail gate (it produces evidence, not assertions).
- Existing suites (`BeliefSimulationTest`, `BacktestComparisonTest`, `ProdBssPrescriptionTest`)
  remain unchanged until the adoption ceremony (§4); they move only when a default is adopted.

## 6. Approaches considered

- **A — Offline forward-chaining harness + human adoption (this design).** Honest under n=1,
  re-runnable as users arrive, zero runtime risk. **Chosen.**
- **B — Runtime empirical-Bayes on the default prior.** Full auto-recalibration in-app.
  Rejected: over-automates an n=1 decision, adds runtime risk, hard to gate.
- **C — One-shot throwaway script.** Fastest, but not re-runnable for user #2 and leaves no
  auditable artifact. Rejected.
