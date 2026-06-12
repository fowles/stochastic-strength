# About + Debug & Advanced Stats Screen

## Motivation

Power users and developers need a way to inspect the internals of the
progression engine — current per-muscle baselines, the events that changed
them, and per-exercise coefficient history including the heuristic that
produced each value. We want this surfaced without cluttering the screens
ordinary users interact with daily.

The approach: add a standard **About** screen (reachable from `HomeScreen`)
that links out to a hidden-but-not-obscured **Debug and Advanced Stats**
screen via a plain button at the bottom of About. Ordinary users have no
reason to navigate there; power users can find it by reading the About
page.

## Scope

In scope:

- New `AboutScreen` reachable from `HomeScreen`.
- New `DebugStatsScreen` reachable from `AboutScreen`, showing a muscle
  baseline grid and an exercise coefficient list (recent changes + full
  alphabetical).
- New `MuscleBaselineDetailScreen` showing a baseline-over-time chart
  plus a chronological list of baseline change events for one muscle
  group.
- New `ExerciseCoefficientDetailScreen` showing a coefficient-over-time
  chart plus a list of coefficient change events including the heuristic
  name and raw metadata.
- Extracting `StrengthGrid` from `HistoryScreen` into a reusable component
  used by both `HistoryScreen` and `DebugStatsScreen`.
- A small reusable `DebugLineChart` composable factored from the
  single-series subset of `ExerciseDetailScreen`'s chart.

Out of scope:

- Any write/reset actions (e.g. "Recompute coefficients now", "Reset
  baseline"). The Debug screen is read-only inspection.
- Tap-to-unlock or other concealment mechanisms — the entry point is a
  plain visible button on the About screen.
- A licenses / OSS acknowledgments section on About. May be added later.
- Changes to the underlying `BaselineChangeLog` /
  `CoefficientChangeLog` schemas or to the writers in
  `WorkoutRepository`. Both tables already capture the data we need.

## Navigation

Four new routes added to `AppNavigation.kt`:

| Route                            | Arg                       | Screen                              |
| -------------------------------- | ------------------------- | ----------------------------------- |
| `about`                          | —                         | `AboutScreen`                       |
| `debug`                          | —                         | `DebugStatsScreen`                  |
| `debug/muscle/{muscleGroup}`     | `String` (enum name)      | `MuscleBaselineDetailScreen`        |
| `debug/coefficient/{exerciseId}` | `Long`                    | `ExerciseCoefficientDetailScreen`   |

The coefficient detail route is named distinctly from the existing
`exercise/{exerciseId}` route so the two screens don't collide.

`HomeScreen` gains an `onAbout: () -> Unit` parameter and a new
`OutlinedButton("About")` placed below the existing "Locations" button.
`AppNavigation` wires it to `navigate("about")`.

Back navigation everywhere uses the standard `navController.popBackStack()`
pattern.

## About screen

`ui/about/AboutScreen.kt`. No ViewModel — all content is static or derived
from `BuildConfig`.

Layout (top to bottom, inside a `Column` with
`verticalScroll(rememberScrollState())`):

1. `TopAppBar` with title "About" and a back arrow.
2. **Header block:** "Stochastic Strength" (`headlineLarge`), tagline
   "Random workouts. Real progress.", and a version line
   `"Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"`.
3. **How it works blurb:** two short paragraphs in `bodyMedium` explaining
   the baseline / coefficient model in plain English. Exact wording
   refined during implementation; the goal is to give ordinary users
   enough context to understand what's in the Debug screen if they wander
   in. Concept-level draft:

   > Every muscle group has a **baseline** — the app's estimate of your
   > 1-rep max for that group. All your working weights are derived from
   > it.
   >
   > Every exercise has a **coefficient** — how hard that lift is for you
   > relative to the baseline. After each session, your feedback nudges
   > the baseline up or down, and over time the app learns your
   > individual coefficients from your performance.

4. **GitHub link:** `OutlinedButton` labelled "View on GitHub" with a
   launch icon. Tapping fires
   `Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fowles/stochastic-strength"))`.
5. `Spacer` with `weight(1f)` (or large fixed height) pushing the next
   button toward the bottom of the screen.
6. `OutlinedButton("Debug and Advanced Stats")` → `navigate("debug")`.

## Debug landing screen (`DebugStatsScreen`)

`ui/debug/DebugStatsScreen.kt` + `DebugStatsViewModel.kt`.

### ViewModel state

```kotlin
data class DebugStatsState(
    val loading: Boolean = true,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val muscleStrengths: List<MuscleGroupStrength> = emptyList(),
    val referenceExerciseIds: Map<MuscleGroup, Long> = emptyMap(),
    val recentCoefficientChanges: List<CoefficientRow> = emptyList(),
    val allCoefficients: List<CoefficientRow> = emptyList(),
)

data class CoefficientRow(
    val exerciseId: Long,
    val exerciseName: String,
    val currentCoefficient: Float,
    val previousCoefficient: Float?,        // populated only for "recently changed" rows; null otherwise
    val computedAt: Long?,                  // null = never computed
    val heuristicName: String?,             // null = never computed
    val heuristicMetadataPreview: String?,  // populated only for "recently changed" rows; first 80 chars, newlines flattened
)
```

`referenceExerciseIds` is the same map `HistoryScreen` uses today to wire
muscle-card taps to the exercise detail screen; in this screen the grid
instead navigates to `debug/muscle/{muscleGroup}`, but the extracted
`StrengthGrid` keeps the parameter for symmetry.

### Data sources

New `WorkoutRepository` methods (all read-only, additive):

- `getAllCoefficientRows(): List<CoefficientRow>` — joins
  `db.exerciseDao().getAll()` (all exercises, including disliked) with
  `db.coefficientChangeLogDao().getLatestPerExercise()`. Exercises with no
  log row receive `currentCoefficient = coefficientSource.get(exercise) ?: 0f`,
  `computedAt = null`, `heuristicName = null`, `previousCoefficient = null`.
  Sorted alphabetically by exercise name.
- `getRecentCoefficientChanges(limit: Int): List<CoefficientRow>` — pulls
  the `limit` most-recent rows from `coefficient_change_log` ordered by
  `computedAt DESC`, joins exercise name, populates
  `previousCoefficient`, `heuristicName`, and `heuristicMetadataPreview`.
  Default `limit = 2`.

Both require a new DAO query on `CoefficientChangeLogDao`:

```kotlin
@Query("SELECT * FROM coefficient_change_log ORDER BY computedAt DESC LIMIT :limit")
suspend fun getMostRecent(limit: Int): List<CoefficientChangeLog>
```

### Layout

```
TopAppBar("Debug and Advanced Stats", back arrow)
LazyColumn:
  SectionHeader("Muscle Baselines")
  StrengthGrid → tap navigates to debug/muscle/{muscleGroup}
  SectionHeader("Recently Changed Coefficients")   // omitted if list empty
  CoefficientRow × ≤2  → tap navigates to debug/coefficient/{exerciseId}
  HorizontalDivider
  SectionHeader("All Exercises")
  CoefficientRow × N (alphabetical) → tap navigates to debug/coefficient/{exerciseId}
```

### CoefficientRow UI

Alphabetical rows (compact):

```
Bench Press                                              0.842
EstCoefConsensusHeuristic
```

Recently-changed rows (taller, more context):

```
Bench Press                                              0.842
0.860 → 0.842 · 2 hours ago
EstCoefConsensusHeuristic · {"shrinkage":0.6,"n":12,...}
```

- Exercise name in `bodyLarge`, coefficient right-aligned formatted as
  `"%.3f"`.
- For never-computed alphabetical rows the secondary line reads
  `"not yet computed"` in `onSurfaceVariant`.
- Relative timestamp uses `DateUtils.getRelativeTimeSpanString` from the
  Android SDK.
- Metadata preview: `heuristicMetadata?.replace("\n", " ")?.take(80)`,
  trailing ellipsis if truncated.

### StrengthGrid extraction

`HistoryScreen.kt` currently has `private` composables `StrengthGrid` and
`StrengthCard`. They move to `ui/components/StrengthGrid.kt`, made
`internal`, with the tap target generalised so both call sites can use
the same component:

```kotlin
@Composable
internal fun <T> StrengthGrid(
    strengths: List<MuscleGroupStrength>,
    tapTargets: Map<MuscleGroup, T>,
    weightUnit: WeightUnit,
    onTap: (T) -> Unit,
    modifier: Modifier = Modifier,
)
```

`HistoryScreen` instantiates with `T = Long` (exercise id, same map it
builds today). `DebugStatsScreen` instantiates with `T = MuscleGroup`
(`MuscleGroup.entries.associateWith { it }`) and an `onTap` that
navigates to `debug/muscle/{muscleGroup}`.

## Per-muscle baseline detail (`MuscleBaselineDetailScreen`)

`ui/debug/MuscleBaselineDetailScreen.kt` + `MuscleBaselineDetailViewModel.kt`.

### ViewModel state

```kotlin
data class MuscleBaselineDetailState(
    val loading: Boolean = true,
    val muscleGroup: MuscleGroup,
    val currentBaseline: Float = 0f,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val events: List<BaselineEvent> = emptyList(),    // newest-first
    val chartPoints: List<ChartPoint> = emptyList(),  // oldest-first
)

data class BaselineEvent(
    val sessionId: Long,
    val timestamp: Long,
    val previousBaseline: Float,
    val newBaseline: Float,
    val reason: BaselineChangeReason,
    val feedbacks: List<SetFeedback>,    // parsed from CSV string in DB
    val sessionReps: Int?,
    val minReductionFraction: Float?,
)
```

### Data sources

New `WorkoutRepository.getBaselineEvents(muscleGroup): List<BaselineChangeLog>`
calls `db.baselineChangeLogDao().getAll()` and filters by `muscleGroup` in
Kotlin. Chronologically there are very few of these per muscle group
(one per session that touched it), so filtering in memory is fine and
avoids a new DAO query.

The ViewModel parses the comma-separated `feedbacks: String?` into
`List<SetFeedback>` by splitting on `,` and mapping each token via
`SetFeedback.valueOf`. Unknown tokens are dropped silently rather than
crashing — defensive against schema drift, since `feedbacks` is a stored
string.

### Chart points

For each event ordered by timestamp ascending, emit
`ChartPoint(event.timestamp, event.newBaseline)`. Prepend one synthetic
anchor point at `events.first().timestamp - 86_400_000L` (one day before
the first change) carrying `events.first().previousBaseline`, so the
chart visualises the starting baseline before any progression occurred.
If `events` is empty the chart section shows the same "No history yet"
fallback as `ExerciseDetailScreen`.

### Layout

```
TopAppBar(muscleGroup.displayName(), back arrow)
LazyColumn:
  Header card:
    "Current baseline"
    "<formatted weight>"  (WeightFormatter.format)
  SectionHeader("Baseline over time")
  DebugLineChart (or "No history yet" placeholder)
  SectionHeader("Change events")
  BaselineEventRow × N  (newest first)
```

### BaselineEventRow

```
Mar 12 · 4:30 PM                                  PROGRESSION
85.0 kg → 90.0 kg
Feedbacks: GOOD, GOOD, EASY · reps: 5
Reduction floor: 5%
```

- Timestamp formatted with the existing `DATETIME_FORMATTER` pattern
  (`"MMM d, yyyy · h:mm a"`).
- Reason rendered as a `labelSmall` tag in `onSurfaceVariant` on the
  right-hand side of the timestamp row.
- Feedbacks line omitted if list empty (e.g. for `MANUAL_OVERRIDE`).
- Reduction floor formatted as `"%.0f%%".format(minReductionFraction * 100f)`;
  whole line omitted if `minReductionFraction` is null.
- Session ID is intentionally not displayed.

## Per-exercise coefficient detail (`ExerciseCoefficientDetailScreen`)

`ui/debug/ExerciseCoefficientDetailScreen.kt` +
`ExerciseCoefficientDetailViewModel.kt`.

### ViewModel state

```kotlin
data class ExerciseCoefficientDetailState(
    val loading: Boolean = true,
    val exercise: Exercise? = null,
    val currentCoefficient: Float = 0f,
    val seedCoefficient: Float?,                          // null if no seed
    val events: List<CoefficientEvent> = emptyList(),     // newest-first
    val chartPoints: List<ChartPoint> = emptyList(),      // oldest-first
)

data class CoefficientEvent(
    val computedAt: Long,
    val previousCoefficient: Float?,
    val coefficient: Float,
    val heuristicName: String,
    val heuristicMetadata: String?,                       // raw, unparsed
)
```

### Data sources

- `WorkoutRepository.getCoefficientEvents(exerciseId): List<CoefficientChangeLog>`
  — new method, calls a new DAO query
  `@Query("SELECT * FROM coefficient_change_log WHERE exerciseId = :exerciseId ORDER BY computedAt ASC") suspend fun getForExercise(exerciseId: Long)`.
- `WorkoutRepository.getExerciseById` — already exists.
- Seed coefficient: `WorkoutRepository.getSeedCoefficient(exercise): Float?`
  — new method that delegates to the constructor-injected
  `coefficientSource.get(exercise)`. This exposes the static seed for the
  Debug header without leaking `CoefficientSource` to the ViewModel.

### Chart points

Same shape as the muscle screen: `ChartPoint(event.computedAt, event.coefficient)`
oldest-first. If `events.first().previousCoefficient` is non-null,
prepend a synthetic anchor at `events.first().computedAt - 86_400_000L`
carrying that previous coefficient. If it's null (genuinely the first
ever computation, with no prior value), skip the anchor.

