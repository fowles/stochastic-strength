# Selectable progression chart + time-traveling Cross-tuning

**Date:** 2026-06-23
**Status:** Approved, pending implementation plan
**Builds on:** `2026-06-23-per-exercise-progression-chart-design.md` (the chart + cross-tuning bars this extends)

## Problem

The per-exercise progression chart and the Cross-tuning bars are static: the
bars always reflect the latest state, and the chart's dots carry no detail. We
want the chart to be **selectable over time** — picking a session updates the
entire Cross-tuning section to that moment — and we want the dots to reveal the
sets that produced them.

## Goals

1. A **numeric header** at the top of the Cross-tuning section: the same colored
   boxes as the chart legend (own = primary blue, siblings = grey, merged = error
   red) but showing the **values** of those three lines at the selected session.
2. The chart is **selectable**. Selecting a session updates the *entire*
   Cross-tuning section — the numeric header **and** the agreement + contribution
   bars — to reflect that session's point in time (estimates as they stood then).
3. A **tooltip** pinned on the selected session's dots, stacking one block per dot
   (the target exercise first, then each sibling that trained that day):

   ```
   Deadlift
   ~11@125lbs
   ~11@125lbs
   9@125lbs
   ```

## Non-goals

- No DB/schema changes; no new durable state. Frames are recomputed on demand in
  the same muscle replay the chart already runs.
- No change to progression/prescription behavior.
- The muscle-detail screen's cross-tuning section is unchanged (it has no chart to
  select on); this feature is the exercise-detail screen only.

## Decisions (from brainstorming)

- **Recompute scope:** everything historical — header *and* bars recompute at the
  selected session's timestamp.
- **Header vs legend:** keep both. The text legend (boxes + labels) stays above the
  chart; the numeric header (boxes + values) is new, at the top of Cross-tuning.
- **Tooltip content:** all dots at the selected session — one block per exercise
  with a dot that day, target first.
- **Selection model:** default = latest session selected (section shows current
  state, as today); selection **persists** when you lift your finger until you pick
  another session.

## Design

### 1. Data layer — per-session frames (extend the existing builder)

`ExerciseProgressionSeriesBuilder` already replays the target's muscle through
`ReplayEngine` with a recording observer that samples the 5 plot series per
session. Extend that same observer (single replay) to also emit one **frame** per
session that touched the muscle. The builder returns a combined result:

```kotlin
data class ExerciseProgressionData(
    val series: ExerciseProgressionSeries,   // unchanged — drives the chart lines/dots
    val frames: List<ProgressionFrame>,      // one per session that touched the muscle, ascending
)

data class ProgressionFrame(
    val timestampMs: Long,
    val own: Float,                          // own-estimate line value at this session
    val siblings: Float?,                    // leave-one-out prediction (null if undefined)
    val merged: Float,                       // merged effectiveE1rm at this session
    val crossTuning: List<CrossTuningRow>,   // computeCrossTuning(...) at THIS session's asOf
    val observations: List<SessionExerciseObservation>, // per dot, target first
)

data class SessionExerciseObservation(
    val exerciseId: Long,
    val name: String,
    val sets: List<ObservedSet>,
)

data class ObservedSet(
    val reps: Int,           // implied reps (RIR reserve added) or actual reps
    val isEstimate: Boolean, // true => render with a leading "~"
    val weightKg: Float,     // domain stays unit-free; screen converts
)
```

Inside the observer, for each session that touched the muscle (the same guard the
series sampling uses), at the post-step `asOf`:

- `own` / `siblings` / `merged` — already computed by `sampleSession` (reuse it; it
  returns these as single-point lists). The frame stores the scalar values.
- `crossTuning` — call `computeCrossTuning(snapshot.currentEstimates,
  snapshot.seedCoefficients, namesById, muscleIds, now = asOf)`. `namesById` is
  loaded once from `db.exerciseDao().getAll()` at the top of `build`.
