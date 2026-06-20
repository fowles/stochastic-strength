# Muscle Baseline Debug — Coefficient Deviation Chart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "Current baseline" card on `MuscleBaselineDetailScreen` with a horizontal diverging bar list showing each exercise's current coefficient relative to its seed (`coeff / seed - 1`), so developers can spot baseline gains hidden inside coefficient drift.

**Architecture:** Extract a pure helper `computeCoefficientDeviations` for testability, then call it from `MuscleBaselineDetailViewModel.init`. The chart is a native Compose `Column` of `Row`s (one per exercise) — not Vico — because the dataset is small (≤11 rows) and we want full control over zero-centered horizontal bars, which Vico's `ColumnCartesianLayer` does not produce directly.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Room. Unit tests use JUnit on the JVM (`src/test/`).

**Spec:** `docs/superpowers/specs/2026-06-13-muscle-baseline-debug-coefficient-deviation-design.md`

---

## File Structure

**New files (production):**

- None. The new bar list is added as private composables inside `MuscleBaselineDetailScreen.kt` (it's used in exactly one place, ≤80 lines, and lives with its only caller).

**New files (test):**

- `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/CoefficientDeviationTest.kt` — JVM unit tests for the helper.

**Modified files:**

- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt`
  — add `CoefficientDeviationRow` data class, add `coefficientDeviations` to state, extract pure helper `computeCoefficientDeviations`, call it in `init`.
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailScreen.kt`
  — remove "Current baseline" card, tighten `SectionHeader` and chart padding, add new "Coefficient vs seed" section with private composables for the bar list and rows.

---

### Task 1: Add the helper + failing unit tests

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/CoefficientDeviationTest.kt`

We start with the pure helper because it has the most logic (filter + sort + arithmetic) and is the only piece worth unit-testing in this change.

- [ ] **Step 1: Add `CoefficientDeviationRow` and helper signature to `MuscleBaselineDetailViewModel.kt`**

Open `MuscleBaselineDetailViewModel.kt`. After the `BaselineEvent` data class (around line 30) and before `MuscleBaselineDetailState`, add:

```kotlin
data class CoefficientDeviationRow(
    val name: String,
    val deviation: Float,
)

/**
 * Returns the per-exercise drift of `current` coefficient vs `seed`,
 * expressed as `current / seed - 1` and sorted descending. Exercises
 * whose seed is `0f` are omitted (bodyweight — ratio undefined).
 *
 * If an exercise has no entry in [currentCoefficients] the current value
 * falls back to its seed, yielding a deviation of `0f`.
 */
internal fun computeCoefficientDeviations(
    exercises: List<Pair<Long, String>>,
    seedByName: Map<String, Float>,
    currentByExerciseId: Map<Long, Float>,
): List<CoefficientDeviationRow> {
    val rows = exercises.mapNotNull { (id, name) ->
        val seed = seedByName[name] ?: return@mapNotNull null
        if (seed == 0f) return@mapNotNull null
        val current = currentByExerciseId[id] ?: seed
        CoefficientDeviationRow(name = name, deviation = current / seed - 1f)
    }
    return rows.sortedByDescending { it.deviation }
}
```

The `exercises` parameter is `List<Pair<Long, String>>` rather than `List<Exercise>` so tests don't have to construct `Exercise` entities with all their fields. The ViewModel will map down to pairs before calling.

- [ ] **Step 2: Write the failing unit tests**

Create `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/CoefficientDeviationTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class CoefficientDeviationTest {

    @Test
    fun `positive and negative drift are computed and sorted descending`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(
                1L to "Bench Press",     // 1.25 / 1.0  = +25%
                2L to "Incline Bench",   // 0.918 / 0.85 = +8%
                3L to "Decline Bench",   // 0.9215 / 0.95 = -3%
                4L to "Dumbbell Press",  // 0.328 / 0.40 = -18%
            ),
            seedByName = mapOf(
                "Bench Press" to 1.00f,
                "Incline Bench" to 0.85f,
                "Decline Bench" to 0.95f,
                "Dumbbell Press" to 0.40f,
            ),
            currentByExerciseId = mapOf(
                1L to 1.25f,
                2L to 0.918f,
                3L to 0.9215f,
                4L to 0.328f,
            ),
        )

        assertEquals(listOf("Bench Press", "Incline Bench", "Decline Bench", "Dumbbell Press"),
            rows.map { it.name })
        assertEquals(0.25f, rows[0].deviation, 1e-4f)
        assertEquals(0.08f, rows[1].deviation, 1e-4f)
        assertEquals(-0.03f, rows[2].deviation, 1e-4f)
        assertEquals(-0.18f, rows[3].deviation, 1e-4f)
    }

    @Test
    fun `seed of zero is omitted`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(
                1L to "Push-Up",
                2L to "Bench Press",
            ),
            seedByName = mapOf(
                "Push-Up" to 0f,
                "Bench Press" to 1.0f,
            ),
            currentByExerciseId = mapOf(
                1L to 0.5f, // would otherwise produce divide-by-zero
                2L to 1.1f,
            ),
        )

        assertEquals(listOf("Bench Press"), rows.map { it.name })
    }

    @Test
    fun `unknown seed is omitted`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(1L to "Unknown Exercise"),
            seedByName = emptyMap(),
            currentByExerciseId = mapOf(1L to 1.0f),
        )

        assertEquals(emptyList<CoefficientDeviationRow>(), rows)
    }

    @Test
    fun `missing current falls back to seed yielding zero deviation`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(1L to "Bench Press"),
            seedByName = mapOf("Bench Press" to 1.0f),
            currentByExerciseId = emptyMap(),
        )

        assertEquals(1, rows.size)
        assertEquals("Bench Press", rows[0].name)
        assertEquals(0f, rows[0].deviation, 1e-6f)
    }

    @Test
    fun `empty input returns empty list`() {
        val rows = computeCoefficientDeviations(
            exercises = emptyList(),
            seedByName = emptyMap(),
            currentByExerciseId = emptyMap(),
        )

        assertEquals(emptyList<CoefficientDeviationRow>(), rows)
    }
}
```

- [ ] **Step 3: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.CoefficientDeviationTest"`

