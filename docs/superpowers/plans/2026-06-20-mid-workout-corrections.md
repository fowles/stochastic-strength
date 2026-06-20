# Mid-workout corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an unobtrusive kebab menu to the warmup/working/timed set screens that lets the user swap an exercise (no equipment / dislike), adjust the session weight (plan-only), end the current exercise, or stop the whole workout — every action landing on the rest page so it can be undone.

**Architecture:** Menu actions do not mutate the database or commit at tap time. Each transitions the current `ActiveSet` into a `Resting` carrying a `StagedAction { undoTarget, commitTarget, pendingSwap }`. The rest timer commits the change (and persists any deferred side-effect); Undo discards it and restores the captured originating `ActiveSet`. A new tiered `pickReplacement` parameter drives same-muscle/weightedness replacement for the swap flow only.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room. Unit tests on JVM (`testDebugUnitTest`); controller tests are instrumented (`connectedAndroidTest`, in-memory Room).

## Global Constraints

- Package: `io.github.fowles.stochastic_strength`.
- No DI framework; `WorkoutSessionController` is constructed with `(database, repository, bus, scope)`.
- Progression engine, baseline math, and `strengthOverrides` MUST NOT change — weight adjustment is plan-only.
- No new `WorkoutCommand`s; the notification/bus flow is unchanged.
- App increment: `WeightFormatter.minIncrement(unit)` (2.5 kg / 5 lb).
- Loaded-ness of an exercise: `coefficientSource.get(exercise)?.let { it > 0f } ?: false`.
- Commit messages end with: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. The repo is a colocated git+jj repo; `git commit` is fine.
- Run a single JVM test class: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`.
- Run instrumented controller tests: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"` (emulator is typically running).

---

### Task 1: Tiered `pickReplacement` parameter

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`

**Interfaces:**
- Produces: `enum class ReplacementTier { WEIGHTED_MUSCLE, MUSCLE, ANY }` (top-level in the `domain` package, same file) and
  `fun WorkoutPlanner.pickReplacement(plan: WorkoutPlan, removedIndex: Int, tiers: List<ReplacementTier> = listOf(ReplacementTier.ANY)): PlannedExercise?`
- Consumes: existing private `candidatesFor`, `pickFrom`, and `coefficientSource`.

- [ ] **Step 1: Write the failing test**

Add to `WorkoutPlannerTest.kt`. First extend the existing `planner(...)` helper to accept a coefficient source (add this parameter with a default, and pass it through to the `WorkoutPlanner` constructor's `coefficientSource =` argument):

```kotlin
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan

// add parameter `coefficientSource: CoefficientSource = ExerciseCoefficients` to planner(...) and
// pass `coefficientSource = coefficientSource` into the WorkoutPlanner(...) constructor call.

@Test
fun pickReplacement_tiers_preferSameMuscleAndLoadedness() {
    val removed = exercise(1, "removed", MuscleGroup.CHEST, equipment = Equipment.BARBELL)
    val chestLoaded = exercise(2, "chestLoaded", MuscleGroup.CHEST, equipment = Equipment.BARBELL)
    val chestUnloaded = exercise(3, "chestUnloaded", MuscleGroup.CHEST, equipment = Equipment.BODYWEIGHT)
    val backLoaded = exercise(4, "backLoaded", MuscleGroup.BACK, equipment = Equipment.BARBELL)
    val coeffs = object : CoefficientSource {
        override fun get(exercise: Exercise): Float? = mapOf(
            1L to 1.0f, 2L to 0.8f, 3L to null, 4L to 1.0f,
        )[exercise.id]
    }
    val p = planner(
        exercises = listOf(removed, chestLoaded, chestUnloaded, backLoaded),
        strengths = strengthsFor(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 100f),
        coefficientSource = coeffs,
    )
    val plan = WorkoutPlan(exercises = listOf(PlannedExercise(exercise = removed)), locationId = null, sessionReps = 8)

    val tiers = listOf(ReplacementTier.WEIGHTED_MUSCLE, ReplacementTier.MUSCLE, ReplacementTier.ANY)
    // Tier 1 (same muscle + loaded) is non-empty -> must pick chestLoaded.
    assertEquals(chestLoaded.id, p.pickReplacement(plan, 0, tiers)!!.exercise.id)
}

@Test
fun pickReplacement_tiers_fallThroughToMuscleThenAny() {
    val removed = exercise(1, "removed", MuscleGroup.CHEST, equipment = Equipment.BARBELL)
    val chestUnloaded = exercise(3, "chestUnloaded", MuscleGroup.CHEST, equipment = Equipment.BODYWEIGHT)
    val backLoaded = exercise(4, "backLoaded", MuscleGroup.BACK, equipment = Equipment.BARBELL)
    val coeffs = object : CoefficientSource {
        override fun get(exercise: Exercise): Float? = mapOf(1L to 1.0f, 3L to null, 4L to 1.0f)[exercise.id]
    }
    val p = planner(
        exercises = listOf(removed, chestUnloaded, backLoaded),
        strengths = strengthsFor(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 100f),
        coefficientSource = coeffs,
    )
    val plan = WorkoutPlan(exercises = listOf(PlannedExercise(exercise = removed)), locationId = null, sessionReps = 8)
    val tiers = listOf(ReplacementTier.WEIGHTED_MUSCLE, ReplacementTier.MUSCLE, ReplacementTier.ANY)
    // Tier 1 empty (no same-muscle loaded), Tier 2 (same muscle) -> chestUnloaded.
    assertEquals(chestUnloaded.id, p.pickReplacement(plan, 0, tiers)!!.exercise.id)
}

