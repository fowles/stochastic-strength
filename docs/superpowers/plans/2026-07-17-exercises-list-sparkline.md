# Exercises List Sparklines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a small merged-est-1RM sparkline (last 6 months) to the right side of every row in the exercises list.

**Architecture:** Reuse `ExerciseProgressionSeriesBuilder.buildAllMergedSeries(db)` (one replay, already built for the History highlight). A new pure module `ExerciseSparklines` windows each series to 6 months, drops series with < 2 points, and normalizes values to `[0,1]` offsets. A `WorkoutRepository` method adapts the DB, `ExercisesViewModel` computes it once on init, and a lightweight Compose `Canvas` composable `Sparkline` draws a line + gradient fill in each `ExerciseRow`.

**Tech Stack:** Kotlin, Jetpack Compose (Canvas, Material3), Room (unchanged), JUnit4 (JVM unit tests).

## Global Constraints

- Package root: `io.github.fowles.stochastic_strength`.
- No belief/policy/replay math changes — **no backtest impact** (do not touch `BeliefConfig`, folds, pooling, or `backtest/`).
- No Room schema change (no `AppDatabase` version bump).
- Sparkline color is theme `MaterialTheme.colorScheme.primary`; neutral — no red/green trend coloring.
- Version control is jj (colocated). Commit at each task checkpoint with `jj commit -m`; do **not** push (the user owns reshape + push). Every commit message ends with the two trailers shown in the commit steps.
- `ProgressionPoint` is `data class ProgressionPoint(val timestampMs: Long, val value: Float)` in `domain/progression/`.

---

