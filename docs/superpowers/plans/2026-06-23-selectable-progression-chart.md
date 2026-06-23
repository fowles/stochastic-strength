# Selectable Progression Chart + Time-Traveling Cross-Tuning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the exercise-detail progression chart selectable so picking a session time-travels the entire Cross-tuning section (numeric header + bars) to that moment, and pins a tooltip showing each dot's sets.

**Architecture:** The existing `ExerciseProgressionSeriesBuilder` replays one muscle through `ReplayEngine` once. Extend that single replay to also emit a per-session `ProgressionFrame` (the three line values, the cross-tuning rows evaluated at that session's `asOf`, and per-exercise set observations). The chart gains Vico marker wiring (`markerVisibilityListener` + `persistentMarkers`) to drive a screen-owned selection; the ViewModel pre-bakes each frame into display strings keyed by epoch-day.

**Tech Stack:** Kotlin, Jetpack Compose + Material3, Vico (vendored at `vendor/vico/`), Room, JUnit (JVM unit tests under `app/src/test/`).

## Global Constraints

- No DB/schema changes; no new durable state — frames are recomputed on demand in the same muscle replay the chart already runs.
- No change to progression/prescription behavior.
- This feature touches the **exercise-detail** screen only (`ExerciseCoefficientDetailScreen` / `…ViewModel`). The muscle-detail screen's cross-tuning section is unchanged.
- Domain stays **unit-free**: builder emits `weightKg`; the screen/ViewModel converts to the user's `WeightUnit` via `WeightFormatter`.
- Numeric-header and legend color boxes both come from `progressionColors()` (already `internal`) — single source of truth for own=primary, siblings=grey, merged=error.
- Package root: `io.github.fowles.stochastic_strength`. JVM unit tests: `./gradlew :app:testDebugUnitTest`. Compose/Vico gate: `./gradlew :app:assembleDebug`.

---

### Task 1: Shared `impliedObservedSet` helper + `ObservedSet` (dedupe RIR reserve arithmetic)

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ObservedSet.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt:53-63` (`formatBaselineSetLine`)
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ObservedSetTest.kt`
- Test (existing or create): `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `WorkoutSet` (`data/model/WorkoutSet.kt`), `SetFeedback` (`TOO_HARD, HURT, RIR_0_1, RIR_2_4, RIR_5_PLUS`), `SessionSignalExtractor.RESERVE_RIR_0_1 = 0.5f`, `RESERVE_RIR_2_4 = 3f`, `RESERVE_RIR_5_PLUS = 6f`.
- Produces:
  - `data class ObservedSet(val reps: Int, val isEstimate: Boolean, val weightKg: Float)`
  - `fun impliedObservedSet(set: WorkoutSet): ObservedSet?` — returns a numeric rep observation, or `null` for sets that carry none.

**Design note (resolves a spec ambiguity):** `impliedObservedSet` returns non-null **only** for sets that yield a real numeric rep count: the three RIR feedbacks (`isEstimate = true`, reps = `round(targetReps + reserve)`) and `TOO_HARD` with a non-null `actualReps` (`isEstimate = false`). It returns `null` for: no feedback (warmup/unfinished), `HURT` (an injury flag, no rep observation), and `TOO_HARD` with `null actualReps`. The baseline screen keeps rendering `HURT` as `"hurt@…"` and the `?`-reps case as `"?@…"` itself — those are display concerns, not arithmetic, so they stay in the UI. This keeps the duplicated `RESERVE_RIR_*` math in exactly one place (the helper) while preserving every existing `formatBaselineSetLine` string.

- [ ] **Step 1: Write the failing helper test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ObservedSetTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservedSetTest {
    private fun set(feedback: SetFeedback?, targetReps: Int = 8, actualReps: Int? = null) =
        WorkoutSet(
            sessionId = 1L, exerciseId = 1L, setNumber = 1,
            targetWeight = 50f, targetReps = targetReps, actualReps = actualReps, feedback = feedback,
        )

    @Test fun rirFeedbacksAddReserveAndAreEstimates() {
        assertEquals(ObservedSet(reps = 9, isEstimate = true, weightKg = 50f), impliedObservedSet(set(SetFeedback.RIR_0_1)))   // 8 + 0.5 -> round 9 (HALF_UP? see note)
        assertEquals(ObservedSet(reps = 11, isEstimate = true, weightKg = 50f), impliedObservedSet(set(SetFeedback.RIR_2_4))) // 8 + 3
        assertEquals(ObservedSet(reps = 14, isEstimate = true, weightKg = 50f), impliedObservedSet(set(SetFeedback.RIR_5_PLUS))) // 8 + 6
    }

    @Test fun tooHardUsesActualRepsObservedNotEstimated() {
        assertEquals(ObservedSet(reps = 6, isEstimate = false, weightKg = 50f), impliedObservedSet(set(SetFeedback.TOO_HARD, actualReps = 6)))
    }

    @Test fun nonObservationsReturnNull() {
        assertNull(impliedObservedSet(set(feedback = null)))                 // warmup / unfinished
        assertNull(impliedObservedSet(set(SetFeedback.HURT)))                // injury flag
        assertNull(impliedObservedSet(set(SetFeedback.TOO_HARD, actualReps = null))) // no reps recorded
    }
}
```

Note on rounding: `formatBaselineSetLine` uses Kotlin's `Float.roundToInt()` (round half **up**), so `8 + 0.5 = 8.5 -> 9`. Use `kotlin.math.roundToInt()` in the helper to match exactly.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ObservedSetTest"`
Expected: FAIL — `impliedObservedSet` / `ObservedSet` unresolved.

- [ ] **Step 3: Implement the helper**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ObservedSet.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.SessionSignalExtractor
import kotlin.math.roundToInt

/** One displayable set observation in unit-free form. [reps] is the implied/observed rep count. */
data class ObservedSet(
    val reps: Int,
    /** true => an RIR-derived estimate (render with a leading "~"); false => an observed count. */
    val isEstimate: Boolean,
    val weightKg: Float,
)

/**
 * The numeric rep observation a set implies, or null when it carries none.
 *
 * RIR feedbacks add the same reserve offsets [SessionSignalExtractor] uses for the implied 1RM and
 * are marked as estimates. TOO_HARD with a recorded [WorkoutSet.actualReps] is an observed (non-
 * estimate) count. Warmups/unfinished sets (no feedback), HURT (an injury flag, no rep estimate),
 * and TOO_HARD without actualReps carry no numeric observation and return null.
 */
fun impliedObservedSet(set: WorkoutSet): ObservedSet? {
    val feedback = set.feedback ?: return null
    val reps: Int
    val isEstimate: Boolean
    when (feedback) {
        SetFeedback.RIR_0_1 -> { reps = (set.targetReps + SessionSignalExtractor.RESERVE_RIR_0_1).roundToInt(); isEstimate = true }
        SetFeedback.RIR_2_4 -> { reps = (set.targetReps + SessionSignalExtractor.RESERVE_RIR_2_4).roundToInt(); isEstimate = true }
        SetFeedback.RIR_5_PLUS -> { reps = (set.targetReps + SessionSignalExtractor.RESERVE_RIR_5_PLUS).roundToInt(); isEstimate = true }
        SetFeedback.TOO_HARD -> { reps = set.actualReps ?: return null; isEstimate = false }
        SetFeedback.HURT -> return null
    }
    return ObservedSet(reps = reps, isEstimate = isEstimate, weightKg = set.targetWeight)
}
```

- [ ] **Step 4: Run the helper test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ObservedSetTest"`
Expected: PASS (3/3).

- [ ] **Step 5: Re-express `formatBaselineSetLine` on the helper**

In `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt`, replace the body of `formatBaselineSetLine` (lines 53-63) with:

```kotlin
internal fun formatBaselineSetLine(set: WorkoutSet, weightUnit: WeightUnit): String? {
    val feedback = set.feedback ?: return null
    val weight = formatWeightCompact(set.targetWeight, weightUnit)
    if (feedback == SetFeedback.HURT) return "hurt@$weight"
    val observed = impliedObservedSet(set)
        ?: return if (feedback == SetFeedback.TOO_HARD) "?@$weight" else null
    val prefix = if (observed.isEstimate) "~" else ""
    return "$prefix${observed.reps}@$weight"
}
```

Add the import `import io.github.fowles.stochastic_strength.domain.progression.impliedObservedSet`. Remove the now-unused `import kotlin.math.roundToInt` and `import io.github.fowles.stochastic_strength.domain.SessionSignalExtractor` **only if** no other reference to them remains in the file (grep first).

- [ ] **Step 6: Add a regression test pinning the exact strings**

In `MuscleBaselineDetailViewModelTest.kt` (create the file if absent; otherwise add to it), assert the strings are unchanged:

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatBaselineSetLineTest {
    private fun set(feedback: SetFeedback?, actualReps: Int? = null) = WorkoutSet(
        sessionId = 1L, exerciseId = 1L, setNumber = 1,
        targetWeight = 50f, targetReps = 8, actualReps = actualReps, feedback = feedback,
    )

    @Test fun rirIsTildeEstimate() {
        assertEquals("~11@110lbs", formatBaselineSetLine(set(SetFeedback.RIR_2_4), WeightUnit.LBS))
    }
    @Test fun tooHardShowsActualReps() {
        assertEquals("6@50.0kg", formatBaselineSetLine(set(SetFeedback.TOO_HARD, actualReps = 6), WeightUnit.KG))
    }
    @Test fun tooHardWithoutRepsShowsQuestionMark() {
        assertEquals("?@50.0kg", formatBaselineSetLine(set(SetFeedback.TOO_HARD), WeightUnit.KG))
    }
    @Test fun hurtRendersHurt() {
        assertEquals("hurt@50.0kg", formatBaselineSetLine(set(SetFeedback.HURT), WeightUnit.KG))
    }
    @Test fun noFeedbackIsNull() {
        assertNull(formatBaselineSetLine(set(feedback = null), WeightUnit.KG))
    }
}
```

(`110lbs` = 50 kg × 2.20462 → `"%.0flbs"` = `110lbs`. Verify against `formatWeightCompact`; if the rounding lands on `110`, keep — otherwise adjust the literal to the actual output, do not change production code.)

- [ ] **Step 7: Run both test classes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ObservedSetTest" --tests "io.github.fowles.stochastic_strength.ui.debug.FormatBaselineSetLineTest"`
Expected: PASS (all). If a `…lbs` literal mismatches, fix the **test literal** to the produced string.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ObservedSet.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ObservedSetTest.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModelTest.kt
git commit -m "feat: shared impliedObservedSet helper; re-express formatBaselineSetLine on it

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Per-session frames in the builder + `getExerciseProgressionData` repo seam

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt:367-368`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt:70` (call-site only, keep behavior identical)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilderTest.kt`

**Interfaces:**
- Consumes (Task 1): `ObservedSet`, `impliedObservedSet`. Existing: `sampleSession(...)`, `computeCrossTuning(estimates, seedCoef, namesById, muscleExerciseIds, now, projector, updater)`, `CrossTuningRow`, `ReplayEngine.run`, `ReplaySnapshot`, `SessionSignalExtractor.aggregateSession`.
- Produces:
  - `data class SessionExerciseObservation(val exerciseId: Long, val name: String, val sets: List<ObservedSet>)`
  - `data class ProgressionFrame(val timestampMs: Long, val own: Float?, val siblings: Float?, val merged: Float?, val crossTuning: List<CrossTuningRow>, val observations: List<SessionExerciseObservation>)`
  - `data class ExerciseProgressionData(val series: ExerciseProgressionSeries, val frames: List<ProgressionFrame>)`
  - `internal fun buildFrame(targetId: Long, muscleIds: List<Long>, snapshot: ReplaySnapshot, sets: List<WorkoutSet>, asOf: Long, namesById: Map<Long, String>, projector: MuscleStrengthProjector): ProgressionFrame` — pure, no DB.
  - `ExerciseProgressionSeriesBuilder.build(db, exerciseId): ExerciseProgressionData` (return type changes).
  - `WorkoutRepository.getExerciseProgressionData(exerciseId: Long): ExerciseProgressionData` (replaces `getExerciseProgressionSeries`).

**Design note:** `own/siblings/merged` are nullable `Float?` — `sampleSession` already returns each as a 0-or-1-element list; the frame stores `.firstOrNull()?.value`. `observations` lists the **target first**, then siblings in `muscleIds` order, including only exercises that have at least one non-null `impliedObservedSet` that session. `crossTuning` is evaluated at `asOf` (so an earlier session's frame differs from a later one). Build a pure `buildFrame` so it is JVM-testable without a DB, mirroring `sampleSession`.

- [ ] **Step 1: Write the failing `buildFrame` test**

Add to `ExerciseProgressionSeriesBuilderTest.kt`:

```kotlin
    @Test
    fun frameCarriesLineValuesCrossTuningAndObservationsTargetFirst() {
        val snap = snapshot() // ex1 (CHEST, seed 1.0, E=100), ex2 (CHEST, seed 0.6, E=60)
        val names = mapOf(1L to "Bench", 2L to "Incline")
        val asOf = 1_000L
        // Both exercises trained: target ex1 at 100x5 (RIR_2_4), sibling ex2 at 60x5.
        val sets = listOf(set(exerciseId = 1L, weight = 100f, reps = 5), set(exerciseId = 2L, weight = 60f, reps = 5))

        val frame = buildFrame(
            targetId = 1L, muscleIds = listOf(1L, 2L), snapshot = snap,
            sets = sets, asOf = asOf, namesById = names, projector = MuscleStrengthProjector(),
        )

        // Line values match sampleSession.
        val sample = sampleSession(1L, listOf(1L, 2L), snap, sets, asOf, MuscleStrengthProjector())
        assertEquals(sample.ownEstimate.first().value, frame.own!!, 1e-3f)
        assertEquals(sample.merged.first().value, frame.merged!!, 1e-3f)

        // Cross-tuning evaluated at asOf, one row per weighted exercise.
        assertEquals(2, frame.crossTuning.size)

        // Observations: target first, then sibling; each carries an ObservedSet.
        assertEquals(listOf(1L, 2L), frame.observations.map { it.exerciseId })
        assertEquals("Bench", frame.observations.first().name)
        assertEquals(1, frame.observations.first().sets.size)
    }

    @Test
    fun frameObservationsOmitExercisesThatDidNotTrain() {
        val snap = snapshot()
        val names = mapOf(1L to "Bench", 2L to "Incline")
        // Only sibling ex2 trained this session.
        val sets = listOf(set(exerciseId = 2L, weight = 60f, reps = 5))
        val frame = buildFrame(1L, listOf(1L, 2L), snap, sets, 1_000L, names, MuscleStrengthProjector())
        assertEquals(listOf(2L), frame.observations.map { it.exerciseId })
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeriesBuilderTest"`
Expected: FAIL — `buildFrame` / `ProgressionFrame` unresolved.

- [ ] **Step 3: Add the frame types and `buildFrame`**

In `ExerciseProgressionSeriesBuilder.kt`, add after `ExerciseProgressionSeries` (around line 21):

```kotlin
data class SessionExerciseObservation(
    val exerciseId: Long,
    val name: String,
    val sets: List<ObservedSet>,
)

data class ProgressionFrame(
    val timestampMs: Long,
    val own: Float?,
    val siblings: Float?,
    val merged: Float?,
    val crossTuning: List<CrossTuningRow>,
    val observations: List<SessionExerciseObservation>,
)

data class ExerciseProgressionData(
    val series: ExerciseProgressionSeries,
    val frames: List<ProgressionFrame>,
)
```

Add `buildFrame` next to `sampleSession`:

```kotlin
/**
 * One session's [ProgressionFrame] at [asOf]: the three line values, the cross-tuning rows as they
 * stood then, and per-exercise displayable set observations (target first, then siblings in
 * [muscleIds] order; exercises with no displayable set are omitted). Pure; no DB.
 */
internal fun buildFrame(
    targetId: Long,
    muscleIds: List<Long>,
    snapshot: ReplaySnapshot,
    sets: List<WorkoutSet>,
    asOf: Long,
    namesById: Map<Long, String>,
    projector: MuscleStrengthProjector,
): ProgressionFrame {
    val sample = sampleSession(targetId, muscleIds, snapshot, sets, asOf, projector)
    val crossTuning = computeCrossTuning(
        estimates = snapshot.currentEstimates,
        seedCoef = snapshot.seedCoefficients,
        namesById = namesById,
        muscleExerciseIds = muscleIds,
        now = asOf,
        projector = projector,
    )
    val setsByExercise = sets.groupBy { it.exerciseId }
    val orderedIds = listOf(targetId) + muscleIds.filter { it != targetId }
    val observations = orderedIds.mapNotNull { id ->
        val name = namesById[id] ?: return@mapNotNull null
        val observed = setsByExercise[id].orEmpty()
            .sortedBy { it.setNumber }
            .mapNotNull { impliedObservedSet(it) }
        if (observed.isEmpty()) null
        else SessionExerciseObservation(exerciseId = id, name = name, sets = observed)
    }
    return ProgressionFrame(
        timestampMs = asOf,
        own = sample.ownEstimate.firstOrNull()?.value,
        siblings = sample.siblingsEstimate.firstOrNull()?.value,
        merged = sample.merged.firstOrNull()?.value,
        crossTuning = crossTuning,
        observations = observations,
    )
}
```

- [ ] **Step 4: Make `build` return `ExerciseProgressionData`**

Replace the `build` function body. Load `namesById` once, accumulate frames in the same observer, and return both:

```kotlin
    suspend fun build(db: AppDatabase, exerciseId: Long): ExerciseProgressionData {
        val snapshot = ReplaySnapshot.loadStaticFromDb(db)
        val muscle = snapshot.exerciseMuscle[exerciseId]
            ?: return ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        val muscleIds = snapshot.muscleExerciseIds[muscle]
            ?: return ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        if (exerciseId !in muscleIds) {
            return ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        }
        val namesById = db.exerciseDao().getAll().associate { it.id to it.name }

        val ownEstimate = mutableListOf<ProgressionPoint>()
        val siblingsEstimate = mutableListOf<ProgressionPoint>()
        val merged = mutableListOf<ProgressionPoint>()
        val ownObservations = mutableListOf<ProgressionPoint>()
        val siblingObservations = mutableListOf<ProgressionPoint>()
        val frames = mutableListOf<ProgressionFrame>()

        engine.run(db, snapshot) { _, asOf, sets, snap, result ->
            if (result.steps.any { it.muscle == muscle }) {
                val sample = sampleSession(exerciseId, muscleIds, snap, sets, asOf, projector)
                ownEstimate += sample.ownEstimate
                siblingsEstimate += sample.siblingsEstimate
                merged += sample.merged
                ownObservations += sample.ownObservations
                siblingObservations += sample.siblingObservations
                frames += buildFrame(exerciseId, muscleIds, snap, sets, asOf, namesById, projector)
            }
        }

        return ExerciseProgressionData(
            series = ExerciseProgressionSeries(
                ownEstimate = ownEstimate,
                siblingsEstimate = siblingsEstimate,
                merged = merged,
                ownObservations = ownObservations,
                siblingObservations = siblingObservations,
            ),
            frames = frames,
        )
    }
```

Add the import `import io.github.fowles.stochastic_strength.data.model.WorkoutSet` if not already present (it is, line 4). No new DAO call beyond `exerciseDao().getAll()` (already used elsewhere).

- [ ] **Step 5: Update the repo seam**

In `WorkoutRepository.kt`, replace lines 367-368:

```kotlin
    suspend fun getExerciseProgressionData(exerciseId: Long): ExerciseProgressionData =
        progressionSeriesBuilder.build(db, exerciseId)
```

Update the import of `ExerciseProgressionSeries` to add `ExerciseProgressionData` (same package `domain.progression`). Grep the file for any other use of `getExerciseProgressionSeries` — there is none besides the ViewModel.

- [ ] **Step 6: Keep the ViewModel compiling (behavior identical)**

In `ExerciseCoefficientDetailViewModel.kt`, change line 70 from:

```kotlin
            val series = repository.getExerciseProgressionSeries(exerciseId)
```

to:

```kotlin
            val series = repository.getExerciseProgressionData(exerciseId).series
```

No other ViewModel change in this task — frames are consumed in Task 4.

- [ ] **Step 7: Run the builder tests + full unit suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeriesBuilderTest"`
Expected: PASS (existing 4 + new 2 = 6).

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (no compile breakage from the seam change).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilderTest.kt
git commit -m "feat: per-session ProgressionFrame + getExerciseProgressionData seam

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Chart selection + persistent tooltip marker (Vico)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/ExerciseProgressionChart.kt`

**Interfaces:**
- Consumes: existing `ExerciseProgressionChart(series, yFormatter, modifier)`, `progressionColors()`, `timestampToLocalEpochDay`, `epochDayLabel`. Vico (confirmed in `vendor/vico/`): `rememberCartesianChart(..., marker, markerVisibilityListener, persistentMarkers)`; `CartesianMarkerVisibilityListener` (`onShown`/`onUpdated`/`onHidden`); `CartesianChart.PersistentMarkerScope.at(x: Number)`; `rememberDefaultCartesianMarker(label, valueFormatter, …)`; `DefaultCartesianMarker.ValueFormatter.format(context, targets): CharSequence`; `CartesianMarker.Target.x: Double`.
- Produces: new signature
  ```kotlin
  internal fun ExerciseProgressionChart(
      series: List<ProgressionChartSeries>,
      yFormatter: (Float) -> String,
      selectedSessionEpochDay: Long? = null,
      onSelectEpochDay: (Long) -> Unit = {},
      tooltipLabel: (epochDay: Long) -> CharSequence = { "" },
      modifier: Modifier = Modifier,
  )
  ```
  The new params have **defaults** so the existing call site (`ExerciseCoefficientDetailScreen`) keeps compiling unchanged until Task 4 wires real values.

**Design note:** Selection is screen-owned; the chart is stateless. `onShown`/`onUpdated` read `targets.first().x` (already a series x — markers snap to points) and forward `x.toLong()` via `onSelectEpochDay`, but only when it differs from the last forwarded value (guard against recomposition loops). `onHidden` is ignored so the pin persists after release. `persistentMarkers = { selectedSessionEpochDay?.let { marker at it } }` keeps the marker (and its tooltip) at the selection. The marker's `ValueFormatter` returns `tooltipLabel(target.x.toLong())` — a multi-line `CharSequence` renders as stacked lines.

This task is gated on `assembleDebug` (no unit test — Vico marker behavior is verified on-device).

- [ ] **Step 1: Add imports**

In `ExerciseProgressionChart.kt`, add:

```kotlin
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.compose.common.shape.markerCorneredShape
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
```

(`fill`, `CorneredShape`, `MaterialTheme`, `dp` are already imported.)

- [ ] **Step 2: Widen the signature**

Change the `ExerciseProgressionChart` signature (lines 49-54) to add the three new params **before** `modifier`, each with the default shown in the Interfaces block above.

- [ ] **Step 3: Build the tooltip marker**

Inside `ExerciseProgressionChart`, after `val colors = progressionColors()` (line 72), add a marker whose label is driven by `tooltipLabel`:

```kotlin
    val tooltipLabelState = rememberUpdatedState(tooltipLabel)
    val markerValueFormatter = remember {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val epochDay = targets.firstOrNull()?.x?.toLong()
            epochDay?.let { tooltipLabelState.value(it) } ?: ""
        }
    }
    val markerLabel = rememberTextComponent(
        color = MaterialTheme.colorScheme.onSurface,
        background = rememberShapeComponent(
            fill = fill(MaterialTheme.colorScheme.surface),
            shape = markerCorneredShape(CorneredShape.Corner.Rounded),
            strokeThickness = 1.dp,
            strokeFill = fill(MaterialTheme.colorScheme.outline),
        ),
        padding = insets(8.dp, 4.dp),
    )
    val marker = rememberDefaultCartesianMarker(label = markerLabel, valueFormatter = markerValueFormatter)
```

- [ ] **Step 4: Build the visibility listener (selection driver)**

Add, after the marker:

```kotlin
    val onSelectState = rememberUpdatedState(onSelectEpochDay)
    val lastForwarded = remember { ValueHolder() }
    val visibilityListener = remember {
        object : CartesianMarkerVisibilityListener {
            private fun forward(targets: List<CartesianMarker.Target>) {
                val epochDay = targets.firstOrNull()?.x?.toLong() ?: return
                if (lastForwarded.value != epochDay) {
                    lastForwarded.value = epochDay
                    onSelectState.value(epochDay)
                }
            }
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) = forward(targets)
            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) = forward(targets)
            // onHidden intentionally not overridden: selection persists after the finger lifts.
        }
    }
