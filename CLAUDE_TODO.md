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
