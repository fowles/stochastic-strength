# Workout State Cleanup — Design Spec

**Date:** 2026-06-10  
**Status:** Implemented  
**Goal:** Improve architectural clarity first, then eliminate mutable-state bugs that follow from unclear structure.

---

## Problem Summary

`WorkoutViewModel` (626 lines) has grown into three interleaved concerns:

1. **Session state machine** — `WorkoutState` transitions, timers, planner rebuilds
2. **Android lifecycle / UI concerns** — profile loading, location resolution, navigation, Strava
3. **App-level coupling** — writes to `StochasticStrengthApp.workoutNotificationState` and reads from `app.workoutCommandFlow`

This interleaving causes several concrete issues:

- `planner: WorkoutPlanner?` is rebuilt asynchronously in 4 places; there is a window where it diverges from `_state`
- `_workoutCompleted: MutableStateFlow<Boolean>` is a one-shot event modeled as persistent state; `LaunchedEffect(workoutCompleted)` can re-navigate on recomposition
- `pendingLocationRefresh: Boolean` is a manual cross-lifecycle flag
- `workoutNotificationState` and `workoutCommandFlow` live on `StochasticStrengthApp` despite being session-scoped
- `WorkoutViewModel.loadDoneSummary` and `SummaryViewModel.summary` duplicate the same DB queries

---

## Design

### 1. `WorkoutSessionBus` — named container for app-level session state

```kotlin
class WorkoutSessionBus {
    val notificationState = MutableStateFlow<WorkoutNotificationState?>(null)
    val commandFlow = MutableSharedFlow<WorkoutCommand>(extraBufferCapacity = 8)
}
```

`StochasticStrengthApp` instantiates one: `val workoutSessionBus = WorkoutSessionBus()`.  
`WorkoutNotificationService` reads from `app.workoutSessionBus.notificationState` (same access pattern as today, but named clearly).  
The ViewModel bridges commands and writes notification state via the controller.  

This is a grouping change only — no behavioral change — but it makes session-scoped state explicit and co-located.

---

### 2. `WorkoutSessionController` — owns the state machine

Extracted from `WorkoutViewModel`. Holds everything that belongs to an active workout session.

**Constructor inputs:**
```kotlin
class WorkoutSessionController(
    private val repository: WorkoutRepository,
    private val database: AppDatabase,
    private val bus: WorkoutSessionBus,
    private val weightUnit: WeightUnit,
    private val scope: CoroutineScope,
)
```

**Exposed state:**
```kotlin
val state: StateFlow<WorkoutState>
val navigationEvent: Flow<NavigationEvent>  // Channel-backed, consumed on collection
```

**Commands (public functions):**
- `startFirstExercise()`
- `replaceExercise(index: Int, reason: ExerciseRemovalReason)`
- `setExerciseCount(targetCount: Int)`
- `adjustExerciseWeight(index: Int, delta: Float)`
- `completeWarmupSet()`
- `startTimedSet()`
- `recordFeedback(feedback: SetFeedback)`
- `undoLastSet()`
- `skipRest()`
- `reduceExerciseWeight(completedReps: Int)`
- `undoLastSetFromDone()`
- `completeWorkout()`
- `onLocationRefreshed(locationId: Long)`

**Key invariants:**
- `planner` and `WorkoutState` are always updated together within the same coroutine. No external caller can rebuild the planner independently.
- `setState` writes both `_state` and `bus.notificationState` atomically.
- Timer jobs (`restTimerJob`, `timedSetTimerJob`, `addExerciseJob`) are private to the controller.
- The controller has no dependency on Android's `Application` or `ViewModel` — it is testable on the JVM.

**`NavigationEvent`:**
```kotlin
sealed interface NavigationEvent {
    data object WorkoutCompleted : NavigationEvent
}
```
Backed by `Channel<NavigationEvent>(Channel.BUFFERED)`, exposed as `receiveAsFlow()`. Consumed on collection — no re-fire risk.

---

### 3. `WorkoutViewModel` after the split (~100 lines)

Responsibilities:
1. Load `UserProfile` (weight unit, preferred exercise count) before constructing the controller
2. Resolve location via `LocationService`
3. Construct `WorkoutSessionController` and expose its `state` and `stravaState`
4. Bridge `bus.commandFlow` → controller methods (`init` block)
5. Collect `controller.navigationEvent` via `LaunchedEffect(Unit)` → call `onWorkoutDone()`
6. `onResumed()`: re-read location from DB, call `controller.onLocationRefreshed()` if changed — no flag needed
7. Delegate all workout commands to the controller
8. `onNavigatedToLocationEdit()` removed (replaced by stateless check in `onResumed`)
9. Strava handling unchanged (UI-lifecycle concern, stays in ViewModel)

**Flows exposed to the screen:**
- `state: StateFlow<WorkoutState>` — delegated from controller
- `weightUnit: StateFlow<WeightUnit>` — loaded once, rarely changes
- `stravaState: StateFlow<StravaExportState>` — from `StravaExportController`
- `doneSummary: StateFlow<WorkoutSummaryData?>` — loaded by the ViewModel via `repository.loadSummary(sessionId)` when it observes `WorkoutState.Done`; the controller emits `NavigationEvent.WorkoutCompleted` only after `applySessionProgression` finishes, so the summary is available before navigation fires

`_workoutCompleted` is removed entirely.

---

### 4. Summary loading deduplication

Extract `suspend fun loadSummary(sessionId: Long): WorkoutSummaryData` into `WorkoutRepository`.

Both `WorkoutViewModel` (for the in-workout done summary) and `SummaryViewModel` call this shared function. No logic duplication.

---

## Files Changed

| File | Change |
|------|--------|
| `ui/workout/WorkoutSessionBus.kt` | **New** |
| `ui/workout/WorkoutSessionController.kt` | **New** |
| `StochasticStrengthApp.kt` | Replace two fields with `val workoutSessionBus = WorkoutSessionBus()` |
| `ui/workout/WorkoutViewModel.kt` | Shrink to ~100 lines; delegate to controller |
| `domain/WorkoutRepository.kt` | Add `loadSummary(sessionId)` |
| `ui/summary/SummaryViewModel.kt` | Call `repository.loadSummary()` |
| `notification/WorkoutNotificationService.kt` | Read from `app.workoutSessionBus.notificationState` |
| `notification/WorkoutCommandReceiver.kt` | Emit to `app.workoutSessionBus.commandFlow` |

**No changes needed:** `WorkoutState.kt`, `WorkoutScreen.kt`, all other screens, DAOs, domain models.

---

## What This Does Not Change

- The `WorkoutState` sealed interface itself — it remains as-is
- The Strava export flow
- Navigation routes
- Database schema
- Test coverage of domain logic (already tested; controller is a new target for future tests)