```

Add a tiny mutable holder at file scope (top-level, below the enums) to avoid recomposing the listener:

```kotlin
private class ValueHolder(var value: Long? = null)
```

- [ ] **Step 5: Wire marker + listener + persistentMarkers into the chart**

Change the `rememberCartesianChart(...)` call (lines 115-119) to pass the marker, listener, and persistent pin:

```kotlin
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(lineProvider = lineProvider, pointSpacing = 0.dp, rangeProvider = rangeProvider),
            startAxis = VerticalAxis.rememberStart(valueFormatter = yValueFormatter),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = dateFormatter, labelRotationDegrees = 45f),
            marker = marker,
            markerVisibilityListener = visibilityListener,
            persistentMarkers = { selectedSessionEpochDay?.let { marker at it.toDouble() } },
        ),
```

(`PersistentMarkerScope.at` takes a `Number`; epoch-day x values are emitted as `Long`→`Double` in `lineSeries`, so pin at `it.toDouble()`.)

- [ ] **Step 6: Build to gate the Vico API**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. If `markerCorneredShape` / `insets` import paths differ, resolve against `vendor/vico/sample/compose/src/main/kotlin/com/patrykandpatrick/vico/sample/compose/Marker.kt` (the reference marker) — do not change the design.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/ExerciseProgressionChart.kt
git commit -m "feat: selectable progression chart with persistent tooltip marker

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: ViewModel frame views + screen wiring (numeric header + time-traveling Cross-tuning)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailScreen.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModelTest.kt` (create)

