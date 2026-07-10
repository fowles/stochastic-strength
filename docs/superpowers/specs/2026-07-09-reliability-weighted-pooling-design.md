# Reliability-weighted muscle pooling (Phase 3) — design

**Date:** 2026-07-09
**Status:** approved (brainstorm)
**Source master design:** `2026-07-06-belief-policy-reframe-design.md` §3
**Touches:** `domain/progression/MuscleStrengthProjector.kt`, `EstimatorConfig`
(in `ExerciseBelief.kt`), `domain/progression/CrossTuning.kt`, the pooling doc
`docs/adaptation/04-muscle-pooling.md`, and the gate tests `BeliefSimulationTest`
+ `ProdBssPrescriptionTest` + the full-history backtest.

## Summary

Phase 3 is the **pooling swap**. The read-time muscle projector keeps its
hub-and-spokes shape — every loaded exercise for a muscle informs one shared
muscle level, then borrows back from it — but two things change:

1. **How tightly each exercise couples to the hub** becomes per-equipment-class
   (transfer tightness τ) instead of one global `tauBridge`.
2. **The blend math** becomes the clean precision-weighted Gaussian form from the
   master design §3, replacing the hand-rolled `neff` / `kappa` / `siblingExcess`
   machinery.

It is a pure read-time change: the stored per-exercise beliefs are never mutated,
there is no Room migration, and derived state is still rebuilt by replay.

## Non-goals

- **No NxN / learned cross-couplings.** The shared-latent star is kept
  deliberately: this is a single-user, data-sparse, replay-recomputed system.
  N exercises give N opinions about **one** hidden level (well-posed); a learned
  pairwise matrix is N² unknowns from N noisy signals (underdetermined), buys
  nothing at cold start (falls back to a prior anyway), and is heavy inside
  replay. Finer *prior* structure (movement-pattern τ sub-classes, secondary-muscle
  spokes) is a possible future phase, not this one.
- **No per-user fitting (Phase 4).** τ values are fixed constants here.
- **No policy change.** `PrescriptionPolicy` is untouched; it still receives a
  pooled mean and a σ.

## Model

Per muscle, exercise *i*'s fresh log-capacity is modelled as
`ℓ + ln(seedCoef_i) + c_i`, with a personal offset `c_i ~ N(0, τ_i²)` whose
spread τ depends on the exercise's equipment class.

### Transfer tightness τ by equipment class

| Equipment enum | class | τ |
|---|---|---|
| `BARBELL` | barbell | 0.08 |
| `MACHINE`, `CABLE_MACHINE` | machine/cable | 0.20 |
| `DUMBBELL`, `KETTLEBELL`, `BODYWEIGHT`, `BAND` | other loaded | 0.25 |

`BODYWEIGHT`/`BAND` carry seed coefficient 0 and are already excluded from
pooling; they never reach a τ lookup. A small pure helper maps `Equipment → τ`
off `EstimatorConfig` (three new constants, `tauBridge` removed).

### "How much do I know about myself" = the July-9 evidence number

Every place the master design §3 writes the belief variance `σ_i²`, the **mean /
borrowing** math instead reads the adaptation-immune **`evidenceVar`**. This is
the one substantive carry-over from the 2026-07-09 projector-evidence-gate work,
which post-dates the §3 text: adaptive attention inflates the live `sigma2` to let
a consistently-surprised belief's *mean* move, and pooling that read the inflated
`sigma2` would misread the re-opening as "uninformed" and let confident siblings
drag the belief back (the prod-BSS regression). `evidenceVar` is immune to that
inflation, so a well-observed exercise keeps its full weight and self-anchor even
mid-surprise.

## Step 1 — the muscle level (precision-weighted, evidence-based)

Opinions and their variances:

```
o_i = aged μ_i − ln(seedCoef_i)
v_i = aged evidenceVar_i + τ_i²          // NOTE: evidenceVar, not sigma2
```