### Layout

```
TopAppBar(exercise.name, back arrow)
LazyColumn:
  Header card:
    "Current coefficient"
    "0.842"
    "Seed: 0.850"  (small, onSurfaceVariant — omitted if null)
  SectionHeader("Coefficient over time")
  DebugLineChart (or "No coefficient changes yet" placeholder)
  SectionHeader("Change events")
  CoefficientEventRow × N (newest first)
```

### CoefficientEventRow

```
Mar 12 · 4:30 PM                         EstCoefConsensusHeuristic
0.860 → 0.842
─ metadata ─────────────────────────────
{ "muscleGroup": "CHEST", "shrinkage": 0.6, ... }
```

- Heuristic name in `labelSmall` `primary`.
- Metadata block rendered inside a small bordered `Surface` (radius 4dp,
  surface variant background), text in
  `MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)`.
- Metadata block omitted if `heuristicMetadata` is null.
- No expand/collapse — the block is just there. If it gets very long it
  contributes naturally to `LazyColumn` scroll.
- Previous coefficient is omitted from the `→` line if null (first ever
  computation).

## Reusable chart component (`DebugLineChart`)

`ui/debug/components/DebugLineChart.kt`.

```kotlin
@Composable
internal fun DebugLineChart(
    points: List<ChartPoint>,
    yFormatter: (Float) -> String,
    modifier: Modifier = Modifier,
)
```