**Interfaces:**
- Consumes (Task 2): `ExerciseProgressionData`, `ProgressionFrame`, `SessionExerciseObservation`, `ObservedSet`, `repository.getExerciseProgressionData`. (Task 3): `ExerciseProgressionChart(series, yFormatter, selectedSessionEpochDay, onSelectEpochDay, tooltipLabel, modifier)`. Existing: `progressionColors()`, `ProgressionColorRole`, `CrossTuningSection`, `WeightFormatter.format`, `timestampToLocalEpochDay`.
- Produces:
  - `data class FrameView(val timestampMs: Long, val headerOwn: String, val headerSiblings: String, val headerMerged: String, val crossTuning: List<CrossTuningRow>, val tooltip: CharSequence)`
  - state additions: `framesByEpochDay: Map<Long, FrameView>`, `defaultEpochDay: Long?`
  - pure helpers in the ViewModel file (top-level `internal`):
    - `internal fun formatObservedSet(s: ObservedSet, unit: WeightUnit): String`
    - `internal fun formatTooltip(observations: List<SessionExerciseObservation>, unit: WeightUnit): CharSequence`
    - `internal fun buildFrameViews(frames: List<ProgressionFrame>, unit: WeightUnit, zone: ZoneId): Pair<Map<Long, FrameView>, Long?>`

