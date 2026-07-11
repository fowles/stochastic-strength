# Problem Definition — The Variance Budget Is Misspecified, and the Constants Can't Be Trusted

**Date:** 2026-07-11
**Status:** Problem statement for a fresh, data-driven phase. NOT a solution design.
**Precursors:** Phase 4 per-user MAP fitting, Phase 5 recalibration harness (`RecalibrationHarness`,
`RecalibrationReportTest`, `RecalibrationDecompositionTest` — all committed and re-runnable).

---

## 0. Read this first: the operating principle for the next phase

**Trust the data, not the constants or the algorithm that happen to exist.**

Almost every number in `EstimatorConfig` was chosen a priori — a reasonable-sounding guess — and
then *pinned by a synthetic-lifter simulation* (`BeliefSimulationTest`) whose noise model encodes the
same guesses. "The model passes the simulation" is therefore close to circular: it mostly confirms
that the constants agree with the assumptions used to pick them. The only ground truth we have is the
**real training history** and how well the model predicts held-out sessions of it.

When we finally let that held-out data speak (Phase 5), it disagreed sharply with the constants. The
next phase must be built to *listen to the data first* and treat every existing constant — and the
synthetic simulation itself — as a suspect to be re-derived or re-justified, not a fixed point to
tune around. Do not shrink toward the current defaults; they have no independent claim (they are
n=0 guesses). Do not tune to make the synthetic lifter happy; it is an assumption, not evidence.

This document deliberately states the **problem and the evidence**, not a solution. The solution
should be brainstormed fresh, unbiased by the arbitrary structure we happen to have today.

---

## 1. The problem in one paragraph

