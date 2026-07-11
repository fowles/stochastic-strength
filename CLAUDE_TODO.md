# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — cleanup

- **`ReplayEngine` default-stepper config trap (phase-4 review, 2026-07-10).** `ReplayEngine`'s no-arg default `stepper = SessionProgressionStepper()` uses default config even if `ReplayEngine(config = X)` is constructed with a non-default config — so `ExerciseBelief.seed/.override` (which use the engine's `config`) and the fold stepper could diverge. NOT a live bug: all three production call sites build `SessionProgressionStepper(config = config)` explicitly. Tidy by having `ReplayEngine` derive its default stepper from its own `config`. Found by the phase-4 whole-branch review.

- **Chart μ-drift parity (phase-2 follow-up, 2026-07-09).** `ExerciseProgressionSeriesBuilder` calls `MuscleStrengthProjector.project(...)` without `muscleLastObs`, so the exercise-detail chart's lines/dots age variance (q) but skip detraining μ-drift, while the planner path applies it. After a long layoff the chart line sits above the weight the planner will actually prescribe. Cosmetic; fix by threading the muscle clock into the series builder (it replays from scratch, so it can compute muscleLastObs) for chart/planner parity. Found by the phase-2 whole-branch review.

## Open — next phase

- **Phase 5: recalibrate GLOBAL defaults from data (empirical-Bayes prior).** Phase 4 personalizes per user from best-guess default constants; the natural next layer is to let accumulated real histories re-center the *defaults themselves* (population-level fit sets the prior center; per-user fit personalizes from there — same `HyperparameterFitter` machinery run over the pooled data). Blocked on n>1 users to be statistically honest (calibrate on some, validate on others; avoid the n=1 circularity of grading on training data). Also: **variance-growth (`processNoisePerDay`) saturated its ×4 fit bound** on the current single history — a strong hint the default (8e-5/day) is too low; when doing this, widen its bound and refit to find the interior value before adopting a new default. Full ceremony (sim re-pin, backtest re-baseline, ProdBSS check) since it moves everyone's prescriptions. Decided 2026-07-10 (user: ship phase 4 now, defaults-from-data as its own phase).

## Open — intended / accepted-by-design (no action needed, kept for visibility)