- [ ] **Step 1: Write failing ViewModel-helper tests**

Create `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModelTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.progression.ObservedSet
import io.github.fowles.stochastic_strength.domain.progression.ProgressionFrame
import io.github.fowles.stochastic_strength.domain.progression.SessionExerciseObservation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ExerciseCoefficientDetailViewModelTest {

    @Test fun tooltipStacksNameThenSetsTargetFirst() {
        val obs = listOf(
            SessionExerciseObservation(1L, "Deadlift", listOf(
                ObservedSet(reps = 11, isEstimate = true, weightKg = 56.7f),
                ObservedSet(reps = 11, isEstimate = true, weightKg = 56.7f),
                ObservedSet(reps = 9, isEstimate = false, weightKg = 56.7f),
            )),
        )
        val tip = formatTooltip(obs, WeightUnit.LBS).toString()
        // Name header then one line per set; "~" only on estimates.
        assertEquals("Deadlift\n~11@125lbs\n~11@125lbs\n9@125lbs", tip)
    }

    @Test fun buildFrameViewsKeysByEpochDayAndDefaultsToLatest() {
        val zone = ZoneId.of("UTC")
        val dayMs = 86_400_000L
        val frames = listOf(
            ProgressionFrame(timestampMs = dayMs * 10, own = 100f, siblings = 90f, merged = 95f, crossTuning = emptyList(), observations = emptyList()),
            ProgressionFrame(timestampMs = dayMs * 20, own = 110f, siblings = 92f, merged = 99f, crossTuning = emptyList(), observations = emptyList()),
        )
        val (map, default) = buildFrameViews(frames, WeightUnit.KG, zone)
        assertEquals(2, map.size)
        assertEquals(20L, default)            // latest frame's epoch-day
        assertEquals("110.0kg", map.getValue(20L).headerOwn)
    }
}
```

