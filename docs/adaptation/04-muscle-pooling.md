# Read-time muscle pooling — how exercises cross-inform at prescription time

Source: `domain/progression/MuscleStrengthProjector.kt`
(cross-tuning view: `domain/progression/CrossTuning.kt`)
Design note: `docs/superpowers/specs/2026-07-06-belief-policy-reframe-design.md` §3

Folding is local: each [exercise belief](03-exercise-estimates.md) moves on its own
evidence only. That keeps failures from corrupting siblings, but on its own it would
leave a cold or long-unseen exercise stuck at an uncertain guess. The fix is to let
exercises borrow strength from each other **at read time**, without ever mutating the
stored beliefs. That is `MuscleStrengthProjector.project`, and it runs every time the
app needs a weight (and after every session, to record derived projections).

It does two things: compute a muscle **level**, then **shrink** each exercise toward what
that level predicts.

## Step 1 — the muscle level (n_eff-weighted with seed anchor)

Each loaded exercise (positive seed coefficient) offers a seed-relative opinion of how
strong the muscle is: `μ_i − ln(seedCoef_i)`. The level is the n_eff-weighted average
of those opinions against a fixed-weight seed prior:

```
lnPrior = mean over loaded exercises of (μ_i − ln(seedCoef_i))  // seed-anchored anchor
lnLevel = (levelPrior · lnPrior + Σ neff_i · (μ_i − ln(seedCoef_i))) /
          (levelPrior + Σ neff_i)
```

**n_eff** (effective sample size) is the exercise's precision above the seed floor, in
`poolObsVar` units, computed from the **clean `evidenceVar`** (not the live `sigma2`):

```
neff(belief) = max(0, (1/evidenceVar − 1/σ_seed²) · poolObsVar)
```

Reading `evidenceVar` — the variance the belief would have without adaptation inflation
([#3](03-exercise-estimates.md)) — is deliberate: adaptive attention inflates `sigma2` to
move a consistently-surprised belief's mean, and if pooling read `sigma2` it would misread
that re-opening as "uninformed" and let confident siblings pull the belief back (the prod-BSS
regression). `evidenceVar` tracks accumulated evidence and is immune to that inflation, so a
well-observed exercise keeps its full pooling weight and self-anchor even right after a
surprise.

- A **seed-fresh or stale exercise** has evidenceVar ≈ σ_seed² or larger → neff ≈ 0 → it
  contributes little to the level and is carried by the anchor and siblings.
- A **well-trained exercise** (small evidenceVar) has neff > 0 and votes with weight
  proportional to how much its precision exceeds the seed floor.
- `levelPrior = 0.5` is the fixed effective sample size of the seed anchor. A
  thinly-evidenced muscle leans on it; a stale lone voter decays back toward the seed
  level rather than defining the level by itself.

`poolObsVar = 2.0e-3` defines the scale of one vote (calibrated by `BeliefSimulationTest`
to bracket the coverage-vs-p table at 2.0e-3 mid-band). Phase 3 deletes this and
replaces it with per-equipment-class τ (see below).

## Step 2 — shrink each exercise toward its prediction (bridge pooling)

For each exercise, the level predicts a target via that exercise's seed coefficient, and
the belief mean is blended toward that prediction. The prediction's evidence is capped
at what a τ-noised transfer earns (`kappa = min(poolObsVar / tauBridge², siblingExcess)`):

```
lnPred  = ln(seedCoef) + lnLevel
kappa   = min(poolObsVar / tauBridge², siblingExcess)
lnUsed  = (neff_self · μ + kappa · lnPred) / (neff_self + kappa)
```

where `tauBridge = 0.25` and `siblingExcess` is the sum of `max(0, neff_j − neff_self)`
over all sibling exercises j. The evidence gate means:

- A **confident** exercise (large neff_self) is barely moved by the level prediction —
  same-age or staler siblings cannot lift a fresh own measurement.
- A **cold or stale** exercise (neff_self ≈ 0) leaks authority to the sibling pool and
  adopts the prediction weighted by kappa.

`pooledSigma` — reported as the own aged belief σ — is the uncertainty fed into
`PrescriptionPolicy` for z-shading ([#5](05-prescription-policy.md)).

`exp(lnUsed)` is the exercise's **projected effective 1RM** (`effectiveE1rm`). The derived
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

**Old priorStrength/evidence gate:** The previous phase-1 pooling used a `priorStrength = 1.0`
fixed weight for the sibling prediction and an evidence gate that blocked siblings from
pulling up a fresh measurement. Both are replaced. The tauBridge kappa cap provides the
same safety — a fresh own measurement (high neff) is arithmetically barely moved by the
prediction (kappa ≪ neff), while the evidence gate (siblingExcess) preserves the
directionality invariant without a hard threshold.

## Phase 3 note

Phase 3 (pooling swap) will replace `tauBridge` with per-equipment-class transfer
tightness τ (barbell 0.08, machine/cable 0.20, other loaded 0.25) and the kappa cap with
proper leave-one-out shrink (LOO-σ²_ℓ in place of the kappa formula). That swap requires
a `BeliefSimulationTest` re-pin and a real-history backtest re-baseline.
