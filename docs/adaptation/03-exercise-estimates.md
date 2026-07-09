# Per-exercise beliefs — the durable progression state

Source: `domain/progression/ExerciseBelief.kt`, `domain/progression/BeliefUpdater.kt`
Tuning: `EstimatorConfig` (pinned by `BeliefSimulationTest`)
Applied by: `WorkoutRepository.applySessionProgression` via `SessionProgressionStepper`

The **only durable progression state** is one `ExerciseBelief` per loaded exercise:

```
ExerciseBelief(mu: Float, sigma2: Float, updatedAt: Long)
```

`mu` is the mean of ln(fresh 1RM, kg) — the log of your first-set, pre-fatigue one-rep
max. `sigma2` is the variance; √sigma2 reads as **relative uncertainty** (0.04 ≈ ±4%).
There is no stored baseline and no stored coefficient.

A brand-new exercise is seeded at its starting 1RM with `sigma = sigmaSeed = 0.25`
(±25% uncertainty). A manual weight override (including historical DETRAIN rows) is
written with `sigma = sigmaOverride = 0.10` — tighter, because the user has just stated
a number.

## Aging: variance growth and detraining drift

Whenever a belief is read or folded, it is first **aged** from `updatedAt` to `now`:

1. **Variance growth.** σ² increases by `processNoisePerDay = 8e-5` per idle day,
   clamped to [σ_min², σ_max²] = [0.02², 0.30²]. A belief gets progressively
   less certain the longer the exercise goes untrained. At the seed uncertainty (0.25²),
   variance is already near the ceiling and barely grows further — but a well-trained
   belief (σ near σ_min) doubles in about 3 weeks of inactivity.

2. **Detraining drift.** μ decreases when the **muscle** (not just this exercise) has
   been unloaded long enough. Drift counts the overlap of [updatedAt, now] with the
   window (muscleLastObs + 14 d, ∞) — the 14-day grace period means skipping a couple
   of weeks doesn't immediately reduce the estimate. After grace: μ decreases by
   `detrainRatePerWeek = 0.01` per idle week, capped at `detrainCap = 0.25` per idle
   gap. A new observation on any exercise in the muscle resets the gap. A muscle that
   has never been observed does not drift. This replaces the old detraining dialog —
   detraining now happens automatically and passively; if the drift was large enough to
   matter at next prescription, `PlanPreview` shows a one-line notice.

Aging is a **pure function of timestamps**, so replay is deterministic.

## Folding per-set observations into the belief

`SessionProgressionStepper` feeds each `SetObservation` into `BeliefUpdater`, in set
order by `setNumber`. Each fold ages the belief first, then applies either a Gaussian or
censored (Tobit) update.

**Gaussian update** (counted failure, TOO_HARD with actualReps): standard scalar Kalman
step. Kalman gain k = σ²/(σ² + s²) where s is the observation noise from the set.

**Censored update** (RIR buckets, uncounted failure, RIR_5_PLUS): observation z = x + s·ε
constrained to [L, U] (either bound may be null = unbounded). With σ_t² = σ² + s²:

```
α = (L − μ) / σ_t,  β = (U − μ) / σ_t,  Z = Φ(β) − Φ(α)
m_z = μ + σ_t · (φ(α) − φ(β)) / Z
v_z = σ_t² · (1 + (α·φ(α) − β·φ(β)) / Z − ((φ(α) − φ(β)) / Z)²)
k   = σ² / σ_t²
μ'  = μ + k · (m_z − μ)
σ'² = clamp(σ² − k²·(σ_t² − v_z))
```

This is the truncated-Gaussian moment match — exact for this model. Numerically: α, β
clamped to ±6; if Z < 1e-6 (prior mass misses the window) the update falls back to a
Gaussian at the violated bound.

σ² is clamped to [σ_min², σ_max²] = [0.02², 0.30²] inside every fold step (per set,
not once per exercise). Later sets in the same session age with Δt = 0, so only the
first set of a session pays the variance-growth term.

## HURT never touches the belief

HURT sets produce no `SetObservation` and reach neither μ nor σ². They are collected as
muscle-level policy events during replay and applied at prescription time as a decaying
caution multiplier. See [#5](05-prescription-policy.md) and `domain/policy/PrescriptionPolicy.kt`.

## Why folding is local

A fold for exercise *i* touches only *i*'s belief. A failure on the barbell bench never
reaches into the dumbbell fly's stored numbers. This makes the "a failure must not
corrupt siblings" guarantee **structural** rather than something the math has to be
careful about. Sharing strength between exercises happens at read time, non-destructively
([#4](04-muscle-pooling.md)).

## What replaced the old fold

`ExerciseEstimateUpdater.fold` with its asymmetric `wUp`/`wDown`/`wDownSnap` weights,
`confidenceCap`, and `halfLifeMs` is deleted. The belief model separates these concerns
cleanly: the Bayesian update has no policy bias (no up/down asymmetry), uncertainty grows
via process noise (aging), and evidence accumulates as reduced σ² (clamp plays the role
of the old confidence cap). Prescription preferences (conservative shading, overload push)
live entirely in `PrescriptionPolicy` ([#5](05-prescription-policy.md)).

## Output

An updated `ExerciseBelief` per trained exercise, written back into the belief map that
`replayDerivedState` rebuilds and `MuscleStrengthProjector` reads. Phase 3 note:
`processNoisePerDay`, `detrainRatePerWeek`, and the rep-noise bases are candidates for
per-user MAP fitting in phase 4.