Note: `125lbs` assumes `56.7 kg × 2.20462 ≈ 125`; `WeightFormatter.format(56.7f, LBS)` output string drives the exact literal — adjust the literal to the real output, not the code. Confirm whether `WeightFormatter.format` appends a space (e.g. `"125 lbs"`); match it exactly in `formatObservedSet`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.ExerciseCoefficientDetailViewModelTest"`
Expected: FAIL — helpers unresolved.

- [ ] **Step 3: Add the pure helpers + `FrameView` + state fields**

In `ExerciseCoefficientDetailViewModel.kt`:

Add imports:
```kotlin
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.progression.ObservedSet
import io.github.fowles.stochastic_strength.domain.progression.ProgressionFrame
import io.github.fowles.stochastic_strength.domain.progression.SessionExerciseObservation
import io.github.fowles.stochastic_strength.ui.debug.components.timestampToLocalEpochDay
import java.time.ZoneId
```

Add `FrameView` and helpers (top level, after the `data class` block):

```kotlin
data class FrameView(
    val timestampMs: Long,
    val headerOwn: String,
    val headerSiblings: String,
    val headerMerged: String,
    val crossTuning: List<CrossTuningRow>,
    val tooltip: CharSequence,
)

internal fun formatObservedSet(s: ObservedSet, unit: WeightUnit): String {
    val prefix = if (s.isEstimate) "~" else ""
    return "$prefix${s.reps}@${WeightFormatter.format(s.weightKg, unit)}"
}

internal fun formatTooltip(observations: List<SessionExerciseObservation>, unit: WeightUnit): CharSequence =
    observations.joinToString("\n") { obs ->
        (listOf(obs.name) + obs.sets.map { formatObservedSet(it, unit) }).joinToString("\n")
    }

private fun headerValue(v: Float?, unit: WeightUnit): String =
    v?.let { WeightFormatter.format(it, unit) } ?: "—"

internal fun buildFrameViews(
    frames: List<ProgressionFrame>,
    unit: WeightUnit,
    zone: ZoneId,
): Pair<Map<Long, FrameView>, Long?> {
    if (frames.isEmpty()) return emptyMap<Long, FrameView>() to null
    val byEpochDay = LinkedHashMap<Long, FrameView>()
    for (f in frames) {
        val epochDay = timestampToLocalEpochDay(f.timestampMs, zone)
        byEpochDay[epochDay] = FrameView(   // later same-day frame overwrites; nearest/last wins
            timestampMs = f.timestampMs,
            headerOwn = headerValue(f.own, unit),
            headerSiblings = headerValue(f.siblings, unit),
            headerMerged = headerValue(f.merged, unit),
            crossTuning = f.crossTuning,
            tooltip = formatTooltip(f.observations, unit),
        )
    }
    val defaultEpochDay = timestampToLocalEpochDay(frames.maxBy { it.timestampMs }.timestampMs, zone)
    return byEpochDay to defaultEpochDay
}
```

