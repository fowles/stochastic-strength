# Exercise Reorder on Plan Preview — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a drag-handle grip icon to each exercise row on the plan preview screen so the user can reorder exercises by dragging.

**Architecture:** Add `sh.calvin.reorderable` to wrap the existing `LazyColumn` in `PlanPreviewContent` with reorderable state. Each row gets a drag handle icon with `Modifier.draggableHandle()`; the handle initiates vertical drags while the existing `SwipeToDismissBox` continues to own horizontal swipes. `WorkoutSessionController.moveExercise` performs a single `removeAt`/`add` mutation on the in-memory exercise list — no DB changes needed.

**Tech Stack:** `sh.calvin.reorderable:reorderable:2.4.0`, Jetpack Compose `LazyColumn`, `Icons.Filled.Menu` (material-icons-core)

## Global Constraints

- Min SDK 33, Target SDK 36
- Do NOT add `material-icons-extended` — only `material-icons-core` is in scope
- Order is session-only — no DB schema changes, no migrations
- Follow existing `WorkoutSessionController` patterns: guard on state type, mutate list copy, call `setState`
- All commands run from repo root `/Users/mfk/dev/stochastic-strength`

---

### Task 1: Add reorderable library dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `libs.reorderable` catalog alias, available to import in Task 3

- [ ] **Step 1: Add version and library entry to `libs.versions.toml`**

In the `[versions]` block add:
```toml
reorderable = "2.4.0"
```

In the `[libraries]` block add:
```toml
reorderable = { group = "sh.calvin.reorderable", name = "reorderable", version.ref = "reorderable" }
```

- [ ] **Step 2: Add implementation dependency to `app/build.gradle.kts`**

In the `dependencies { }` block, after the existing `implementation(libs.vico.compose.m3)` line, add:
```kotlin
implementation(libs.reorderable)
```

- [ ] **Step 3: Sync and build to verify the dependency resolves**

Run:
```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL with no unresolved-reference errors.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: add sh.calvin.reorderable dependency"
```

---

