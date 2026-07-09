# Belief + policy estimator reframe — design

**Date:** 2026-07-06
**Status:** approved design, pre-implementation
**Supersedes (as living design):** the per-exercise estimate progression design
(2026-06-21) and the seed-vote projector design (2026-06-23), which remain as
historical records.

## Motivation

The current estimator is a heuristic approximation of a model it never writes
down: a hierarchical Bayesian filter over log-capacities with censored feedback
observations. Because the model is implicit, three things are conflated —
observation uncertainty with policy (the asymmetric `wUp`/`wDown` weights),
evidence with recency (confidence increments by policy weight), and estimation
with prescription (the estimate is deliberately biased because it is prescribed
directly). Each recent production issue (BSS over-prescription, the evidence
gate, the detraining dialog, the HURT factor) is a patch for one of these
conflations.

This design makes the model explicit. The architecture is unchanged — replay as
the sole source of truth, per-exercise-local writes, read-time pooling,
in-memory derived state — but the quantities become honest: a **belief** (mean
+ uncertainty of fresh 1RM per exercise) updated only by what the data says,
and a **prescription policy** that owns every training decision.

## Decisions log (brainstorming outcomes)

| Question | Decision |
|---|---|
| Scope | Full reframe, one spec |
| Per-set fatigue model | Included — the belief means *fresh* 1RM |
| Default aggressiveness | Match today's feel (progression rate, fail rate); knobs adjustable later |
| Detraining dialog | Deleted; automatic drift + passive plan-preview notice |
| Per-user fitting | Full fitting (5 hyperparameters), heavily guardrailed |
| Implementation strategy | Staged replacement behind stable seams (4 phases, each shippable) |

## Goals

1. Honest per-exercise belief: mean of ln(fresh 1RM, kg) + variance; no policy
   bias inside the estimator.
2. One `PrescriptionPolicy` owning: overload push δ, uncertainty shading z,
   fatigue discount, failure ceiling, HURT caution with decay, layoff easing +
   notice, and the sore-muscle planner cooldown.
3. Reliability-weighted pooling: per-equipment-class transfer tightness τ,
   leave-one-out shrink, evidence gate deleted.
4. Per-user MAP fitting of 5 hyperparameters by one-step-ahead predictive
   scoring during replay, regularized toward global defaults.
5. Behavior-neutral defaults, verified by the rewritten simulation harness and
   an old-vs-new backtest on real exported history.
6. Zero Room migrations. Everything new is derived from the existing set log
   and override rows. Historical DETRAIN/OVERRIDE rows replay unchanged.

## Non-goals (this cycle)

Secondary-muscle credit (primary muscle only); load signal from bodyweight
movements; uncertainty-directed exercise selection; variable sets per exercise
(3 stays fixed); changes to the rep→1RM formula; UI work beyond dialog
removal, the notice line, and minor debug additions.

---

## 1. The belief

```
ExerciseBelief(mu: Float, sigma2: Float, updatedAt: Long)
```

`mu` = mean of ln(fresh 1RM, kg), where *fresh* means first-set pre-fatigue
capacity. `sigma2` = variance; √sigma2 reads as relative uncertainty (0.04 ≈
±4%). Replaces `ExerciseEstimate(lnE, confidence, updatedAt)`; confidence ≡
precision = 1/σ².

**Seeding.** Onboarding seed rows: μ = ln(seed 1RM), σ = σ_seed. Manual
override rows (user-edited weights, including historical DETRAIN rows):
σ = σ_override.

**Aging** — applied whenever a belief is read or folded, from `updatedAt` to
`now`:

1. Variance: σ² ← clamp(σ² + q·Δt, σ_min², σ_max²).
2. Detraining drift, keyed on the **muscle's** last load observation (an
   exercise skipped while its siblings train has not detrained — pooling covers
   it). Let `muscleLast` = time of the muscle's most recent load observation
   before `now`. Drift days = the overlap of [updatedAt, now] with
   (muscleLast + grace, ∞). μ ← μ − driftRate·(driftDays/7), capped at
   driftCap per idle gap (a new observation resets the gap). Pure function of
   timestamps ⇒ replay stays deterministic.

