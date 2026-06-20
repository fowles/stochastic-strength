# Workout State Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `WorkoutSessionController` from `WorkoutViewModel` to isolate the state machine, eliminate loose mutable vars, and move session-scoped state off the Application class.

**Architecture:** Create `WorkoutSessionBus` to replace the two ad-hoc fields on `StochasticStrengthApp`, then extract all state machine logic into `WorkoutSessionController`, leaving `WorkoutViewModel` as a thin Android lifecycle adapter (~100 lines). Replace the `_workoutCompleted: MutableStateFlow<Boolean>` one-shot hack with a `Channel`-backed `navigationEvent` flow. Deduplicate the summary DB loading into a shared top-level function.

**Tech Stack:** Kotlin, Coroutines (Channel, StateFlow, CoroutineScope), Room, Jetpack Compose / AndroidViewModel.

**Spec:** `docs/superpowers/specs/2026-06-10-workout-state-cleanup-design.md`

---

## File Map

| Action | Path | Purpose |
|--------|------|---------|
| **Create** | `ui/workout/WorkoutSessionBus.kt` | Named holder for session-scoped app state |
| **Create** | `ui/workout/WorkoutSessionController.kt` | State machine, timers, planner, NavigationEvent |
| **Modify** | `StochasticStrengthApp.kt` | Replace two fields with `workoutSessionBus` |
| **Modify** | `notification/WorkoutNotificationService.kt` | Read from `app.workoutSessionBus.notificationState` |
| **Modify** | `notification/WorkoutCommandReceiver.kt` | Emit to `app.workoutSessionBus.commandFlow` |
| **Modify** | `ui/WorkoutSummaryData.kt` | Add `loadWorkoutSummary` top-level function |
| **Modify** | `ui/summary/SummaryViewModel.kt` | Call `loadWorkoutSummary` |
| **Modify** | `ui/workout/WorkoutViewModel.kt` | Rewrite to delegate to controller |
| **Modify** | `ui/workout/WorkoutScreen.kt` | Replace `workoutCompleted` flow with `navigationEvent`, remove `onNavigatedToLocationEdit` call |

---

## Task 1: WorkoutSessionBus — group session-scoped app state

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionBus.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/notification/WorkoutNotificationService.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/notification/WorkoutCommandReceiver.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`

- [x] **Step 1: Create `WorkoutSessionBus.kt`**

```kotlin
package io.github.fowles.stochastic_strength.ui.workout

