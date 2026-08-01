# Read-time muscle pooling, prescription, and policy caps

Source: `domain/belief/BeliefPooling.kt` (pooling), `domain/belief/BeliefPrescriber.kt`
(success-chance target), `domain/policy/PrescriptionPolicy.kt` (caps + nudge)
Cross-tuning view: `domain/progression/CrossTuning.kt`
Trace: `domain/belief/PrescriptionTrace.kt` — the "why this weight" explanation, one gym
sentence per stage below

Folding is local: each [exercise estimate](03-exercise-estimates.md) moves on its own
evidence only. That keeps failures from corrupting siblings, but on its own it would leave
a cold or long-unseen exercise stuck at a stale guess. The fix is to let exercises borrow
strength from each other **at read time**, without ever mutating stored estimates. That is
`BeliefPooling.effective`, and it runs every time the app needs a weight (and after every
session, to record the derived projections).

## Step 1 — confidence-weighted muscle level

Each loaded exercise (positive seed coefficient) with an estimate votes a seed-relative
opinion of how strong the muscle is: `mu_j − ln(seedCoef_j)`, weighted by its **confidence**
— the inverse of its (aged) uncertainty plus a cross-lift-independence term:

```
weight_j = 1 / (sigma2_j + crossLiftIndependenceEstimate^2)
levelLn  = Σ(weight_j · vote_j) / Σ(weight_j)
```

`crossLiftIndependenceEstimate` is our one guess at how much same-muscle lifts move
independently (`fitted` on real history, `BeliefConfig.crossLiftIndependenceEstimate` —
replacing the old design's three simulator-tuned per-equipment values, which collapsed to
one when actually checked against data). There is no separate seed-anchor constant: a cold
exercise has no estimate and so casts no vote, but as soon as it's seeded it sits at
`sigmaSeed` and anchors the level like any other voter — tight evidence naturally
outweighs wide evidence, with no threshold or gate involved.

## Step 2 — leave-one-out sibling prediction, then a confidence blend

For each exercise, the *other* exercises in the muscle predict its capacity — leave-one-out,
so an exercise never borrows its own evidence back:

```
sibling.mu     = ln(coef_i) + levelLn(excluding i)
sibling.sigma2 = 1 / weight(excluding i) + crossLiftIndependenceEstimate^2
```

The exercise's **effective estimate** is the confidence-weighted blend of its own aged
estimate with that sibling prediction:

```
p_own = 1 / sigma2_own
p_sib = 1 / sigma2_sibling
mu_eff    = (p_own · mu_own + p_sib · mu_sibling) / (p_own + p_sib)
sigma2_eff = 1 / (p_own + p_sib)
```

- A **confident** exercise (small own `sigma2`) outweighs its siblings; the prediction
  barely moves it.
- A **cold or stale** exercise (large or absent own `sigma2`) leans on the prediction —
  its sibling pool carries it until it earns its own evidence. An exercise with no estimate
  at all takes the sibling prediction outright.

This blend is **non-destructive**: it reads the estimate map but never writes it. The
durable state stays purely per-exercise; pooling is a lens applied on the way out.
`BeliefSessionStep` uses this same pooling both as the **cold starting guess** for an
exercise's first fold and as the **derived projection** (muscle level + per-exercise
effective 1RM) written to
`MuscleGroupStrength`/`baseline_history`/`coefficient_history` — always a recomputed view,
never a stored source of truth.

## Prescription — success-chance target, then policy

`BeliefPrescriber.targetE1rm` turns an effective estimate into a raw target: a weight we
believe you'll make about 70% of the time (equivalently, the 30th percentile of the
estimated capacity):

```
target = exp(mu_eff − cautionMargin · sqrt(sigma2_eff))
```