**HURT does not touch the belief** — neither μ nor σ (see Policy).

## 2. Observations: what a set tells us

Each working set is one observation of ln(fresh 1RM) **at its own weight** —
mid-session drops are ordinary observations, not a special bracket case. Sets
update the belief sequentially in set order; each update ages the belief first.

**Fresh-basis conversion.** Set k (1-indexed) happens under fatigue: capacity
at set k = fresh·(1 − φ·(k−1)). All observed bounds/values are divided by
(1 − φ·(k−1)) — i.e., in log space, shifted by −ln(1 − φ·(k−1)).

**Feedback mapping** (set at weight w, target reps r, rep→1RM formula f):

| Feedback | Constraint on set capacity | Update type |
|---|---|---|
| TOO_HARD, actualReps = a | ln f(w, a+½), rep capacity ∈ [a, a+1) | Gaussian, tight |
| TOO_HARD, no count | < ln f(w, r) | one-sided from above |
| RIR_0_1 | ∈ [ln f(w, r), ln f(w, r+2)) | interval |
| RIR_2_4 | ∈ [ln f(w, r+2), ln f(w, r+5)) | interval |
| RIR_5_PLUS | ≥ ln f(w, r+5) | one-sided from below |
| HURT | none (policy event only) | skipped |

Sets with weight ≤ 0 or on zero/null-coefficient exercises carry no load
observation (unchanged from today).

**Noise.** Specified in rep units, converted through the local slope
λ = ∂ln f(w, ρ)/∂ρ at ρ = r (central difference). Rep-space std:
s_reps = √(base² + (ρ_rel·r)²) with base = repNoiseCounted for counted
failures, repNoiseBucket for the RIR buckets. Log-1RM noise s = λ·s_reps.
The steeper slope at light absolute loads makes accessory-lift noisiness
emerge automatically.

**Update math.** Gaussian case: standard scalar Kalman step. Censored case
(observation z = x + s·ε constrained to [L, U], prior x ~ N(μ, σ²)): with
σ_t² = σ² + s², α = (L−μ)/σ_t, β = (U−μ)/σ_t, Z = Φ(β) − Φ(α):

```
m_z = μ + σ_t·(φ(α) − φ(β))/Z
v_z = σ_t²·(1 + (α·φ(α) − β·φ(β))/Z − ((φ(α) − φ(β))/Z)²)
k   = σ²/σ_t²
μ'  = μ + k·(m_z − μ)
σ'² = σ² − k²·(σ_t² − v_z)
```

One-sided cases set α → −∞ or β → +∞. Numerical guards: clamp α, β to ±6;
if Z < 10⁻⁶ treat as a Gaussian observation at the violated bound. Φ/φ via an
Abramowitz–Stegun erf approximation (no stdlib erf). Unit-tested against
numerical integration.

**Deleted:** the recency EMA (`RECENCY_BETA`), the any-failure-caps-at-zero
rule (now emergent: a failure is a tight downward observation), `bracketAggregate`,
`SessionAggregate`, and the never-consumed `sessionConfidence`.
`SessionSignalExtractor` shrinks to a set→observation translator.

## 3. Pooling

Model per muscle: exercise i's fresh log-capacity = ℓ + ln(seedCoef_i) + c_i,
with personal offset c_i ~ N(0, τ_i²). τ by equipment class: **barbell 0.08;
machine/cable 0.20; every other loaded class 0.25.**

