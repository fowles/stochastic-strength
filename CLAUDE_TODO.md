# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Larger refactors (deferred from 2026-06-13 cleanup pass)

- **Split `WorkoutScreen.kt` (1035 lines) by state.** 14 inline composables; each
  `*Content` is bound to a single `WorkoutState` variant with no cross-talk.
  Suggested split: `PlanPreviewContent.kt` (PlanPreview + ExercisePreviewRow +
  ExerciseActionRow), `ActiveSetContent.kt` (ActiveSet + TimedSet +
  ExerciseSetLayout + FeedbackButtons), `WarmupSetContent.kt`,
  `RestingContent.kt` (Resting + WeightReductionCard + NextExerciseCard +
  RemainingExerciseList + RemainingExerciseRow), `DoneContent.kt`. Mechanical
  move + import fixes. Biggest long-term velocity win in the codebase.

- **Decompose `WorkoutRepository.applySessionProgression` (~55 lines).** Mixes
  four concerns: hurt-flag update, session rep target lookup, per-muscle
  baseline progression (the meat), and change-log writes. Extract helpers
  `updateHurtFlags(...)`, `determineSessionReps(...)`,
  `progressMuscleBaselines(...)`. The previous memory entry said this was
  "skipped or superseded" by the WorkoutSessionController extraction; that's
  not true — the function still exists in its long form and is the single
  hardest thing in WorkoutRepository to follow.

## Debug & Advanced Stats follow-ups (from 2026-06-12 final review)

- **No ViewModel tests for the new debug screens.** Two behaviours worth
  covering if revisited: (a) the synthetic anchor logic in
  `MuscleBaselineDetailViewModel`, (b) the `parseFeedbacks` CSV splitting in
  the same file.

## Baseline heuristic follow-ups (from 2026-06-15 final review)

- **Surface `heuristicMetadata` on `MuscleBaselineDetailScreen`.** New
  `BaselineHistory` PROGRESSION rows now carry `heuristicName` and
  `heuristicMetadata` (e.g. `target=132.50,conf=0.78,safety=consistent_up`).
  The coefficient-detail screen renders the equivalent for coefficients, but
  `BaselineEvent` does not expose `heuristicMetadata` and
  `MuscleBaselineDetailViewModel` ignores it. Add the field to `BaselineEvent`,
  populate it from `log.heuristicMetadata`, and render below the feedbacks
  line.

- **Missing unit-test cases for `EstBaselineConsensusHeuristic`.** The spec
  listed 15 cases; 13 are implemented. Add: (a) a test where the down cap
  binds (e.g., `TOO_HARD actualReps=1` at low weight with coef=1.0 → raw step
  exceeds `ln(1.10)`); (b) a test where two exercises in one muscle group with
  different coefficients verify the confidence-weighted aggregate arithmetic.

- **KG/LBS double-rounding in floor check.** The heuristic rounds via
  `WeightFormatter.round(..., unit = WeightUnit.KG)` (constructor default),
  but the repository re-rounds via the user's actual `WeightUnit`. For LBS
  users the two grids (2.5 kg vs. 5 lb ≈ 2.27 kg) don't coincide, so the
  floor's `bNew == bOld` check fires on a KG-rounded value the repo then
  shifts. Not a regression (the old engine had the same issue at finer
  granularity) and direction of movement is preserved, but worth fixing by
  passing the user's `WeightUnit` into the heuristic constructor at
  `StochasticStrengthApp` wiring.