- `observations` — for every muscle exercise with full-weight sets that session
  (target first, then siblings in the muscle's id order), build a
  `SessionExerciseObservation` from that exercise's sets. Each `ObservedSet` is
  derived from the set's feedback via the **shared implied-reps helper** below.

The builder is domain and stays UI-free: it emits structured `ObservedSet`s, never
formatted strings. The screen converts `weightKg` to the user's unit.

**Shared implied-reps helper.** The "~11" reps estimate (target reps + RIR reserve)
already lives in `MuscleBaselineDetailViewModel.formatBaselineSetLine`. Factor the
reps-derivation into one domain function so the new tooltip and the existing
baseline-event rows cannot drift:

```kotlin
// domain — returns null for sets that contribute no displayable observation
// (warmups / unfinished). HURT has weight but no rep estimate (reps = actualReps
// or 0, isEstimate = false); the screen decides how to render HURT.
fun impliedObservedSet(set: WorkoutSet): ObservedSet?
```

`formatBaselineSetLine` is then re-expressed in terms of this helper (its `~`/`@`
string formatting stays in the UI layer). This removes the duplicated
`RESERVE_RIR_*` arithmetic.

`getExerciseProgressionSeries(exerciseId)` is **replaced** by
`getExerciseProgressionData(exerciseId): ExerciseProgressionData` (its only caller
is the exercise-detail ViewModel, updated in §3). The repo method is the ViewModel
seam.

### 2. Chart — selection + persistent marker

Extend `ExerciseProgressionChart` with:

```kotlin
fun ExerciseProgressionChart(
    series: List<ProgressionChartSeries>,
    yFormatter: (Float) -> String,
    selectedSessionEpochDay: Long?,             // x of the pinned session, or null
    onSelectEpochDay: (Long?) -> Unit,          // touch → nearest session's epoch-day
    tooltipLabel: (epochDay: Long) -> CharSequence, // stacked blocks for that session
    modifier: Modifier = Modifier,
)
```

Wiring (APIs confirmed present in vendored Vico):

- `markerVisibilityListener: CartesianMarkerVisibilityListener` — `onShown` /
  `onUpdated` read `targets.first().x` (an epoch-day `Double`), snap it to the
  nearest series x, and call `onSelectEpochDay`. `onHidden` is **ignored** so the
  selection persists after release.
- `persistentMarkers = { selectedSessionEpochDay?.let { marker at it } }`
  (`PersistentMarkerScope.at(x: Number)`) pins the marker — and therefore the
  tooltip — at the selected session.
- The marker's `DefaultCartesianMarker.ValueFormatter` returns
  `tooltipLabel(target.x.toLong())` so the bubble shows the stacked exercise
  blocks rather than raw y-values. A multi-line `CharSequence` (newline-separated)
  renders as the example shows.

Selection state is owned by the screen (hoisted); the chart is stateless w.r.t.
selection. The x-domain is epoch-day (as today), so selection is keyed by
epoch-day; the ViewModel maps epoch-day ↔ session timestamp (nearest, since two
sessions can share a day).

### 3. Screen + ViewModel

The ViewModel loads `ExerciseProgressionData` and pre-computes a per-session view
keyed by epoch-day:

```kotlin
data class FrameView(
    val timestampMs: Long,
    val headerOwn: String, val headerSiblings: String, val headerMerged: String, // WeightFormatter
    val crossTuning: List<CrossTuningRow>,
    val tooltip: CharSequence,   // stacked "Name / ~reps@weight" blocks, target first
)
// state: framesByEpochDay: Map<Long, FrameView>, defaultEpochDay: Long? (latest)
```

`tooltip` and the header strings are formatted here with `WeightFormatter` +
`state.weightUnit` (display units stay in the UI layer). The "~reps@weight" line
reuses the same `~`/`@` formatting the baseline screen uses, now fed by
`ObservedSet`.

The screen holds `var selectedEpochDay by remember { mutableStateOf(defaultEpochDay) }`.
Layout:

```
[legend: boxes + labels]                         (above chart, unchanged)
[ExerciseProgressionChart(selected, onSelect, tooltipLabel)]
SectionHeader("Cross-tuning")
  [numeric header: ■primary <own>  ■grey <siblings>  ■red <merged>]   ← selected FrameView
  CrossTuningSection(selectedFrame.crossTuning, highlightedName = exercise.name)
```

`onSelectEpochDay` updates `selectedEpochDay`; the numeric header and
`CrossTuningSection` both read `framesByEpochDay[selectedEpochDay]`. If the
selected epoch-day has no frame (e.g. an empty muscle), fall back to the default;
if there are no frames at all, show the existing empty placeholder.

The numeric-header colored boxes reuse `progressionColors()` (now `internal`) so
they match the chart strokes exactly — same source of truth as the legend.

### 4. Testing

- **Builder (`ExerciseProgressionSeriesBuilderTest` additions):** a session's frame
  carries the right `own/siblings/merged`; `crossTuning` is evaluated at the
  session's `asOf` (a later session's frame differs from an earlier one as
  estimates move); `observations` lists the target first then siblings, and only
  exercises that trained that day; `ObservedSet` rep/estimate/weight values.
- **Shared helper (`impliedObservedSet`):** RIR feedbacks → reserve-added reps with
  `isEstimate = true`; TOO_HARD → actual reps, `isEstimate = false`; warmup/unfinished
  → null. Add a test asserting `formatBaselineSetLine` still produces the same
  strings after being re-expressed on the helper (no regression on the baseline
  screen).
- **Cross-tuning historical (`CrossTuningTest` reuse):** already covered at a given
  `now`; the frame test above exercises the per-session evaluation.
- **Chart/ViewModel:** keep to label/format-level unit tests (epoch-day↔timestamp
  mapping, default = latest, frame lookup). Vico marker behavior is verified via
  `assembleDebug` + the on-device check, not unit tests.

## Risks

- **Vico API (highest):** `markerVisibilityListener`, `persistentMarkers { … at x }`,
  and a custom multi-line marker label. All three are present in the vendored Vico
  (`CartesianMarkerVisibilityListener` with onShown/onUpdated/onHidden;
  `PersistentMarkerScope.at(x: Number)`). Gate on `assembleDebug` and an on-device
  pass. Fallback if a capability misbehaves: drive selection from our own touch
  handler and keep the marker transient for the tooltip.
- **Epoch-day collisions:** two sessions on one calendar day map to one x; selection
  resolves to the nearest/last session that day. Acceptable; noted.
- **Tooltip height:** "all dots at that session" can stack several blocks. Acceptable
  per the decision; the bubble grows with the count.
