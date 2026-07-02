# Warmup Rest Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a 90-second rest screen between the last warmup set and the first working set.

**Architecture:** Add `WARMUP_DONE` to the existing `StagedKind` enum. `completeWarmupSet()` in `WorkoutSessionController` calls `stageRest()` instead of directly clearing `warmupSetIndex` when the last warmup finishes. The staged-rest machinery (timer, undo, notification, advance) reuses unchanged. `RestingContent` gets a subtitle line and an up-next card correction for the new kind.

**Tech Stack:** Kotlin, Jetpack Compose, coroutines, Room (in-memory for tests), AndroidJUnit4

## Global Constraints

- Min SDK 33, Target SDK 36
- All controller tests are **instrumented** (`androidTest`), not unit tests — they use Room in-memory + real `WorkoutSessionController`
- Run instrumented tests with: `./gradlew :app:connectedAndroidTest`
- Run unit tests with: `./gradlew :app:testDebugUnitTest`
- No new DB migrations, no new composables, no new states

---

### Task 1: State machine — `WARMUP_DONE` staged rest

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt`

**Interfaces:**
- Produces: `StagedKind.WARMUP_DONE` (new enum value, consumed by Task 2)
- Produces: after `completeWarmupSet()` on the last warmup, state is `WorkoutState.Resting` with `staged.kind == WARMUP_DONE`, `staged.undoTarget` = last warmup `ActiveSet`, `staged.commitTarget` = `ActiveSet(setIndex=0, warmupSetIndex=null)`, `currentSetRowId == NO_ROW`

- [ ] **Step 1: Write three failing instrumented tests**

Add a `toLastWarmup()` helper and three tests to `WorkoutSessionControllerTest`. Also update `toWorkingSet()` to skip the warmup rest (after this change it lands in `Resting` before the first working set).

In `WorkoutSessionControllerTest.kt`, replace `toWorkingSet()` with:

```kotlin
private suspend fun toLastWarmup() {
    var s = controller.state.value as? WorkoutState.ActiveSet ?: return
    while (s.warmupSetIndex != null && s.warmupSetIndex!! + 1 < s.plannedExercise.warmupSets.size) {
        controller.completeWarmupSet()
        delay(20)
        s = controller.state.value as? WorkoutState.ActiveSet ?: return
    }
}

private suspend fun toWorkingSet() {
    toLastWarmup()
    val s = controller.state.value
    if (s is WorkoutState.ActiveSet && s.warmupSetIndex != null) {
        controller.completeWarmupSet()
        val resting = awaitState<WorkoutState.Resting>()
        if (resting.staged?.kind == StagedKind.WARMUP_DONE) {
            controller.skipRest()
            awaitState<WorkoutState.ActiveSet>()
        }
    }
}
```

Add these three tests:

```kotlin
@Test
fun completeWarmupSet_lastWarmup_transitionsToWarmupDoneResting() = runBlocking {
    toLastWarmup()
    val lastWarmupState = controller.state.value as WorkoutState.ActiveSet
    assertNotNull(lastWarmupState.warmupSetIndex)

    controller.completeWarmupSet()
    val resting = awaitState<WorkoutState.Resting>()
    assertEquals(StagedKind.WARMUP_DONE, resting.staged!!.kind)
    assertEquals(lastWarmupState.exerciseIndex, resting.staged!!.commitTarget!!.exerciseIndex)
    assertEquals(0, resting.staged!!.commitTarget!!.setIndex)
    assertNull(resting.staged!!.commitTarget!!.warmupSetIndex)
    assertEquals(WorkoutSessionController.NO_ROW, resting.currentSetRowId)
}

@Test
fun completeWarmupSet_warmupDoneRest_skipAdvancesToFirstWorkingSet() = runBlocking {
    toLastWarmup()
    controller.completeWarmupSet()
    awaitState<WorkoutState.Resting>()

    controller.skipRest()
    val active = awaitState<WorkoutState.ActiveSet>()
    assertEquals(0, active.setIndex)
    assertNull(active.warmupSetIndex)
}