Add to `ExerciseCoefficientDetailState`:
```kotlin
    val framesByEpochDay: Map<Long, FrameView> = emptyMap(),
    val defaultEpochDay: Long? = null,
```

- [ ] **Step 4: Populate the new state in `init`**

In the `init` block, replace the `val series = repository.getExerciseProgressionData(exerciseId).series` line with capturing the full data and building frames:

```kotlin
            val data = repository.getExerciseProgressionData(exerciseId)
            val series = data.series
            val (framesByEpochDay, defaultEpochDay) =
                buildFrameViews(data.frames, weightUnit, ZoneId.systemDefault())
```

Keep the existing `progressionSeries` mapping (it reads `series.*`). Remove the now-unused `crossTuning = repository.getCrossTuning(exercise.primaryMuscle)` line **and** drop `crossTuning` from the emitted state — the screen now reads cross-tuning from the selected frame. Add `framesByEpochDay` and `defaultEpochDay` to the `_state.value = ExerciseCoefficientDetailState(...)` constructor call, and remove the `crossTuning = crossTuning` argument and the `crossTuning` field from `ExerciseCoefficientDetailState`.

(Remove the now-unused import `import io.github.fowles.stochastic_strength.domain.progression.CrossTuningRow`? No — `FrameView` and `state` still reference `CrossTuningRow`; keep it.)

