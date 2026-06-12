# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Debug & Advanced Stats follow-ups (from 2026-06-12 final review)

- **GitHub button missing launch icon.** `ui/about/AboutScreen.kt` "View on
  GitHub" `OutlinedButton` is text-only; the spec calls for a launch icon
  (`Icons.Default.OpenInNew`) as a clarity cue that the button leaves the app.

- **Coefficient metadata `Surface` missing shape + border.** Spec asked for
  "small bordered `Surface` (radius 4dp, surface variant background)"; the
  implementation uses the default rectangular shape with no border. See
  `ui/debug/ExerciseCoefficientDetailScreen.kt`'s `CoefficientEventRow`.

- **`SectionHeader` is duplicated four times.** Identical `@Composable private
  fun SectionHeader(title: String)` in `HistoryScreen.kt`,
  `DebugStatsScreen.kt`, `MuscleBaselineDetailScreen.kt`,
  `ExerciseCoefficientDetailScreen.kt`. Extract into `ui/components/`.

- **`DATETIME_FORMATTER` duplicated three times.** Same `DateTimeFormatter`
  pattern in `HistoryScreen.kt`, `MuscleBaselineDetailScreen.kt`,
  `ExerciseCoefficientDetailScreen.kt`. Extract alongside `SectionHeader`.

- **`getBaselineEvents` filters in Kotlin.** Spec endorsed this for the current
  data size, but a DAO-side `WHERE muscleGroup = :muscleGroup` query is the
  long-term fix once a user accumulates thousands of sessions.

- **`getLatestPerExercise` uses `MAX(id)` as proxy for "latest".** Works today
  because inserts are sequential, but if the app ever back-fills or edits log
  entries the semantics diverge from `computedAt`. Consider changing to
  `WHERE computedAt = (SELECT MAX(computedAt) ...)` next time the DAO is
  touched.

- **No ViewModel tests for the new debug screens.** Two behaviours worth
  covering if revisited: (a) the synthetic anchor logic in
  `MuscleBaselineDetailViewModel`, (b) the `parseFeedbacks` CSV splitting in
  the same file.