### Task 1: Pure `ExerciseSparklines` transforms

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseSparklines.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseSparklinesTest.kt`

**Interfaces:**
- Consumes: `ProgressionPoint(timestampMs: Long, value: Float)` from this package.
- Produces:
  - `ExerciseSparklines.DEFAULT_WINDOW_MS: Long` (~6 months)
  - `ExerciseSparklines.windowValues(seriesById: Map<Long, List<ProgressionPoint>>, firstPerformedById: Map<Long, Long>, nowMs: Long, windowMs: Long = DEFAULT_WINDOW_MS): Map<Long, List<Float>>` — per exercise keeps points in `max(now − windowMs, firstPerformed) .. now`; an exercise absent from `firstPerformedById` (never performed itself) is dropped.
  - `ExerciseSparklines.normalize(values: List<Float>): List<Float>` — min→0, max→1, flat→all 0.5, `< 2` values→empty.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseSparklinesTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSparklinesTest {
    private val day = 24L * 3600 * 1000
    private fun p(daysAgo: Long, v: Float, now: Long) = ProgressionPoint(now - daysAgo * day, v)
    // Performed well before the window so first-performed trimming is out of the way unless tested.
    private fun performedLongAgo(now: Long) = mapOf(1L to now - 10_000L * day)

    @Test fun windowValues_keepsInWindowValuesInOrder() {
        val now = 1_000_000_000_000L
        val series = mapOf(
            1L to listOf(p(200, 100f, now), p(30, 110f, now), p(1, 120f, now)),
        )
        val out = ExerciseSparklines.windowValues(series, performedLongAgo(now), now, windowMs = 182L * day)
        // The 200-days-ago point is outside the 182-day window and is dropped.
        assertEquals(listOf(110f, 120f), out[1L])
    }

    @Test fun windowValues_dropsSeriesWithFewerThanTwoInWindowPoints() {
        val now = 1_000_000_000_000L
        val series = mapOf(
            1L to listOf(p(200, 100f, now), p(1, 120f, now)),  // only 1 in-window
            2L to emptyList(),                                  // none
        )
        val first = mapOf(1L to now - 10_000L * day, 2L to now - 10_000L * day)
        val out = ExerciseSparklines.windowValues(series, first, now, windowMs = 182L * day)
        assertTrue(out.isEmpty())
    }

    @Test fun windowValues_excludesFuturePoints() {
        val now = 1_000_000_000_000L
        val series = mapOf(1L to listOf(p(10, 100f, now), p(-5, 130f, now), p(2, 120f, now)))
        val out = ExerciseSparklines.windowValues(series, performedLongAgo(now), now, windowMs = 182L * day)
        // The point 5 days in the FUTURE (daysAgo = -5) is excluded; two valid points remain.
        assertEquals(listOf(100f, 120f), out[1L])
    }

    @Test fun windowValues_dropsPointsBeforeFirstPerformed() {
        val now = 1_000_000_000_000L
        // Three in-window sibling-informed points; the exercise was first performed itself 20 days ago.
        val series = mapOf(1L to listOf(p(60, 100f, now), p(40, 105f, now), p(10, 120f, now)))
        val first = mapOf(1L to now - 20 * day)
        val out = ExerciseSparklines.windowValues(series, first, now, windowMs = 182L * day)
        // Only the 10-days-ago point is at/after first-performed — that leaves 1 point, so dropped.
        assertTrue(out.isEmpty())
    }

    @Test fun windowValues_keepsFromFirstPerformedOnward() {
        val now = 1_000_000_000_000L
        val series = mapOf(1L to listOf(p(60, 100f, now), p(40, 105f, now), p(10, 120f, now)))
        val first = mapOf(1L to now - 50 * day)
        val out = ExerciseSparklines.windowValues(series, first, now, windowMs = 182L * day)
        // 60-days-ago precedes first-performed (50) and is dropped; 40 and 10 remain.
        assertEquals(listOf(105f, 120f), out[1L])
    }

    @Test fun windowValues_dropsExerciseNeverPerformedItself() {
        val now = 1_000_000_000_000L
        val series = mapOf(1L to listOf(p(30, 100f, now), p(10, 120f, now)))  // sibling-only points
        val out = ExerciseSparklines.windowValues(series, firstPerformedById = emptyMap(), nowMs = now, windowMs = 182L * day)
        assertTrue(out.isEmpty())
    }

    @Test fun normalize_mapsMinToZeroMaxToOne() {
        assertEquals(listOf(0f, 0.5f, 1f), ExerciseSparklines.normalize(listOf(10f, 20f, 30f)))
    }

    @Test fun normalize_flatSeriesIsAllHalf() {
        assertEquals(listOf(0.5f, 0.5f, 0.5f), ExerciseSparklines.normalize(listOf(42f, 42f, 42f)))
    }

    @Test fun normalize_tooFewValuesIsEmpty() {
        assertTrue(ExerciseSparklines.normalize(listOf(5f)).isEmpty())
        assertTrue(ExerciseSparklines.normalize(emptyList()).isEmpty())
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseSparklinesTest"`
Expected: FAIL — compile error, `ExerciseSparklines` is unresolved.

- [x] **Step 3: Write minimal implementation**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseSparklines.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

/**
 * Pure sparkline transforms for the exercises list. No Android, no DB.
 */
object ExerciseSparklines {
    /** Default lookback for the exercises-list sparklines: ~6 months. */
    const val DEFAULT_WINDOW_MS: Long = 182L * 24 * 3600 * 1000