@Test
fun pickReplacement_defaultAny_unchangedBehavior() {
    val removed = exercise(1, "removed", MuscleGroup.CHEST)
    val other = exercise(2, "other", MuscleGroup.BACK)
    val p = planner(
        exercises = listOf(removed, other),
        strengths = strengthsFor(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 100f),
    )
    val plan = WorkoutPlan(exercises = listOf(PlannedExercise(exercise = removed)), locationId = null, sessionReps = 8)
    // Default tiers = [ANY]; only `other` is a candidate.
    assertEquals(other.id, p.pickReplacement(plan, 0)!!.exercise.id)
}
```

Add imports if missing: `io.github.fowles.stochastic_strength.data.model.Equipment` (already present), `CoefficientSource`, `ExerciseCoefficients` are in the same package (no import needed).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`
Expected: FAIL to compile — `pickReplacement` has no 3-arg overload / `ReplacementTier` unresolved.

- [ ] **Step 3: Implement**

In `WorkoutPlanner.kt`, add the enum near the top (after the imports, before the class):

```kotlin
enum class ReplacementTier { WEIGHTED_MUSCLE, MUSCLE, ANY }
```

Replace the existing `pickReplacement` function:

```kotlin
fun pickReplacement(plan: WorkoutPlan, removedIndex: Int): PlannedExercise? {
    val remaining = plan.exercises.filterIndexed { i, _ -> i != removedIndex }
    return pickFrom(candidatesFor(plan, remaining), remaining, plan.sessionReps)
}
```

with:

