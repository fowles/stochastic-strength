# Projector Evidence Gate — Design

**Date:** 2026-07-09
**Status:** Approved (brainstorming)
**Context:** Extends the adaptive-attention work (`docs/superpowers/plans/2026-07-09-belief-filter-adaptive-attention.md`). Read that plan's Background first.

## Problem

The adaptive-attention fixes (observation-noise floor + innovation-run variance inflation) make the per-exercise Kalman belief track a clear, consistent signal: prod BSS's own belief now honestly lands at ~18.7 kg fresh 1RM (down from 30 kg), and prescribing from the own belief gives the user-demonstrated **20 lb**. But the *pooled* prescription is 25 lb, because the muscle-pooling projector nudges BSS's belief back up (18.7 → ~20.4 kg) toward its stronger siblings.

Root cause: `MuscleStrengthProjector.neff(belief)` derives an exercise's pooling weight purely from its current variance:

```
neff = ((1/σ²  −  1/σ_seed²) · poolObsVar).coerceAtLeast(0)
```

Fix B legitimately inflates σ to let the belief's mean move on a surprise. But that inflation *also* drops `neff`, and `neff` feeds the sibling-override gate:

```
cSelf         = neff(self)
siblingExcess = Σ_j≠self  max(0, neff(j) − cSelf)
kappa         = min(poolObsVar/tauBridge² , siblingExcess)   // capped at 0.032
lnUsed        = (cSelf·μ_self  +  kappa·lnPred) / (cSelf + kappa)
```

When adaptation drops `cSelf` from ~2.2 (evidence-level) to ~0.23 (inflated-σ), the capped 0.032 pull toward the siblings' prediction stops being negligible: `lnUsed` shifts ~12% up → 18.7 kg becomes ~20.4 kg → 25 lb. (Verified arithmetic: with `cSelf ≈ 2.2`, `lnUsed` moves ~1% → ~18.9 kg → 20 lb.)

The bug is a **conflation of two distinct quantities**: how *uncertain* the belief is right now (σ, which adaptation inflates) vs. how much *direct evidence* the exercise has accumulated (which adaptation must not erase). `neff` should measure the second; it currently measures the first.

This is the same class of issue as the 2026-06-24 fix ("siblings must not override a well-measured exercise"), re-opened by adaptive σ-inflation.

## Approach (chosen: uniform)

Introduce an **adaptation-immune "clean variance"** on the belief and drive `neff` from it, replacing the σ-based derivation **everywhere** it is used (muscle-level vote weight, self-anchor `cSelf`, and `siblingExcess`). One consistent notion of "how much do I know," used uniformly — the σ→n_eff conflation is deleted, not patched.

Rejected alternatives:
- **Precision counter** (`evidence += poolObsVar/s²` per fold): simplest code, but over-credits censored folds (a vague RIR interval treated as a sharp reading), and real histories are censored-heavy. Rejected for honesty.
- **Gate only the sibling-override** (keep σ-based n_eff for the level vote): smaller blast radius, but leaves a split-brain where "confidence" means σ in one place and evidence in another. Rejected in favour of a single uniform quantity.

## Design

### `ExerciseBelief.evidenceVar`

A new `Float` field: the variance the belief *would* have if adaptation never inflated it — the "clean" (evidence-only) variance. Lower = more accumulated evidence.

- `seed(e1rm)` → `evidenceVar = σ_seed²` (⇒ `neff = 0`, a cold exercise knows nothing about itself — unchanged behavior).
- `override(e1rm)` → `evidenceVar = σ_override²` (⇒ `neff ≈ 0.17`, a manual override carries its current modest pooling weight — unchanged behavior).
- Default `= sigmaSeed²`-equivalent via the factory methods; not persisted (in-memory derived state, rebuilt by replay — no Room migration, consistent with `mu`/`sigma2`/`innovationRun`).

### Fold updates (both `foldGaussian` and `foldCensored`)

Each fold updates `evidenceVar` alongside the real `sigma2`, using the **same** update math but from the **un-inflated prior** (`aged.evidenceVar`, never the adaptation-inflated `sigma2`) and the **shared aged mean** (`aged.mu`):

