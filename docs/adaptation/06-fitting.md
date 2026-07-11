# Per-user MAP fitting — calibrating the model to you

Source: `domain/progression/HyperparameterFitter.kt`, `domain/progression/PredictiveScoreAccumulator.kt`
Wired in: `domain/WorkoutRepository.kt` (background fit after session finish), `domain/progression/DerivedStateStore.kt` (holds result in memory)
Spec: `docs/superpowers/specs/2026-07-10-per-user-map-fitting.md`

Every constant in the progression model was chosen to work reasonably well across people
in general. But people differ: some adapt quickly, some fatigue more across sets than
others, some equipment classes transfer more cleanly to one another than a universal τ
would suggest. Phase-4 fitting turns the four most consequential global constants into
per-user values, inferred directly from your history.

## The four fitted parameters

Each parameter is expressed as a **multiplier on its global default**, bounded to
÷4 … ×4 so the fit can adapt substantially but never produce an absurd model:

- **Strength-drift rate** — how fast the process noise q grows variance between
  sessions. A multiplier above 1 means you are more variable than the global model
  assumes (e.g. highly session-dependent performance); below 1 means your numbers are
  unusually repeatable.
- **Per-set fatigue** — the φ factor that discounts each subsequent set from the
  fresh-1RM basis. If you fade faster across sets than the default φ = 0.03 implies,
  the fit raises this multiplier; if you hold your strength through multiple sets, it
  lowers it.
- **Variance-growth rate** — related to but independent of drift: how quickly
  uncertainty accumulates when an exercise has *not* been trained for a while (the
  aging component between sessions). A user who trains very consistently may warrant a
  smaller value; someone with irregular schedules may need a larger one.
- **Cross-exercise transfer scale (τ scale)** — a single multiplier on all per-
  equipment-class τ values (tauBarbell, tauMachineCable, tauOtherLoaded). A smaller τ
  means exercises within a muscle group transfer more tightly to one another — the
  pooled level exerts stronger pull. A larger τ means exercises are treated as more
  independent.

## What is deliberately not fitted: feedback trust

Rep-noise — the observation model uncertainty that governs how much a single set's
RIR or rep-count moves the belief — is **not** a fitted parameter, and this choice is
intentional.

Your own RIR and rep feedback is the clearest training signal the system has. An early
experiment that allowed the fit to tune observation noise found it converging to near
its maximum allowed value: the optimizer discovered it could lower the predictive loss
by nearly ignoring the very data it was supposed to explain. That is a failure mode,
not an insight. Leaving feedback trust pinned to the global rep-noise defaults
(`repNoiseBucket` 0.75 / `repNoiseCounted` 0.5 reps) means the system always takes your
reported effort seriously, and the four structural parameters are fit against that
honest foundation.

## The objective: predictive scoring

The fitter asks: *if I had used these hyperparameters all along, how well would the
model have predicted what you actually did?*

For each session and each exercise trained in that session, it replays your history up
to that point with a candidate set of hyperparameters θ, then scores how well the
one-step-ahead prediction matched the observation. The score for each set combines:

- the **pooled belief mean** (from the LOO muscle-level projection) as the prediction
  centre, and
- the exercise's own **clean variance** (`evidenceVar`) as the prediction spread —
  using the adaptation-immune variance so that an unusually large prior inflation from
  adaptive attention is not penalised as if the model was simply uncertain.

Censored observations (RIR buckets, "too hard") and Gaussian observations (rep-counted
failures) are scored with their appropriate likelihoods. The total log-likelihood
across all observations is the objective.

The fit is **maximum a posteriori (MAP)**: lognormal priors centred at 1 (the global
default multiplier) are added to the log-likelihood, with a prior standard deviation
of 0.5 in log space. This means that with thin history the fit stays close to the
global defaults rather than chasing noise; the data only pulls the parameters away
from defaults when the evidence is consistent and strong.

Optimisation runs in log-parameter space (so the ÷4 … ×4 bounds become symmetric ±
ln 4 ≈ 1.39) using Nelder-Mead, which requires no gradient and handles the non-
smooth censored likelihood cleanly.

## Guardrails

Several safeguards prevent the fit from degrading prescription quality:

- **Minimum history floor** (`minFitSessions ≈ 15`). Fewer than this many completed
  sessions and the fit does not run at all — the defaults are used directly.
- **Default fallback**. After the fit converges, the system evaluates the fitted θ
  and the default θ on the *same* held-out predictive score. If the fitted parameters
  do not beat the defaults, the defaults are used for prescription. You never regress
  from the global model just because the optimiser found a local noise minimum.
- **Bounded multipliers**. The ÷4 … ×4 constraint is enforced as a hard box during
  optimisation; the result can never produce, say, a negative fatigue factor or a
  process noise that causes the variance floor to be hit every session.

## Execution model

The fit runs **in the background**, triggered after `WorkoutRepository.finishSession()`
completes replay. It is keyed on a `FitKey(sessionCount, latestEndTime)` held in
`DerivedStateStore`; if the key matches the current history snapshot, the cached result
is returned immediately and no recomputation occurs. This means the fit self-warms on
startup (the first prescription after launch gets the already-fitted θ without waiting)
and never runs unnecessarily in a loop.

**θ is never persisted to Room.** Like all derived state, it is deterministically
recomputed from the history on every replay. This keeps the database schema stable
(no migration required for the fit), and means a fresh install or history restore
automatically produces the correct fitted values.

The debug screen exposes the current fitted multipliers, the fitted vs. default
predictive scores, and the session count used, so the fit is observable without
requiring a build flag.
