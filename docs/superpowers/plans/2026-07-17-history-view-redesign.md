# History View Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the History screen's muscle-grid + flat-list layout with a stochastic motivational highlight card, a paged month calendar that marks workout days, and a month-grouped scrolling session list.

**Architecture:** All decision logic lives in two pure Kotlin components under `domain/history/` (`HistoryHighlight` picks the motivational string; `HistoryRows` maps sessions to calendar/list structures) — both JVM-unit-tested. `WorkoutRepository` assembles the highlight's input series from the DB (it has the query context). `HistoryViewModel` wires them and chooses the daily-vs-debug random seed. The Compose layer (`HighlightCard`, `MonthCalendar`, rebuilt `HistoryScreen`) is presentation only.

**Tech Stack:** Kotlin, Jetpack Compose + Material3, Room, `java.time` (`LocalDate`/`YearMonth`/`ZoneId`), JUnit4 (JVM unit tests under `app/src/test/`).

## Global Constraints

- Package root: `io.github.fowles.stochastic_strength`.
- Min SDK 33, Target SDK 36. `java.time` is available (no desugaring needed).
- Build: `./gradlew :app:assembleDebug`. Unit tests: `./gradlew :app:testDebugUnitTest`.
- No new Room entities, no schema/version bump — everything derives from existing data.
- No changes to progression/belief/estimator math. The backtest gate must stay green.
- This repo has **no Compose UI tests** — do not add `createComposeRule` tests. UI tasks are verified by a passing `assembleDebug` build; behavioral logic is pushed into pure components that ARE unit-tested.
- Version control is `jj`; commit at each task checkpoint. The user owns reshape + push.
- Weight display uses `WeightFormatter.format(kg, unit)`. Muscle labels use `MuscleGroup.displayName()`.
- The quip **"The bar does not care about your feelings. Add weight to it anyway."** must be present in the quip pool (committed by the user).
- `BuildConfig.DEBUG` gating lives ONLY in the ViewModel (keeps `HistoryHighlight` Android-free and testable).

---

### Task 1: `HistoryHighlight` pure component ✅ COMPLETE

Pure, Android-free. Reuses the existing `ProgressionPoint(timestampMs: Long, value: Float)` from `domain/progression`. Given per-lift and per-muscle value series, produces one motivational display string, deterministic under an injected `Random`.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlight.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlightTest.kt`

**Interfaces:**
- Consumes: `ProgressionPoint` (`domain.progression.ProgressionPoint`), `MuscleGroup` (`data.model.MuscleGroup`), `WeightUnit` (`data.model.WeightUnit`), `WeightFormatter` (`domain.WeightFormatter`).
- Produces:
  - `enum class HighlightKind { LIFT, MUSCLE }`
  - `data class HighlightSeries(val subject: String, val muscle: MuscleGroup?, val points: List<ProgressionPoint>, val kind: HighlightKind)`
  - `data class HighlightConfig(val monthWindowMs: Long = 30L*24*3600*1000, val quarterWindowMs: Long = 90L*24*3600*1000, val liftMinGainKg: Float = 2f, val muscleMinGainFraction: Float = 0.03f, val quipOnlyProbability: Float = 0.25f, val appendQuipProbability: Float = 0.4f)`
  - `object HistoryHighlight { fun pick(series: List<HighlightSeries>, weightUnit: WeightUnit, nowMs: Long, random: Random, config: HighlightConfig = HighlightConfig()): String }`

- [x] **Step 1: Write the failing test**

Create `HistoryHighlightTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.history

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random
import org.junit.Test

class HistoryHighlightTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 3600 * 1000
    // Seed chosen so the first nextFloat() > quipOnlyProbability (shows a stat, not a quip-only).
    private fun statSeed() = Random(1)

    private fun liftSeries(subject: String, muscle: MuscleGroup, startKg: Float, endKg: Float) =
        HighlightSeries(
            subject = subject, muscle = muscle, kind = HighlightKind.LIFT,
            points = listOf(
                ProgressionPoint(now - 40 * day, startKg),
                ProgressionPoint(now, endKg),
            ),
        )

    @Test
    fun `lift gain over month is reported in absolute weight`() {
        val text = HistoryHighlight.pick(
            series = listOf(liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 70f)),
            weightUnit = WeightUnit.KG, nowMs = now, random = statSeed(),
        )
        assertTrue(text, text.contains("Bench Press"))
        assertTrue(text, text.contains("10.0 kg"))
    }

    @Test
    fun `muscle gain is reported as a percent`() {
        val series = HighlightSeries(
            subject = MuscleGroup.CHEST.displayName(), muscle = MuscleGroup.CHEST,
            kind = HighlightKind.MUSCLE,
            points = listOf(ProgressionPoint(now - 40 * day, 100f), ProgressionPoint(now, 115f)),
        )
        val text = HistoryHighlight.pick(
            series = listOf(series), weightUnit = WeightUnit.KG, nowMs = now, random = statSeed(),
        )
        assertTrue(text, text.contains("15%"))
    }

    @Test
    fun `flat and negative series never produce a stat, only a quip`() {
        val flat = liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 60f)
        val down = liftSeries("Squat", MuscleGroup.QUADS, 100f, 90f)
        // Any seed: with no qualifying candidate the result must be a bare quip from the pool.
        repeat(20) { s ->
            val text = HistoryHighlight.pick(
                series = listOf(flat, down), weightUnit = WeightUnit.KG,
                nowMs = now, random = Random(s.toLong()),
            )
            assertTrue(text, HistoryHighlight.QUIPS.any { it.text == text })
        }
    }

    @Test
    fun `sub-threshold gain does not qualify`() {
        val tiny = liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 61f) // 1kg < 2kg floor
        repeat(20) { s ->
            val text = HistoryHighlight.pick(
                series = listOf(tiny), weightUnit = WeightUnit.KG, nowMs = now, random = Random(s.toLong()),
            )
            assertTrue(text, HistoryHighlight.QUIPS.any { it.text == text })
        }
    }

    @Test
    fun `same date and data produces the same pick`() {
        val series = listOf(liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 70f))
        val a = HistoryHighlight.pick(series, WeightUnit.KG, now, Random(42))
        val b = HistoryHighlight.pick(series, WeightUnit.KG, now, Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `empty series returns a generic quip`() {
        val text = HistoryHighlight.pick(emptyList(), WeightUnit.KG, now, Random(7))
        val quip = HistoryHighlight.QUIPS.first { it.text == text }
        assertEquals(null, quip.muscle) // standalone quips are always generic
    }

    @Test
    fun `committed quip is present in the pool`() {
        assertTrue(HistoryHighlight.QUIPS.any {
            it.text == "The bar does not care about your feelings. Add weight to it anyway."
        })
    }

    @Test
    fun `quip-only outcome is reachable even when a stat qualifies`() {
        val series = listOf(liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 70f))
        val sawQuipOnly = (0 until 200).any { s ->
            val text = HistoryHighlight.pick(series, WeightUnit.KG, now, Random(s.toLong()))
            HistoryHighlight.QUIPS.any { it.text == text }
        }
        assertTrue("expected at least one quip-only pick across seeds", sawQuipOnly)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.HistoryHighlightTest"`
Expected: FAIL — unresolved reference `HistoryHighlight` / `HighlightSeries`.

- [x] **Step 3: Write minimal implementation**

Create `HistoryHighlight.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.history

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint
import kotlin.math.roundToInt
import kotlin.random.Random

enum class HighlightKind { LIFT, MUSCLE }

data class HighlightSeries(
    val subject: String,
    val muscle: MuscleGroup?,
    val points: List<ProgressionPoint>,
    val kind: HighlightKind,
)

data class HighlightConfig(
    val monthWindowMs: Long = 30L * 24 * 3600 * 1000,
    val quarterWindowMs: Long = 90L * 24 * 3600 * 1000,
    val liftMinGainKg: Float = 2f,
    val muscleMinGainFraction: Float = 0.03f,
    val quipOnlyProbability: Float = 0.25f,
    val appendQuipProbability: Float = 0.4f,
)

/** A gym non-sequitur in the Yoked-Galileo voice. Muscle-keyed quips only attach to that muscle. */
data class Quip(val text: String, val muscle: MuscleGroup? = null)

private data class Window(val ms: Long, val label: String)
private data class Candidate(val text: String, val muscle: MuscleGroup?)

object HistoryHighlight {

    val QUIPS: List<Quip> = listOf(
        Quip("The bar does not care about your feelings. Add weight to it anyway."),
        Quip("Somewhere, Yoked Galileo is proud of you."),
        Quip("Diesel Tycho Brahe measured the heavens. You measure the gains."),
        Quip("The iron never lies, and today it says nice things."),
        Quip("Gravity filed a complaint. Ignore it."),
        Quip("You cannot flex a spreadsheet. Go lift something."),
        Quip("Way to nail the vanity lifts!", muscle = MuscleGroup.BICEPS),
        Quip("Beach muscles, activated.", muscle = MuscleGroup.BICEPS),
        Quip("Nobody skips this day. Respect.", muscle = MuscleGroup.QUADS),
    )

    private val genericQuips = QUIPS.filter { it.muscle == null }

    fun pick(
        series: List<HighlightSeries>,
        weightUnit: WeightUnit,
        nowMs: Long,
        random: Random,
        config: HighlightConfig = HighlightConfig(),
    ): String {
        val windows = listOf(
            Window(config.monthWindowMs, "this month"),
            Window(config.quarterWindowMs, "this quarter"),
        )
        val candidates = series.flatMap { s ->
            windows.mapNotNull { w -> candidate(s, w, weightUnit, nowMs, config) }
        }

        // Playful: sometimes (or always, when nothing qualifies) just show a standalone quip.
        if (candidates.isEmpty() || random.nextFloat() < config.quipOnlyProbability) {
            return genericQuips.random(random).text
        }

        val chosen = candidates.random(random)
        if (random.nextFloat() < config.appendQuipProbability) {
            val eligible = QUIPS.filter { it.muscle == null || it.muscle == chosen.muscle }
            return "${chosen.text} ${eligible.random(random).text}"
        }
        return chosen.text
    }

    private fun candidate(
        s: HighlightSeries,
        w: Window,
        unit: WeightUnit,
        nowMs: Long,
        config: HighlightConfig,
    ): Candidate? {
        val latest = s.points.lastOrNull { it.timestampMs <= nowMs } ?: return null
        val baseline = s.points.lastOrNull { it.timestampMs <= nowMs - w.ms } ?: return null
        val gain = latest.value - baseline.value
        return when (s.kind) {
            HighlightKind.LIFT -> {
                if (gain < config.liftMinGainKg) return null
                Candidate("Your ${s.subject} is up ${WeightFormatter.format(gain, unit)} ${w.label}.", s.muscle)
            }
            HighlightKind.MUSCLE -> {
                if (baseline.value <= 0f) return null
                val frac = gain / baseline.value
                if (frac < config.muscleMinGainFraction) return null
                val pct = (frac * 100f).roundToInt()
                Candidate("Your ${s.subject} is up $pct% ${w.label}.", s.muscle)
            }
        }
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.HistoryHighlightTest"`
Expected: PASS (all 8 tests).

- [x] **Step 5: Commit**

```bash
jj commit -m "feat: HistoryHighlight pure motivational-string picker"
```

---

### Task 2: `HistoryRows` pure calendar/list helpers

Pure `java.time` mapping helpers shared by the calendar and the list: workout-day set, month-of-timestamp, and the month-grouped row structure with day→row-index lookup.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/history/HistoryRows.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/history/HistoryRowsTest.kt`

**Interfaces:**
- Consumes: `java.time.LocalDate`, `java.time.YearMonth`, `java.time.ZoneId`, `java.time.Instant`.
- Produces:
  - `sealed interface HistoryRow { data class MonthHeader(val month: YearMonth) : HistoryRow; data class Entry(val itemIndex: Int, val date: LocalDate) : HistoryRow }`
  - `object HistoryRows`:
    - `fun localDate(epochMs: Long, zone: ZoneId): LocalDate`
    - `fun workoutDays(startTimesMs: List<Long>, zone: ZoneId): Set<LocalDate>`
    - `fun buildRows(dates: List<LocalDate>): List<HistoryRow>` — `dates` in display (newest-first) order; inserts a `MonthHeader` whenever the `YearMonth` changes; `Entry.itemIndex` indexes into `dates`.
    - `fun firstRowIndexForDate(rows: List<HistoryRow>, date: LocalDate): Int?`

- [x] **Step 1: Write the failing test**

Create `HistoryRowsTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryRowsTest {

    private val zone = ZoneId.of("America/New_York")

    private fun ms(y: Int, m: Int, d: Int, h: Int = 12): Long =
        ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `workoutDays collapses multiple sessions on one day and uses the zone`() {
        val days = HistoryRows.workoutDays(listOf(ms(2026, 7, 4, 9), ms(2026, 7, 4, 18), ms(2026, 7, 1)), zone)
        assertEquals(setOf(LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 1)), days)
    }

    @Test
    fun `buildRows inserts a header at each month boundary, newest first`() {
        val dates = listOf(
            LocalDate.of(2026, 7, 17),
            LocalDate.of(2026, 7, 2),
            LocalDate.of(2026, 6, 28),
        )
        val rows = HistoryRows.buildRows(dates)
        assertEquals(
            listOf(
                HistoryRow.MonthHeader(YearMonth.of(2026, 7)),
                HistoryRow.Entry(0, dates[0]),
                HistoryRow.Entry(1, dates[1]),
                HistoryRow.MonthHeader(YearMonth.of(2026, 6)),
                HistoryRow.Entry(2, dates[2]),
            ),
            rows,
        )
    }

    @Test
    fun `firstRowIndexForDate finds the entry row position`() {
        val dates = listOf(LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 2))
        val rows = HistoryRows.buildRows(dates)
        assertEquals(2, HistoryRows.firstRowIndexForDate(rows, LocalDate.of(2026, 7, 2)))
        assertNull(HistoryRows.firstRowIndexForDate(rows, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `buildRows on empty input is empty`() {
        assertEquals(emptyList<HistoryRow>(), HistoryRows.buildRows(emptyList()))
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.HistoryRowsTest"`
Expected: FAIL — unresolved reference `HistoryRows`.

- [x] **Step 3: Write minimal implementation**

Create `HistoryRows.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.history

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

sealed interface HistoryRow {
    data class MonthHeader(val month: YearMonth) : HistoryRow
    data class Entry(val itemIndex: Int, val date: LocalDate) : HistoryRow
}

object HistoryRows {

    fun localDate(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    fun workoutDays(startTimesMs: List<Long>, zone: ZoneId): Set<LocalDate> =
        startTimesMs.map { localDate(it, zone) }.toSet()

    fun buildRows(dates: List<LocalDate>): List<HistoryRow> {
        val rows = mutableListOf<HistoryRow>()
        var currentMonth: YearMonth? = null
        dates.forEachIndexed { index, date ->
            val month = YearMonth.from(date)
            if (month != currentMonth) {
                rows += HistoryRow.MonthHeader(month)
                currentMonth = month
            }
            rows += HistoryRow.Entry(index, date)
        }
        return rows
    }

    fun firstRowIndexForDate(rows: List<HistoryRow>, date: LocalDate): Int? {
        val idx = rows.indexOfFirst { it is HistoryRow.Entry && it.date == date }
        return if (idx >= 0) idx else null
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.HistoryRowsTest"`
Expected: PASS (4 tests).

- [x] **Step 5: Commit**

```bash
jj commit -m "feat: HistoryRows pure calendar/list mapping helpers"
```

---

### Task 3: Repository highlight-series assembly + ViewModel wiring

Add a repository method that builds the `HighlightSeries` list from the DB (per-muscle baseline history + per-active-lift merged progression), then rewire `HistoryViewModel`/`HistoryState` to drop the muscle grid and expose the highlight string + workout-day set. `BuildConfig.DEBUG` picks the random seed here.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (add method after `getBaselineEvents`, ~line 385)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryViewModel.kt`

**Interfaces:**
- Consumes: `HistoryHighlight` / `HighlightSeries` / `HighlightKind` (Task 1); `HistoryRows.workoutDays` (Task 2); existing `repository.getBaselineEvents(muscle)`, `repository.getExerciseProgressionData(id)`, `repository.getAllSessions()`, `repository.observeAllExercises()`, `db.workoutSetDao().getSetsForSession(id)`.
- Produces:
  - `WorkoutRepository.buildHighlightSeries(nowMs: Long): List<HighlightSeries>`
  - `HistoryState(highlight: String, workoutDays: Set<LocalDate>, sessions: List<SessionListItem>, weightUnit: WeightUnit, loading: Boolean, pendingDeleteSessionId: Long?, message: String?)` — `muscleStrengths` and `referenceExerciseIds` removed.

- [x] **Step 1: Add `buildHighlightSeries` to `WorkoutRepository`**

Add these imports if missing (near the other `domain`/`data.model` imports):

```kotlin
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.history.HighlightKind
import io.github.fowles.stochastic_strength.domain.history.HighlightSeries
import io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint
```

Insert after `getBaselineEvents` (line ~385):

```kotlin
/**
 * Input series for the History highlight card. Per-muscle series come from baseline_history;
 * per-lift series are the merged (belief) 1RM progression for each exercise trained in the last
 * ~90 days. Heavy (a muscle replay per active lift) but only runs on the cold History screen.
 */
suspend fun buildHighlightSeries(nowMs: Long): List<HighlightSeries> {
    val quarterMs = 90L * 24 * 3600 * 1000
    val muscleSeries = MuscleGroup.entries.mapNotNull { muscle ->
        val points = getBaselineEvents(muscle)
            .map { ProgressionPoint(it.timestamp, it.newBaseline) }
        if (points.isEmpty()) null
        else HighlightSeries(muscle.displayName(), muscle, points, HighlightKind.MUSCLE)
    }

    val activeSessions = getAllSessions().filter { it.startTime >= nowMs - quarterMs }
    val activeExerciseIds = activeSessions
        .flatMap { db.workoutSetDao().getSetsForSession(it.id).map { s -> s.exerciseId } }
        .toSet()
    val exercisesById = observeAllExercises().first().associateBy { it.id }
    val liftSeries = activeExerciseIds.mapNotNull { id ->
        val exercise = exercisesById[id] ?: return@mapNotNull null
        val merged = getExerciseProgressionData(id).series.merged
        if (merged.isEmpty()) null
        else HighlightSeries(exercise.name, exercise.primaryMuscle, merged, HighlightKind.LIFT)
    }

    return muscleSeries + liftSeries
}
```

- [x] **Step 2: Build to verify the repository method compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Rewire `HistoryViewModel` / `HistoryState`**

Replace the imports block additions and the `HistoryState` + `reloadInternal` in `HistoryViewModel.kt`.

Change imports — remove `MuscleGroup`, `MuscleGroupStrength`, `ExerciseCoefficients`; add:

```kotlin
import io.github.fowles.stochastic_strength.BuildConfig
import io.github.fowles.stochastic_strength.domain.history.HistoryHighlight
import io.github.fowles.stochastic_strength.domain.history.HistoryRows
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
```

(`Dispatchers` and `withContext` are already imported for export/import — keep them.)

Replace `HistoryState`:

```kotlin
data class HistoryState(
    val highlight: String = "",
    val workoutDays: Set<LocalDate> = emptySet(),
    val sessions: List<SessionListItem> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    val loading: Boolean = true,
    val pendingDeleteSessionId: Long? = null,
    val message: String? = null,
)
```

Replace `reloadInternal`:

```kotlin
private suspend fun reloadInternal(message: String? = null) {
    val zone = ZoneId.systemDefault()
    val nowMs = System.currentTimeMillis()
    val profile = app.database.userProfileDao().getProfile()
    val weightUnit = profile?.weightUnit ?: WeightUnit.KG
    val locations = repository.getLocations().associateBy { it.id }
    val rawSessions = repository.getAllSessions()
    val sessions = rawSessions.map { session ->
        SessionListItem(
            session = session,
            locationName = session.locationId?.let { locations[it]?.name },
            exerciseNames = repository.getSessionExerciseNames(session.id),
            durationSeconds = if (session.endTime != null)
                (session.endTime - session.startTime) / 1000L
            else 0L,
        )
    }
    val workoutDays = HistoryRows.workoutDays(
        rawSessions.filter { it.endTime != null }.map { it.startTime }, zone,
    )
    // Daily-stable pick in release; re-roll on every open in debug for easy testing.
    val seed = if (BuildConfig.DEBUG) System.nanoTime() else LocalDate.now(zone).toEpochDay()
    val highlight = withContext(Dispatchers.Default) {
        HistoryHighlight.pick(
            series = repository.buildHighlightSeries(nowMs),
            weightUnit = weightUnit,
            nowMs = nowMs,
            random = Random(seed),
        )
    }
    _state.value = HistoryState(
        highlight = highlight,
        workoutDays = workoutDays,
        sessions = sessions,
        weightUnit = weightUnit,
        loading = false,
        message = message,
    )
}
```

- [x] **Step 4: Build to verify wiring compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL — `HistoryScreen.kt` still references `state.muscleStrengths` / `referenceExerciseIds` (fixed in Task 5). Confirm the ONLY errors are in `HistoryScreen.kt`; `HistoryViewModel.kt` and `WorkoutRepository.kt` must compile clean.

- [x] **Step 5: Commit**

```bash
jj commit -m "feat: highlight-series assembly + History VM wiring (grid removed)"
```

(The tree does not build yet — `HistoryScreen` is rebuilt in Task 5. That is expected; the commit checkpoints the VM/repo layer.)

---

### Task 4: `MonthCalendar` composable

A one-month, weekday-aligned grid. Workout days render with a large filled circle. ‹ › arrows and horizontal swipe page months. Tapping a workout day emits its `LocalDate`.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/MonthCalendar.kt`

**Interfaces:**
- Consumes: `HistoryRows` not needed here; uses `java.time.YearMonth`/`LocalDate`/`DayOfWeek`.
- Produces:
  - `@Composable fun MonthCalendar(shownMonth: YearMonth, workoutDays: Set<LocalDate>, onMonthChange: (YearMonth) -> Unit, onDayTap: (LocalDate) -> Unit, modifier: Modifier = Modifier)`

- [x] **Step 1: Write the composable**

Create `MonthCalendar.kt`:

```kotlin
package io.github.fowles.stochastic_strength.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun MonthCalendar(
    shownMonth: YearMonth,
    workoutDays: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onDayTap: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .pointerInput(shownMonth) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag > 60f) onMonthChange(shownMonth.minusMonths(1))
                        else if (totalDrag < -60f) onMonthChange(shownMonth.plusMonths(1))
                        totalDrag = 0f
                    },
                ) { _, drag -> totalDrag += drag }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { onMonthChange(shownMonth.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                text = "${shownMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${shownMonth.year}",
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { onMonthChange(shownMonth.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            // Week starts Monday to match ISO DayOfWeek ordering used below.
            // DayOfWeek is a Java enum → values(), not the Kotlin-only .entries.
            for (dow in DayOfWeek.values()) {
                Text(
                    text = dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        val firstOfMonth = shownMonth.atDay(1)
        // Monday=1..Sunday=7 → blanks before day 1.
        val leadingBlanks = firstOfMonth.dayOfWeek.value - 1
        val daysInMonth = shownMonth.lengthOfMonth()
        val cells = leadingBlanks + daysInMonth
        val rows = (cells + 6) / 7

        for (week in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (dowIndex in 0 until 7) {
                    val cellIndex = week * 7 + dowIndex
                    val dayNumber = cellIndex - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..daysInMonth) {
                            val date = shownMonth.atDay(dayNumber)
                            val isWorkout = date in workoutDays
                            val cellModifier = if (isWorkout) {
                                Modifier
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { onDayTap(date) }
                            } else Modifier
                            Box(
                                modifier = cellModifier.aspectRatio(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = dayNumber.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isWorkout) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isWorkout) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [x] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL only in `HistoryScreen.kt` (Task 5); `MonthCalendar.kt` itself must contribute no errors.

- [x] **Step 3: Commit**

```bash
jj commit -m "feat: MonthCalendar composable with workout-day circles"
```

---

### Task 5: `HighlightCard` + rebuilt `HistoryScreen`

Add the highlight card, then rebuild `HistoryScreen`'s body as three regions: pinned card, pinned calendar, scrolling month-grouped list. Remove the `StrengthGrid`. Calendar day-tap scrolls the list to that day.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HighlightCard.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryScreen.kt`

**Interfaces:**
- Consumes: `HistoryState.highlight`, `HistoryState.workoutDays`, `HistoryState.sessions` (Task 3); `MonthCalendar` (Task 4); `HistoryRows.buildRows` / `firstRowIndexForDate` / `localDate`, `HistoryRow` (Task 2).
- Produces: `@Composable fun HighlightCard(text: String, modifier: Modifier = Modifier)` and the rebuilt `HistoryScreen` body.

- [x] **Step 1: Write `HighlightCard`**

Create `HighlightCard.kt`:

```kotlin
package io.github.fowles.stochastic_strength.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun HighlightCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
```

- [x] **Step 2: Rebuild the `HistoryScreen` body**

In `HistoryScreen.kt`:

Remove imports `SectionHeader`, `StrengthGrid`. Add:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.rememberCoroutineScope
import io.github.fowles.stochastic_strength.domain.history.HistoryRow
import io.github.fowles.stochastic_strength.domain.history.HistoryRows
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
```

Replace the `if (state.loading) { ... }` block through the end of the `LazyColumn` (lines ~149–195) with:

```kotlin
if (state.loading) {
    LoadingBox(contentPadding = padding)
    return@Scaffold
}

val zone = ZoneId.systemDefault()
val entryDates = state.sessions.map { HistoryRows.localDate(it.session.startTime, zone) }
val rows = HistoryRows.buildRows(entryDates)
val listState = rememberLazyListState()
val scope = rememberCoroutineScope()

var shownMonth by remember(state.sessions) {
    mutableStateOf(entryDates.firstOrNull()?.let { YearMonth.from(it) } ?: YearMonth.now(zone))
}

Column(modifier = Modifier.fillMaxSize().padding(padding)) {
    HighlightCard(text = state.highlight)

    MonthCalendar(
        shownMonth = shownMonth,
        workoutDays = state.workoutDays,
        onMonthChange = { shownMonth = it },
        onDayTap = { date ->
            HistoryRows.firstRowIndexForDate(rows, date)?.let { index ->
                scope.launch { listState.animateScrollToItem(index) }
            }
        },
    )

    if (state.sessions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No sessions yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(
                rows,
                key = { row ->
                    when (row) {
                        is HistoryRow.MonthHeader -> "h-${row.month}"
                        is HistoryRow.Entry -> "s-${state.sessions[row.itemIndex].session.id}"
                    }
                },
            ) { row ->
                when (row) {
                    is HistoryRow.MonthHeader -> MonthDividerRow(row.month)
                    is HistoryRow.Entry -> {
                        val item = state.sessions[row.itemIndex]
                        SessionRow(
                            item = item,
                            onClick = { onSessionTap(item.session.id) },
                            onDelete = { viewModel.requestDelete(item.session.id) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}
```

Add the divider composable next to `SessionRow` (above `formatDuration`):

```kotlin
@Composable
private fun MonthDividerRow(month: YearMonth) {
    Text(
        text = "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
```

Also change the `items(...)` import already present (`androidx.compose.foundation.lazy.items`) stays — it now iterates `rows`. The old `SectionHeader` calls and `StrengthGrid` item are deleted by the replacement above.

- [x] **Step 3: Build the whole app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no remaining references to `muscleStrengths`, `referenceExerciseIds`, `SectionHeader`, or `StrengthGrid` in `HistoryScreen.kt`).

- [x] **Step 4: Run the full unit suite (regression + gate)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — including the backtest gate (`BeliefScoreTest`) unchanged, plus the new `HistoryHighlightTest` and `HistoryRowsTest`.

- [x] **Step 5: Commit**

```bash
jj commit -m "feat: rebuild History screen — highlight card, calendar, month-grouped list"
```

---

## Verification (after all tasks)

- `./gradlew :app:assembleDebug` — green.
- `./gradlew :app:testDebugUnitTest` — green, backtest gate unchanged.
- On-device (use the `verify`/`run` skill): open History →
  - highlight card shows a stat or quip; in a debug build, reopening re-rolls it;
  - calendar shows the latest month, workout days have large filled circles, ‹ › and swipe page months;
  - tapping a workout-day circle scrolls the list to that day;
  - the list shows month-divider rows; delete + export/import still work.

## Self-review notes

- **Spec coverage:** highlight card (Task 1 + 3), per-lift absolute + per-muscle percent (Task 1 `candidate`), ~30/90d windows (HighlightConfig), positive-only + threshold (candidate gating), committed quip (QUIPS + test), daily seed / debug re-roll (VM Step 3), quip-only-with-data + empty-case quip (Task 1 tests), big-circle calendar + paging + tap-to-scroll (Tasks 4–5), month-divider rows (Task 5), StrengthGrid removed (Task 3 state + Task 5 body), state field changes (Task 3). All covered.
- **No placeholders:** every code step is complete.
- **Type consistency:** `HighlightSeries`/`HighlightKind` (Task 1) reused verbatim in Task 3; `HistoryRow`/`HistoryRows` (Task 2) reused in Task 5; `MonthCalendar` signature (Task 4) matches its call site (Task 5).