Expected: 5 tests pass.

- [ ] **Step 4: Commit**

```bash
jj commit -m "feat(debug): add computeCoefficientDeviations helper for muscle baseline screen"
```

---

### Task 2: Wire the helper into `MuscleBaselineDetailViewModel`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt`

- [ ] **Step 1: Add `coefficientDeviations` to `MuscleBaselineDetailState`**

In `MuscleBaselineDetailViewModel.kt`, update the `MuscleBaselineDetailState` data class:

```kotlin
data class MuscleBaselineDetailState(
    val loading: Boolean = true,
    val muscleGroup: MuscleGroup,
    val currentBaseline: Float = 0f,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val events: List<BaselineEvent> = emptyList(),
    val chartPoints: List<DebugChartPoint> = emptyList(),
    val coefficientDeviations: List<CoefficientDeviationRow> = emptyList(),
)
```

Note: `currentBaseline` stays on the state for now — even though the card is removed in Task 3, removing the field is part of Task 3 so the diff is colocated with the UI change that drops the only reader.

- [ ] **Step 2: Add `import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients`**

At the top of `MuscleBaselineDetailViewModel.kt`, add the import alongside the existing imports.

- [ ] **Step 3: Compute deviations inside the `init` coroutine**

Inside `init { viewModelScope.launch { … } }`, after `val logs = repository.getBaselineEvents(muscleGroup)` and before the `_state.value =` assignment, add:

```kotlin
            val allExercises = app.database.exerciseDao().getAll()
                .filter { it.primaryMuscle == muscleGroup }
            val latestUserCoefficients = app.database.coefficientChangeLogDao()
                .getLatestPerExercise()
                .associate { it.exerciseId to it.coefficient }
            val coefficientDeviations = computeCoefficientDeviations(
                exercises = allExercises.map { it.id to it.name },
                seedByName = ExerciseCoefficients.byName,
                currentByExerciseId = latestUserCoefficients,
            )
```

- [ ] **Step 4: Pass `coefficientDeviations` into the new state**

Update the `_state.value = MuscleBaselineDetailState(...)` call to include `coefficientDeviations = coefficientDeviations,`.