```kotlin
fun pickReplacement(
    plan: WorkoutPlan,
    removedIndex: Int,
    tiers: List<ReplacementTier> = listOf(ReplacementTier.ANY),
): PlannedExercise? {
    val removed = plan.exercises[removedIndex].exercise
    val remaining = plan.exercises.filterIndexed { i, _ -> i != removedIndex }
    val all = candidatesFor(plan, remaining)
    for (tier in tiers) {
        val filtered = when (tier) {
            ReplacementTier.WEIGHTED_MUSCLE -> all.filter {
                it.primaryMuscle == removed.primaryMuscle && isLoaded(it) == isLoaded(removed)
            }
            ReplacementTier.MUSCLE -> all.filter { it.primaryMuscle == removed.primaryMuscle }
            ReplacementTier.ANY -> all
        }
        pickFrom(filtered, remaining, plan.sessionReps)?.let { return it }
    }
    return null
}

private fun isLoaded(exercise: Exercise): Boolean =
    coefficientSource.get(exercise)?.let { it > 0f } ?: false
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`
Expected: PASS (all classes, including the pre-existing planner tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt
git commit -m "feat: tiered pickReplacement fallback parameter

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Staged-rest model + `stopWorkout()`

This task introduces the whole staged-action engine and proves it end-to-end with the simplest action (`stopWorkout`).

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/RestingContent.kt` (keep it compiling with nullable `lastFeedback`)
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt`

**Interfaces:**
- Produces:
  - `WorkoutState.Resting.lastFeedback: SetFeedback?` (now nullable), `WorkoutState.Resting.staged: StagedAction? = null`
  - `data class StagedAction(kind, undoTarget, commitTarget, pendingSwap = null)`
  - `data class PendingSwap(reason: ExerciseRemovalReason, exerciseId: Long, locationId: Long?)`
  - `enum class StagedKind { SWAP, ADJUST_WEIGHT, END_EXERCISE, STOP_WORKOUT }`
  - `WorkoutSessionController.NO_ROW: Long`, `WorkoutSessionController.stopWorkout()`, private `stageRest(...)`, private `nextExerciseActiveSet(...)`, private suspend `persistSwap(...)`
  - `WorkoutViewModel.stopWorkout()`
- Consumes: `ExerciseRemovalReason` (already in `WorkoutSessionController.kt`).

- [ ] **Step 1: Write the failing test**

Add to `WorkoutSessionControllerTest.kt`. First add a reusable session-start helper and refactor `setUp` to use it (so later tasks can start a 2-exercise session). Replace the body of `setUp`'s `runBlocking { ... }` (everything after building `db`, `bus`, `scope`, `repository`, and the `derivedState.rebuild { ... }` block) with a call to `startSession(1)`, and add:

```kotlin
private fun startSession(count: Int) = runBlocking {
    controller = WorkoutSessionController(db, repository, bus, scope)
    controller.initializeSession(
        locationId = null, locationName = null,
        preferredExerciseCount = count, preferredRepMin = 5, preferredRepMax = 10,
        weightUnit = WeightUnit.KG,
    )
    controller.adjustExerciseCount(count)
    awaitStateNotLoading()
    controller.startFirstExercise()
    awaitState<WorkoutState.ActiveSet>()
}
```

Then the test:

```kotlin
@Test
fun stopWorkout_landsOnRest_commitFinishes() = runBlocking {
    controller.stopWorkout()
    val resting = awaitState<WorkoutState.Resting>()
    assertNotNull(resting.staged)
    assertEquals(StagedKind.STOP_WORKOUT, resting.staged!!.kind)
    assertEquals(WorkoutSessionController.NO_ROW, resting.currentSetRowId)
    controller.skipRest()
    awaitState<WorkoutState.Done>()
}

@Test
fun stopWorkout_undoRestoresActiveSet() = runBlocking {
    val before = controller.state.value as WorkoutState.ActiveSet
    controller.stopWorkout()
    awaitState<WorkoutState.Resting>()
    controller.undoLastSet()
    val after = awaitState<WorkoutState.ActiveSet>()
    assertEquals(before.exerciseIndex, after.exerciseIndex)
    assertEquals(before.warmupSetIndex, after.warmupSetIndex)
}
```

Add imports: `StagedKind` is in the same package (no import). `assertNotNull` is already imported.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: FAIL to compile — `stopWorkout`, `StagedKind`, `NO_ROW` unresolved.

- [ ] **Step 3a: Model changes in `WorkoutState.kt`**

Make `lastFeedback` nullable, add the `staged` field, and add the new types at the bottom of the file:

```kotlin
data class Resting(
    val plan: WorkoutPlan,
    val exerciseIndex: Int,
    val completedSetIndex: Int,
    val sessionId: Long,
    val secondsRemaining: Int,
    val lastFeedback: SetFeedback?,
    val weightReductionApplied: Boolean = false,
    val weightAtSetStart: Float,
    val currentSetRowId: Long,
    val staged: StagedAction? = null,
) : WorkoutState
```

At the bottom of `WorkoutState.kt` (outside the sealed interface):

```kotlin
enum class StagedKind { SWAP, ADJUST_WEIGHT, END_EXERCISE, STOP_WORKOUT }

data class StagedAction(
    val kind: StagedKind,
    val undoTarget: WorkoutState.ActiveSet,
    val commitTarget: WorkoutState.ActiveSet?,
    val pendingSwap: PendingSwap? = null,
)

data class PendingSwap(
    val reason: ExerciseRemovalReason,
    val exerciseId: Long,
    val locationId: Long?,
)
```

- [ ] **Step 3b: Controller changes in `WorkoutSessionController.kt`**

Add `const val NO_ROW = -1L` to the `companion object` (alongside `REST_SECONDS`).

Add the helpers and `stopWorkout`:

```kotlin
fun stopWorkout() {
    val current = _state.value as? WorkoutState.ActiveSet ?: return
    stageRest(current, StagedAction(
        kind = StagedKind.STOP_WORKOUT,
        undoTarget = current,
        commitTarget = null,
    ))
}

private fun stageRest(current: WorkoutState.ActiveSet, staged: StagedAction) {
    val target = staged.commitTarget
    setState(WorkoutState.Resting(
        plan = target?.plan ?: current.plan,
        exerciseIndex = target?.exerciseIndex ?: current.exerciseIndex,
        completedSetIndex = current.setIndex,
        sessionId = current.sessionId,
        secondsRemaining = REST_SECONDS,
        lastFeedback = null,
        weightAtSetStart = current.plannedExercise.sessionWeight,
        currentSetRowId = NO_ROW,
        staged = staged,
    ))
    startRestTimer()
}

private fun nextExerciseActiveSet(
    plan: WorkoutPlan,
    index: Int,
    sessionId: Long,
): WorkoutState.ActiveSet? {
    if (index !in plan.exercises.indices) return null
    val ex = plan.exercises[index]
    return WorkoutState.ActiveSet(
        plan = plan,
        exerciseIndex = index,
        setIndex = 0,
        sessionId = sessionId,
        warmupSetIndex = if (ex.warmupSets.isNotEmpty()) 0 else null,
    )
}

private suspend fun persistSwap(swap: PendingSwap, overrides: Map<MuscleGroup, Float>) {
    when (swap.reason) {
        ExerciseRemovalReason.DISLIKE -> {
            val ex = database.exerciseDao().getById(swap.exerciseId) ?: return
            database.exerciseDao().update(ex.copy(isDisliked = true))
        }
        ExerciseRemovalReason.NO_EQUIPMENT -> {
            val locationId = swap.locationId ?: return
            repository.excludeExercise(locationId, swap.exerciseId)
        }
        ExerciseRemovalReason.SKIP_TODAY -> Unit
    }
    planner = repository.buildPlanner(sessionLocationId, weightUnit, overrides)
}
```

Add the import `io.github.fowles.stochastic_strength.data.model.MuscleGroup` if not present (it is used by `persistSwap`'s `overrides` type).

In `undoLastSet()`, add the staged short-circuit at the very top (after `restTimerJob?.cancel()` and the `resting` cast):

```kotlin
fun undoLastSet() {
    restTimerJob?.cancel()
    val resting = _state.value as? WorkoutState.Resting ?: return
    resting.staged?.let {
        setState(it.undoTarget)
        return
    }
    // ...existing body unchanged...
}
```

In `advanceAfterRest()`, add the staged branch at the very top (after the `current` cast):

```kotlin
private fun advanceAfterRest() {
    val current = _state.value as? WorkoutState.Resting ?: return
    val staged = current.staged
    if (staged != null) {
        scope.launch {
            staged.pendingSwap?.let { persistSwap(it, current.plan.strengthOverrides) }
            val target = staged.commitTarget
            if (target != null) setState(target) else finishWorkout(current.plan, current.sessionId)
        }
        return
    }
    // ...existing body unchanged...
}
```

- [ ] **Step 3c: ViewModel + RestingContent**

In `WorkoutViewModel.kt`, add after the other `controller` delegations:

```kotlin
fun stopWorkout() = controller.stopWorkout()
```

In `RestingContent.kt`, replace the subtitle `Text` that reads
`"Logged: ${state.lastFeedback.displayLabel}"` with a staged-aware subtitle:

```kotlin
val subtitle = when (state.staged?.kind) {
    StagedKind.STOP_WORKOUT -> "Finishing workout"
    StagedKind.END_EXERCISE -> "Exercise stopped"
    StagedKind.SWAP -> "Swapped exercise"
    StagedKind.ADJUST_WEIGHT -> "Weight changed"
    null -> "Logged: ${state.lastFeedback?.displayLabel ?: ""}"
}
Text(
    subtitle,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.primary,
)
```

Then make the card area staged-aware. Replace the `when { ... }` block inside the
0.2f-weight card `Box` so the staged case is handled first:

```kotlin
when {
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
    state.lastFeedback == SetFeedback.TOO_HARD && !state.weightReductionApplied
        && !plannedExercise.exercise.isTimed -> {
        // ...existing WeightReductionCard branch unchanged...
    }
    state.lastFeedback == SetFeedback.TOO_HARD && state.weightReductionApplied
        && moreSetsForThisExercise && weightReduced -> {
        // ...existing "Reduced weight" NextExerciseCard branch unchanged...
    }
    !moreSetsForThisExercise && nextExercise != null -> {
        // ...existing "Next up" branch unchanged...
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: PASS (new `stopWorkout_*` tests and all pre-existing controller tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/RestingContent.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt
git commit -m "feat: staged-rest model and stop-workout action

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `endCurrentExercise()`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt`

**Interfaces:**
- Consumes: `stageRest`, `nextExerciseActiveSet`, `StagedAction`, `StagedKind` (Task 2).
- Produces: `WorkoutSessionController.endCurrentExercise()`, `WorkoutViewModel.endCurrentExercise()`.

- [ ] **Step 1: Write the failing test**

Add to `WorkoutSessionControllerTest.kt`. Add a helper that drives the controller to the first working set (past warmups) and one that completes a working set:

```kotlin
private suspend fun toWorkingSet() {
    var s = controller.state.value
    while (s is WorkoutState.ActiveSet && s.warmupSetIndex != null) {
        controller.completeWarmupSet()
        delay(20)
        s = controller.state.value
    }
}

@Test
fun endExercise_noLoggedSets_singleExercise_finishesOnCommit() = runBlocking {
    // Fresh on warmup/set 0 => no logged sets.
    controller.endCurrentExercise()
    val resting = awaitState<WorkoutState.Resting>()
    assertEquals(StagedKind.END_EXERCISE, resting.staged!!.kind)
    controller.skipRest()
    awaitState<WorkoutState.Done>()
    delay(100)
    assertEquals(0, db.workoutSetDao().getAll().size) // nothing logged
}

@Test
fun endExercise_undoRestoresOriginatingSet() = runBlocking {
    val before = controller.state.value as WorkoutState.ActiveSet
    controller.endCurrentExercise()
    awaitState<WorkoutState.Resting>()
    controller.undoLastSet()
    val after = awaitState<WorkoutState.ActiveSet>()
    assertEquals(before.exerciseIndex, after.exerciseIndex)
    assertEquals(before.warmupSetIndex, after.warmupSetIndex)
}

@Test
fun endExercise_hasLoggedSets_keepsLoggedAndAdvances() = runBlocking {
    startSession(2) // two exercises in the plan
    toWorkingSet()
    controller.recordFeedback(SetFeedback.RIR_2_4) // logs set 1 of exercise 0
    awaitState<WorkoutState.Resting>()
    controller.skipRest()
    awaitState<WorkoutState.ActiveSet>() // now on exercise 0, set 2 (hasLogged)

    controller.endCurrentExercise()
    val resting = awaitState<WorkoutState.Resting>()
    // commitTarget advances to the second exercise (index 1).
    assertEquals(1, resting.staged!!.commitTarget!!.exerciseIndex)
    controller.skipRest()
    val active = awaitState<WorkoutState.ActiveSet>()
    assertEquals(1, active.exerciseIndex)
    delay(100)
    assertEquals(1, db.workoutSetDao().getAll().size) // the logged set is retained
}

@Test
fun endExercise_noLoggedSets_multiExercise_removesAndAdvances() = runBlocking {
    startSession(2)
    val firstId = (controller.state.value as WorkoutState.ActiveSet)
        .plannedExercise.exercise.id
    controller.endCurrentExercise() // on warmup/set 0 of exercise 0 => no logged sets
    val resting = awaitState<WorkoutState.Resting>()
    val target = resting.staged!!.commitTarget!!
    // Exercise 0 removed; the second exercise now occupies index 0.
    assertEquals(0, target.exerciseIndex)
    assertTrue(target.plan.exercises.none { it.exercise.id == firstId })
    controller.skipRest()
    awaitState<WorkoutState.ActiveSet>()
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: FAIL to compile — `endCurrentExercise` unresolved.

- [ ] **Step 3: Implement**

In `WorkoutSessionController.kt`:

```kotlin
fun endCurrentExercise() {
    val current = _state.value as? WorkoutState.ActiveSet ?: return
    val i = current.exerciseIndex
    val hasLogged = current.warmupSetIndex == null && current.setIndex > 0
    val commitTarget = if (hasLogged) {
        nextExerciseActiveSet(current.plan, i + 1, current.sessionId)
    } else {
        val trimmed = current.plan.exercises.toMutableList().also { it.removeAt(i) }
        nextExerciseActiveSet(current.plan.copy(exercises = trimmed), i, current.sessionId)
    }
    stageRest(current, StagedAction(
        kind = StagedKind.END_EXERCISE,
        undoTarget = current,
        commitTarget = commitTarget,
    ))
}
```

In `WorkoutViewModel.kt`:

```kotlin
fun endCurrentExercise() = controller.endCurrentExercise()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt
git commit -m "feat: end-current-exercise neutral stop

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `setActiveSetWeight(newWeight)`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt`

**Interfaces:**
- Consumes: `stageRest`, `StagedAction`, `StagedKind`, `WeightFormatter`, `planner.computeWarmupSets` (Task 2 / existing).
- Produces: `WorkoutSessionController.setActiveSetWeight(newWeight: Float)`, `WorkoutViewModel.setActiveSetWeight(newWeight: Float)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun setActiveSetWeight_stagesResumeSameSetAtNewWeight() = runBlocking {
    toWorkingSet()
    val active = controller.state.value as WorkoutState.ActiveSet
    val i = active.exerciseIndex
    val original = active.plannedExercise.sessionWeight
    val target = original + 5f

    controller.setActiveSetWeight(target)
    val resting = awaitState<WorkoutState.Resting>()
    assertEquals(StagedKind.ADJUST_WEIGHT, resting.staged!!.kind)
    val commit = resting.staged!!.commitTarget!!
    // Same set coordinates.
    assertEquals(active.exerciseIndex, commit.exerciseIndex)
    assertEquals(active.setIndex, commit.setIndex)
    assertEquals(active.warmupSetIndex, commit.warmupSetIndex)
    // New weight applied to the plan.
    assertEquals(
        WeightFormatter.round(target, WeightUnit.KG),
        commit.plan.exercises[i].sessionWeight,
    )
    // Baseline override untouched.
    assertTrue(commit.plan.strengthOverrides.isEmpty())
}

@Test
fun setActiveSetWeight_undoRestoresOriginalWeight() = runBlocking {
    toWorkingSet()
    val active = controller.state.value as WorkoutState.ActiveSet
    val original = active.plannedExercise.sessionWeight
    controller.setActiveSetWeight(original + 5f)
    awaitState<WorkoutState.Resting>()
    controller.undoLastSet()
    val after = awaitState<WorkoutState.ActiveSet>()
    assertEquals(original, after.plannedExercise.sessionWeight)
}
```

Add import: `import io.github.fowles.stochastic_strength.domain.WeightFormatter`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: FAIL to compile — `setActiveSetWeight` unresolved.

- [ ] **Step 3: Implement**

In `WorkoutSessionController.kt`:

```kotlin
fun setActiveSetWeight(newWeight: Float) {
    val current = _state.value as? WorkoutState.ActiveSet ?: return
    val i = current.exerciseIndex
    val pe = current.plannedExercise
    val w = WeightFormatter.round(newWeight, weightUnit).coerceAtLeast(WeightFormatter.minIncrement(weightUnit))
    if (w == pe.sessionWeight) return
    val exercises = current.plan.exercises.toMutableList()
    exercises[i] = pe.copy(
        sessionWeight = w,
        warmupSets = when {
            pe.exercise.isTimed -> emptyList()
            current.warmupSetIndex != null -> planner?.computeWarmupSets(w) ?: pe.warmupSets
            else -> pe.warmupSets
        },
    )
    val newPlan = current.plan.copy(exercises = exercises)
    val commitTarget = WorkoutState.ActiveSet(
        plan = newPlan,
        exerciseIndex = i,
        setIndex = current.setIndex,
        sessionId = current.sessionId,
        warmupSetIndex = current.warmupSetIndex,
    )
    stageRest(current, StagedAction(
        kind = StagedKind.ADJUST_WEIGHT,
        undoTarget = current,
        commitTarget = commitTarget,
    ))
}
```

In `WorkoutViewModel.kt`:

```kotlin
fun setActiveSetWeight(newWeight: Float) = controller.setActiveSetWeight(newWeight)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt
git commit -m "feat: plan-only mid-workout weight adjustment

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: `swapCurrentExercise(reason)`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt`

**Interfaces:**
- Consumes: `stageRest`, `nextExerciseActiveSet`, `persistSwap`, `StagedAction`, `PendingSwap`, `StagedKind`, `ExerciseRemovalReason`, `ReplacementTier` (Tasks 1–2).
- Produces: `WorkoutSessionController.swapCurrentExercise(reason: ExerciseRemovalReason)`, `WorkoutViewModel.swapCurrentExercise(reason: ExerciseRemovalReason)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun swap_noLoggedSets_replacesInPlace() = runBlocking {
    val active = controller.state.value as WorkoutState.ActiveSet
    val originalId = active.plannedExercise.exercise.id
    controller.swapCurrentExercise(ExerciseRemovalReason.DISLIKE)
    val resting = awaitState<WorkoutState.Resting>()
    val target = resting.staged!!.commitTarget!!
    assertEquals(StagedKind.SWAP, resting.staged!!.kind)
    assertEquals(0, target.exerciseIndex)
    // Replaced in place: original gone, exactly one exercise, different id.
    assertEquals(1, target.plan.exercises.size)
    assertTrue(target.plan.exercises.none { it.exercise.id == originalId })
}

@Test
fun swap_commitPersistsDislike_undoDoesNot() = runBlocking {
    val originalId = (controller.state.value as WorkoutState.ActiveSet).plannedExercise.exercise.id

    // Undo path: no persistence.
    controller.swapCurrentExercise(ExerciseRemovalReason.DISLIKE)
    awaitState<WorkoutState.Resting>()
    controller.undoLastSet()
    awaitState<WorkoutState.ActiveSet>()
    delay(100)
    assertEquals(false, db.exerciseDao().getById(originalId)!!.isDisliked)

    // Commit path: persists.
    controller.swapCurrentExercise(ExerciseRemovalReason.DISLIKE)
    awaitState<WorkoutState.Resting>()
    controller.skipRest()
    awaitState<WorkoutState.ActiveSet>()
    delay(100)
    assertEquals(true, db.exerciseDao().getById(originalId)!!.isDisliked)
}

@Test
fun swap_hasLoggedSets_keepsOriginalAndInsertsAfter() = runBlocking {
    toWorkingSet()
    controller.recordFeedback(SetFeedback.RIR_2_4) // log a set for exercise 0
    awaitState<WorkoutState.Resting>()
    controller.skipRest()
    val active = awaitState<WorkoutState.ActiveSet>() // exercise 0, set 2 (hasLogged)
    val originalId = active.plannedExercise.exercise.id

    controller.swapCurrentExercise(ExerciseRemovalReason.DISLIKE)
    val resting = awaitState<WorkoutState.Resting>()
    val target = resting.staged!!.commitTarget!!
    // Original kept at 0, replacement inserted at 1; commit jumps to index 1.
    assertEquals(originalId, target.plan.exercises[0].exercise.id)
    assertEquals(1, target.exerciseIndex)
    assertEquals(2, target.plan.exercises.size)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: FAIL to compile — `swapCurrentExercise` unresolved.

- [ ] **Step 3: Implement**

In `WorkoutSessionController.kt`:

```kotlin
fun swapCurrentExercise(reason: ExerciseRemovalReason) {
    val current = _state.value as? WorkoutState.ActiveSet ?: return
    val i = current.exerciseIndex
    val original = current.plannedExercise.exercise
    val hasLogged = current.warmupSetIndex == null && current.setIndex > 0
    val p = planner ?: return

    val rejectedPlan = current.plan.copy(
        sessionRejectedIds = current.plan.sessionRejectedIds + original.id,
    )
    val replacementRaw = p.pickReplacement(
        rejectedPlan, i,
        listOf(ReplacementTier.WEIGHTED_MUSCLE, ReplacementTier.MUSCLE, ReplacementTier.ANY),
    )
    val replacement = replacementRaw?.let { it.copy(originalSessionWeight = it.sessionWeight) }

    val exercises = rejectedPlan.exercises.toMutableList()
    val commitIndex: Int
    when {
        replacement == null && hasLogged -> {
            commitIndex = i + 1 // keep original, advance past it
        }
        replacement == null -> {
            exercises.removeAt(i)
            commitIndex = i
        }
        hasLogged -> {
            exercises.add(i + 1, replacement)
            commitIndex = i + 1
        }
        else -> {
            exercises[i] = replacement
            commitIndex = i
        }
    }
    val newPlan = rejectedPlan.copy(exercises = exercises)
    val commitTarget = nextExerciseActiveSet(newPlan, commitIndex, current.sessionId)

    stageRest(current, StagedAction(
        kind = StagedKind.SWAP,
        undoTarget = current,
        commitTarget = commitTarget,
        pendingSwap = PendingSwap(reason, original.id, sessionLocationId),
    ))
}
```

In `WorkoutViewModel.kt`:

```kotlin
fun swapCurrentExercise(reason: ExerciseRemovalReason) = controller.swapCurrentExercise(reason)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt
git commit -m "feat: mid-workout exercise swap with deferred persistence

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: `SetActionsMenu` + weight modal UI and wiring

This is a UI task; verification is build + manual. It must not break existing tests.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/SetActionsMenu.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WeightAdjustDialog.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/ActiveSetContent.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WarmupSetContent.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`

**Interfaces:**
- Consumes: `WorkoutViewModel.swapCurrentExercise`, `setActiveSetWeight`, `endCurrentExercise`, `stopWorkout` (Tasks 2–5); `ExerciseRemovalReason`; `WeightFormatter.platesPerSide`, `minIncrement`, `round`, `format`.
- Produces: `SetActionsMenu(...)` and `WeightAdjustDialog(...)` composables; an updated `ExerciseSetLayout` signature that renders the menu.

- [ ] **Step 1: Create `SetActionsMenu.kt`**

```kotlin
package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
internal fun SetActionsMenu(
    weightAdjustable: Boolean,
    onAdjustWeight: () -> Unit,
    onSwapNoEquipment: () -> Unit,
    onSwapDislike: () -> Unit,
    onEndExercise: () -> Unit,
    onStopWorkout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Set options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Adjust weight") },
            enabled = weightAdjustable,
            onClick = { expanded = false; onAdjustWeight() },
        )
        DropdownMenuItem(
            text = { Text("Swap — no equipment") },
            onClick = { expanded = false; onSwapNoEquipment() },
        )
        DropdownMenuItem(
            text = { Text("Swap — don't like it") },
            onClick = { expanded = false; onSwapDislike() },
        )
        DropdownMenuItem(
            text = { Text("End exercise") },
            onClick = { expanded = false; onEndExercise() },
        )
        DropdownMenuItem(
            text = { Text("Stop workout") },
            onClick = { expanded = false; onStopWorkout() },
        )
    }
}
```

- [ ] **Step 2: Create `WeightAdjustDialog.kt`**

The dialog tracks a local working weight; ± updates it and the plate breakdown; Done commits once.

```kotlin
package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter

@Composable
internal fun WeightAdjustDialog(
    exerciseName: String,
    startWeight: Float,
    equipment: Equipment,
    weightUnit: WeightUnit,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val increment = WeightFormatter.minIncrement(weightUnit)
    var working by remember { mutableFloatStateOf(startWeight) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(exerciseName) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(onClick = {
                        working = (working - increment).coerceAtLeast(increment)
                    }) { Text("−") }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        WeightFormatter.format(working, weightUnit),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = { working += increment }) { Text("+") }
                }
                if (equipment == Equipment.BARBELL) {
                    WeightFormatter.platesPerSide(working, weightUnit)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(working) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 3: Thread the menu through `ExerciseSetLayout` and the timed layout**

In `ActiveSetContent.kt`, add a `menu` slot to `ExerciseSetLayout` and render it top-right above the exercise name. Change the signature:

```kotlin
@Composable
internal fun ExerciseSetLayout(
    exercise: Exercise,
    progressLabel: String,
    progressColor: Color,
    weight: Float,
    reps: Int,
    weightUnit: WeightUnit,
    menu: @Composable () -> Unit = {},
    bottomContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            menu()
        }
        Text(exercise.name, style = MaterialTheme.typography.headlineMedium)
        // ...rest unchanged...
```

`ActiveSetContent` and `TimedSetContent` receive a single `actions: @Composable () -> Unit` slot from the screen. Add `actions: @Composable () -> Unit` parameters to `ActiveSetContent` and `TimedSetContent`; pass `menu = actions` into `ExerciseSetLayout`, and in `TimedSetContent` render `Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }` at the top of its `Column`. Import `androidx.compose.foundation.layout.Row` and `androidx.compose.foundation.layout.Arrangement` where needed.

Update `ActiveSetContent`'s signature and call site likewise:

```kotlin
@Composable
internal fun ActiveSetContent(
    state: WorkoutState.ActiveSet,
    weightUnit: WeightUnit,
    onFeedback: (SetFeedback) -> Unit,
    onStartTimedSet: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val exercise = state.plannedExercise.exercise
    if (exercise.isTimed) {
        TimedSetContent(state = state, onStartTimedSet = onStartTimedSet, onFeedback = onFeedback, actions = actions)
    } else {
        ExerciseSetLayout(
            exercise = exercise,
            progressLabel = "Set ${state.setIndex + 1} of ${state.totalSets}",
            progressColor = MaterialTheme.colorScheme.primary,
            weight = state.plannedExercise.sessionWeight,
            reps = state.plannedExercise.sessionReps,
            weightUnit = weightUnit,
            menu = actions,
        ) {
            Text("How many more reps could you have done?", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            FeedbackButtons(onFeedback = onFeedback)
        }
    }
}
```

In `WarmupSetContent.kt`, add `actions: @Composable () -> Unit` and pass `menu = actions` into `ExerciseSetLayout`.

- [ ] **Step 4: Build the actions slot in `WorkoutScreen.kt`**

Hoist the weight-dialog state in the screen and pass a single `actions` slot to both `WarmupSetContent` and `ActiveSetContent`. In the `is WorkoutState.ActiveSet ->` branch:

```kotlin
is WorkoutState.ActiveSet -> {
    var showWeightDialog by rememberSaveable(s.exerciseIndex, s.setIndex, s.warmupSetIndex) {
        mutableStateOf(false)
    }
    val planned = s.plannedExercise
    val weightAdjustable = planned.sessionWeight > 0f && !planned.exercise.isTimed
    val actions: @Composable () -> Unit = {
        SetActionsMenu(
            weightAdjustable = weightAdjustable,
            onAdjustWeight = { showWeightDialog = true },
            onSwapNoEquipment = { viewModel.swapCurrentExercise(ExerciseRemovalReason.NO_EQUIPMENT) },
            onSwapDislike = { viewModel.swapCurrentExercise(ExerciseRemovalReason.DISLIKE) },
            onEndExercise = { viewModel.endCurrentExercise() },
            onStopWorkout = { viewModel.stopWorkout() },
        )
    }
    if (showWeightDialog) {
        WeightAdjustDialog(
            exerciseName = planned.exercise.name,
            startWeight = planned.sessionWeight,
            equipment = planned.exercise.equipment,
            weightUnit = weightUnit,
            onConfirm = { newWeight ->
                showWeightDialog = false
                viewModel.setActiveSetWeight(newWeight)
            },
            onDismiss = { showWeightDialog = false },
        )
    }
    if (s.warmupSetIndex != null) {
        WarmupSetContent(state = s, weightUnit = weightUnit, onDone = viewModel::completeWarmupSet, actions = actions)
    } else {
        ActiveSetContent(
            state = s,
            weightUnit = weightUnit,
            onFeedback = viewModel::recordFeedback,
            onStartTimedSet = viewModel::startTimedSet,
            actions = actions,
        )
    }
}
```

Add imports to `WorkoutScreen.kt`: `androidx.compose.runtime.getValue` (present), `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.setValue`, `androidx.compose.runtime.saveable.rememberSaveable`.

- [ ] **Step 5: Build and run the full suite**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: PASS.

- [ ] **Step 6: Manual verification**

Install (`./gradlew :app:installDebug`), start a workout, and confirm on a warmup set and a working set: the kebab opens the menu; Adjust weight shows the modal with a live plate breakdown that changes on ±; Done lands on the rest page and Undo restores the set at the original weight; Swap / End exercise / Stop workout each land on rest and Undo reverts them.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/SetActionsMenu.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WeightAdjustDialog.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/ActiveSetContent.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WarmupSetContent.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt
git commit -m "feat: set-actions kebab menu and weight modal

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Kebab `SetActionsMenu` on warmup/working/timed, weight item disabled when unweighted → Task 6.
- Weight modal with live plate breakdown → Task 6 (`WeightAdjustDialog`).
- Staged-rest universal-undo model (`StagedAction`/`PendingSwap`/`StagedKind`, `NO_ROW`, `advanceAfterRest`/`undoLastSet`) → Task 2.
- Swap (keep-logged/append vs replace-in-place, deferred persistence, tiered pick) → Tasks 1 + 5.
- Adjust weight plan-only, resume-same-set → Task 4.
- End exercise (hasLogged keeps + advances; !hasLogged removes) → Task 3.
- Stop workout (commit finishes) → Task 2.
- `pickReplacement` default `[ANY]` unchanged for PlanPreview/location refresh → Task 1.
- `RestingContent` staged subtitle + suppressed next-up when ending → Task 2.

**Placeholder scan:** none — every code step is concrete.

**Type consistency:** `setActiveSetWeight(newWeight: Float)`, `swapCurrentExercise(reason: ExerciseRemovalReason)`, `endCurrentExercise()`, `stopWorkout()`, `StagedAction(kind, undoTarget, commitTarget, pendingSwap)`, `PendingSwap(reason, exerciseId, locationId)`, `StagedKind { SWAP, ADJUST_WEIGHT, END_EXERCISE, STOP_WORKOUT }`, `NO_ROW`, and `pickReplacement(plan, removedIndex, tiers)` are used identically across tasks.