@Test
fun completeWarmupSet_warmupDoneRest_undoReturnsToLastWarmup() = runBlocking {
    toLastWarmup()
    val lastWarmupState = controller.state.value as WorkoutState.ActiveSet
    controller.completeWarmupSet()
    awaitState<WorkoutState.Resting>()

    controller.undoLastSet()
    val after = awaitState<WorkoutState.ActiveSet>()
    assertEquals(lastWarmupState.warmupSetIndex, after.warmupSetIndex)
    assertEquals(lastWarmupState.exerciseIndex, after.exerciseIndex)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest.completeWarmupSet_lastWarmup_transitionsToWarmupDoneResting"
```

Expected: FAIL — `StagedKind` has no `WARMUP_DONE` value (compile error or wrong state).

- [ ] **Step 3: Add `WARMUP_DONE` to `StagedKind`**

In `WorkoutState.kt`, change:

```kotlin
enum class StagedKind { SWAP, ADJUST_WEIGHT, END_EXERCISE, STOP_WORKOUT }
```

to:

```kotlin
enum class StagedKind { SWAP, ADJUST_WEIGHT, END_EXERCISE, STOP_WORKOUT, WARMUP_DONE }
```

- [ ] **Step 4: Update `completeWarmupSet()` in `WorkoutSessionController.kt`**

Replace the existing function:

```kotlin
fun completeWarmupSet() {
    val current = _state.value as? WorkoutState.ActiveSet ?: return
    val warmupIdx = current.warmupSetIndex ?: return
    val nextIdx = warmupIdx + 1
    setState(current.copy(
        warmupSetIndex = if (nextIdx < current.plannedExercise.warmupSets.size) nextIdx else null,
    ))
}
```

with:

```kotlin
fun completeWarmupSet() {
    val current = _state.value as? WorkoutState.ActiveSet ?: return
    val warmupIdx = current.warmupSetIndex ?: return
    val nextIdx = warmupIdx + 1
    if (nextIdx < current.plannedExercise.warmupSets.size) {
        setState(current.copy(warmupSetIndex = nextIdx))
    } else {
        val commitTarget = WorkoutState.ActiveSet(
            plan = current.plan,
            exerciseIndex = current.exerciseIndex,
            setIndex = 0,
            sessionId = current.sessionId,
            warmupSetIndex = null,
        )
        stageRest(current, StagedAction(
            kind = StagedKind.WARMUP_DONE,
            undoTarget = current,
            commitTarget = commitTarget,
        ))
    }
}
```

- [ ] **Step 5: Run all instrumented tests**

```
./gradlew :app:connectedAndroidTest
```

Expected: all tests pass, including the three new ones.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt
git add app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt
git commit -m "feat: stage warmup-to-working-set rest via WARMUP_DONE kind"
```

---

### Task 2: UI — subtitle and up-next card for `WARMUP_DONE`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/RestingContent.kt`

**Interfaces:**
- Consumes: `StagedKind.WARMUP_DONE` (from Task 1)

- [ ] **Step 1: Update subtitle `when` block in `RestingContent.kt`**

Find the subtitle `when` block (around line 91–97):

```kotlin
val subtitle = when (state.staged?.kind) {
    StagedKind.STOP_WORKOUT -> "Finishing workout"
    StagedKind.END_EXERCISE -> "Exercise stopped"
    StagedKind.SWAP -> "Swapped exercise"
    StagedKind.ADJUST_WEIGHT -> "Weight changed"
    null -> "Logged: ${state.lastFeedback?.displayLabel ?: ""}"
}
```

Add the new arm:

```kotlin
val subtitle = when (state.staged?.kind) {
    StagedKind.STOP_WORKOUT -> "Finishing workout"
    StagedKind.END_EXERCISE -> "Exercise stopped"
    StagedKind.SWAP -> "Swapped exercise"
    StagedKind.ADJUST_WEIGHT -> "Weight changed"
    StagedKind.WARMUP_DONE -> "Warmup complete"
    null -> "Logged: ${state.lastFeedback?.displayLabel ?: ""}"
}
```

- [ ] **Step 2: Update the up-next card in the staged branch**

Find the `state.staged != null ->` branch in the `Box(modifier = Modifier.weight(0.2f) ...)` block (around line 157–168):

```kotlin
state.staged != null -> {
    val up = state.staged.commitTarget?.let { it.plan.exercises.getOrNull(it.exerciseIndex) }
    if (up != null) {
        val warmup = up.warmupSets.firstOrNull()
        NextExerciseCard(
            title = if (warmup != null) "Warm up" else "Up next",
            exerciseName = up.exercise.name,
            weight = warmup?.weight ?: up.sessionWeight,
            equipment = up.exercise.equipment,
            weightUnit = weightUnit,
        )
    }
}
```

Replace with:

```kotlin
state.staged != null -> {
    val up = state.staged.commitTarget?.let { it.plan.exercises.getOrNull(it.exerciseIndex) }
    if (up != null) {
        val isWarmupDone = state.staged.kind == StagedKind.WARMUP_DONE
        val warmup = if (isWarmupDone) null else up.warmupSets.firstOrNull()
        NextExerciseCard(
            title = if (isWarmupDone) "First set"
                    else if (warmup != null) "Warm up"
                    else "Up next",
            exerciseName = up.exercise.name,
            weight = warmup?.weight ?: up.sessionWeight,
            equipment = up.exercise.equipment,
            weightUnit = weightUnit,
        )
    }
}
```

- [ ] **Step 3: Build and verify**

```
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL with no errors or warnings about unhandled `StagedKind` values.

- [ ] **Step 4: Manual verification on device**

Launch the app and start a workout with a barbell exercise (which has warmup sets). Verify:
1. Tapping "Next Warm-up" advances through warmups as before (no rest).
2. Tapping "Start Working Sets" on the last warmup → rest screen appears showing:
   - Subtitle: "Warmup complete"
   - Up-next card: "First set: [exercise name]" with working-set weight (not warmup weight)
   - 90-second countdown
3. "Skip Rest" → first working set (`ActiveSet` with `setIndex=0`, no warmup indicator).
4. "Undo" from that rest screen → returns to the last warmup set.

- [ ] **Step 5: Run full test suite**

```
./gradlew :app:connectedAndroidTest
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/RestingContent.kt
git commit -m "feat: show warmup-complete subtitle and first-set card on warmup rest screen"
```
