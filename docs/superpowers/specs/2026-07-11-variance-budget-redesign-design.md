# Variance-Budget Redesign — Implementation Phase (obs-noise + session day-effect)

**Date:** 2026-07-11
**Status:** Design, approved. Ready for an implementation plan.
**Precursor:** the measurement/identification study (sub-project 1), SHIPPED 2026-07-11.
Study spec: `2026-07-11-variance-identification-study-design.md`. Problem statement:
`2026-07-11-variance-budget-problem-definition.md` (its §0 is the binding principle).
Study report: `app/build/variance-identification-report.txt`.

---

## 0. Operating principle (inherited, binding)

**Trust the real held-out data, not the constants or the synthetic simulation.** Do not shrink
fitted values toward existing defaults; the defaults are n=0 guesses. Do not tune to make
`BeliefSimulationTest` green if that conflicts with held-out forward-chaining CV on the real history —
fix or make the sim honest instead. See `feedback_trust_the_data` in memory.

## 1. What the study established (the mandate for this phase)

Held-out one-step-ahead log-score, forward-chaining CV over the real 24-session history
(B0 = default = −277.5; B1 = procNoise ×16 = −225.7):

- **obs-noise ×3 → −201.2 (+76.3 vs B0), INTERIOR.** Observation noise is underspecified.
- **session day-effect σ_day ≈ 0.18 → −221.2 (+56.2 vs B0), INTERIOR.** A genuine whole-session
  good-day/bad-day random intercept the model lacks.
- Residual decomposition: total one-step residual var **0.0465 = 42.8% between-session + 57.2%
  within-session.** The day-effect owns the between share; obs-noise owns the within share.
- `student-t` and `procNoise` gained but **pin their bounds** → release valves, correctly demoted.
- `transfer-τ` and `anchor-precision` **flat** → exonerated, not the problem, left untouched.

Because obs-noise and the day-effect were each measured *in isolation against B0*, they partly explain
the **same** residual. Adopting both at their solo optima (×3 / 0.18) would double-count. This phase
**re-fits them jointly** against the deployed mechanism.

## 2. Scope (decided)

**In scope:** adopt *both* structural winners together —
1. raise the observation-noise budget (fixed global defaults), and
2. add a transient session day-effect random intercept to the real filter —
with a **joint** offline re-fit, re-pinned/re-baselined gates, and docs.

**Explicitly deferred (out of this phase):** re-freeing rep-noise to the per-user
`HyperparameterFitter`; absolute/decoupled fitter bounds; explicit light-lift prescription damping;
full demotion of `BeliefSimulationTest` from a pinning gate; adding `σ_day` to the prescription
predictive band. Each is noted where it touches the design.

## 3. The augmented observation model

Every load-bearing set is modeled in log space as:

```
obs_setLn  =  μ_exercise  −  fatigue(set k)  +  d_session  +  ε ,   ε ~ N(0, σ_obs²)
             └── durable per-exercise belief ─┘   └ shared ┘
                                                  d_session ~ N(0, σ_day²)   (new)
```

Two changes vs today, both justified by the 42.8% / 57.2% residual split:

1. **`σ_obs` gets more budget.** The four observation-noise constants — `repNoiseBucket`,
   `repNoiseCounted`, `repNoiseRel`, `obsModelSd` — are scaled by a single multiplier `obsMult`
   (uniform scaling is exactly what the study measured; per-constant re-identification is a deeper
   cut, deferred). This soaks up the **within-session** share.
2. **`d_session` is new.** One shared latent per session, `N(0, σ_day²)`, absorbing the
   **between-session** good-day/bad-day share. It is a **transient shared nuisance**: estimated from
   the session's own sets, integrated out of the belief updates, and **discarded after the session —
   never durable.** It is not drift and not an athlete-level state (an AR/persistent day-effect goes
   beyond what the study measured and is out of scope).

`obsMult` and `σ_day` are **jointly re-fit** (§5). Expect `obsMult < 3` once `d` owns the
between-session share.

## 4. Filter integration — two-pass session fold

`d` couples all of a session's sets, so `SessionProgressionStepper.step()` replaces today's
per-exercise-independent loop with a session-scoped **two-pass** fold:

- **Pass 1 — estimate `d_S`.** Using each exercise's *pre-session* aged belief as the offset-free
  predicted mean, combine every load-bearing set's residual `(obsLocation − predMean)` —
  precision-weighted by `cleanVar + σ_obs²`, censored sets contributing their moment-matched location
  — into the posterior `d_S ~ N(m_d, v_d)`, starting from the `N(0, σ_day²)` prior. No belief mutation
  in this pass.
- **Pass 2 — fold beliefs.** Fold each set into its own exercise belief exactly as today (aging,
  adaptive attention, censoring, drift — all unchanged), but with the observation **shifted by `−m_d`**
  and its obs-noise variance **inflated by `v_d`** (marginalizing residual day-uncertainty). The
  per-exercise fold stays otherwise **local** — the only cross-exercise coupling is the shared `d_S`.