- [ ] **Step 5: Build to verify**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat(debug): compute per-exercise coefficient deviations in MuscleBaselineDetailViewModel"
```

---

### Task 3: Remove the "Current baseline" card and tighten section spacing

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailScreen.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt`

- [ ] **Step 1: Delete the current-baseline card from the screen**

In `MuscleBaselineDetailScreen.kt`, delete the `item { Card(...) { ... } }` block (currently lines 69-88), starting from the line containing `item {` immediately after `LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {` through and including the matching closing `}` of that item.

After this edit, the `LazyColumn` should start directly with `item { SectionHeader("Baseline over time") }`.

- [ ] **Step 2: Drop unused imports**

In `MuscleBaselineDetailScreen.kt`, the removal of the card may leave imports unused. Delete these imports if they no longer have references:

- `androidx.compose.material3.Card`
- `androidx.compose.material3.CardDefaults`

Keep `Row`, `padding`, `Column`, `MaterialTheme`, `Text` — they remain in use elsewhere.

Run `./gradlew :app:assembleDebug` after the deletion to surface any stale imports the compiler complains about, then fix them.

- [ ] **Step 3: Drop `currentBaseline` from the state and ViewModel**

In `MuscleBaselineDetailViewModel.kt`:

- Remove the `val currentBaseline: Float = 0f,` line from `MuscleBaselineDetailState`.
- Remove the local `val currentBaseline = repository.getMuscleGroupStrengths()...` lookup inside `init`.
- Remove `currentBaseline = currentBaseline,` from the `_state.value = MuscleBaselineDetailState(...)` call.

- [ ] **Step 4: Tighten the SectionHeader spacing**

In `MuscleBaselineDetailScreen.kt`, change `SectionHeader`'s padding from:

```kotlin
            .padding(horizontal = 16.dp, vertical = 12.dp),
```

to:

```kotlin
            .padding(horizontal = 16.dp, vertical = 4.dp),
```

- [ ] **Step 5: Tighten the line chart wrapper padding**

In the same file, change the `DebugLineChart` `modifier` from:

```kotlin
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
```

to:

```kotlin
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp, vertical = 0.dp),
```

- [ ] **Step 6: Build to verify**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat(debug): drop current-baseline card and tighten section spacing"
```

---

### Task 4: Add the "Coefficient vs seed" section with horizontal diverging bars

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailScreen.kt`

- [ ] **Step 1: Add the new section to the `LazyColumn`**

In `MuscleBaselineDetailScreen.kt`, between `item { SectionHeader("Baseline over time") }`'s block (which renders the line chart or the placeholder) and `item { SectionHeader("Change events") }`, insert:

```kotlin
            item { SectionHeader("Coefficient vs seed") }

            item {
                if (state.coefficientDeviations.isEmpty()) {
                    EmptyDeviationsPlaceholder()
                } else {
                    CoefficientDeviationList(state.coefficientDeviations)
                }
            }
```

- [ ] **Step 2: Add required imports**

