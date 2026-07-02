# Exercise Reorder on Plan Preview

**Date:** 2026-07-02  
**Status:** Approved

## Summary

Add drag-handle reordering to the workout plan preview screen. The user can hold a grip icon on any exercise row and drag it to a new position. Order is session-only — it resets each workout.

## Dependency

Add `sh.calvin.reorderable` version `2.4.0` to `libs.versions.toml` and `app/build.gradle.kts`.

```toml
# libs.versions.toml
[versions]
reorderable = "2.4.0"

[libraries]
reorderable = { group = "sh.calvin.reorderable", name = "reorderable", version.ref = "reorderable" }
```

```kotlin
// app/build.gradle.kts
implementation(libs.reorderable)
```

## Data Layer

### `WorkoutSessionController`

Add one method:

```kotlin
fun moveExercise(from: Int, to: Int) {
    val preview = state as? WorkoutState.PlanPreview ?: return
    val exercises = preview.plan.exercises.toMutableList()
    exercises.add(to, exercises.removeAt(from))
    setState(preview.copy(plan = preview.plan.copy(exercises = exercises)))
}
```

`to` is the destination index in the post-removal list — this is the index the `reorderable` library provides in its `onMove` callback, so the one-liner is correct for both moving earlier and moving later.

No DB changes. Order lives only in the in-memory `WorkoutState.PlanPreview.plan.exercises` for the session lifetime. The progression engine is order-agnostic.

### `WorkoutViewModel`

```kotlin
fun moveExercise(from: Int, to: Int) = controller.moveExercise(from, to)
```

## UI Layer

### `PlanPreviewContent`

Add parameter:

```kotlin
onMove: (from: Int, to: Int) -> Unit,
```

Replace the `LazyColumn` with a reorderable variant:

```kotlin
val reorderState = rememberReorderableLazyListState(onMove = { from, to ->
    onMove(from.index, to.index)
})

LazyColumn(state = reorderState.listState, modifier = Modifier.weight(1f)) {
    items(plan.exercises, key = { it.exercise.id }) { planned ->
        val index = plan.exercises.indexOf(planned)
        ReorderableItem(reorderState, key = planned.exercise.id) { isDragging ->
            ExercisePreviewRow(
                planned = planned,
                weightUnit = weightUnit,
                dragHandleModifier = Modifier.draggableHandle(),
                onReplace = { reason -> onReplace(index, reason) },
                onWeightDecrement = ...,
                onWeightIncrement = ...,
                onTap = { onExerciseTap(planned.exercise.id) },
                modifier = Modifier.animateItem(),
            )
            HorizontalDivider()
        }
    }
}
```

### `ExercisePreviewRow`

Add parameter:

```kotlin
dragHandleModifier: Modifier,
```

Add a grip icon to the left of the row content, applying `dragHandleModifier`:

```kotlin
Icon(
    imageVector = Icons.Filled.Menu,
    contentDescription = "Reorder",
    modifier = dragHandleModifier
        .padding(horizontal = 8.dp)
        .size(24.dp),
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
)
```

`Icons.Filled.Menu` is available in `material-icons-core`. The swipe-to-dismiss wraps the rest of the row unchanged — gestures do not conflict because drag is vertical (handle-initiated) and swipe is horizontal.

### `WorkoutScreen`

Pass through to `PlanPreviewContent`:

```kotlin
onMove = { from, to -> viewModel.moveExercise(from, to) }
```

## What Doesn't Change

- No DB schema changes, no migrations
- Progression engine processes exercises independently — order doesn't matter
- "Swipe left to reject" hint stays; no reorder hint needed (drag handle is self-evident)
- No persistence across sessions

## Testing

`moveExercise` is a trivial list mutation wired identically to existing `adjustWeight` / `replaceExercise` patterns. Verify by building and smoke-testing on device.