- **Gaussian:** `k_c = evidenceVar/(evidenceVar + s²)`; `evidenceVar_post = clampVar((1 − k_c)·evidenceVar)`.
- **Censored:** re-run the truncated-Gaussian variance reduction with `evidenceVar` as the prior spread and the same `(mean, lowerLn, upperLn, s)`, yielding the exact information credit for that interval. In the `z < MIN_MASS` degenerate branch, fall back to the Gaussian-at-the-bound variance reduction on `evidenceVar`, mirroring the real track.

The real `sigma2`/`mu` update is unchanged (still uses the adaptation-inflated prior from `adaptPrior`).

### Aging (`age()`)

Grow `evidenceVar` by `processNoisePerDay · idleDays`, with the same `clampVar` bounds as `sigma2`. Detraining drift affects only the mean, so `evidenceVar` gets the variance-growth arm only. This makes a stale exercise's evidence fade (its `neff` decays toward 0) so it re-borrows from siblings — preserving today's "stale lone voter decays to the seed" behavior.

### Adaptation (`adaptPrior()`)

Does **not** touch `evidenceVar`. Its job is to inflate `sigma2`'s prior so the mean can move; the evidence track is by definition the counterfactual where that never happened.

### Projector (`MuscleStrengthProjector.neff`)

```
neff(aged) = ((1/aged.evidenceVar − 1/config.sigmaSeed²) · config.poolObsVar).coerceAtLeast(0f)
```

The existing formula, reading `evidenceVar` instead of `sigma2`. All three consumers (level vote weight, `cSelf`, `siblingExcess`) inherit it — no other projector change.

## Why it works (and is safe at the edges)

- **BSS (well-observed, σ inflated by a surprise):** `evidenceVar` stays small (≈ what σ² would be without inflation), so `cSelf` stays ~1.6–2.2. The capped `kappa` (0.032) is then negligible against `cSelf` → `lnUsed ≈ μ_own` → ~18.9 kg → **20 lb**. Robust regardless of how much evidence siblings pile up, because it works through the self-anchor.
- **Cold exercise:** `evidenceVar = σ_seed²` → `neff = 0` → `cSelf = 0` → `lnUsed = lnPred` → adopts the sibling prediction fully. Preserved.
- **Stale exercise:** `evidenceVar` decayed by aging → `neff → 0` → re-borrows. Preserved.
- **Well-behaved exercise (no adaptation firing):** `evidenceVar == sigma2` at all times, so `neff` equals today's value term-for-term → minimal backtest movement outside the adaptation cases.

## Known simplification

The censored variance reduction depends on the mean, and this design uses the shared real (adaptation-moved) mean rather than a separate shadow mean. A fully separate shadow *belief* (own clean mean + variance, a parallel plain filter) would be exact to the last digit but doubles the fold and introduces a second mean nobody prescribes from. Because `kappa` is capped at 0.032, the self-anchor only needs `evidenceVar` to be robustly *small* for a well-observed exercise — which the shared-mean update delivers — so the residual is immaterial to the outcome. One shadow variance, shared mean.

## Testing & re-baseline gates

- **Unit:** `evidenceVar` initialization (seed/override), fold-updates-it-from-the-un-inflated-prior, `age()` decays it, `adaptPrior` leaves it untouched, and a projector test showing an adaptation-inflated-σ / high-evidence belief is NOT overridden by confident siblings (the BSS regression, in miniature).
- **`BeliefUpdaterFoldTest`:** exact fold math for `mu`/`sigma2` unchanged (new field is additive).
- **`BeliefSimulationTest`:** re-pin `uncertaintyZ`/`overloadDelta`/`poolObsVar` from measurement; NIS guard stays green.
- **`BacktestComparisonTest` (BAND):** this touches the muscle-level computation, so deltas are expected. Attribute them; if large (à la phase-2 Task 10), surface via AskUserQuestion before re-pinning the BAND.
- **`ProdBssPrescriptionTest`:** re-pin to **20 lb** (the acceptance criterion).

## Scope / non-goals

- No per-exercise fatigue φ, no τ-pooling rework (those remain separate axes). This is exactly the `neff` evidence-decoupling and nothing more.
- No Room migration (`ExerciseBelief` is in-memory derived state).
- Sequenced *before* the adaptive-attention plan's Task 4/5 re-baseline so those gates measure the corrected pooling.
