# Finished-Workout Inspiration Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a `HighlightCard` on the workout-complete screen, between the Duration line and the exercise list, with a fact about a lift or muscle group performed in this session paired with a quip.

**Architecture:** Reuse the History highlight machinery (`HistoryHighlight` + `HighlightCard`). Add session scoping to the domain layer, a repository orchestration method, a `StateFlow<String?>` on `WorkoutViewModel` computed when the workout finishes, and an optional slot in the shared `WorkoutSummaryContent`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), JUnit (JVM unit tests).

## Global Constraints

- Package root: `io.github.fowles.stochastic_strength`
- No schema migration, no backtest impact.
- Unit test command: `./gradlew :app:testDebugUnitTest`
- Build command: `./gradlew :app:assembleDebug`
- Version control: jj colocated; `git commit` at each checkpoint, user owns reshape/push.

---

### Task 1: Session scoping in the domain layer

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlight.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlightTest.kt`

**Interfaces:**
- Consumes: existing `HighlightSeries`, `HighlightKind`, `HighlightConfig`, `HistoryHighlight.pick(...)`.
- Produces:
  - `HighlightSeries` gains a new field `val exerciseId: Long? = null` (last constructor parameter, defaulted).
  - `fun HistoryHighlight.scopeToSession(series: List<HighlightSeries>, exerciseIds: Set<Long>, muscles: Set<MuscleGroup>): List<HighlightSeries>`

- [ ] **Step 1: Write the failing tests**

Add to `HistoryHighlightTest.kt` (inside the existing test class; add imports `HighlightSeries`, `HighlightKind`, `HistoryHighlight`, `MuscleGroup`, `ProgressionPoint`, `WeightUnit`, `kotlin.random.Random`, `org.junit.Assert.*`, `org.junit.Test` if not already present):

```kotlin
@Test
fun scopeToSession_keepsSessionLiftsAndMuscles() {
    val pts = listOf(ProgressionPoint(0L, 100f), ProgressionPoint(1L, 110f))
    val inLift = HighlightSeries("Bench Press", MuscleGroup.CHEST, pts, HighlightKind.LIFT, exerciseId = 1L)
    val outLift = HighlightSeries("Squat", MuscleGroup.QUADS, pts, HighlightKind.LIFT, exerciseId = 2L)
    val inMuscle = HighlightSeries("Chest", MuscleGroup.CHEST, pts, HighlightKind.MUSCLE)
    val outMuscle = HighlightSeries("Quads", MuscleGroup.QUADS, pts, HighlightKind.MUSCLE)

    val scoped = HistoryHighlight.scopeToSession(
        series = listOf(inLift, outLift, inMuscle, outMuscle),
        exerciseIds = setOf(1L),
        muscles = setOf(MuscleGroup.CHEST),
    )

    assertEquals(listOf(inLift, inMuscle), scoped)
}