**Level.** Opinions o_i = aged μ_i − ln(seedCoef_i), each with variance
v_i = aged σ_i² + τ_i². Prior on ℓ: mean ℓ₀ = unweighted mean of the o_i
(equals seed level for a cold muscle, exactly today's anchor), precision λ₀.
Posterior: precision P = λ₀ + Σ 1/v_i, mean = (λ₀·ℓ₀ + Σ o_i/v_i)/P,
variance σ_ℓ² = 1/P. Display level = exp(posterior mean).

**Shrink.** Per exercise: sibling prediction p_i = ℓ_LOO(i) + ln(seedCoef_i)
with variance σ²_ℓLOO(i) + τ_i², where ℓ_LOO excludes exercise i (fixes the
own-vote double count; O(n²) with n ≤ ~16 is negligible). Pooled belief:

```
σ̃_i² = 1/(1/σ_i² + 1/(σ²_ℓLOO + τ_i²))
μ̃_i  = σ̃_i²·(μ_i/σ_i² + p_i/(σ²_ℓLOO + τ_i²))
```

(μ̃, σ̃) feed the policy. Derived coefficient for display/history =
exp(μ̃_i)/level, as today.

**The evidence gate is deleted.** Its safety job (never re-prescribe near a
just-failed weight) moves to the policy's failure ceiling; a fresh tight
measurement dominates its own blend arithmetically (precision ~hundreds vs
≤ 1/τ² ≈ 16 from any loose-class prediction).

## 4. Prescription policy

`domain/policy/PrescriptionPolicy` (pure). The planner asks
`prescribe(exercise, sessionReps) → weight`. In order:

1. **Base target:** t = μ̃ − z·σ̃ + δ.
2. **Fatigue discount:** t += ln(1 − φ·(S−1)), S = 3 — the *last* set is the
   one targeted at RIR 0–1.
3. **Failure ceiling.** From the most recent completed session containing this
   exercise: if any set failed at (w_f, r_f), ceiling = min over failed sets of
   f(w_f, r_f) (raw set basis; the clear-miss factor and grid absorb the ≤3%
   basis mismatch). A **clear** miss (shortfall ≥ 2 reps, or uncounted) binds
   at ceilingFactorClear × ceiling; whenever nearest-rounding would land at or
   above the failed weight's rep-equivalent, the weight **rounds down** instead
   — which is what makes "strictly below the failed weight" survive coarse
   grids. A
   **marginal** miss (shortfall ≤ 1) binds at 1.0 × ceiling — re-prescribing
   the same grid weight is allowed (preserves the pinned hold-the-weight
   behavior). Rep-aware by construction. Expires after ceilingExpiry, or when
   superseded by any newer completed session on the exercise.
4. **HURT caution:** multiplier Π over hurt events e in the muscle of
   (1 − hurtDepth·2^(−Δt_e / hurtHalfLife)), floored at hurtFloor. Applied to
   exp(t). Matches today's immediate ×0.85 but heals automatically. The
   `ExerciseHurtState` UI flag is unrelated and untouched.
5. **Grid rounding** via `WeightFormatter.round` (round-down whenever an
   unexpired clear ceiling exists and nearest-rounding would land at/above the
   failed weight's rep-equivalent, round-nearest otherwise — see item 3).
6. **Sore-muscle cooldown:** `muscleRested(...)` — verbatim port of the
   planner's current 2-day rule (any TOO_HARD, or >1 RIR_0_1, on a loaded
   exercise in the past 2 days excludes the muscle from generation).
7. **Layoff notice:** if the detraining drift applied to any planned muscle
   since its previous session exceeds noticeThreshold, `PlanPreview` shows one
   passive line ("eased ~X% after the break"). No dialog, no decision.

Policy state (per-exercise ceilings, hurt events, muscle-last-trained times) is
assembled during replay into `DerivedStateStore`. Manual per-exercise weight
overrides bypass the policy exactly as today.

## 5. Per-user fitting

**Fitted (5 scalars):** q, driftRate, reportNoiseScale (one multiplier on both
rep-noise bases), φ, τScale (one multiplier on all τ classes). **Not fitted:**
z and δ (training preferences, not data properties).

**Objective:** Σ over all folded observations of log P(observation | **pooled**
pre-fold belief) — the pooled blend (μ̃, σ̃) recomputed at fold time, not the
own belief alone, because τ enters the model only through pooling: scoring
against own beliefs would leave τScale invisible to the objective and
permanently prior-pinned. Closed form for both Gaussian (predictive density
under N(μ̃, σ̃²+s²)) and censored (log Z as defined above), plus lognormal
log-priors on each parameter centered at the global defaults with sd priorSd
(log space). MAP. (Folds themselves still update the own belief only; pooling
remains read-time.)

**Mechanics:** Nelder-Mead in log-parameter space, fixed initial simplex around
the defaults, iteration cap ~200; each evaluation is one in-memory replay over
preloaded history (no DB inside the loop). Runs inside `replayDerivedState`:
load history once → fit θ → one final replay with fitted θ writes derived rows
→ θ cached in `DerivedStateStore`, re-fit skipped when history is unchanged
(keyed on completed-session count + latest end time). Deterministic and
idempotent; **θ is never persisted**.

**Guardrails:** hard bounds [default/4, default×4]; prior sd calibrated so
< ~20 sessions stays ≈ defaults; fitter skipped entirely below minFitSessions
completed sessions; final check that fitted θ scores ≥ defaults, else fall back
to defaults; debug screen shows fitted-vs-default values and the score gain.

## 6. Defaults

All initial values; the simulation harness tunes them before pinning.

| Parameter | Default | Meaning |
|---|---|---|
| σ_seed | 0.25 | seed-row uncertainty (±25%) |
| σ_override | 0.10 | manual-override uncertainty |
| σ_min / σ_max | 0.02 / 0.30 | belief uncertainty floor / ceiling |
| q | 8×10⁻⁵ /day | variance growth (trained lift ~doubles in ~3 weeks) |
| grace / driftRate / driftCap | 14 d / 1%/week / 25% per idle gap | detraining |
| φ | 0.03 /set | per-set fatigue fraction |
| repNoiseBucket / repNoiseCounted | 0.75 / 0.5 reps | report noise bases |
| ρ_rel | 0.06 | rep-magnitude noise term |
| τ barbell / machine+cable / other loaded | 0.08 / 0.20 / 0.25 | transfer tightness |
| λ₀ | 0.5 | level prior precision (today's levelPrior) |
| z | 0.5 | uncertainty shading |
| δ | 0.01 | overload push (tuned with z to match current feel) |
| ceilingFactorClear / marginal | 0.97 / 1.0 | failure ceiling binding factors |
| ceilingExpiry | 28 d | ceiling lifetime |
| hurtDepth / hurtHalfLife / hurtFloor | 0.15 / 14 d / 0.6 | HURT caution |
| noticeThreshold | 3% | layoff notice trigger |
| fit bounds / priorSd / minFitSessions | ×÷4 / 0.5 / 15 | fitting guardrails |

## 7. Code map

**New** (pure, `domain/`): `policy/PrescriptionPolicy.kt` (+ `PolicyState`),
`progression/BeliefUpdater.kt` (aging, Gaussian + censored updates, erf),
`progression/SetObservation.kt`, `progression/HyperparameterFitter.kt`.

**Reshaped:** `ExerciseEstimate` → `ExerciseBelief`; `EstimatorConfig` → the §6
parameters; `MuscleStrengthProjector` → precision+τ weights, LOO shrink,
pooled (μ̃, σ̃) output; `SessionProgressionStepper` → sequential per-set folds +
policy-event collection (no muscle-wide hurt fold); `ReplayEngine` → PolicyState
accumulation + predictive-score hook; `WorkoutRepository.replayDerivedState` →
fit → final replay → store; `WorkoutPlanner` → takes the policy object instead
of the `prescribedE1rm` map, loses `recentlyFailedMuscles`; `DerivedStateStore`
→ additionally holds θ and PolicyState.

**Deleted:** `DetrainingModel`, `DetrainingDialog`, `WorkoutPlan.detrainOverrides`
/ PlanPreview detraining plumbing; `RECENCY_BETA`, `bracketAggregate`,
`SessionAggregate`; `wUp`, `wDown`, `wDownSnap`, `hurtFactor`, `confidenceCap`,
`halfLifeMs`; the projector evidence gate.

**Untouched:** Room schema v17 and all entities/DAOs, seed tables,
`WorkoutGenerator`, the rep→1RM formula, warmups, backup, Strava.

**Display continuity:** `MuscleGroupStrength`/`baseline_history` = exp(level);
`coefficient_history` = pooled ÷ level; chart dots = the session's broad-prior implied
observation (`impliedSessionE1rm`); the post-session belief mean is already the own-estimate
line, so dots keep showing what the session said. *(Amended during phase 2: dots equal to
the line would carry no information.)* Debug gains a σ band and a fitted-θ panel.

## 8. Implementation phases

Each phase ends shippable: TDD throughout, simulation re-pinned, full suite +
lint green, jj checkpoint commit.

1. **Policy layer** against the *existing* estimator: `PrescriptionPolicy` with
   failure ceiling + HURT-as-policy (delete the `hurt()` fold), z/δ present but
   neutral (z = 0, δ = 0). Planner rewired to consume the policy;
   `muscleRested` moves. Also builds the **backtest harness** and freezes the
   fixture of per-session prescriptions over the real exported history,
   generated from main **before phase 1 lands** — the comparison baseline for
   all four phases (phase 1's own deltas are the intended HURT/ceiling
   semantic changes; the pinned band is set after inspecting them).
2. **Belief swap:** `ExerciseBelief`, per-set observations, censored updates,
   aging with q + detraining drift; dialog deleted, notice added; z/δ activated
   and tuned with the sim to match current feel.
3. **Pooling swap:** τ-weighted level, LOO shrink, gate deleted; re-pin.
4. **Fitting:** scorer, fitter, guardrails, debug panel; re-pin.

## 9. Testing & verification

**Unit:** censored updates vs numerical integration (golden tolerances); aging
and drift curves; erf accuracy; ceiling semantics (clear/marginal, rep-aware,
expiry, round-down); HURT decay/compounding; fitter recovers planted parameters
from synthetic histories, respects bounds, skips below the floor.

**Simulation harness** (same synthetic-lifter frame): *match-feel pins* —
convergence ≤ 12 sessions, tail tracking error ≤ 8%, jitter ≤ 6%, last-set RIR
in the 0–2 band, fail rate ≤ 0.40. *New pins* — calibration (~80% of outcomes
inside the 80% predictive interval, with tolerance); bad-day recovery (one
fluke drop-cascade → prescription recovers within ~2 sessions, ceiling still
blocks immediate re-prescription); layoff (8-week gap → eased return, no
failure spike, re-convergence ≤ 3 sessions); censored responsiveness (a
30%-underestimated lifter pressing 5+ converges within ~4 sessions). Existing
behavioral tests carry forward re-anchored on the policy: failure → next
weight below failed weight; marginal miss holds the grid weight; cold exercise
with trained siblings within 12% of truth; stale/same-age siblings don't pull
up a fresh estimate. `ProdBssPrescriptionTest` stays end-to-end and is
re-pinned (expected ≈ 20 lb).

**Real-history backtest (key gate):** exported backup JSON in test resources;
JVM test replays the full real history through the new stack and asserts: no
NaN/degenerate beliefs anywhere; per-session prescription deltas vs the frozen
current-main fixture within a pinned band; fitted θ in-bounds with score ≥
defaults.

**Instrumented:** existing `connectedAndroidTest` suite stays green.

## 10. Rollout

One release. On update, replay reinterprets all history with the new math —
prescription shifts are exactly what the backtest pinned. No migration; backup
format untouched (dbVersion unchanged). `docs/adaptation/` rewritten (02–04
replaced; new 05-prescription-policy and 06-fitting pages; 01-time-estimation
untouched). Version bump at the end per convention.
