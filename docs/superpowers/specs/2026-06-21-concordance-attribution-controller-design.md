# Concordance-based attribution: confidence sets speed, concordance sets where it lands

**Date:** 2026-06-21
**Status:** Approved (design), pending implementation plan
**Supersedes:** `2026-06-21-bracket-capacity-snap-design.md` — that spec explicitly
chose "no robust/median common mode" and "accept the baseline dip," and treated a
bracket as a special-cased gauge-preserving snap. Both choices are reversed here
(see *Why the previous spec was locally wrong*). The bracket signal extractor from
that work (`SessionSignalExtractor.bracketAggregate`) is **kept as-is**; only the
controller's attribution changes.

## Problem

A loaded exercise prescribed far too heavy produces a mid-session drop-cascade —
Bulgarian Split Squat on Jun 18: `55 lb fail (2) → 35 lb fail (2) → 20 lb completed
(RIR 0-1)`, bracketing true 10-rep capacity at ~20 lb. The committed bracket-snap
gets the *signal* right (est1RM ≈ 20 lb, `bracketConfidence = 0.95`) but still
mis-prescribes the next session (~40 lb), because the **controller's attribution
model is wrong** in two ways that only show up at the extremes:

1. **One violent reading drags the shared baseline.** The common mode is a plain
   confidence-weighted mean, so a lone outlier (Bulgarian −50%) pulls the baseline
   down ~7–10%, which then *lowers Goblet's prescription* even though Goblet was
   easy. A lone exercise being off is not evidence the whole muscle got weaker.
2. **A wrong baseline can hide forever.** A baseline set too high produces a
   drop-cascade on *every* exercise — at once, or one per session. If brackets are
   treated as purely exercise-specific (never touching the baseline), every
   coefficient collapses to compensate while the baseline stays wrong, corrupting
   the gauge and over-prescribing every new/untrained exercise (seed × wrong baseline).

These pull in opposite directions, so no per-session, per-exercise rule fixes both.

## Core insight

Two **orthogonal** questions about any session reading:

- **How fast do we believe it?** → set by **confidence**. A bracket is a hard
  physical demonstration → move fast. An ordinary RIR reading → move slowly. This
  is *all* the bracket flag earns.
- **Where does it land — baseline or coefficient?** → set by **concordance**,
  never by the bracket flag. The component of movement *shared across the pool*
  goes to the baseline (cross-exercise strength); each exercise's *deviation* from
  that shared component goes to its own coefficient.

"Shared vs specific" cannot be decided from one exercise in one session. It is read
from concordance on **two timescales**:

- **Within a session** — robustly. One exercise off against calm peers is an
  outlier → its move lands in its coefficient, baseline barely moves. *Every*
  exercise moving together is the consensus → baseline moves, coefficients barely.
- **Across sessions** — the only durable trace of a wrong baseline surfacing one
  lift at a time is the **coefficient geomean drifting**. A gentle, robust
  reclaimer moves that shared drift back into the baseline.

## Design

The controller is `RollingConservingProgressionController`. Three changes; the
`ProgressionController` interface and `SessionSignalExtractor` are unchanged.

### Unified update (the decomposition that makes it coherent)

For exercise *i* with innovation `eᵢ = ln(emaEstᵢ) − ln(baseline·coefᵢ)`:

- **Total product move** = `k_eff(sᵢ) · eᵢ`, where the *product* is `baseline·coefᵢ`
  (the only thing the user sees, through the prescription) and `sᵢ ∈ [0,1]` is the
  reading's confidence-derived speed. This is the convergence rate of the
  prescription toward the measurement — and it is independent of the split below
  (because `kB·common + kC·(eᵢ−common) = k·eᵢ` when `kB=kC=k`).
- **Split** of that move: `common → baseline`, `eᵢ − common → coefᵢ`, where
  `common` is now a **robust** location estimate (below), so the split — not the
  total — is what concordance controls.

### 1. Confidence → speed (`sᵢ`)

`sᵢ = bracketConfidence` today (0 for ordinary readings, 0.95 for a demonstrated
bracket); the field already exists on `ProgressionObservation`. Speed is realized
in three coupled places, all interpolated from `sᵢ` so that `sᵢ = 0` is
byte-identical to today's behavior:

- **EMA bypass:** `βeff = β + (1−β)·sᵢ`. At `sᵢ = 1`, `emaEst = thisReading` (no
  smoothing — a hard measurement is not a noisy trend sample). At `sᵢ = 0`,
  `β = 0.5` as today.
- **Gain:** `k_eff = kC + (kCSnap − kC)·sᵢ` (keep `kCSnap = 1.0`).
- **Clamp:** `maxStep = maxLogStepC + (maxLogStepCSnap − maxLogStepC)·sᵢ`
  (keep `maxLogStepCSnap = ln 2`).

The baseline move's speed is governed by the **robust weight mass** behind
`common` (a lone down-weighted outlier carries little mass → baseline barely
moves even at high `sᵢ`; a unanimous high-confidence drop carries full mass →
baseline moves fast). The baseline clamp is likewise relaxed in proportion to
that mass-weighted confidence.

### 2. Robust common mode (within-session concordance)

Replace the weighted-mean `common` with a robust M-estimator over `{eᵢ}` with
pool weights `{wᵢ}` (recency × confidence):