@Test
fun pick_withNoQuipOnly_returnsFactWhenCandidateExists() {
    // A clear month-over-month lift gain guarantees a candidate.
    val monthMs = 30L * 24 * 3600 * 1000
    val now = 10L * monthMs
    val series = listOf(
        HighlightSeries(
            subject = "Bench Press",
            muscle = MuscleGroup.CHEST,
            points = listOf(
                ProgressionPoint(now - 2 * monthMs, 100f),
                ProgressionPoint(now, 120f),
            ),
            kind = HighlightKind.LIFT,
            exerciseId = 1L,
        ),
    )
    // Try several seeds; with quipOnlyProbability = 0f none may be a bare quip.
    repeat(20) { seed ->
        val text = HistoryHighlight.pick(
            series = series,
            weightUnit = WeightUnit.KG,
            nowMs = now,
            random = Random(seed.toLong()),
            config = HighlightConfig(quipOnlyProbability = 0f),
        )
        assertTrue("expected a fact, got: $text", text.contains("Bench Press is up"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.HistoryHighlightTest"`
Expected: FAIL — `scopeToSession` unresolved and `HighlightSeries` has no `exerciseId` parameter.

- [ ] **Step 3: Add the `exerciseId` field**

In `HistoryHighlight.kt`, change the `HighlightSeries` data class:

```kotlin
data class HighlightSeries(
    val subject: String,
    val muscle: MuscleGroup?,
    val points: List<ProgressionPoint>,
    val kind: HighlightKind,
    val exerciseId: Long? = null,
)
```

- [ ] **Step 4: Add the `scopeToSession` function**

Inside `object HistoryHighlight`, add:

```kotlin
/** Filter series down to lifts/muscles performed in one session. */
fun scopeToSession(
    series: List<HighlightSeries>,
    exerciseIds: Set<Long>,
    muscles: Set<MuscleGroup>,
): List<HighlightSeries> = series.filter { s ->
    when (s.kind) {
        HighlightKind.LIFT -> s.exerciseId in exerciseIds
        HighlightKind.MUSCLE -> s.muscle in muscles
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.HistoryHighlightTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlight.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlightTest.kt
git commit -m "feat(history): session scoping for highlight facts"
```

---

### Task 2: Repository orchestration

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (method `buildHighlightSeries` ~line 398, and add a new method after it)

**Interfaces:**
- Consumes: `HistoryHighlight.scopeToSession`, `HistoryHighlight.pick`, `HighlightConfig`, `db.workoutSetDao().getSetsForSession(sessionId)`, `db.exerciseDao().getByIds(ids)`.
- Produces: `suspend fun WorkoutRepository.buildSessionHighlight(sessionId: Long, weightUnit: WeightUnit, nowMs: Long, random: Random): String`

- [ ] **Step 1: Fill `exerciseId` on lift series**

In `buildHighlightSeries()`, update the `liftSeries` mapping so each `LIFT` series carries its exercise id:

```kotlin
val liftSeries = progressionSeriesBuilder.buildAllMergedSeries(db).mapNotNull { (id, points) ->
    val exercise = exercisesById[id] ?: return@mapNotNull null
    if (points.isEmpty()) null
    else HighlightSeries(exercise.name, exercise.primaryMuscle, points, HighlightKind.LIFT, exerciseId = id)
}
```

- [ ] **Step 2: Add `buildSessionHighlight`**

Immediately after `buildHighlightSeries()`, add:

```kotlin
/**
 * Highlight string for the finished-workout card: a fact about a lift or muscle
 * performed in [sessionId], paired with a quip. Always tries a session fact
 * (quipOnlyProbability = 0), falling back to a bare quip only when nothing
 * qualifies. Seed [random] with the session id for a stable-per-session pick.
 */
suspend fun buildSessionHighlight(
    sessionId: Long,
    weightUnit: WeightUnit,
    nowMs: Long,
    random: Random,
): String {
    val series = buildHighlightSeries()
    val exerciseIds = db.workoutSetDao().getSetsForSession(sessionId)
        .map { it.exerciseId }.toSet()
    val muscles = db.exerciseDao().getByIds(exerciseIds.toList())
        .map { it.primaryMuscle }.toSet()
    val scoped = HistoryHighlight.scopeToSession(series, exerciseIds, muscles)
    return HistoryHighlight.pick(
        series = scoped,
        weightUnit = weightUnit,
        nowMs = nowMs,
        random = random,
        config = HighlightConfig(quipOnlyProbability = 0f),
    )
}
```

Ensure imports exist in the file: `HighlightConfig` (from `domain.history`), `kotlin.random.Random`, `WeightUnit`. Add any that are missing.

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "feat(workout): repository buildSessionHighlight"
```

---

### Task 3: ViewModel state

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`

**Interfaces:**
- Consumes: `repository.buildSessionHighlight(...)`, `_weightUnit`, existing `controller.state` collector.
- Produces: `val doneHighlight: StateFlow<String?>` on `WorkoutViewModel`.

- [ ] **Step 1: Add the backing state**

Below the existing `_doneSummary` declaration (~line 51), add:

```kotlin
private val _doneHighlight = MutableStateFlow<String?>(null)
val doneHighlight: StateFlow<String?> = _doneHighlight.asStateFlow()
```

- [ ] **Step 2: Compute on Done, clear otherwise**

In the `controller.state.collect { s -> ... }` block (~line 108-117), extend the branches so the highlight is computed alongside the summary and cleared when leaving Done:

```kotlin
viewModelScope.launch {
    controller.state.collect { s ->
        when {
            s is WorkoutState.Done && _doneSummary.value == null -> {
                _doneSummary.value = loadWorkoutSummary(app.database, s.sessionId)
                _doneHighlight.value = withContext(Dispatchers.Default) {
                    app.workoutRepository.buildSessionHighlight(
                        sessionId = s.sessionId,
                        weightUnit = _weightUnit.value,
                        nowMs = System.currentTimeMillis(),
                        random = Random(s.sessionId),
                    )
                }
            }
            s !is WorkoutState.Done -> {
                _doneSummary.value = null
                _doneHighlight.value = null
            }
        }
    }
}
```

Add imports if missing: `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`, `kotlin.random.Random`. (`app.workoutRepository` is the repository singleton; if the class already holds a `repository` reference, use that instead.)

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt
git commit -m "feat(workout): doneHighlight state on WorkoutViewModel"
```

---

### Task 4: Summary content slot + wire the card

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/WorkoutSummaryContent.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/DoneContent.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`

**Interfaces:**
- Consumes: `doneHighlight: StateFlow<String?>` (Task 3), `HighlightCard` from `ui.history`.
- Produces: `WorkoutSummaryContent` gains `belowDuration: (@Composable ColumnScope.() -> Unit)? = null`; `DoneContent` gains `highlight: String?`.

- [ ] **Step 1: Add the `belowDuration` slot**

In `WorkoutSummaryContent.kt`, add the parameter (after `footer`, or before it — keep `header`/`footer` last-lambda ergonomics by placing `belowDuration` before `header`):

```kotlin
@Composable
fun WorkoutSummaryContent(
    summary: WorkoutSummaryData?,
    modifier: Modifier = Modifier,
    onExerciseTap: ((Long) -> Unit)? = null,
    belowDuration: (@Composable ColumnScope.() -> Unit)? = null,
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
```

Render it between the Duration `Text` and the `Spacer(Modifier.height(24.dp))` that precedes the exercise loop:

```kotlin
            Text(
                "Duration: ${minutes}m ${seconds}s",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (belowDuration != null) {
                Spacer(Modifier.height(12.dp))
                belowDuration()
            }
            Spacer(Modifier.height(24.dp))
```

- [ ] **Step 2: Pass the card from `DoneContent`**

In `DoneContent.kt`, add the `highlight` parameter and render `HighlightCard` in the slot:

```kotlin
import io.github.fowles.stochastic_strength.ui.history.HighlightCard
```

```kotlin
@Composable
internal fun DoneContent(
    doneSummary: WorkoutSummaryData?,
    highlight: String?,
    stravaState: StravaExportState,
    onExportToStrava: () -> Unit,
    onDone: () -> Unit,
) {
    WorkoutSummaryContent(
        summary = doneSummary,
        belowDuration = highlight?.let { text -> { HighlightCard(text) } },
        header = {
            Text("Workout Complete!", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
        },
        footer = {
            StravaExportButton(
                onExportToStrava = onExportToStrava,
                stravaState = stravaState,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        },
    )
}
```

- [ ] **Step 3: Wire `doneHighlight` in `WorkoutScreen`**

In `WorkoutScreen.kt`, near the other `collectAsState()` calls (~line 40), add:

```kotlin
val doneHighlight by viewModel.doneHighlight.collectAsState()
```

Then pass it in the `Done` branch (~line 167):

```kotlin
                is WorkoutState.Done -> DoneContent(
                    doneSummary = doneSummary,
                    highlight = doneHighlight,
                    stravaState = stravaState,
                    onExportToStrava = viewModel::onExportToStrava,
                    onDone = viewModel::completeWorkout,
                )
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/WorkoutSummaryContent.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/DoneContent.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt
git commit -m "feat(workout): show inspiration card on finished-workout view"
```

---

### Task 5: Full regression pass

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 2: Confirm the debug build assembles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: On-device visual check (deferred if no device)**

Finish a workout and confirm the card appears between "Duration" and the first exercise, shows a session-scoped fact + quip (or a bare quip when the session has no measurable gain), and animates the scramble reveal. Note this as pending if no device is attached.

---

## Notes for the implementer

- `HighlightCard` lives in `ui.history` and is safe to reuse cross-package; it self-animates on `text` change.
- The historical `SummaryScreen` intentionally passes no `belowDuration` — do not add the card there.
- No Room version bump: this feature reads existing tables only.