The model's **variance budget is misspecified**: real session-to-session performance scatters far
more than the estimator's variance model assumes. The filter has two places to put variance —
**observation noise** (how much a single set's RIR/rep signal is trusted) and **process noise**
(`processNoisePerDay`, how fast the belief's variance grows between sessions). Phase 4 *pinned
observation noise low by design*, so process noise is the only free knob, and when fit to real data
it runs to whatever bound it is given, soaking up all the unexplained scatter. Raising it genuinely
improves held-out prediction — but it is a **release valve for a mis-specified model**, not a real
measurement of drift, and pushing it to the level the data "wants" destabilizes prescriptions
(especially light lifts). The right fix is to **re-identify where the variance actually belongs**,
from data — not to crank one symptom knob.

---

## 2. The evidence (all reproducible from committed tests)

Run `RecalibrationReportTest` and `RecalibrationDecompositionTest` (need the personal
`app/src/test/resources/backtest/history.json`; both no-op without it). Forward-chaining
cross-validation over the real 24-session history, held-out one-step-ahead predictive score.

1. **`processNoisePerDay` has no interior optimum — it pins any bound.** Widening the fit box
   ÷16…×16 → ÷64…×64 just moved the fitted multiplier from ×16 to ×61 (still against the wall). The
   out-of-sample CV-vs-cap curve *peaks* around ×16 (+31.3 vs default) and **degrades** when the cap
   is widened further (×32 → +28.3, ×64 → +23.3). So its "value" is a **regularization choice**, not
   a measured parameter. A parameter with no interior is a model-misspecification signal.

2. **Process noise is ~95% of the entire recalibration gain.** Single-param decomposition (fixed
   config, held-out tail), delta vs all-default baseline:
   - procNoise alone (×16): **+51.7** of the +62.4 all-three gain.
   - τ alone (×2.6): **+0.29**. fatigue alone (×0.15): **+0.68**. Both ≈ zero standalone.
   - Leave-one-out: removing procNoise collapses +62.4 → **+2.2**; removing τ → 53.6; removing
     fatigue → 59.6.
   - procNoise dose-response decelerates: ×4 +29.6, ×8 +43.2, ×16 +51.7.
   - **Interpretation:** τ and fatigue have no independent out-of-sample value; they only "help" as
     co-adaptations to a cranked procNoise. The whole recalibration is one thing: process noise
     compensating for under-modeled variance.

3. **The per-user fitter's bound is *relative* to the default, and procNoise pins it.** The mature
   (fitted) path always lands near `default × fitterCap`. So raising the global default proportionally
   raises the fitted procNoise, pushing it *past* the CV-optimal ×16 into the over-fitting regime.
   Default and fitter bound are not independent knobs.

4. **Adopting procNoise at the CV-beneficial level destabilizes light-lift prescriptions.** On the
   real history, the lightest accessory (a 5 lb lift) swings 5 → 15 lb session-to-session under the
   more reactive belief — a real consequence of high process noise, not grid noise alone; it trips the
   fitted-band trip-wire at 200%. Real-weight lifts (≥ 10 kg) reprice modestly (≤ 20%, p95 ≈ 15%). The
   pathology is concentrated where the belief is thin and the grid is coarse.

5. **The synthetic-lifter simulation disagrees with the real data — because the sim is tamer than
   reality.** At the data-preferred process noise, `BeliefSimulationTest`'s 80% predictive interval
   over-covers (~0.96 vs 0.80 target): the intervals are "too wide" *for the synthetic lifter*. But on
   the real history the wider intervals are **better** calibrated (higher held-out predictive density —
   that is what drove the signal). The synthetic lifter's session-to-session variance is smaller than
   the real athlete's. **This means the simulation is not a trustworthy gate for variance
   calibration** — it validates the constants against assumptions, not against reality.

---

## 3. Why the current constants and simulation cannot be trusted as-is

- `EstimatorConfig` holds ~20 hand-chosen constants (z, δ, `obsModelSd`, `adaptRunThreshold`,
  `adaptInflationPerExcess`, `levelAnchorPrecision`, `tauBarbell/MachineCable/OtherLoaded`,
  `fatiguePerSet`, `processNoisePerDay`, `repNoiseBucket/Counted/Rel`, `sigmaSeed`, …). Each has a
  plausible-sounding KDoc justification; none were fit to held-out real data.
- Most are **pinned by `BeliefSimulationTest`**, a synthetic lifter whose noise/fatigue model is
  *defined to match the estimator's model* (e.g. the sim's cross-set fatigue is set equal to
  `EstimatorConfig.fatiguePerSet`). So the simulation cannot discover that the model is wrong about
  variance — it assumes the model is right. Finding 5 shows exactly this blind spot.
- Phase 4 **pinned observation noise / rep-trust** because letting the fitter tune it made it "learn
  to distrust the user's feedback" (drove `repNoise` to its ×4 cap). That was read as a failure mode
  and the knob was frozen. **Re-open that conclusion.** It may not have been "the fitter misbehaving";
  it may have been the fitter correctly detecting that real residuals are large and, given a
  mis-structured model, the only way it could express that was by inflating the one noise term it was
  allowed to move. The Phase-5 procNoise pinning is the same signal reappearing on the *other* knob.
  The lesson is not "pin the knobs" — it is "the variance structure is wrong."

---

## 4. The open questions for a data-driven redesign (frame, do not pre-answer)

The next phase should approach these from the data, not from the current structure:

- **Where does the real session-to-session variance actually live?** Observation noise (per-set
  signal), process noise (between-session drift), a distinct **day-to-day performance** component
  (whole-session multiplicative "good day / bad day" that is neither per-set noise nor permanent
  drift), heavier-tailed observations, or some combination? The current model has no explicit
  day-effect term; a large chunk of the "unexplained scatter" may be exactly that.
- **Can observation noise and process noise be jointly identified from the real history** (not the
  sim, not with one frozen), so the variance budget is split by evidence instead of by decree?
- **What is the right validation gate?** Held-out forward-chaining CV on real history is the ground
  truth used here. Should the synthetic simulation be (a) rebuilt so its noise matches the real
  athlete, (b) demoted from a pinning gate to a smoke test, or (c) replaced by data-CV gates? Do not
  let a synthetic gate veto a data-supported change again without scrutiny.
- **Should the per-user fitter use absolute bounds** (or per-parameter, non-relative bounds) so that
  a global default and the fitter's reach are decoupled, and so procNoise can't be dragged into the
  over-fitting regime just because the default moved?
- **Light-lift / coarse-grid behavior:** does prescription need explicit damping or a floor so that a
  legitimately more-reactive belief does not produce jumpy 5→15 lb accessory swings? Is relative-%
  even the right way to reason about prescriptions near the grid floor?
- **Which constants survive contact with data at all?** Re-derive or re-justify each one against
  held-out CV; retire the ones that are only sim-pinned artifacts.
- **Are the constants for converting to or from pool muscle belief reasonable?**
  Look at the data to get a sense for it when we have corresponding exercises
  near each other.

## 5. What NOT to do (bias traps observed this phase)

- Do **not** adopt `processNoisePerDay ×N` as a patch. It is a symptom knob; the CV win is real but it
  is compensating for the wrong model structure, and cranking it destabilizes prescriptions.
- Do **not** shrink fitted/measured values toward the existing defaults "to be safe." The defaults are
  guesses; loyalty to them re-imports the guesswork. Legitimate caution comes from the *data's own*
  uncertainty (trajectory spread, non-stationarity, identifiability, ablation), never from the prior
  constant. (See `feedback_trust_the_data` in memory.)
- Do **not** trust an aggregate flag or a convergence summary over a direct held-out measurement. In
  Phase 5 the projector's "STABLE" flag on τ was misleading — τ's real standalone CV value was ~0.
- Do **not** tune parameters to make `BeliefSimulationTest` green if that conflicts with real held-out
  CV. Fix (or demote) the simulation instead.

## 6. State / reproduction / what is committed

- **Nothing was adopted.** `EstimatorConfig` is unchanged (`processNoisePerDay = 8.0e-5`); the working
  tree is green; the ceremony (constant edit, sim re-pin, re-baseline) was reverted after this problem
  surfaced.
- **Committed and re-runnable** (all in `app/src/test/.../domain/backtest/`, analysis-only, test tree):
  `RecalibrationHarness` (forward-chaining CV + `heldOutTailScore` + `configWithMultipliers`),
  `RecalibrationReportTest` (per-param report → `app/build/recalibration-report.txt`),
  `RecalibrationDecompositionTest` (single-param decomposition →
  `app/build/recalibration-decomposition.txt`). Harness fit box is currently ÷64…×16 (CV-optimal cap;
  see the sweep comment in `harnessFitConfig`).
- Requires the personal fixture `app/src/test/resources/backtest/history.json` (gitignored); all these
  tests no-op without it.
- Design/plan history for the harness: `docs/superpowers/specs/2026-07-10-phase5-defaults-from-data-design.md`,
  `docs/superpowers/plans/2026-07-10-phase5-defaults-from-data.md`; fitting background:
  `docs/adaptation/06-fitting.md`.

---

**Next step:** brainstorm a data-driven variance-budget redesign in a fresh context, starting from
§1 and §4, treating §0 as the binding principle.