Prior on the level ℓ: mean `ℓ₀` = unweighted mean of the `o_i` (equals the seed
level for a cold muscle — exactly today's anchor), with a fixed anchor precision
`λ₀` (new config `levelAnchorPrecision`, replaces `levelPrior`). Posterior:

```
P       = λ₀ + Σ 1/v_i
lnLevel = (λ₀·ℓ₀ + Σ o_i/v_i) / P
σ_ℓ²    = 1/P
level   = exp(lnLevel)
```

`poolObsVar` is deleted: `evidenceVar` and `τ²` are both native log-variance, so
they combine directly with no scaling constant. (A well-trained exercise has
`evidenceVar` well below `σ_seed²`, so `v_i ≈ τ_i²`; a cold or stale one has
`evidenceVar ≈ σ_seed²` or larger, so it leans on the anchor and siblings.)

## Step 2 — borrow back (leave-one-out shrink)

For each exercise, recompute the level **excluding that exercise** (`ℓ_LOO(i)`,
`σ²_ℓLOO(i)`) so it does not vote for its own prediction. O(n²) with n ≤ ~16 is
negligible. The prediction and the blended mean:

```
lnPred_i = ℓ_LOO(i) + ln(seedCoef_i)
predVar_i = σ²_ℓLOO(i) + τ_i²

ownPrec  = 1 / evidenceVar_i            // NOTE: evidenceVar, not sigma2
predPrec = 1 / predVar_i
μ̃_i      = (ownPrec·μ_i + predPrec·lnPred_i) / (ownPrec + predPrec)
```

`exp(μ̃_i)` is the exercise's **projected effective 1RM** (`effectiveE1rm`); the
derived coefficient is `effectiveE1rm / level`, unchanged. A well-evidenced own
belief (large `ownPrec`, mid-surprise or not) is barely moved; a cold one
(`ownPrec ≈ 1/σ_seed²`) is dominated by `predPrec` and adopts the prediction — for
barbell more strongly (τ 0.08 ⇒ predPrec up to ~156) than for other loaded (τ 0.25
⇒ ~16).

### Reported σ for the policy — deliberate divergence from §3

The master design §3 feeds the policy the **shrunk** `σ̃_i` (own live σ blended
down by confident siblings). **This design does not.** It reports the exercise's
**own live aged σ (`sqrt(sigma2)`), un-shrunk**, exactly as the projector does
today. Two reasons, both consequences of the July-9 decisions:

- **Surprise hedge.** Adaptive attention inflates `sigma2` on a consistent
  surprise so the policy prescribes more cautiously while the belief catches up.
  Blending that σ down with confident siblings (§3's σ̃) would quietly undo the
  hedge. Reporting own live σ preserves it.
- **Cold-start caution.** A cold exercise borrows its *mean* from siblings (good)
  but should still be prescribed conservatively on its first outing. §3's σ̃ would
  report high confidence (siblings are confident) and prescribe aggressively;
  own live σ (≈ σ_seed) keeps it conservative.

So the mean is sibling-informed (via `evidenceVar`), but the reported uncertainty
is purely own and live. This intentional mean/σ split mirrors the two distinct
questions `evidenceVar` was introduced to separate: *how much accumulated evidence
do I have for positioning* vs *how uncertain am I right now for hedging*.

## Deletions

- `EstimatorConfig.poolObsVar`, `tauBridge`, `levelPrior` — removed.
- `MuscleStrengthProjector.neff`, the `kappa`/`siblingExcess` machinery, and the
  explicit evidence gate — removed. The gate's safety job is already covered: the
  failure ceiling lives in `PrescriptionPolicy`, and a tight own belief
  arithmetically dominates any loose-class sibling prediction, so no confident
  sibling can overrule a well-measured exercise.

## Interfaces (unchanged shapes)

`MuscleStrengthProjector.project(...)` keeps its signature and returns the same
`MuscleProjection(level, effectiveE1rm, derivedCoef, pooledSigma)`. Internals are
rewritten; `pooledSigma` now documented as own live aged σ (already its value
today). `CrossTuning` (debug view) is updated to the new internal quantities
(no more `neff`/`kappa` surfaced).

## Verification & gates

- **`BeliefSimulationTest`** — re-pinned. Calibration on the model-matched
  synthetic lifter (coverage-vs-p table) sets `λ₀` and confirms the τ classes
  don't distort coverage.
- **`ProdBssPrescriptionTest`** — must still land the demonstrated **20 lb** end
  to end (the prod-BSS regression guard; a dumbbell/other-loaded lift at τ 0.25).
- **Full-history backtest** — **re-baselined** (BAND), pre-approved as part of
  this phase; the reprice is attributed to the pooling swap and recorded.
- **Unit tests** — τ-by-equipment mapping; level is evidence-weighted (surprised
  σ-inflated exercise keeps its vote); LOO excludes self; a cold exercise adopts
  the sibling prediction; a well-evidenced exercise resists confident siblings
  (prod-BSS in miniature); reported σ equals own live σ (not sibling-shrunk).

## Docs

`docs/adaptation/04-muscle-pooling.md` rewritten to this model (drop the "Phase 3
note", replace Step 1/Step 2 math, remove `poolObsVar`/`neff`/`kappa` language,
document the own-live-σ divergence). `CLAUDE.md` progression section updated (τ by
class, `poolObsVar`/`tauBridge`/`levelPrior` gone).