`cautionMargin = 0.5244` and `targetSuccessChance = 0.70` are `semantic` — a plain caution
choice, not tuned against data: back off `cautionMargin` standard deviations below the best
guess and you land on a weight with a ~70% chance of success (`Φ(cautionMargin) = 0.70`).
Cold starts are automatically humble (large `sigma2_eff` pulls the target well below
`mu_eff`); as evidence accumulates and `sigma2_eff` shrinks, the target rises toward
`mu_eff`. This raw target is then scaled to the session's chosen rep target by the same
load-aware 1RM formula used everywhere else, and handed to the policy layer.

## Policy: demonstrated-capacity cap, HURT backoff, and the overload nudge

`PrescriptionPolicy.prescribe` is a pure function over plain set-log facts
(`PolicyFacts`, rebuilt from the sets alone — no estimate state) — the constitution's
boundary criterion: plain arithmetic, no inference, no learned constants.

- **Demonstrated-capacity cap.** Within a 28-day expiry window (`semantic`,
  `CAP_EXPIRY_MS`), an exercise's prescription is capped by what its **most recent
  session** demonstrated — a failure caps at the failed weight's implied 1RM; a clean
  session caps at its highest demonstrated upper bound; an all-RIR-5+ session is
  uncapped. A newer session's cap always supersedes an older one entirely, so weight
  creeps back up in proportion to what you've actually shown, rather than jumping straight
  back to a number you just failed.
- **HURT backoff.** A HURT set multiplies its muscle's prescriptions down by 15%
  (`semantic`, `HURT_DEPTH`), fading with a 14-day half-life (`HURT_HALF_LIFE_MS`), floored
  so stacked backoffs never drop a prescription below 60% of raw (`HURT_FLOOR`).
- **Overload nudge.** If an exercise's most recent session was entirely RIR ≥ 2 (no
  failures, nothing tight) and still within the cap window, the prescription is bumped up
  by one grid increment before the cap comparison — the smallest available plate
  (`semantic`). This is what makes steady-state progress possible: in-band feedback
  legitimately leaves `mu` unmoved (the estimate is confirmed, not pushed), so without the
  nudge a clean session would never raise the weight. The demonstrated-capacity cap still
  applies on top and can clamp the nudge away.
- **Rest cooldown.** A muscle hard-stressed within 2 days (`semantic`, `COOLDOWN_MS`) is
  excluded at planning time, as before.

All five policy constants are `semantic` — plain gym-language choices, never touched by
the backtest fitness function, which scores the raw estimate pre-clamp. Every
backtest run reports a **clamp-bind rate**: how often the cap actually binds, and by how
much. A clamp that binds frequently or by a large margin is treated as an estimator health
signal (see the plan's Results appendix for the current numbers), not something to tune
away here.

## The "why this weight" trace

`PrescriptionTraceBuilder.build` explains one prescription end to end, one line per stage
— own estimate, sibling pull, effective estimate, success target, HURT backoff, overload
nudge, capacity cap, rounding — each citing the sets or estimate numbers behind it. (The
on-screen stage labels still read "Own belief"/"Effective belief" in code; only the
"Success target" line was renamed from "Risk percentile".) It never re-implements the math:
it calls `BeliefPrescriber.targetE1rm` and `PrescriptionPolicy.prescribe` directly and
reports what they did, so the trace can never drift from what the app actually prescribes.
It powers the debug exercise-detail screen's "Why this weight" section.

## The cross-tuning view (debug)

`computeCrossTuning` is a read-only diagnostic on the same pooling math. For each exercise
it reports **agreement** — how far its own (aged) estimate sits from a leave-one-out
sibling prediction — and **contribution** — its share of the muscle's total pooling
confidence weight (`weight_i / Σweight`). It changes no state.

## What replaced the old gauge problem

The previous baseline×coefficient design had a *gauge*: you could scale a whole muscle's
coefficients up and divide its baseline down for identical weights, so the scale could
silently drift and a separate renormalization pass had to sweep it back. That ambiguity
stays gone under the estimate stack for the same reason it went away under the older
per-exercise design: there is no stored coefficient. The durable state is one estimate per
exercise; the level and derived coefficients are recomputed freshly from a fixed seed
anchor and confidence weighting on every read, so there is nothing to renormalize.