- Seed with the weighted mean `m₀`.
- Iterate (≈2–3 steps) Huber reweighting: `ψᵢ = min(1, δ / |eᵢ − m|)`,
  `m ← Σ wᵢψᵢeᵢ / Σ wᵢψᵢ`, with `δ ≈ ln(1.10)` (a >~10% deviation from consensus
  is treated as an outlier).
- `common ← m`.

Effect: `{−0.6, +0.03, 0}` → `common ≈ −0.02` (Bulgarian rejected → baseline
~flat, the −0.6 lands in Bulgarian's coefficient). `{−0.55, −0.6, −0.5}` →
`common ≈ −0.55` (consensus → baseline moves, coefficients barely). Both in a
single session, no hand-labelling. With a 2-exercise pool the estimator
degrades gracefully toward the mean (two points cannot disambiguate "one off"
from "shared half-off").

The differential `eᵢ − common` then moves each coefficient on **its own**
evidence; no forced per-session sum-zero (the reclaimer owns the gauge over time).

### 3. Robust geomean reclaimer (cross-session concordance)

After the per-session update, re-base shared coefficient drift into the baseline
as a **product-preserving gauge transform** (no current prescription changes — it
only fixes what new/untrained exercises predict):

- For loaded exercises, `cᵢ = ln(coefᵢ / seedCoefᵢ)`.
- `center = robustCenter({cᵢ})` — the *same* Huber primitive as §2, so a lone
  idiosyncratic coefficient (Bulgarian genuinely hard per-dumbbell) is rejected
  and **not** reclaimed, while a collective drift is.
- Apply `baseline ·= exp(ρ·center)` and, for **all** loaded exercises of the
  muscle, `coefᵢ ·= exp(−ρ·center)`, with reclaim rate `ρ ∈ (0,1]` (start ~0.5).
  Products are preserved exactly; the baseline absorbs the shared offset.

Sequential-bracket case: after Bulgarian, Goblet, Barbell each drop in turn,
`center` becomes clearly negative → baseline is pulled down and coefficients
restored toward seed → the wrong baseline self-corrects and new exercises are
predicted correctly. Lone-outlier case: `center ≈ 0` → no reclaim → Bulgarian
stays specifically low. This is a robust reinstatement of the deleted
`SeedNormalizer`, now folded into the controller as a gauge transform.

## Why the previous spec was locally wrong

- "Accept the baseline dip" assumed a lone outlier *should* move the baseline a
  little. It should not — §2 rejects it. The dip lowered Goblet's prescription,
  violating the real acceptance criterion.
- "Gauge-preserving snap, peers inflate to hold the geomean" forced Goblet to jump
  on no evidence of its own. §1/§2 move Goblet only on Goblet's signal.
- "Brackets are exercise-specific" breaks for a globally-wrong baseline (§ Problem 2).
  §3 lets concordant brackets reach the baseline over time.

## Acceptance criteria (outcomes, not internal numbers)

Asserted on **next prescriptions** via `WorkoutPlanner`, not on raw baseline/coef
(those are free gauge artifacts):

1. **Drop-cascade** `55/35/20 complete`: next Bulgarian ≈ **20 lb**; next Goblet
   **≥ 65 lb** (its last); baseline materially undisturbed by Bulgarian.
2. **All-failed** `55/35/20 fail`: next Bulgarian ≈ demonstrated capacity (≲ 20 lb);
   next Goblet **≥ 65 lb**.
3. **Unanimous drop** (new): all quad lifts bracket low together in one session →
   the **baseline** drops, coefficient geomean stays ~1.0.
4. **Drift-in-turn** (new): a too-high baseline revealed one lift per session over
   several sessions → baseline converges down, geomean returns toward ~1.0, no
   coefficient runaway.
5. **No regression:** at `bracketConfidence = 0` the controller is byte-identical
   to today; `ProgressionControllerSimulationTest`'s existing assertions
   (`coefInflation ∈ 0.97..1.03` under cross-exercise growth, settle-to-RIR_0_1,
   jitter, fail-rate) still pass.

## Testing

- **`ProgressionControllerTest`** (unit, synthetic innovations): robust common
  rejects a lone outlier (baseline ~flat) but follows a unanimous shift (baseline
  moves); reclaimer is product-preserving and pulls collective drift into the
  baseline while ignoring a lone offset; `sᵢ = 0` path unchanged.
- **`BulgarianBracketCharacterizationTest`** (end-to-end): rewrite the two existing
  cases to assert on **next prescriptions** (Bulgarian ≈ 20, Goblet ≥ 65) instead
  of raw coef/baseline; add the unanimous-drop and drift-in-turn cases (criteria 3–4).
- **`ProgressionControllerSimulationTest`**: extend with a multi-session
  drift-in-turn scenario asserting baseline convergence and geomean recovery;
  confirm the existing growth/gauge assertions are untouched.

## Risks

- **Small pools (N≤2)** limit robustness — accepted; documented degradation to the
  mean. Most muscles have ≥3 loaded exercises.
- **Reclaimer over-eager** (`ρ` too high) could chase noise into the baseline.
  Mitigated by the robust `center` (rejects lone offsets) and tuned against the
  drift-in-turn and growth sim scenarios.
- **Huber `δ` / iteration count** are tuning knobs; pinned by the unanimous-drop vs
  lone-outlier sim cases rather than chosen a priori.
