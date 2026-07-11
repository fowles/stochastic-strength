# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — cleanup

- **`ReplayEngine` default-stepper config trap (phase-4 review, 2026-07-10).** `ReplayEngine`'s no-arg default `stepper = SessionProgressionStepper()` uses default config even if `ReplayEngine(config = X)` is constructed with a non-default config — so `ExerciseBelief.seed/.override` (which use the engine's `config`) and the fold stepper could diverge. NOT a live bug: all three production call sites build `SessionProgressionStepper(config = config)` explicitly. Tidy by having `ReplayEngine` derive its default stepper from its own `config`. Found by the phase-4 whole-branch review.

- **Chart μ-drift parity (phase-2 follow-up, 2026-07-09).** `ExerciseProgressionSeriesBuilder` calls `MuscleStrengthProjector.project(...)` without `muscleLastObs`, so the exercise-detail chart's lines/dots age variance (q) but skip detraining μ-drift, while the planner path applies it. After a long layoff the chart line sits above the weight the planner will actually prescribe. Cosmetic; fix by threading the muscle clock into the series builder (it replays from scratch, so it can compute muscleLastObs) for chart/planner parity. Found by the phase-2 whole-branch review.

## Open — next phase

- **NEXT PHASE: data-driven variance-budget redesign (problem defined 2026-07-11).** Phase-5 adoption was ATTEMPTED and DEFERRED: adopting the data-preferred `processNoisePerDay` revealed a deeper problem — the model's **variance budget is misspecified**, and the constants/synthetic-sim can't be trusted to guide the fix. **Full crisp problem statement + all evidence: `docs/superpowers/specs/2026-07-11-variance-budget-problem-definition.md`** — read that first; it is the seed for a FRESH, unbiased brainstorm (user wants to restart in a clean context and fix this data-driven, not biased by the arbitrary initial constants). Binding principle: **trust the data (real held-out CV), not the guessed constants or the synthetic-lifter sim that pins them.** See [[feedback_trust_the_data]].
  - **Key findings (reproducible from committed `RecalibrationReportTest` + `RecalibrationDecompositionTest`):** procNoise has NO interior (pins any bound; CV peaks ~×16 then over-fits); procNoise is ~95% of the entire recalibration CV gain (τ +0.29 / fatigue +0.68 standalone ≈ 0 — pure co-adaptations); it's a *release valve* for obs-noise being pinned low (Phase 4) — re-open that decision; cranking it to the CV-beneficial level destabilizes light-lift prescriptions (5→15 lb accessory swings); the synthetic `BeliefSimulationTest` over-covers at high procNoise because the sim is tamer than the real athlete → the sim is not a trustworthy variance-calibration gate.
  - **NOT adopted / clean state:** `EstimatorConfig` unchanged (`processNoisePerDay = 8.0e-5`); tree green; ceremony reverted. Harness/decomposition tests committed (analysis-only, test tree), fit box ÷64…×16.
  - **Harness quality nits (non-blocking):** `classify` double-sorts the mature half; relative-IQR flag mis-reads monotonic climbs as STABLE and absolutely-tight small multipliers as FRAGILE (read the trajectory, not the flag); `classify(emptyList())`/`assemble(emptyRows)` edge paths untested; τ nearly inert in the synthetic unit tests (single-exercise muscle).
  - **Still genuinely blocked on n>1** for any population-level empirical-Bayes claim (n=1 personalizes = training-data circularity); harness takes `List<UserHistory>` so user #2 slots in with no structural change.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