- `d_S` is discarded after the session. **No new `ReplaySnapshot` field carried across sessions, no DB
  migration.** `replayDerivedState()` stays idempotent.

This is a cleaner variant of the study's causal within-session `DayEffectScorer` (it conditions every
fold on the full-session `d` estimate rather than a running one). Consistency is preserved because §5
re-fits `σ_day` against *this* mechanism.

Edge cases the fold must handle: `σ_day = 0` ⇒ `v_d = 0`, `m_d = 0` ⇒ **bit-identical to today**; a
single-exercise session (still a valid `d_S`, just weaker); an all-HURT/empty session (HURT sets are
policy-only and contribute no observation, so they never inform `d_S`).

## 5. Joint offline re-fit

Wire `obsMult` and `sessionDayEffectSd` into `EstimatorConfig`, both defaulting so the filter is
**behavior-identical to today** until adoption (`obsMult = 1`, `σ_day = 0`). Then reuse the existing
`RecalibrationHarness` forward-chaining CV to grid-search **both together against held-out
one-step-ahead score** — fitting against the *actual deployed two-pass mechanism*, not a study proxy.

- 2D grid over `(obsMult, σ_day)`; adopt the joint argmax.
- **Require an interior optimum in *both* dimensions.** If either pins a bound it is a release valve
  (§0); stop and reconsider rather than adopt against the wall.
- Bake the joint optimum into `EstimatorConfig` defaults. Emit a committed report
  (sibling to `recalibration-report.txt`) as the evidence of record.

## 6. Prescription impact & light-lift behavior

Both changes **reduce** belief reactivity — more obs-noise shrinks the Kalman gain; the day-effect
marginalization stops a globally-high session from shoving every belief up. This is the **opposite** of
the procNoise release valve that destabilized light lifts.

- **Verify** with the existing `LightLiftSwing` diagnostic (ex29, today swings 4.5 ↔ 6.8 kg): expect
  the max session-to-session step to **shrink**, not grow. Explicit light-lift damping stays deferred
  and is expected to prove unnecessary.
- **Prescription band unchanged this phase.** The day-effect is a fold-time nuisance; a fresh session's
  unknown `d` is *not* added to the `z·σ̃` prescription band. (Adding `σ_day²` there is a defensible
  honesty tweak but would systematically lower every prescription and muddy the re-baseline — deferred,
  noted.) `PrescriptionPolicy` is otherwise untouched.

## 7. Gates — data beats the sim

- **`BeliefSimulationTest` — re-pin, and make it honest.** The sim is tamer than reality (problem
  statement §finding 5); wider intervals will make its 80% predictive interval over-cover. Rather than
  merely relaxing the assertion, **inject a matching `σ_day` into the synthetic lifter's
  data-generating process** so the sim actually exercises the day-effect machinery, then re-pin the
  constants. The sim is **not** a veto: if it ever conflicts with held-out CV, CV wins. (Full demotion
  from a pinning gate stays deferred.)
- **Re-baseline** `ProdBssPrescriptionTest` (pins the prod BSS) and the full backtest BAND,
  user-approved, documenting the reprice direction/magnitude — the same ceremony as every prior phase.

## 8. Testing & rollout sequence

1. **TDD the two-pass day-effect fold** behind `σ_day` + `obsMult`, both defaulting to today's values
   (no behavior change): `σ_day = 0` ⇒ identical to today; a uniformly-high session yields `d_S > 0`
   and *dampened* per-exercise updates; single-exercise, censored-set, and empty/HURT sessions behave
   sanely; replay stays idempotent.
2. **Joint re-fit**, adopt the interior joint optimum, commit the report.
3. **Re-pin `BeliefSimulationTest`** (with injected `σ_day`) + **re-baseline** the backtest and
   `ProdBssPrescriptionTest`.
4. **Docs:** `EstimatorConfig` KDoc + `docs/adaptation/` refreshed to describe the day-effect and the
   obs-noise budget; whole-branch opus review before done.

No DB migration. jj commits at each checkpoint; user reshapes and pushes.

## 9. Success criteria

- The two-pass fold is behavior-identical to today when `σ_day = 0, obsMult = 1` (regression-pinned).
- The joint `(obsMult, σ_day)` optimum is **interior in both dimensions** and improves held-out CV over
  B0; report committed.
- `LightLiftSwing` max step does not grow (expected to shrink).
- `BeliefSimulationTest` re-pinned with an honest (day-effect-bearing) synthetic lifter; backtest and
  `ProdBssPrescriptionTest` re-baselined with documented, user-approved reprice.
- Full JVM + instrumented suites green; whole-branch review clean.
</content>
</invoke>