- [ ] **Step 5: Run the helper tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.ExerciseCoefficientDetailViewModelTest"`
Expected: PASS (2/2). Fix any `lbs`/`kg` literal to match `WeightFormatter.format` output.

- [ ] **Step 6: Wire the screen — selection state, chart, numeric header, time-traveling cross-tuning**

In `ExerciseCoefficientDetailScreen.kt`:

Add imports:
```kotlin
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.fowles.stochastic_strength.ui.debug.components.progressionColors
import io.github.fowles.stochastic_strength.ui.debug.components.ProgressionColorRole
```

Replace the chart `item { … }` (lines 60-76) and the Cross-tuning `item { … }` (lines 80-88) so selection is hoisted across both. Inside `LazyColumn`, before the chart item, hold the selection:

```kotlin
            // Selection persists across recomposition; resets to latest when data (re)loads.
            // Hoisted here so the chart and the cross-tuning section share it.
```

Use a single selection state declared in the composable body (above `LazyColumn`) — `LazyColumn` item lambdas can read it:

```kotlin
        var selectedEpochDay by rememberSaveable(state.defaultEpochDay) {
            mutableStateOf(state.defaultEpochDay)
        }
        val selectedFrame = state.framesByEpochDay[selectedEpochDay]
            ?: state.defaultEpochDay?.let { state.framesByEpochDay[it] }
```