### Task 2: Add `moveExercise` to `WorkoutSessionController` and `WorkoutViewModel`, with test

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt`

**Interfaces:**
- Produces: `WorkoutSessionController.moveExercise(from: Int, to: Int)` and `WorkoutViewModel.moveExercise(from: Int, to: Int)`

- [ ] **Step 1: Write the failing test**

Add to `WorkoutSessionControllerTest` (at the end of the class, before the closing brace):

```kotlin
@Test
fun moveExercise_swapsExerciseOrder() = runBlocking {
    // Use a fresh DB so setUp's active session doesn't interfere.
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val freshDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    freshDb.userProfileDao().insert(
        UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
    )
    freshDb.exerciseDao().insertAll(listOf(
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
    ))
    val freshRepo = WorkoutRepository(freshDb)
    seedDerivedStrength(freshDb, freshRepo)
    val freshController = WorkoutSessionController(freshDb, freshRepo, WorkoutSessionBus(), scope)
    freshController.initializeSession(
        locationId = null, locationName = null,
        preferredExerciseCount = 2, preferredRepMin = 5, preferredRepMax = 10,
        weightUnit = WeightUnit.KG,
    )
    freshController.adjustExerciseCount(2)
    // Wait for Loading → PlanPreview
    val deadline = System.currentTimeMillis() + 2000
    while (System.currentTimeMillis() < deadline && freshController.state.value is WorkoutState.Loading) {
        delay(20)
    }

    val before = (freshController.state.value as WorkoutState.PlanPreview).plan.exercises
    assertEquals(2, before.size)
    val firstId = before[0].exercise.id
    val secondId = before[1].exercise.id

    freshController.moveExercise(0, 1)

    val after = (freshController.state.value as WorkoutState.PlanPreview).plan.exercises
    assertEquals(secondId, after[0].exercise.id)
    assertEquals(firstId, after[1].exercise.id)

    freshDb.close()
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest.moveExercise_swapsExerciseOrder"
```
Expected: FAIL with "Unresolved reference: moveExercise".

- [ ] **Step 3: Implement `moveExercise` in `WorkoutSessionController`**

Add after `adjustExerciseWeight` (around line 264):

```kotlin
fun moveExercise(from: Int, to: Int) {
    val preview = _state.value as? WorkoutState.PlanPreview ?: return
    val exercises = preview.plan.exercises.toMutableList()
    exercises.add(to, exercises.removeAt(from))
    setState(preview.copy(plan = preview.plan.copy(exercises = exercises)))
}
```

- [ ] **Step 4: Add delegation in `WorkoutViewModel`**

Add after `adjustExerciseWeight` in `WorkoutViewModel.kt` (around line 136):

```kotlin
fun moveExercise(from: Int, to: Int) = controller.moveExercise(from, to)
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest.moveExercise_swapsExerciseOrder"
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt
git commit -m "feat: add WorkoutSessionController.moveExercise"
```

---

### Task 3: Wire drag-handle reordering in `PlanPreviewContent` and `WorkoutScreen`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/PlanPreviewContent.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`

**Interfaces:**
- Consumes: `WorkoutViewModel.moveExercise(from: Int, to: Int)` from Task 2

- [ ] **Step 1: Update imports in `PlanPreviewContent.kt`**

Add these imports (after the existing import block):

```kotlin
import androidx.compose.material.icons.filled.Menu
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
```

`Modifier.draggableHandle()` is an extension on `ReorderableItemScope` and requires no separate import — it is available automatically within the `ReorderableItem` trailing lambda.

- [ ] **Step 2: Add `onMove` parameter to `PlanPreviewContent`**

Change the function signature from:

```kotlin
internal fun PlanPreviewContent(
    state: WorkoutState.PlanPreview,
    weightUnit: WeightUnit,
    onStart: () -> Unit,
    onReplace: (index: Int, reason: ExerciseRemovalReason) -> Unit,
    onSetExerciseCount: (Int) -> Unit,
    onSetRepRange: (repMin: Int, repMax: Int) -> Unit,
    onAdjustWeight: (index: Int, delta: Float) -> Unit,
    onEditLocation: (locationId: Long) -> Unit,
    onExerciseTap: (exerciseId: Long) -> Unit,
)
```

to:

```kotlin
internal fun PlanPreviewContent(
    state: WorkoutState.PlanPreview,
    weightUnit: WeightUnit,
    onStart: () -> Unit,
    onReplace: (index: Int, reason: ExerciseRemovalReason) -> Unit,
    onSetExerciseCount: (Int) -> Unit,
    onSetRepRange: (repMin: Int, repMax: Int) -> Unit,
    onAdjustWeight: (index: Int, delta: Float) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onEditLocation: (locationId: Long) -> Unit,
    onExerciseTap: (exerciseId: Long) -> Unit,
)
```

- [ ] **Step 3: Replace the `LazyColumn` with a reorderable-aware version**

Replace the entire `LazyColumn { ... }` block (currently lines 167–185 in `PlanPreviewContent`) with:

```kotlin
val reorderState = rememberReorderableLazyListState(onMove = { from, to ->
    onMove(from.index, to.index)
})

LazyColumn(state = reorderState.listState, modifier = Modifier.weight(1f)) {
    items(plan.exercises, key = { it.exercise.id }) { planned ->
        val index = plan.exercises.indexOf(planned)
        ReorderableItem(
            state = reorderState,
            key = planned.exercise.id,
            modifier = Modifier.animateItem(),
        ) { _ ->
            ExercisePreviewRow(
                planned = planned,
                weightUnit = weightUnit,
                dragHandleModifier = Modifier.draggableHandle(),
                onReplace = { reason -> onReplace(index, reason) },
                onWeightDecrement = if (planned.sessionWeight > 0f) {
                    { onAdjustWeight(index, -2.5f) }
                } else null,
                onWeightIncrement = if (planned.sessionWeight > 0f) {
                    { onAdjustWeight(index, +2.5f) }
                } else null,
                onTap = { onExerciseTap(planned.exercise.id) },
            )
            HorizontalDivider()
        }
    }
}
```

Note: `Modifier.draggableHandle()` is an extension on `ReorderableItemScope`, which is the receiver inside the `ReorderableItem` trailing lambda. The call is valid here.

- [ ] **Step 4: Add `dragHandleModifier` parameter to `ExercisePreviewRow` and render the grip icon**

Change the `ExercisePreviewRow` signature from:

```kotlin
private fun ExercisePreviewRow(
    planned: io.github.fowles.stochastic_strength.domain.model.PlannedExercise,
    weightUnit: WeightUnit,
    onReplace: (ExerciseRemovalReason) -> Unit,
    onWeightDecrement: (() -> Unit)?,
    onWeightIncrement: (() -> Unit)?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
)
```

to:

```kotlin
private fun ExercisePreviewRow(
    planned: io.github.fowles.stochastic_strength.domain.model.PlannedExercise,
    weightUnit: WeightUnit,
    dragHandleModifier: Modifier,
    onReplace: (ExerciseRemovalReason) -> Unit,
    onWeightDecrement: (() -> Unit)?,
    onWeightIncrement: (() -> Unit)?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Inside the `SwipeToDismissBox` content lambda, the inner `Row` currently starts with:
```kotlin
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
        .clickable(onClick = onTap)
        .padding(vertical = 12.dp),
) {
    val weightLabel = ...
```

Add the drag handle as the first child of that `Row`, before `val weightLabel`:

```kotlin
Icon(
    imageVector = Icons.Filled.Menu,
    contentDescription = "Drag to reorder",
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = dragHandleModifier
        .padding(start = 4.dp, end = 8.dp)
        .size(24.dp),
)
```

The drag handle is not shown in `ExerciseActionRow` — that branch has no `Icon` call, so no change needed there.

- [ ] **Step 5: Wire `onMove` in `WorkoutScreen`**

In `WorkoutScreen.kt`, inside the `is WorkoutState.PlanPreview ->` branch, add `onMove` to the `PlanPreviewContent` call:

```kotlin
PlanPreviewContent(
    state = s,
    weightUnit = weightUnit,
    onStart = viewModel::startFirstExercise,
    onReplace = viewModel::replaceExercise,
    onSetExerciseCount = viewModel::setExerciseCount,
    onSetRepRange = viewModel::setRepRange,
    onAdjustWeight = viewModel::adjustExerciseWeight,
    onMove = viewModel::moveExercise,
    onEditLocation = { locationId -> onEditLocation(locationId) },
    onExerciseTap = onExerciseTap,
)
```

- [ ] **Step 6: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL with no compilation errors.

- [ ] **Step 7: Smoke test on device**

Install and open the app. Tap "Start Workout". On the plan preview screen:
- Confirm each exercise row shows a hamburger/grip icon (≡) on the left
- Hold the grip icon on any row and drag up or down — the row should lift and follow the finger
- Release — confirm the row drops into its new position and the list updates
- Confirm swipe-left-to-reject still works on rows (horizontal swipe on the row body, not the handle)
- Confirm weight +/− buttons still work

- [ ] **Step 8: Run full unit test suite**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/PlanPreviewContent.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt
git commit -m "feat: drag-handle exercise reorder on plan preview"
```
