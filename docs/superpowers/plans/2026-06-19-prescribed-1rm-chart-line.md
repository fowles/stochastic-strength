# Prescribed-1RM Chart Line Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Overlay a smooth cubic line on the exercise detail chart showing the planner's prescribed estimated-1RM (`baseline × coefficient`) over time, alongside the existing achieved-1RM dots.

**Architecture:** A pure helper reconstructs the `baseline(t) × coefficient(t)` step function from `BaselineHistory` and `CoefficientHistory`, sampled at the days that already have achieved dots (so the marker's x-grid is unchanged). The `ExerciseDetailViewModel` calls it and exposes a new `prescribedPoints` list; `ExerciseChart` renders it as a tertiary-colored cubic stroke with no point markers.

**Tech Stack:** Kotlin, Jetpack Compose, Vico 2.1.3 (`compose-m3`), JUnit4 (JVM unit tests).

## Global Constraints

- Package: `io.github.fowles.stochastic_strength`.
- The prescribed line MUST use the **true, unrounded** product `BaselineHistory.newBaseline × CoefficientHistory.coefficient` (or `seedCoefficient`). Never reconstruct it from the rounded `sessionWeight`.
- `baseline × coefficient` is already in estimated-1RM units (matches the existing y-axis) — no unit conversion.
- Bodyweight / unloadable exercises (`seedCoefficient ≤ 0`) get no line.
- Both `repository.getBaselineEvents(...)` and `repository.getCoefficientEvents(...)` return lists **sorted ascending** by `timestamp` / `computedAt`.
- Unit tests run on the JVM: `./gradlew :app:testDebugUnitTest`. Vico/Compose code is verified by `./gradlew :app:assembleDebug` + visual check.
- Use the Vico cheat-sheet at `docs/vico-reference.md` for any chart API; grep `vendor/vico/` only if the cheat-sheet is insufficient.

---

### Task 1: `buildPrescribedPoints` reconstruction helper

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailViewModel.kt` (add an internal top-level function alongside the existing `ChartPoint` data class)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailViewModelTest.kt` (new file)

**Interfaces:**
- Consumes: `ChartPoint(dateMs: Long, weightKg: Float)` (already defined in `ExerciseDetailViewModel.kt`); `BaselineHistory` (fields `previousBaseline: Float`, `newBaseline: Float`, `timestamp: Long`); `CoefficientHistory` (fields `coefficient: Float`, `computedAt: Long`).
- Produces:
  ```kotlin
  internal fun buildPrescribedPoints(
      baselineEvents: List<BaselineHistory>,   // ascending by timestamp
      coefficientEvents: List<CoefficientHistory>, // ascending by computedAt
      seedCoefficient: Float,
      dayKeys: Collection<Long>,               // local epoch-day keys with achieved dots
      zone: ZoneId,
  ): List<ChartPoint>
  ```

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailViewModelTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.ui.exercises

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ExerciseDetailViewModelTest {

    private val zone = ZoneOffset.UTC
    private val dayMs = 86_400_000L

    private fun baseline(day: Long, prev: Float, new: Float) = BaselineHistory(
        id = 0,
        sessionId = null,
        muscleGroup = MuscleGroup.CHEST,
        previousBaseline = prev,
        newBaseline = new,
        changeReason = BaselineChangeReason.SESSION,
        timestamp = day * dayMs,
    )

    private fun coeff(day: Long, value: Float) = CoefficientHistory(
        id = 0,
        exerciseId = 1L,
        coefficient = value,
        heuristicName = "test",
        computedAt = day * dayMs,
    )

    @Test
    fun `samples latest baseline and coefficient at or before each day`() {
        val baselines = listOf(baseline(10, 100f, 110f), baseline(20, 110f, 120f))
        val coeffs = listOf(coeff(10, 0.5f), coeff(20, 0.6f))
        val points = buildPrescribedPoints(baselines, coeffs, seedCoefficient = 0.5f,
            dayKeys = listOf(15L, 25L), zone = zone)
        assertEquals(2, points.size)
        // day 15: baseline 110 (event@10), coeff 0.5 (event@10) -> 55
        assertEquals(15 * dayMs, points[0].dateMs)
        assertEquals(55f, points[0].weightKg, 0.0001f)
        // day 25: baseline 120 (event@20), coeff 0.6 (event@20) -> 72
        assertEquals(72f, points[1].weightKg, 0.0001f)
    }

    @Test
    fun `falls back to seed coefficient before first coefficient event`() {
        val baselines = listOf(baseline(10, 100f, 110f))
        val points = buildPrescribedPoints(baselines, coefficientEvents = emptyList(),
            seedCoefficient = 0.4f, dayKeys = listOf(15L), zone = zone)
        assertEquals(1, points.size)
        assertEquals(110f * 0.4f, points[0].weightKg, 0.0001f)
    }

    @Test
    fun `uses previousBaseline for days before the first baseline event`() {
        val baselines = listOf(baseline(10, 90f, 110f))
        val points = buildPrescribedPoints(baselines, listOf(coeff(0, 0.5f)),
            seedCoefficient = 0.5f, dayKeys = listOf(5L), zone = zone)
        assertEquals(1, points.size)
        assertEquals(90f * 0.5f, points[0].weightKg, 0.0001f)
    }

    @Test
    fun `drops leading days when first event previousBaseline is zero`() {
        val baselines = listOf(baseline(10, 0f, 110f)) // INITIAL assessment
        val points = buildPrescribedPoints(baselines, listOf(coeff(0, 0.5f)),
            seedCoefficient = 0.5f, dayKeys = listOf(5L, 15L), zone = zone)
        // day 5 dropped (no baseline yet), day 15 kept
        assertEquals(1, points.size)
        assertEquals(15 * dayMs, points[0].dateMs)
        assertEquals(110f * 0.5f, points[0].weightKg, 0.0001f)
    }

    @Test
    fun `returns empty for bodyweight exercises`() {
        val baselines = listOf(baseline(10, 100f, 110f))
        val points = buildPrescribedPoints(baselines, listOf(coeff(10, 0.5f)),
            seedCoefficient = 0f, dayKeys = listOf(15L), zone = zone)
        assertTrue(points.isEmpty())
    }

    @Test
    fun `uses true unrounded product not a plate-rounded value`() {
        val baselines = listOf(baseline(10, 0f, 101.3f))
        val points = buildPrescribedPoints(baselines, listOf(coeff(10, 0.617f)),
            seedCoefficient = 0.617f, dayKeys = listOf(15L), zone = zone)
        assertEquals(62.5021f, points[0].weightKg, 0.001f)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.exercises.ExerciseDetailViewModelTest"`
Expected: FAIL — `buildPrescribedPoints` unresolved reference.

- [ ] **Step 3: Implement the helper**

In `ExerciseDetailViewModel.kt`, add these imports if missing:

```kotlin
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import java.time.Instant
```

(`ZoneId` is already imported.) Add the function right after the `ChartPoint` data class:

```kotlin
/**
 * Reconstructs the planner's prescribed estimated-1RM (`baseline × coefficient`)
 * over time, sampled at [dayKeys] (the days that already have an achieved dot, so
 * the chart's x-grid and marker stay unchanged).
 *
 * `baseline(day)` is the `newBaseline` of the latest [BaselineHistory] whose local
 * day is ≤ `day`; before the first event it falls back to that event's
 * `previousBaseline` only when positive (an INITIAL assessment has
 * `previousBaseline == 0`, which would drag the line to zero, so those days are
 * dropped). `coefficient(day)` is the latest [CoefficientHistory] ≤ `day`, else
 * [seedCoefficient]. The product is the true, pre-rounding 1RM target — it is read
 * straight from history, never from the rounded session weight.
 *
 * Returns empty for unloadable exercises (`seedCoefficient ≤ 0`).
 */
internal fun buildPrescribedPoints(
    baselineEvents: List<BaselineHistory>,
    coefficientEvents: List<CoefficientHistory>,
    seedCoefficient: Float,
    dayKeys: Collection<Long>,
    zone: ZoneId,
): List<ChartPoint> {
    if (seedCoefficient <= 0f) return emptyList()
    fun epochDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    val baselineByDay = baselineEvents.map { epochDay(it.timestamp) to it }
    val coeffByDay = coefficientEvents.map { epochDay(it.computedAt) to it }
    val leadingBaseline = baselineEvents.firstOrNull()?.previousBaseline?.takeIf { it > 0f }
    return dayKeys.sorted().mapNotNull { day ->
        val baseline = baselineByDay.lastOrNull { it.first <= day }?.second?.newBaseline
            ?: leadingBaseline
            ?: return@mapNotNull null
        val coeff = coeffByDay.lastOrNull { it.first <= day }?.second?.coefficient ?: seedCoefficient
        ChartPoint(dateMs = day * 86_400_000L, weightKg = baseline * coeff)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.exercises.ExerciseDetailViewModelTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailViewModel.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailViewModelTest.kt
git commit -m "feat: add buildPrescribedPoints helper for prescribed-1RM chart line"
```

---

### Task 2: Wire prescribed points into the ViewModel state

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailViewModel.kt`

**Interfaces:**
- Consumes: `buildPrescribedPoints(...)` from Task 1; `repository.getBaselineEvents(muscle: MuscleGroup)`, `repository.getCoefficientEvents(exerciseId: Long)`, `ExerciseCoefficients.byName` (already imported).
- Produces: `ExerciseDetailState.prescribedPoints: List<ChartPoint>` (default `emptyList()`), populated in `loadChartData`.

- [ ] **Step 1: Add the state field**

In `ExerciseDetailState`, add after `shadowPoints`:

```kotlin
    val prescribedPoints: List<ChartPoint> = emptyList(),
```

- [ ] **Step 2: Populate it in `loadChartData`**

In `loadChartData`, after the `computeShadowPoints(...)` call and before `_state.value = ...`, add:

```kotlin
        val seedCoefficient = ExerciseCoefficients.byName[exercise.name] ?: 0f
        val prescribedPoints = buildPrescribedPoints(
            baselineEvents = repository.getBaselineEvents(exercise.primaryMuscle),
            coefficientEvents = repository.getCoefficientEvents(exerciseId),
            seedCoefficient = seedCoefficient,
            dayKeys = primarySetsByDay.keys,
            zone = zone,
        )
```

Then add `prescribedPoints = prescribedPoints,` to the `_state.value = _state.value.copy(...)` block.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the unit suite (no regressions)**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.exercises.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailViewModel.kt
git commit -m "feat: expose prescribedPoints in exercise detail state"
```

---

### Task 3: Render the prescribed-1RM cubic line

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailScreen.kt`

**Interfaces:**
- Consumes: `state.prescribedPoints` from Task 2.
- Vico APIs (see `docs/vico-reference.md`): `LineCartesianLayer.rememberLine(fill, stroke, pointConnector)`, `LineCartesianLayer.LineStroke.continuous(thickness)`, `LineCartesianLayer.PointConnector.cubic()`. All nested in the already-imported `LineCartesianLayer` — no new imports needed.

- [ ] **Step 1: Extend the empty-state guard**

In `ExerciseDetailScreen`, change:

```kotlin
            if (state.primaryPoints.isEmpty() && state.shadowPoints.isEmpty()) {
```

to:

```kotlin
            if (state.primaryPoints.isEmpty() && state.shadowPoints.isEmpty() && state.prescribedPoints.isEmpty()) {
```

- [ ] **Step 2: Pass prescribedPoints into `ExerciseChart`**

Update the `ExerciseChart(...)` call to add the argument:

```kotlin
                ExerciseChart(
                    primaryPoints = state.primaryPoints,
                    shadowPoints = state.shadowPoints,
                    prescribedPoints = state.prescribedPoints,
                    weightUnit = state.weightUnit,
                    onDaySelected = viewModel::selectDay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
```

- [ ] **Step 3: Add the parameter and series to `ExerciseChart`**

Change the signature:

```kotlin
private fun ExerciseChart(
    primaryPoints: List<ChartPoint>,
    shadowPoints: List<ChartPoint>,
    prescribedPoints: List<ChartPoint>,
    weightUnit: WeightUnit,
    onDaySelected: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
```

In the `LaunchedEffect`, change the key list to `LaunchedEffect(primaryPoints, shadowPoints, prescribedPoints)` and add a third series **after** the shadow series, inside the same `lineSeries { ... }` block:

```kotlin
                if (prescribedPoints.isNotEmpty()) {
                    series(
                        x = prescribedPoints.map { it.dateMs / 86_400_000L },
                        y = prescribedPoints.map { it.weightKg },
                    )
                }
```

- [ ] **Step 4: Define the prescribed line and add it to the provider**

After the `shadowLine` definition, add:

```kotlin
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val prescribedLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(fill(tertiaryColor)),
        stroke = LineCartesianLayer.LineStroke.continuous(thickness = 2.dp),
        pointConnector = LineCartesianLayer.PointConnector.cubic(),
    )
```

Change the `hasPrimary`/`hasShadow` block to include the prescribed flag and series (order: primary, shadow, prescribed — matching the transaction order):

```kotlin
    val hasPrimary = primaryPoints.isNotEmpty()
    val hasShadow = shadowPoints.isNotEmpty()
    val hasPrescribed = prescribedPoints.isNotEmpty()
    val lineProvider = remember(hasPrimary, hasShadow, hasPrescribed, primaryLine, shadowLine, prescribedLine) {
        LineCartesianLayer.LineProvider.series(buildList {
            if (hasPrimary) add(primaryLine)
            if (hasShadow) add(shadowLine)
            if (hasPrescribed) add(prescribedLine)
        })
    }
```

- [ ] **Step 5: Add a caption legend under the chart title**

In `ExerciseDetailScreen`, right after the `Text("Estimated One Rep Max", ...)` title and before the `if (state.primaryPoints...)` guard, add a small legend so the line's meaning is clear:

```kotlin
            Row(
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Text(
                    text = "Prescribed target",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

Add the import `import androidx.compose.foundation.background` at the top.

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Visual check**

Install/run the app, open an exercise with history (e.g. via the debug seeder), and confirm: a smooth tertiary-colored curve appears through the achieved dots; bodyweight exercises show no curve; tapping a day still selects it. (See `superpowers:verification-before-completion`.)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailScreen.kt
git commit -m "feat: render prescribed-1RM cubic line on the exercise chart"
```

---

## Self-Review

- **Spec coverage:** units rationale → Task 1 doc + Global Constraints; true-unrounded value → Task 1 Step 1 test + Step 3 impl; baseline/coeff step-function + seed fallback + leading-day rule + bodyweight suppression → Task 1; shared-x-grid sampling (no marker change) → Task 2 (`dayKeys = primarySetsByDay.keys`); cubic tertiary stroke, no points, empty-state guard, legend → Task 3. All spec sections covered.
- **Placeholder scan:** none — every code step shows complete code.
- **Type consistency:** `buildPrescribedPoints` signature identical across Task 1 (def) and Task 2 (call); `prescribedPoints` field name consistent across Tasks 2–3; `ChartPoint(dateMs, weightKg)` matches existing definition; flag naming `hasPrescribed`/`prescribedLine` consistent in Task 3.