Chart item (selectable):

```kotlin
            item {
                val hasData = state.progressionSeries.any { it.points.isNotEmpty() }
                if (!hasData) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("No sessions yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column {
                        ProgressionLegend(state.progressionSeries)
                        ExerciseProgressionChart(
                            series = state.progressionSeries,
                            yFormatter = { value -> WeightFormatter.format(value, state.weightUnit) },
                            selectedSessionEpochDay = selectedEpochDay,
                            onSelectEpochDay = { selectedEpochDay = it },
                            tooltipLabel = { epochDay -> state.framesByEpochDay[epochDay]?.tooltip ?: "" },
                            modifier = Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 16.dp),
                        )
                    }
                }
            }
```

Cross-tuning item (numeric header + time-traveling bars):

```kotlin
            item { SectionHeader("Cross-tuning", verticalPadding = 4.dp) }

            item {
                if (selectedFrame == null) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("No weighted exercises", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column {
                        ProgressionNumericHeader(
                            own = selectedFrame.headerOwn,
                            siblings = selectedFrame.headerSiblings,
                            merged = selectedFrame.headerMerged,
                        )
                        CrossTuningSection(rows = selectedFrame.crossTuning, highlightedName = state.exercise?.name)
                    }
                }
            }
```

Add the numeric-header composable (mirrors `ProgressionLegend`, values instead of labels):

```kotlin
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressionNumericHeader(own: String, siblings: String, merged: String) {
    val colors = progressionColors()
    val entries = listOf(
        ProgressionColorRole.OWN to own,
        ProgressionColorRole.SIBLINGS to siblings,
        ProgressionColorRole.MERGED to merged,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { (role, value) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(10.dp).background(colors.getValue(role), RoundedCornerShape(2.dp)))
                Text(value, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
```

- [ ] **Step 7: Build the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Resolve any remaining reference to the deleted `state.crossTuning` (there should be none after Step 4/6).

- [ ] **Step 8: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (all tests pass).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailScreen.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModelTest.kt
git commit -m "feat: time-traveling cross-tuning + numeric header driven by chart selection

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Deferred (manual, on-device)

- **On-device verification of selection + tooltip (Vico risk).** Open the exercise-detail screen on the emulator, tap a dot, confirm: (1) the tooltip pins and shows the stacked `Name / ~reps@weight` blocks; (2) the numeric header and cross-tuning bars update to the selected session and **persist** after the finger lifts; (3) default = latest session on open. If marker selection misbehaves, the spec's fallback is a custom touch handler with a transient marker — escalate before implementing the fallback.

## Self-Review

**Spec coverage:**
- Numeric header (own/siblings/merged values, shared color boxes) → Task 4 `ProgressionNumericHeader`. ✓
- Chart selectable, whole cross-tuning section time-travels (header + bars) → Task 3 (marker/selection) + Task 4 (selectedFrame drives header + `CrossTuningSection`). ✓
- Tooltip stacking one block per dot, target first → Task 2 `buildFrame` observations order + Task 4 `formatTooltip`. ✓
- Per-session frames recomputed in the one muscle replay, no durable state → Task 2. ✓
- Shared `impliedObservedSet`, `formatBaselineSetLine` re-expressed + regression test → Task 1. ✓
- `getExerciseProgressionData` replaces `getExerciseProgressionSeries` → Task 2. ✓
- Default = latest, persists on release → Task 3 (`onHidden` ignored) + Task 4 (`rememberSaveable(defaultEpochDay)`). ✓
- Domain unit-free; units converted at the screen/VM → Task 2 emits `weightKg`; Task 4 formats. ✓
- Muscle-detail screen unchanged → only `MuscleBaselineDetailViewModel.formatBaselineSetLine` internals refactored (no behavior change, regression-tested). ✓

**Ambiguity resolved:** spec's parenthetical said `impliedObservedSet` returns non-null for HURT; this plan returns `null` for HURT (no numeric rep observation) and keeps `"hurt@…"` rendering in the baseline UI — preserves every existing string and keeps the tooltip numeric-only, consistent with the example. Flagged for the executor.

**Type consistency:** `ExerciseProgressionData`, `ProgressionFrame(own/siblings/merged: Float?)`, `SessionExerciseObservation`, `ObservedSet(reps, isEstimate, weightKg)`, `FrameView`, `getExerciseProgressionData`, `buildFrame`, `buildFrameViews`, `formatTooltip` are used consistently across Tasks 1→4. Chart's new params carry defaults so Task 3 lands before Task 4 without breaking compilation.