Add these imports near the top of `MuscleBaselineDetailScreen.kt` if not already present:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
```

`fillMaxSize`, `Row`, `Box`, and `Alignment` should already be imported from earlier in the file.

Compile errors after this step will tell you which are already imported — remove duplicates.

- [ ] **Step 3: Add `CoefficientDeviationList` composable**

Add this private composable near `SectionHeader`/`EmptyHistoryPlaceholder` at the bottom of `MuscleBaselineDetailScreen.kt`:

```kotlin
@Composable
private fun CoefficientDeviationList(rows: List<CoefficientDeviationRow>) {
    val maxAbs = rows.maxOf { kotlin.math.abs(it.deviation) }.coerceAtLeast(1e-6f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row -> DeviationRow(row, maxAbs) }
    }
}
```

- [ ] **Step 4: Add `DeviationRow` composable**

Below `CoefficientDeviationList`, add:

```kotlin
@Composable
private fun DeviationRow(row: CoefficientDeviationRow, maxAbs: Float) {
    val positiveColor = MaterialTheme.colorScheme.primary
    val negativeColor = MaterialTheme.colorScheme.error
    val guidelineColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(140.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .padding(horizontal = 4.dp),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left half — holds negative bars, anchored to the right edge (center guideline).
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (row.deviation < 0f) {
                        val fraction = ((-row.deviation) / maxAbs).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxWidth(fraction)
                                .height(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(negativeColor),
                        )
                    }
                }
                // Right half — holds positive bars, anchored to the left edge (center guideline).
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (row.deviation > 0f) {
                        val fraction = (row.deviation / maxAbs).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth(fraction)
                                .height(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(positiveColor),
                        )
                    }
                }
            }
            // Center guideline drawn on top of the bars so they appear to start flush against it.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(guidelineColor),
            )
        }
        Text(
            text = formatDeviation(row.deviation),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

private fun formatDeviation(deviation: Float): String {
    val pct = (deviation * 100f).toInt()
    return if (pct >= 0) "+$pct%" else "$pct%"
}
```

The bar area is a Row of two equal-weight halves: the left half hosts negative bars anchored to its right edge (which is the visual center), and the right half hosts positive bars anchored to its left edge. `fillMaxWidth(fraction)` on the colored Box scales relative to its half, so the bar width equals `fraction × (parent / 2)` — exactly the geometry we want. The 1.dp center guideline overlays the Row via `align(Alignment.Center)` so it sits flush against whichever bar is showing.

- [ ] **Step 5: Add the empty-state placeholder**

Below `EmptyHistoryPlaceholder`, add:

```kotlin
@Composable
private fun EmptyDeviationsPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("No weighted exercises", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
```

- [ ] **Step 6: Build to verify**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat(debug): add coefficient-vs-seed diverging bar list to muscle baseline screen"
```

---

### Task 5: Manual verification

**Files:** none (verification only).

- [ ] **Step 1: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: all tests pass, including the 5 new ones in `CoefficientDeviationTest`.

- [ ] **Step 2: Run lint**

Run: `./gradlew :app:lint`

Expected: no new lint errors introduced by this change.

- [ ] **Step 3: Verify on emulator**

Per the instrumented-tests memory, the emulator is typically running. Install and launch:

```bash
./gradlew :app:installDebug
```

Then navigate: Home → About → Debug & Advanced Stats → tap any muscle group with progression history (e.g. one that has at least one workout session).

Visually confirm:
- "Current baseline" card is gone.
- "Baseline over time" header sits visibly closer to the chart than before.
- New "Coefficient vs seed" section appears between the line chart and "Change events".
- Bars are diverging horizontally about a center line, sorted with largest positive at top.
- Exercises with seed = 0 (e.g. Push-Up under CHEST) do not appear.

If a muscle group has no weighted exercises (very unlikely), confirm the "No weighted exercises" placeholder renders.

- [ ] **Step 4: No commit needed — verification only.**

---

## Self-Review Pass

**Spec coverage check:**

- "Remove the Current baseline Card" → Task 3 Step 1 ✓
- "Tighten gap between header and chart" → Task 3 Steps 4–5 ✓
- "New Coefficient vs seed section" → Task 4 ✓
- "Horizontal diverging bar list" → Task 4 Step 4 ✓
- "Per-row layout: name 140dp / bar weight 1f / value 56dp" → Task 4 Step 4 ✓
- "Positive primary, negative error" → Task 4 Step 4 ✓
- "Sort deviation descending" → Task 1 helper ✓
- "Omit seed=0 rows" → Task 1 helper ✓
- "Include disliked exercises" → Task 2 Step 3 uses `getAll()` not `getActive()` ✓
- "Empty state placeholder" → Task 4 Step 5 ✓
- "ViewModel state addition" → Tasks 1–2 ✓
- "Unit-test the pure helper" → Task 1 Steps 2–3 (5 tests) ✓

**Type consistency check:** `CoefficientDeviationRow(name, deviation)` is defined in Task 1 and referenced consistently in Tasks 2–4. `computeCoefficientDeviations` signature `(exercises, seedByName, currentByExerciseId)` matches between definition and call site.

**Placeholder scan:** No "TBD"/"TODO"/"add appropriate"/"similar to" — every code block is complete. Edits to existing code reference exact existing line ranges (`MuscleBaselineDetailScreen.kt:69-88`, etc.) and current padding values to be replaced.