    /**
     * Reduces every exercise's merged 1RM series to the bare values in the input's session/time
     * order, keeping only points in `max(nowMs − windowMs, firstPerformed) .. nowMs`.
     *
     * The window floor is raised to the exercise's first actually-performed set time
     * ([firstPerformedById]) because the merged series carries sibling-driven points from before a
     * lift's own debut (pooling records a point for every exercise in a touched muscle) — those
     * leading points are not this lift's progress and must not be drawn. An exercise absent from
     * [firstPerformedById] (never performed itself) is dropped entirely.
     *
     * Series with fewer than 2 surviving points are dropped — a sparkline needs at least two points
     * to have a shape — so sparse/new lifts get no entry (their row shows nothing).
     */
    fun windowValues(
        seriesById: Map<Long, List<ProgressionPoint>>,
        firstPerformedById: Map<Long, Long>,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Map<Long, List<Float>> {
        val cutoff = nowMs - windowMs
        return seriesById.mapNotNull { (id, points) ->
            val firstPerformed = firstPerformedById[id] ?: return@mapNotNull null
            val floor = maxOf(cutoff, firstPerformed)
            val values = points.filter { it.timestampMs in floor..nowMs }.map { it.value }
            if (values.size < 2) null else id to values
        }.toMap()
    }

    /**
     * Maps [values] to vertical offsets in `[0, 1]`: min → 0, max → 1 (a flat series → all 0.5). The
     * renderer flips these to y-coordinates. Returns empty for fewer than 2 values.
     */
    fun normalize(values: List<Float>): List<Float> {
        if (values.size < 2) return emptyList()
        val min = values.min()
        val max = values.max()
        val span = max - min
        if (span <= 0f) return values.map { 0.5f }
        return values.map { (it - min) / span }
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseSparklinesTest"`
Expected: PASS (8 tests).

- [x] **Step 5: Commit**

```bash
jj commit -m 'feat(exercises): pure sparkline window + normalize transforms

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01H61mBfQ9GnwvCMt9mdeX4d'
```

---

### Task 2: `Sparkline` Canvas composable

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/components/Sparkline.kt`

**Interfaces:**
- Consumes: `ExerciseSparklines.normalize(...)` from Task 1.
- Produces: `@Composable fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier, width: Dp = 96.dp, height: Dp = 28.dp, strokeWidth: Dp = 1.5.dp)`.

No JVM unit test — Compose `Canvas` drawing is verified by the assemble build (Task 3) and on-device. The compilable deliverable is the composable itself.

- [x] **Step 1: Write the composable**

Create `app/src/main/java/io/github/fowles/stochastic_strength/ui/components/Sparkline.kt`:

```kotlin
package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSparklines

/**
 * A tiny static sparkline of [values] (self-normalized to its own min/max): a thin [color] line
 * with a faint vertical gradient fill beneath. Renders nothing for fewer than 2 values.
 *
 * Deliberately drawn on a Compose [Canvas] rather than a Vico chart — a Vico chart is too heavy to
 * instantiate per row in a LazyColumn; a static sparkline needs only two paths.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    height: Dp = 28.dp,
    strokeWidth: Dp = 1.5.dp,
) {
    val offsets = ExerciseSparklines.normalize(values)
    if (offsets.size < 2) return
    Canvas(modifier = modifier.size(width, height)) {
        val stepX = size.width / (offsets.size - 1)
        // Inset top and bottom by the stroke width so the peak/trough aren't clipped.
        val pad = strokeWidth.toPx()
        val usableH = size.height - pad * 2
        fun pointAt(i: Int): Offset {
            // offset 1 = max = top of the box; flip to screen y.
            val y = pad + (1f - offsets[i]) * usableH
            return Offset(i * stepX, y)
        }
        val line = Path().apply {
            val first = pointAt(0)
            moveTo(first.x, first.y)
            for (i in 1 until offsets.size) {
                val pt = pointAt(i)
                lineTo(pt.x, pt.y)
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)),
            ),
        )
        drawPath(path = line, color = color, style = Stroke(width = strokeWidth.toPx()))
    }
}
```

- [x] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
jj commit -m 'feat(exercises): Sparkline Canvas composable (line + gradient fill)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01H61mBfQ9GnwvCMt9mdeX4d'
```

---

### Task 3: Wire repository → ViewModel → row

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSetDao.kt` (add query + projection)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (add method near `getExerciseProgressionData`, ~line 423)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExercisesViewModel.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExercisesScreen.kt`

**Interfaces:**
- Consumes: `ExerciseSparklines` (Task 1), `Sparkline(...)` (Task 2), existing `progressionSeriesBuilder.buildAllMergedSeries(db)`.
- Produces:
  - `WorkoutSetDao.getFirstCompletedAtByExercise(): List<ExerciseFirstCompleted>` and `data class ExerciseFirstCompleted(exerciseId: Long, firstCompletedAt: Long)`
  - `WorkoutRepository.buildExerciseSparklines(windowMs: Long = ExerciseSparklines.DEFAULT_WINDOW_MS, nowMs: Long = System.currentTimeMillis()): Map<Long, List<Float>>`
  - `ExercisesViewModel.sparklines: StateFlow<Map<Long, List<Float>>>`

- [x] **Step 1: Add the first-performed DAO query**

In `WorkoutSetDao.kt`, add a projection data class above the `@Dao interface WorkoutSetDao` declaration (after the imports):

```kotlin
/** One exercise's earliest completed-set time — the first session it was actually performed in. */
data class ExerciseFirstCompleted(val exerciseId: Long, val firstCompletedAt: Long)
```

Then add this query inside the interface (e.g. after `getAll()`):

```kotlin
    @Query("""
        SELECT exerciseId, MIN(completedAt) AS firstCompletedAt
        FROM workout_sets
        WHERE completedAt IS NOT NULL
        GROUP BY exerciseId
    """)
    suspend fun getFirstCompletedAtByExercise(): List<ExerciseFirstCompleted>
```

- [x] **Step 2: Add the repository method**

In `WorkoutRepository.kt`, add the import alongside the other `domain.progression` imports (near line 29):

```kotlin
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSparklines
```

Then add this method immediately after `getExerciseProgressionData` (after line 424):

```kotlin
    /**
     * Per-exercise merged-1RM sparkline values for the exercises list: every exercise's merged
     * (belief) 1RM trend from [ExerciseProgressionSeriesBuilder.buildAllMergedSeries] (ONE replay),
     * windowed to the last [windowMs] and reduced to bare values via [ExerciseSparklines.windowValues].
     * The per-exercise first-performed time trims leading sibling-driven points from before the lift's
     * own debut; exercises with fewer than 2 surviving points are omitted (their row shows nothing).
     */
    suspend fun buildExerciseSparklines(
        windowMs: Long = ExerciseSparklines.DEFAULT_WINDOW_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<Long, List<Float>> {
        val firstPerformed = db.workoutSetDao().getFirstCompletedAtByExercise()
            .associate { it.exerciseId to it.firstCompletedAt }
        return ExerciseSparklines.windowValues(
            progressionSeriesBuilder.buildAllMergedSeries(db), firstPerformed, nowMs, windowMs,
        )
    }
```

- [x] **Step 3: Expose sparklines from the ViewModel**

In `ExercisesViewModel.kt`, add the state flow and populate it in `init`. Replace the existing `init { ... }` block (lines 36–42) with:

```kotlin
    private val _sparklines = MutableStateFlow<Map<Long, List<Float>>>(emptyMap())
    val sparklines: StateFlow<Map<Long, List<Float>>> = _sparklines.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAllExercises().collect { exercises ->
                _state.value = _state.value.copy(exercises = exercises)
            }
        }
        // Computed once (beliefs only change after a workout finishes, and this screen is entered
        // fresh from home) — mirrors the History highlight's one-shot series build.
        viewModelScope.launch {
            _sparklines.value = repository.buildExerciseSparklines()
        }
    }
```

(`MutableStateFlow`, `StateFlow`, `asStateFlow`, `viewModelScope`, `launch` are already imported.)

- [x] **Step 4: Render the sparkline in the row**

In `ExercisesScreen.kt`:

(a) Add imports (with the other `androidx.compose.ui.unit`/component imports):

```kotlin
import io.github.fowles.stochastic_strength.ui.components.Sparkline
```

(b) In `ExercisesScreen`, read the flow next to the existing `hurtMap` collection (after line 48):

```kotlin
    val sparklines by viewModel.sparklines.collectAsState()
```

(c) Pass it into the row — replace the `ExerciseRow(...)` call (lines 128–132) with:

```kotlin
                        ExerciseRow(
                            exercise = exercise,
                            isHurt = hurtMap[exercise.id] ?: false,
                            sparkline = sparklines[exercise.id],
                            onClick = { onExerciseTap(exercise.id) },
                        )
```

(d) Update `ExerciseRow` (lines 141–174) to accept and draw the sparkline. Replace the whole function with:

```kotlin
@Composable
private fun ExerciseRow(
    exercise: Exercise,
    isHurt: Boolean,
    sparkline: List<Float>?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(exercise.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                exercise.equipment.displayName(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sparkline != null) {
            Sparkline(
                values = sparkline,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (exercise.isDisliked) {
            StatusBadge(
                label = "Disliked",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (isHurt) {
            StatusBadge(
                label = "Hurt",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
```

- [x] **Step 5: Build the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [x] **Step 6: Run the full unit test suite (regression check)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all tests pass, including `ExerciseSparklinesTest`.

- [x] **Step 7: Commit**

```bash
jj commit -m 'feat(exercises): merged-1RM sparkline on each exercise row

Repo buildExerciseSparklines (windowed buildAllMergedSeries) -> ViewModel
one-shot StateFlow -> Sparkline in ExerciseRow, right of the name column.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01H61mBfQ9GnwvCMt9mdeX4d'
```

---

### Task 4: On-device verification

**Files:** none (manual/visual).

- [x] **Step 1: Install and launch on the connected device/emulator**

Run: `./gradlew :app:installDebug`
Then open the app → **Exercises**.

- [x] **Step 2: Verify the visuals**

Confirm, on device:
- Exercises trained ≥ 2 times in the last 6 months show a small line + gradient sparkline to the right of the name, before any Disliked/Hurt badge.
- New/dormant lifts (< 2 in-window sessions) show **no** sparkline and the name column keeps its width (nothing shifts).
- The sparkline reads clearly in both light and dark theme (primary color; verify the gradient fill is visible but subtle — recall dynamic-color tokens can be washed out, so eyeball it).
- Rows with a Disliked or Hurt badge still lay out cleanly with the sparkline present.

- [x] **Step 3: Note any visual adjustments**

If width/height/alpha need tuning, adjust the defaults in `Sparkline.kt` (Task 2) or the `padding` in `ExerciseRow`, rebuild (`./gradlew :app:installDebug`), and amend the Task 3 commit (`jj describe` / re-commit). No behavioral logic changes here.

---

## Self-Review

**Spec coverage:**
- Merged est-1RM signal → `buildAllMergedSeries` reused in Task 3 repo method. ✓
- 6-month window → `DEFAULT_WINDOW_MS = 182 days`, `windowValues` (Task 1). ✓
- No leading sibling-only points → `windowValues` raises the floor to first-performed and drops never-performed lifts, fed by `getFirstCompletedAtByExercise` (Tasks 1, 3), covered by `windowValues_dropsPointsBeforeFirstPerformed` / `keepsFromFirstPerformedOnward` / `dropsExerciseNeverPerformedItself`. ✓
- Blank when < 2 in-window points → `windowValues` drop + `Sparkline` early return (Tasks 1, 2). ✓
- Self-normalized per row → `normalize` (Task 1), used by `Sparkline` (Task 2). ✓
- Line + soft gradient fill, primary color, neutral → `Sparkline` (Task 2), color passed in Task 3. ✓
- Compute once on init → `ExercisesViewModel` `init` one-shot launch (Task 3). ✓
- Row layout name(weight 1f) · sparkline ~96×28 · badges → `ExerciseRow` (Task 3), size defaults (Task 2). ✓
- Unit tests for window filter, < 2 drop, normalization → `ExerciseSparklinesTest` (Task 1). ✓
- Canvas not Vico; on-device verification → Task 2 doc + Task 4. ✓
- No backtest/schema impact → no touched math/schema files. ✓

**Placeholder scan:** none — every code step has complete code.

**Type consistency:** `ExerciseSparklines.windowValues(seriesById, firstPerformedById, nowMs, windowMs)`/`normalize`/`DEFAULT_WINDOW_MS`, `WorkoutSetDao.getFirstCompletedAtByExercise(): List<ExerciseFirstCompleted>`, `buildExerciseSparklines`, `sparklines` StateFlow, and `Sparkline(values, color, modifier, ...)` are used with identical names/signatures across Tasks 1–3. The repo method calls `windowValues` with the `firstPerformed` map positionally matching the new second parameter. ✓