import io.github.fowles.stochastic_strength.ui.workout.WorkoutCommand
import io.github.fowles.stochastic_strength.ui.workout.WorkoutNotificationState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class WorkoutSessionBus {
    val notificationState = MutableStateFlow<WorkoutNotificationState?>(null)
    val commandFlow = MutableSharedFlow<WorkoutCommand>(extraBufferCapacity = 8)
}
```

- [x] **Step 2: Update `StochasticStrengthApp` — replace two fields with the bus**

Replace:
```kotlin
val workoutCommandFlow = MutableSharedFlow<WorkoutCommand>(extraBufferCapacity = 8)
val workoutNotificationState = MutableStateFlow<WorkoutNotificationState?>(null)
```
With:
```kotlin
val workoutSessionBus = WorkoutSessionBus()
```

Remove the now-unused imports for `MutableSharedFlow`, `MutableStateFlow`, `WorkoutCommand`, and `WorkoutNotificationState`.

- [x] **Step 3: Update `WorkoutNotificationService` — read from the bus**

In `onStartCommand`, change:
```kotlin
app.workoutNotificationState.collect { state ->
```
to:
```kotlin
app.workoutSessionBus.notificationState.collect { state ->
```

- [x] **Step 4: Update `WorkoutCommandReceiver` — emit to the bus**

Change:
```kotlin
app.applicationScope.launch {
    app.workoutCommandFlow.emit(command)
}
```
to:
```kotlin
app.applicationScope.launch {
    app.workoutSessionBus.commandFlow.emit(command)
}
```

- [x] **Step 5:** Update `WorkoutViewModel` — use bus fields**

In `setState`:
```kotlin
// Change:
val wasNull = app.workoutNotificationState.value == null
_state.value = newState
app.workoutNotificationState.value = newNotifState
if (wasNull && newNotifState != null) {
    app.startForegroundService(Intent(app, WorkoutNotificationService::class.java))
}
// To:
val wasNull = app.workoutSessionBus.notificationState.value == null
_state.value = newState
app.workoutSessionBus.notificationState.value = newNotifState
if (wasNull && newNotifState != null) {
    app.startForegroundService(Intent(app, WorkoutNotificationService::class.java))
}
```

In `init`, change:
```kotlin
app.workoutCommandFlow.collect { command ->
```
to:
```kotlin
app.workoutSessionBus.commandFlow.collect { command ->
```

In `onCleared`, change:
```kotlin
app.workoutNotificationState.value = null
```
to:
```kotlin
app.workoutSessionBus.notificationState.value = null
```

- [x] **Step 6:** Build and run unit tests**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, all unit tests pass. No reference to `workoutCommandFlow` or `workoutNotificationState` should remain outside `WorkoutSessionBus.kt`.

- [x] **Step 7:** Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionBus.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/notification/WorkoutNotificationService.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/notification/WorkoutCommandReceiver.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt
git commit -m "refactor: introduce WorkoutSessionBus for session-scoped app state"
```

---

## Task 2: Deduplicate summary loading

Both `WorkoutViewModel.loadDoneSummary` and `SummaryViewModel.summary` do the same DB queries to build `WorkoutSummaryData`. Extract to a shared top-level function.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/WorkoutSummaryData.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/summary/SummaryViewModel.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`

- [ ] **Step 1: Add `loadWorkoutSummary` to `WorkoutSummaryData.kt`**

Append to the bottom of the file (after the existing data classes):

```kotlin
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.WeightUnit

suspend fun loadWorkoutSummary(db: AppDatabase, sessionId: Long): WorkoutSummaryData {
    val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
    val session = db.workoutSessionDao().getById(sessionId)
    val sets = db.workoutSetDao().getSetsForSession(sessionId)
    val exerciseIds = sets.map { it.exerciseId }.distinct()
    val exerciseById = exerciseIds
        .mapNotNull { id -> db.exerciseDao().getById(id)?.let { id to it } }
        .toMap()
    val setsByExercise = sets.groupBy { it.exerciseId }
    val exercises = exerciseIds.map { id ->
        val exercise = exerciseById[id]
        SummaryExercise(
            name = exercise?.name ?: "Unknown",
            exerciseId = id,
            sets = (setsByExercise[id] ?: emptyList()).sortedBy { it.setNumber }
                .map { it.toSummarySet(exercise?.isTimed ?: false) },
        )
    }
    val duration = if (session != null && session.endTime != null) {
        (session.endTime - session.startTime) / 1000
    } else 0L
    return WorkoutSummaryData(
        startTime = session?.startTime ?: 0L,
        durationSeconds = duration,
        exercises = exercises,
        weightUnit = weightUnit,
    )
}
```

- [ ] **Step 2: Update `SummaryViewModel` to call the shared function**

Replace the entire `summary` flow body:
```kotlin
val summary: StateFlow<WorkoutSummaryData?> = flow {
    val profile = app.database.userProfileDao().getProfile()
    val weightUnit = profile?.weightUnit ?: WeightUnit.KG
    val session = app.database.workoutSessionDao().getById(sessionId)
    val sets = app.database.workoutSetDao().getSetsForSession(sessionId)
    val exerciseIds = sets.map { it.exerciseId }.distinct()
    val exerciseById = exerciseIds
        .mapNotNull { id -> app.database.exerciseDao().getById(id)?.let { id to it } }
        .toMap()
    val setsByExercise = sets.groupBy { it.exerciseId }

    val exercises = exerciseIds.map { id ->
        val exercise = exerciseById[id]
        SummaryExercise(
            name = exercise?.name ?: "Unknown",
            exerciseId = id,
            sets = (setsByExercise[id] ?: emptyList()).sortedBy { it.setNumber }
                .map { it.toSummarySet(exercise?.isTimed ?: false) },
        )
    }

    val duration = if (session != null && session.endTime != null) {
        (session.endTime - session.startTime) / 1000
    } else 0L

    emit(WorkoutSummaryData(
        startTime = session?.startTime ?: 0L,
        durationSeconds = duration,
        exercises = exercises,
        weightUnit = weightUnit,
    ))
}.stateIn(viewModelScope, SharingStarted.Lazily, null)
```
With:
```kotlin
val summary: StateFlow<WorkoutSummaryData?> = flow {
    emit(loadWorkoutSummary(app.database, sessionId))
}.stateIn(viewModelScope, SharingStarted.Lazily, null)
```

Remove the now-unused imports from `SummaryViewModel`: `SummaryExercise`, `toSummarySet`, and any DAO/model imports only used in the old body.

- [x] **Step 3: Update `WorkoutViewModel` — replace `loadDoneSummary` with the shared function**

In `finishWorkout`, change:
```kotlin
_doneSummary.value = loadDoneSummary(sessionId, endTime)
```
to:
```kotlin
_doneSummary.value = loadWorkoutSummary(app.database, sessionId)
```

Delete the entire private `loadDoneSummary` method from `WorkoutViewModel`.

- [x] **Step 4: Build and run unit tests**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, all tests pass. `loadDoneSummary` should no longer exist in the codebase.

```bash
grep -r "loadDoneSummary" app/src/
```
Expected: no output.

- [x] **Step 5:** Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/WorkoutSummaryData.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/summary/SummaryViewModel.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt
git commit -m "refactor: extract loadWorkoutSummary to deduplicate summary DB loading"
```

---

## Task 3: Create `WorkoutSessionController`

Extract the state machine, timers, planner, and session metadata from `WorkoutViewModel` into a standalone `WorkoutSessionController`. The controller has no Android imports — only Kotlin, Coroutines, and the app's data/domain layers.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`

- [ ] **Step 1: Create `WorkoutSessionController.kt` with the full implementation**

```kotlin
package io.github.fowles.stochastic_strength.ui.workout

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import io.github.fowles.stochastic_strength.domain.WorkoutPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface NavigationEvent {
    data object WorkoutCompleted : NavigationEvent
}

class WorkoutSessionController(
    private val database: AppDatabase,
    private val bus: WorkoutSessionBus,
    private val scope: CoroutineScope,
    private val onVibrate: () -> Unit = {},
) {
    private val repository = WorkoutRepository(database)

    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Loading)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private val _navigationEvent = Channel<NavigationEvent>(Channel.BUFFERED)
    val navigationEvent: Flow<NavigationEvent> = _navigationEvent.receiveAsFlow()

    private var weightUnit: WeightUnit = WeightUnit.KG
    private var planner: WorkoutPlanner? = null
    private var sessionStartTime = 0L
    private var sessionLocationId: Long? = null

    private var restTimerJob: Job? = null
    private var timedSetTimerJob: Job? = null
    private var addExerciseJob: Job? = null

    init {
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                bus.notificationState.value = null
            }
        }
    }

    suspend fun initializeSession(
        locationId: Long?,
        locationName: String?,
        preferredExerciseCount: Int,
        weightUnit: WeightUnit,
    ) {
        this.weightUnit = weightUnit
        this.sessionLocationId = locationId
        val p = repository.buildPlanner(locationId, weightUnit)
        planner = p
        val plan = p.generateWorkout()
        setState(WorkoutState.PlanPreview(plan = plan, locationName = locationName))
        adjustExerciseCount(preferredExerciseCount)
    }

    fun startFirstExercise() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        if (preview.plan.exercises.isEmpty()) return
        val frozenExercises = preview.plan.exercises.map { it.copy(originalSessionWeight = it.sessionWeight) }
        val plan = preview.plan.copy(exercises = frozenExercises)
        val firstExercise = plan.exercises[0]
        scope.launch {
            for ((muscle, baseline) in plan.strengthOverrides) {
                database.muscleGroupStrengthDao().upsert(MuscleGroupStrength(muscle, baseline))
            }
            sessionStartTime = System.currentTimeMillis()
            val sessionId = database.workoutSessionDao().insert(
                WorkoutSession(startTime = sessionStartTime, locationId = sessionLocationId)
            )
            setState(WorkoutState.ActiveSet(
                plan = plan,
                exerciseIndex = 0,
                setIndex = 0,
                sessionId = sessionId,
                warmupSetIndex = if (firstExercise.warmupSets.isNotEmpty()) 0 else null,
            ))
        }
    }

    fun replaceExercise(index: Int, reason: ExerciseRemovalReason) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val rejectedId = preview.plan.exercises[index].exercise.id
        val planned = preview.plan.exercises[index]
        scope.launch {
            when (reason) {
                ExerciseRemovalReason.DISLIKE ->
                    database.exerciseDao().update(planned.exercise.copy(isDisliked = true))
                ExerciseRemovalReason.NO_EQUIPMENT -> {
                    val locationId = sessionLocationId ?: return@launch
                    repository.excludeExercise(locationId, planned.exercise.id)
                }
                ExerciseRemovalReason.SKIP_TODAY -> Unit
            }
            val current = _state.value as? WorkoutState.PlanPreview ?: return@launch
            val updatedPlan = current.plan.copy(
                sessionRejectedIds = current.plan.sessionRejectedIds + rejectedId
            )
            val p = if (reason != ExerciseRemovalReason.SKIP_TODAY) {
                repository.buildPlanner(sessionLocationId, weightUnit, updatedPlan.strengthOverrides)
                    .also { planner = it }
            } else {
                planner ?: return@launch
            }
            val currentIndex = updatedPlan.exercises.indexOfFirst { it.exercise.id == rejectedId }
            if (currentIndex < 0) return@launch
            val replacement = p.pickReplacement(updatedPlan, currentIndex)
            val newExercises = updatedPlan.exercises.toMutableList()
            if (replacement != null) newExercises[currentIndex] = replacement else newExercises.removeAt(currentIndex)
            setState(current.copy(plan = updatedPlan.copy(exercises = newExercises)))
        }
    }

    fun adjustExerciseCount(targetCount: Int) {
        addExerciseJob?.cancel()
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val current = preview.plan.exercises
        when {
            targetCount < current.size -> {
                val trimmed = current.take(targetCount.coerceAtLeast(1))
                setState(preview.copy(plan = preview.plan.copy(exercises = trimmed)))
            }
            targetCount > current.size -> {
                val needed = targetCount - current.size
                addExerciseJob = scope.launch {
                    repeat(needed) {
                        val p = _state.value as? WorkoutState.PlanPreview ?: return@launch
                        val extra = planner?.pickAdditional(p.plan) ?: return@launch
                        setState(p.copy(plan = p.plan.copy(exercises = p.plan.exercises + extra)))
                    }
                }
            }
        }
    }

    fun adjustExerciseWeight(index: Int, delta: Float) {
        val state = _state.value as? WorkoutState.PlanPreview ?: return
        val p = planner ?: return
        val exercises = state.plan.exercises.toMutableList()
        val pe = exercises[index]
        val newWeight = WeightFormatter.round(
            (pe.sessionWeight + delta).coerceAtLeast(2.5f),
            weightUnit,
        )
        if (newWeight == pe.sessionWeight) return
        val newBaseline = p.deriveBaselineFromSessionWeight(newWeight, pe)
        if (newBaseline <= 0f) return
        exercises[index] = pe.copy(
            sessionWeight = newWeight,
            warmupSets = if (pe.exercise.isTimed) emptyList() else p.computeWarmupSets(newWeight),
        )
        val muscle = pe.exercise.primaryMuscle
        for (i in exercises.indices) {
            if (i == index) continue
            if (exercises[i].exercise.primaryMuscle == muscle) {
                exercises[i] = p.recomputeExercise(exercises[i], newBaseline)
            }
        }
        val updatedOverrides = state.plan.strengthOverrides + (muscle to newBaseline)
        setState(state.copy(plan = state.plan.copy(exercises = exercises, strengthOverrides = updatedOverrides)))
        scope.launch {
            planner = repository.buildPlanner(sessionLocationId, weightUnit, updatedOverrides)
        }
    }

    fun completeWarmupSet() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        val warmupIdx = current.warmupSetIndex ?: return
        val nextIdx = warmupIdx + 1
        setState(current.copy(
            warmupSetIndex = if (nextIdx < current.plannedExercise.warmupSets.size) nextIdx else null,
        ))
    }

    fun startTimedSet() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        if (current.timerSecondsRemaining != null) return
        setState(current.copy(timerSecondsRemaining = TIMED_SET_SECONDS))
        timedSetTimerJob?.cancel()
        timedSetTimerJob = scope.launch {
            while (true) {
                delay(1000)
                val s = _state.value as? WorkoutState.ActiveSet ?: return@launch
                val remaining = s.timerSecondsRemaining ?: return@launch
                if (remaining <= 1) {
                    onVibrate()
                    recordFeedback(SetFeedback.RIR_0_1)
                    return@launch
                }
                setState(s.copy(timerSecondsRemaining = remaining - 1))
            }
        }
    }

    fun recordFeedback(feedback: SetFeedback) {
        timedSetTimerJob?.cancel()
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        scope.launch {
            val planned = current.plannedExercise
            database.workoutSetDao().insert(
                WorkoutSet(
                    sessionId = current.sessionId,
                    exerciseId = planned.exercise.id,
                    setNumber = current.setIndex + 1,
                    targetWeight = planned.sessionWeight,
                    targetReps = planned.sessionReps,
                    feedback = feedback,
                    completedAt = System.currentTimeMillis(),
                    durationSeconds = if (planned.exercise.isTimed) TIMED_SET_SECONDS else null,
                )
            )
        }
        val completedSetIndex = if (feedback == SetFeedback.HURT) current.totalSets - 1 else current.setIndex
        val isLastSet = completedSetIndex + 1 >= current.totalSets &&
            current.exerciseIndex + 1 >= current.plan.exercises.size
        if (isLastSet) {
            finishWorkout(current.plan, current.exerciseIndex, current.setIndex, current.sessionId)
        } else {
            setState(WorkoutState.Resting(
                plan = current.plan,
                exerciseIndex = current.exerciseIndex,
                completedSetIndex = completedSetIndex,
                recordedSetIndex = current.setIndex,
                sessionId = current.sessionId,
                secondsRemaining = REST_SECONDS,
                lastFeedback = feedback,
                weightAtSetStart = current.plannedExercise.sessionWeight,
            ))
            startRestTimer()
        }
    }

    fun undoLastSet() {
        restTimerJob?.cancel()
        val resting = _state.value as? WorkoutState.Resting ?: return
        val exerciseId = resting.plan.exercises[resting.exerciseIndex].exercise.id
        val restoredExercises = resting.plan.exercises.toMutableList()
        restoredExercises[resting.exerciseIndex] =
            restoredExercises[resting.exerciseIndex].copy(sessionWeight = resting.weightAtSetStart)
        val restoredPlan = resting.plan.copy(exercises = restoredExercises)
        scope.launch {
            database.workoutSetDao().deleteSet(
                sessionId = resting.sessionId,
                exerciseId = exerciseId,
                setNumber = resting.recordedSetIndex + 1,
            )
        }
        setState(WorkoutState.ActiveSet(
            plan = restoredPlan,
            exerciseIndex = resting.exerciseIndex,
            setIndex = resting.recordedSetIndex,
            sessionId = resting.sessionId,
        ))
    }

    fun skipRest() {
        restTimerJob?.cancel()
        advanceAfterRest()
    }

    fun reduceExerciseWeight(completedReps: Int) {
        val resting = _state.value as? WorkoutState.Resting ?: return
        val exercise = resting.plan.exercises[resting.exerciseIndex]
        if (exercise.sessionWeight <= 0f) return
        val newWeight = maxOf(0.5f, WeightFormatter.round(
            ProgressionEngine.scaleReps(exercise.sessionWeight, from = maxOf(1, completedReps), to = exercise.sessionReps),
            weightUnit,
        ))
        val updatedExercises = resting.plan.exercises.toMutableList()
        updatedExercises[resting.exerciseIndex] = exercise.copy(sessionWeight = newWeight)
        setState(resting.copy(plan = resting.plan.copy(exercises = updatedExercises), weightReductionApplied = true))
    }

    fun undoLastSetFromDone() {
        val done = _state.value as? WorkoutState.Done ?: return
        val exerciseId = done.plan.exercises[done.lastExerciseIndex].exercise.id
        scope.launch {
            database.workoutSetDao().deleteSet(
                sessionId = done.sessionId,
                exerciseId = exerciseId,
                setNumber = done.lastRecordedSetIndex + 1,
            )
        }
        setState(WorkoutState.ActiveSet(
            plan = done.plan,
            exerciseIndex = done.lastExerciseIndex,
            setIndex = done.lastRecordedSetIndex,
            sessionId = done.sessionId,
        ))
    }

    fun completeWorkout() {
        val done = _state.value as? WorkoutState.Done ?: return
        scope.launch {
            val reductions = done.plan.exercises
                .filter { it.originalSessionWeight > 0f && it.sessionWeight < it.originalSessionWeight }
                .associate { it.exercise.id to (it.originalSessionWeight - it.sessionWeight) / it.originalSessionWeight }
            repository.applySessionProgression(done.sessionId, reductions)
            _navigationEvent.send(NavigationEvent.WorkoutCompleted)
        }
    }

    fun onLocationRefreshed() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val locationId = sessionLocationId ?: return
        scope.launch {
            val locationName = database.knownLocationDao().getById(locationId)?.name
            val freshPlanner = repository.buildPlanner(locationId, weightUnit, preview.plan.strengthOverrides)
            planner = freshPlanner
            val availableIds = freshPlanner.availableExercises.map { it.id }.toSet()
            var plan = preview.plan
            var i = 0
            while (i < plan.exercises.size) {
                if (plan.exercises[i].exercise.id !in availableIds) {
                    val replacement = freshPlanner.pickReplacement(plan, i)
                    val updated = plan.exercises.toMutableList()
                    if (replacement != null) {
                        updated[i] = replacement
                    } else {
                        updated.removeAt(i)
                        i--
                    }
                    plan = plan.copy(exercises = updated)
                }
                i++
            }
            if (plan != preview.plan || locationName != preview.locationName) {
                setState(WorkoutState.PlanPreview(plan = plan, locationName = locationName))
            }
        }
    }

    private fun startRestTimer() {
        restTimerJob?.cancel()
        restTimerJob = scope.launch {
            while (true) {
                delay(1000)
                val current = _state.value as? WorkoutState.Resting ?: return@launch
                if (current.secondsRemaining <= 1) {
                    onVibrate()
                    advanceAfterRest()
                    return@launch
                }
                setState(current.copy(secondsRemaining = current.secondsRemaining - 1))
            }
        }
    }

    private fun advanceAfterRest() {
        val current = _state.value as? WorkoutState.Resting ?: return
        val plan = current.plan
        val nextSet = current.completedSetIndex + 1
        when {
            nextSet < PlannedExercise.DEFAULT_SETS -> setState(WorkoutState.ActiveSet(
                plan = plan,
                exerciseIndex = current.exerciseIndex,
                setIndex = nextSet,
                sessionId = current.sessionId,
            ))
            current.exerciseIndex + 1 < plan.exercises.size -> {
                val nextExercise = plan.exercises[current.exerciseIndex + 1]
                setState(WorkoutState.ActiveSet(
                    plan = plan,
                    exerciseIndex = current.exerciseIndex + 1,
                    setIndex = 0,
                    sessionId = current.sessionId,
                    warmupSetIndex = if (nextExercise.warmupSets.isNotEmpty()) 0 else null,
                ))
            }
            else -> finishWorkout(plan, current.exerciseIndex, current.recordedSetIndex, current.sessionId)
        }
    }

    private fun finishWorkout(
        plan: WorkoutPlan,
        lastExerciseIndex: Int,
        lastRecordedSetIndex: Int,
        sessionId: Long,
    ) {
        val endTime = System.currentTimeMillis()
        scope.launch {
            database.workoutSessionDao().updateEndTime(sessionId, endTime)
            setState(WorkoutState.Done(
                sessionId = sessionId,
                plan = plan,
                startTime = sessionStartTime,
                endTime = endTime,
                lastExerciseIndex = lastExerciseIndex,
                lastRecordedSetIndex = lastRecordedSetIndex,
            ))
        }
    }

    private fun setState(newState: WorkoutState) {
        _state.value = newState
        bus.notificationState.value = deriveNotificationState(newState)
    }

    private fun deriveNotificationState(state: WorkoutState): WorkoutNotificationState? = when (state) {
        is WorkoutState.ActiveSet -> {
            val planned = state.plannedExercise
            if (state.warmupSetIndex != null) {
                WorkoutNotificationState.WarmupSet(
                    exerciseName = planned.exercise.name,
                    warmupSetLabel = "Warm-up ${state.warmupSetIndex + 1} of ${planned.warmupSets.size}",
                )
            } else if (planned.exercise.isTimed) {
                WorkoutNotificationState.TimedActiveSet(
                    exerciseName = planned.exercise.name,
                    setLabel = "Set ${state.setIndex + 1} of ${state.totalSets}",
                    secondsRemaining = state.timerSecondsRemaining,
                    progressMax = TIMED_SET_SECONDS,
                )
            } else {
                WorkoutNotificationState.ActiveSet(
                    exerciseName = planned.exercise.name,
                    weightLabel = if (planned.exercise.equipment == Equipment.BODYWEIGHT)
                        "Bodyweight"
                    else
                        WeightFormatter.format(planned.sessionWeight, weightUnit),
                    repsLabel = formatQuantity(planned.sessionReps, planned.exercise.isTimed),
                    setLabel = "Set ${state.setIndex + 1} of ${state.totalSets}",
                )
            }
        }
        is WorkoutState.Resting -> {
            val plan = state.plan
            val nextSet = state.completedSetIndex + 1
            val upNextLabel = when {
                nextSet < PlannedExercise.DEFAULT_SETS ->
                    "Next: Set ${nextSet + 1} · ${plan.exercises[state.exerciseIndex].exercise.name}"
                state.exerciseIndex + 1 < plan.exercises.size ->
                    "Next: ${plan.exercises[state.exerciseIndex + 1].exercise.name}"
                else -> "Last set — almost done!"
            }
            WorkoutNotificationState.Resting(
                secondsRemaining = state.secondsRemaining,
                progressMax = REST_SECONDS,
                upNextLabel = upNextLabel,
            )
        }
        is WorkoutState.Done, is WorkoutState.PlanPreview, WorkoutState.Loading -> null
    }

    companion object {
        const val REST_SECONDS = 90
        const val TIMED_SET_SECONDS = 60
    }
}
```

- [ ] **Step 2: Move `ExerciseRemovalReason` from `WorkoutViewModel.kt` to `WorkoutSessionController.kt`**

The enum is currently at the top of `WorkoutViewModel.kt`:
```kotlin
enum class ExerciseRemovalReason { NO_EQUIPMENT, DISLIKE, SKIP_TODAY }
```

Delete it from `WorkoutViewModel.kt`. It is now defined in `WorkoutSessionController.kt`. No import changes needed — both files are in `ui.workout` package, and `WorkoutScreen.kt` imports from the same package.

- [x] **Step 3: Build (controller in isolation, ViewModel still intact)**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. The controller exists but the ViewModel still has its own copy of all logic — that's fine for now. There should be no compilation errors.

- [x] **Step 4: Run unit tests**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: all tests pass.

- [x] **Step 5:** Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt
git commit -m "refactor: add WorkoutSessionController with extracted state machine"
```

---

## Task 4: Slim WorkoutViewModel and update WorkoutScreen

Replace `WorkoutViewModel` with a thin delegation layer and update `WorkoutScreen` to use the channel-backed `navigationEvent` instead of the `workoutCompleted` boolean state.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`

- [ ] **Step 1: Rewrite `WorkoutViewModel.kt`**

Replace the entire file content with:

```kotlin
package io.github.fowles.stochastic_strength.ui.workout

import android.app.Application
import android.content.Intent
import android.location.Geocoder
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WorkoutGenerator
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.location.LocationResult
import io.github.fowles.stochastic_strength.location.LocationService
import io.github.fowles.stochastic_strength.notification.WorkoutNotificationService
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryData
import io.github.fowles.stochastic_strength.ui.loadWorkoutSummary
import io.github.fowles.stochastic_strength.ui.strava.StravaExportController
import io.github.fowles.stochastic_strength.ui.strava.StravaExportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = WorkoutRepository(app.database)
    private val locationService = LocationService(app)

    private val controller = WorkoutSessionController(
        database = app.database,
        bus = app.workoutSessionBus,
        scope = viewModelScope,
        onVibrate = ::vibrate,
    )

    val state: StateFlow<WorkoutState> = controller.state
    val navigationEvent: Flow<NavigationEvent> = controller.navigationEvent

    private val _weightUnit = MutableStateFlow(WeightUnit.KG)
    val weightUnit: StateFlow<WeightUnit> = _weightUnit.asStateFlow()

    private val _doneSummary = MutableStateFlow<WorkoutSummaryData?>(null)
    val doneSummary: StateFlow<WorkoutSummaryData?> = _doneSummary.asStateFlow()

    private val stravaController = StravaExportController(app.stravaExporter, app.database, app.applicationScope)
    val stravaState: StateFlow<StravaExportState> = stravaController.state

    private var preferredExerciseCount: Int = WorkoutGenerator.DEFAULT_EXERCISE_COUNT

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            _weightUnit.value = profile?.weightUnit ?: WeightUnit.KG
            preferredExerciseCount = profile?.preferredExerciseCount ?: WorkoutGenerator.DEFAULT_EXERCISE_COUNT
            val locationId = resolveLocation()
            val locationName = locationId?.let { app.database.knownLocationDao().getById(it)?.name }
            controller.initializeSession(locationId, locationName, preferredExerciseCount, _weightUnit.value)
        }
        viewModelScope.launch {
            app.workoutSessionBus.commandFlow.collect { command ->
                when (command) {
                    is WorkoutCommand.RecordFeedback -> controller.recordFeedback(command.feedback)
                    WorkoutCommand.SkipRest -> controller.skipRest()
                    WorkoutCommand.CompleteWarmupSet -> controller.completeWarmupSet()
                    WorkoutCommand.StartTimedSet -> controller.startTimedSet()
                }
            }
        }
        viewModelScope.launch {
            var serviceStarted = false
            app.workoutSessionBus.notificationState.collect { s ->
                if (!serviceStarted && s != null) {
                    serviceStarted = true
                    app.startForegroundService(Intent(app, WorkoutNotificationService::class.java))
                }
            }
        }
        viewModelScope.launch {
            controller.state.collect { s ->
                if (s is WorkoutState.Done && _doneSummary.value == null) {
                    _doneSummary.value = loadWorkoutSummary(app.database, s.sessionId)
                }
            }
        }
    }

    fun setExerciseCount(targetCount: Int) {
        controller.adjustExerciseCount(targetCount)
        if (targetCount != preferredExerciseCount) {
            preferredExerciseCount = targetCount
            viewModelScope.launch {
                val profile = app.database.userProfileDao().getProfile() ?: return@launch
                app.database.userProfileDao().insert(profile.copy(preferredExerciseCount = targetCount))
            }
        }
    }

    fun startFirstExercise() = controller.startFirstExercise()
    fun replaceExercise(index: Int, reason: ExerciseRemovalReason) = controller.replaceExercise(index, reason)
    fun adjustExerciseWeight(index: Int, delta: Float) = controller.adjustExerciseWeight(index, delta)
    fun completeWarmupSet() = controller.completeWarmupSet()
    fun startTimedSet() = controller.startTimedSet()
    fun recordFeedback(feedback: SetFeedback) = controller.recordFeedback(feedback)
    fun undoLastSet() = controller.undoLastSet()
    fun skipRest() = controller.skipRest()
    fun reduceExerciseWeight(completedReps: Int) = controller.reduceExerciseWeight(completedReps)
    fun undoLastSetFromDone() = controller.undoLastSetFromDone()
    fun completeWorkout() = controller.completeWorkout()

    fun onResumed() {
        if (controller.state.value is WorkoutState.PlanPreview) {
            controller.onLocationRefreshed()
        }
        val done = controller.state.value as? WorkoutState.Done
        if (done != null) stravaController.onResumedWaitingForAuth(done.sessionId, _weightUnit.value)
    }

    fun onExportToStrava() {
        val done = controller.state.value as? WorkoutState.Done ?: return
        stravaController.export(done.sessionId, _weightUnit.value)
    }

    fun onStravaAuthUrlLaunched() = stravaController.onAuthUrlLaunched()
    fun onStravaMessageShown() = stravaController.onMessageShown()

    private suspend fun resolveLocation(): Long? = when (val loc = locationService.resolveLocation(app.database)) {
        is LocationResult.Known -> loc.locationId
        is LocationResult.Unknown -> createLocation(loc.latitude to loc.longitude)
        LocationResult.Unavailable -> null
    }

    private suspend fun createLocation(coords: Pair<Double, Double>): Long =
        app.database.knownLocationDao().insert(
            KnownLocation(
                name = reverseGeocode(coords.first, coords.second),
                latitude = coords.first,
                longitude = coords.second,
            )
        )

    private suspend fun reverseGeocode(lat: Double, lng: Double): String {
        val fallback = "%.4f, %.4f".format(lat, lng)
        if (!Geocoder.isPresent()) return fallback
        return suspendCancellableCoroutine { cont ->
            try {
                Geocoder(app).getFromLocation(lat, lng, 1) { addresses ->
                    val addr = addresses.firstOrNull()
                    val name = when {
                        addr?.thoroughfare != null ->
                            listOfNotNull(addr.subThoroughfare, addr.thoroughfare, addr.locality)
                                .joinToString(" ")
                        addr?.locality != null ->
                            listOfNotNull(addr.locality, addr.adminArea).joinToString(", ")
                        else -> fallback
                    }
                    cont.resume(name)
                }
            } catch (_: Exception) {
                cont.resume(fallback)
            }
        }
    }

    private fun vibrate() {
        val vibrator = app.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        val effect = VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 80)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 80)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 80)
            .compose()
        vibrator.vibrate(effect)
    }
}
```

- [ ] **Step 2: Update `WorkoutScreen.kt` — remove `workoutCompleted`, add navigationEvent collection, remove `onNavigatedToLocationEdit` call**

**Change 1** — Remove line 102 (the `workoutCompleted` state collection):
```kotlin
// Remove this line:
val workoutCompleted by viewModel.workoutCompleted.collectAsState()
```

**Change 2** — Replace lines 130–132 (the `LaunchedEffect` on `workoutCompleted`):
```kotlin
// Remove:
LaunchedEffect(workoutCompleted) {
    if (workoutCompleted) onWorkoutDone()
}
// Add (place it where the removed block was):
LaunchedEffect(Unit) {
    viewModel.navigationEvent.collect { onWorkoutDone() }
}
```

**Change 3** — Remove lines 161 (the `onNavigatedToLocationEdit` call). Change:
```kotlin
onEditLocation = { locationId ->
    viewModel.onNavigatedToLocationEdit()
    onEditLocation(locationId)
},
```
To:
```kotlin
onEditLocation = { locationId ->
    onEditLocation(locationId)
},
```

- [x] **Step 3: Verify no dead references remain**

```bash
grep -r "workoutCompleted\|onNavigatedToLocationEdit\|loadDoneSummary\|workoutCommandFlow\|workoutNotificationState" app/src/
```
Expected: no output (all removed).

```bash
grep -r "ExerciseRemovalReason" app/src/
```
Expected: references only in `WorkoutSessionController.kt` (definition) and `WorkoutScreen.kt` (usage). Not in `WorkoutViewModel.kt`.

- [x] **Step 4: Build and run unit tests**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [x] **Step 5:** Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt
git commit -m "refactor: slim WorkoutViewModel to delegation layer, fix navigation event"
```

---

## Verification

After all tasks complete, confirm the following invariants hold:

```bash
# No session-scoped state lives directly on the Application class
grep -n "MutableStateFlow\|MutableSharedFlow" app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt
```
Expected: no output (all flows are inside `WorkoutSessionBus`).

```bash
# WorkoutViewModel has no private var session fields
grep -n "private var" app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt
```
Expected: only `preferredExerciseCount`.

```bash
# Full test suite passes
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL.