Factored from the single-series happy path of
`ExerciseDetailScreen.ExerciseChart` (lines ~239–363). Drops the shadow
series, the day-selection callback, and the parent-driven `selectedDay`
state. Keeps the `rangeProvider` (15 % vertical padding), the start/bottom
axes, the `rememberSelectionMarker` style marker. The x-axis still maps
`timestamp / 86_400_000L` to days for label formatting.

`ExerciseDetailScreen.ExerciseChart` is **not** refactored to use this
component — it has additional needs (two series, day-selection, shadow
points) that don't belong in the debug variant. Mild code duplication of
the Vico boilerplate is fine here; the two components evolve
independently.

The muscle screen passes
`yFormatter = { WeightFormatter.format(it, weightUnit) }`; the exercise
screen passes `yFormatter = { "%.3f".format(it) }`.

## Repository surface additions

Summary of new methods on `WorkoutRepository`:

```kotlin
suspend fun getAllCoefficientRows(): List<CoefficientRow>
suspend fun getRecentCoefficientChanges(limit: Int = 2): List<CoefficientRow>
suspend fun getBaselineEvents(muscleGroup: MuscleGroup): List<BaselineChangeLog>
suspend fun getCoefficientEvents(exerciseId: Long): List<CoefficientChangeLog>
suspend fun getSeedCoefficient(exercise: Exercise): Float?
```

`CoefficientRow` is a domain DTO defined in `domain/` (not `ui/debug/`)
so it can be returned from the repository directly. The Debug ViewModel
forwards it to its state without remapping.

## DAO additions

`CoefficientChangeLogDao` gains:

```kotlin
@Query("SELECT * FROM coefficient_change_log ORDER BY computedAt DESC LIMIT :limit")
suspend fun getMostRecent(limit: Int): List<CoefficientChangeLog>

@Query("SELECT * FROM coefficient_change_log WHERE exerciseId = :exerciseId ORDER BY computedAt ASC")
suspend fun getForExercise(exerciseId: Long): List<CoefficientChangeLog>
```

No DAO changes needed for `BaselineChangeLogDao` — filtering by muscle
group happens in-memory after `getAll()`. No schema migration. No new
entities.

## Testing

JVM unit tests for each ViewModel using the existing fake-database
patterns (see `WorkoutRepositoryTest` and similar). For each ViewModel:

- Seeds the relevant tables with a small set of change-log rows.
- Verifies the resulting `State` carries the right events (ordering,
  parsing of `feedbacks` CSV, null handling for `previousCoefficient` /
  `heuristicMetadata`).
- Verifies the chart points have the synthetic anchor and oldest-first
  ordering.
- For `DebugStatsViewModel`, verifies that exercises with no log entry
  fall back to the seed coefficient and surface `computedAt = null`.

Composables are not unit-tested; the existing project pattern is to test
ViewModels only.

## Implementation order

A reasonable ordering for the implementation plan:

1. New DAO queries (`getMostRecent`, `getForExercise`) and the new
   repository methods + `CoefficientRow` DTO + their unit tests.
2. `DebugLineChart` reusable composable.
3. Extract `StrengthGrid` from `HistoryScreen` with the generic
   `tapTargets` parameter; verify `HistoryScreen` still compiles and the
   existing exercise-tap behaviour is preserved.
4. `MuscleBaselineDetailScreen` + ViewModel + route.
5. `ExerciseCoefficientDetailScreen` + ViewModel + route.
6. `DebugStatsScreen` + ViewModel + route.
7. `AboutScreen` + route + `HomeScreen` button wiring.
8. Manual verification on device via the `run` skill.
