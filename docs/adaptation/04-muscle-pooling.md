# Read-time muscle pooling — how exercises cross-inform at prescription time

Source: `domain/progression/MuscleStrengthProjector.kt`
(cross-tuning view: `domain/progression/CrossTuning.kt`)
Design note: `docs/superpowers/specs/2026-07-09-reliability-weighted-pooling-design.md`

Folding is local: each [exercise belief](03-exercise-estimates.md) moves on its own
evidence only. That keeps failures from corrupting siblings, but on its own it would
leave a cold or long-unseen exercise stuck at an uncertain guess. The fix is to let
exercises borrow strength from each other **at read time**, without ever mutating the
stored beliefs. That is `MuscleStrengthProjector.project`, and it runs every time the
app needs a weight (and after every session, to record derived projections).

It does two things: compute a precision-weighted muscle **level**, then **shrink** each
exercise toward a leave-one-out prediction from that level.

## Step 1 — the muscle level (precision-weighted with seed anchor)

Each loaded exercise (positive seed coefficient) offers a seed-relative opinion of how
strong the muscle is: `o_i = μ_i − ln(seedCoef_i)`. These opinions are blended with an
anchor prior using precision weights:

```
votePrec_i = 1 / (evidenceVar_i + τ_i²)
ℓ₀ = unweighted mean of the o_i            // anchor mean
lnLevel = (λ₀ · ℓ₀ + Σ votePrec_i · o_i) /
          (λ₀ + Σ votePrec_i)
```

where **λ₀ = `levelAnchorPrecision` = 1.0** is the fixed precision of the anchor
(pinned by `BeliefSimulationTest`), and **τ_i** is the per-equipment-class transfer
tightness (see table below). The anchor mean **ℓ₀** is the unweighted mean of the
opinions — which for a cold muscle equals the seed level — so a thinly-evidenced muscle
falls back to that seed-anchored consensus rather than being defined by one loud voter.

Reading **`evidenceVar`** — the variance the belief would have without adaptation
inflation ([#3](03-exercise-estimates.md)) — is deliberate: adaptive attention inflates
`sigma2` to move a consistently-surprised belief's mean, and if pooling read `sigma2`
it would misread that re-opening as "uninformed" and let confident siblings pull the
belief back (the prod-BSS regression). `evidenceVar` tracks accumulated evidence and
is immune to that inflation, so a well-observed exercise keeps its full pooling weight
and self-anchor even right after a surprise.

### Per-equipment τ table

| Equipment class            | τ    | Interpretation                                    |
|----------------------------|------|---------------------------------------------------|
| `BARBELL`                  | 0.08 | Barbell lifts track the same muscle tightly       |
| `MACHINE`, `CABLE_MACHINE` | 0.20 | Machine/cable — moderate personal-offset variance |
| All other loaded           | 0.25 | Dumbbell, kettlebell, bodyweight, band — loosest  |

A wider τ means "a sibling's prediction is less trustworthy as a stand-in for this
exercise's own weight", so the vote precision `1/(evidenceVar + τ²)` is smaller and
each exercise has weaker influence on the level.

## Step 2 — shrink each exercise toward its LOO prediction

For each exercise *i*, the muscle level is recomputed **leaving *i* out** (LOO), then
the belief mean is blended toward what that LOO level predicts:

```
ℓ_LOO(i)  = level recomputed without exercise i's vote
lnPred_i  = ln(seedCoef_i) + ℓ_LOO(i)
ownPrec   = 1 / evidenceVar_i
predPrec  = 1 / (σ²_ℓLOO + τ_i²)      // LOO level variance + transfer noise
μ̃_i       = (ownPrec · μ_i + predPrec · lnPred_i) / (ownPrec + predPrec)
```

A **confident** exercise (small `evidenceVar`, large `ownPrec`) is barely moved —
the own measurement arithmetically dominates the sibling prediction. A **cold or
stale** exercise (large `evidenceVar`, tiny `ownPrec`) adopts the LOO prediction
almost fully, borrowing strength from its siblings.

A **single-exercise muscle** has an empty LOO pool, so `ℓ_LOO(i)` falls back to the
exercise's own opinion `o_i`; then `lnPred = ln(seedCoef) + o_i = μ_i`, so the shrink is
a no-op and the projection is exactly the own belief (nothing to borrow from).

### Reported σ = own live belief σ (un-shrunk by design)

`pooledSigma` reported to `PrescriptionPolicy` is the exercise's own aged `belief.sigma`
— it is **not** reduced by sibling evidence. This is a deliberate divergence from the
textbook posterior (which would shrink σ̃ too): preserving own σ maintains the
adaptive-attention surprise-hedge (a just-re-opened belief stays cautious) and the
cold-start caution (a seed-level exercise still shades wide). The failure ceiling in
`PrescriptionPolicy` covers the "never re-prescribe near a just-failed weight" safety
job without needing σ shrinkage.

`exp(μ̃_i)` is the exercise's **projected effective 1RM** (`effectiveE1rm`). The derived
coefficient is `effectiveE1rm / level`, kept so that `level × coef == effectiveE1rm` for
display and history.

This shrink is **non-destructive**: it reads the belief map but never writes it. The
durable state stays purely per-exercise; pooling is a lens applied on the way out.

## How the prescription uses it

`MuscleProjection.effectiveE1rm[exerciseId]` (the pooled mean) and
`MuscleProjection.pooledSigma[exerciseId]` (the own σ) are packaged into `PooledBelief`
and handed to `PrescriptionPolicy.prescribe(exercise, sessionReps)`, which applies
z-shading, overload δ, fatigue discount, failure ceiling, HURT caution, and grid rounding
([#5](05-prescription-policy.md)). The per-muscle level is written to `MuscleGroupStrength`
+ a `baseline_history` row, and the derived coefficients to `coefficient_history` — all
projections in the in-memory `DerivedStateStore`, rebuilt by replay, never a stored
source of truth.

## What replaced the old gauge problem and old gate

**Gauge:** The old baseline × coefficient design had a gauge where you could
scale coefficients up and divide baseline down for identical weights — a separate
`SeedNormalizer` had to sweep it back. That ambiguity is **gone**: there is no stored
coefficient to drift. The durable state is one belief per exercise; level and
coefficients are derived from a fixed seed anchor on every read.

**Old n_eff / kappa / siblingExcess gate (phase 2):** The previous pooling used a
`poolObsVar` scale to compute `n_eff`, a `tauBridge` kappa cap, and a `siblingExcess`
evidence gate that blocked siblings from pulling up a fresh measurement. All three are
replaced by precision-weighted LOO shrink. A tight own belief (small `evidenceVar`)
arithmetically dominates any loose-class sibling prediction without a hard threshold,
and the failure ceiling covers the remaining safety job.
