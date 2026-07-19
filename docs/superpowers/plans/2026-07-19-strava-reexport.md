# Strava Re-export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `⋮` overflow menu to the per-workout `SummaryScreen` with a "Re-export to Strava" item that clears the Strava OAuth token and re-runs the full connect + export flow.

**Architecture:** Reuse the existing export pipeline. Re-export = clear the stored token, then call the normal `StravaExportController.export()`; with the token gone the controller emits `NeedsAuth`, and the screen's existing `LaunchedEffect` + `onResumed` logic drives the browser connect flow and finishes the export. UI adds a `TopAppBar` (date title + overflow menu) to what is currently a bare `Scaffold`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Android.

## Global Constraints

- Package: `io.github.fowles.stochastic_strength`.
- No new dependencies; use Material3 components already on the classpath.
- Version control is jj; commit at each task checkpoint, user owns reshape + push.
- No confirmation dialog; the "Re-export to Strava" item is always shown.
- Verification is by `./gradlew :app:assembleDebug` (no JVM test seam exists for `StravaExportController`, which is built from concrete `StravaExporter` + `AppDatabase`).

---

### Task 1: Re-export plumbing (exporter → controller → view model)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/strava/StravaExporter.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/strava/StravaExportController.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/summary/SummaryViewModel.kt`

**Interfaces:**
- Consumes: `StravaTokenStore.clearTokens()` (exists); `StravaExportController.export(sessionId: Long, weightUnit: WeightUnit)` (exists).
- Produces:
  - `StravaExporter.clearTokens()` : Unit
  - `StravaExportController.reexport(sessionId: Long, weightUnit: WeightUnit)` : Unit
  - `SummaryViewModel.onReexportToStrava()` : Unit

- [ ] **Step 1: Expose token clear on the exporter**

In `StravaExporter.kt`, add next to `isAuthenticated()` (around line 67):

```kotlin
fun clearTokens() = tokenStore.clearTokens()
```

- [ ] **Step 2: Add `reexport` to the controller**

In `StravaExportController.kt`, add immediately after the `export(...)` function (after line 32):

```kotlin
fun reexport(sessionId: Long, weightUnit: WeightUnit) {
    exporter.clearTokens()
    export(sessionId, weightUnit)   // now unauthenticated → NeedsAuth → connect flow
}
```

- [ ] **Step 3: Add `onReexportToStrava` to the view model**

In `SummaryViewModel.kt`, add immediately after `onExportToStrava()` (after line 48):

```kotlin
fun onReexportToStrava() {
    val weightUnit = summary.value?.weightUnit ?: WeightUnit.KG
    stravaController.reexport(sessionId, weightUnit)
}
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(strava): re-export plumbing — clearTokens + reexport wiring

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YMbPUs4NjqmdXWPMxB3uoK"
```

---

### Task 2: TopAppBar with overflow menu on `SummaryScreen`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/summary/SummaryScreen.kt`

**Interfaces:**
- Consumes: `SummaryViewModel.onReexportToStrava()` (from Task 1); existing `summary` / `stravaState` flows and `WorkoutSummaryContent`.
- Produces: no new public interface (UI only).

- [ ] **Step 1: Add imports**

In `SummaryScreen.kt`, add these imports (alongside the existing Compose imports):

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

Note: `getValue` is already imported for `by collectAsState()`; keep a single import (do not duplicate).

- [ ] **Step 2: Opt in to the Material3 top-bar API**

Annotate the `SummaryScreen` composable (add above the existing `@Composable` on line 33):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
```

- [ ] **Step 3: Compute the date label once and add menu state**

Inside `SummaryScreen`, just before the `Scaffold { paddingValues ->` line (line 65), add:

```kotlin
    val dateLabel = summary?.let {
        SimpleDateFormat("EEEE, MMM d · h:mm a", Locale.getDefault()).format(Date(it.startTime))
    }
    var menuExpanded by remember { mutableStateOf(false) }
```

- [ ] **Step 4: Add the TopAppBar and drop the duplicate header date**

Replace the `Scaffold { paddingValues ->` opening (line 65) and the content `header` block (lines 70-78) so the date lives in the app bar instead of the content header.

Change the Scaffold opening to:

```kotlin
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { if (dateLabel != null) Text(dateLabel) },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Re-export to Strava") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.onReexportToStrava()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
```

Then change the `WorkoutSummaryContent(...)` call to drop the `header` parameter entirely (remove the whole `header = { ... }` block on lines 70-78), leaving:

```kotlin
        WorkoutSummaryContent(
            summary = summary,
            modifier = Modifier.padding(paddingValues),
            onExerciseTap = onExerciseTap,
            footer = {
```

The `footer` block (Back / Export / Done buttons) is unchanged.

- [ ] **Step 5: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If the compiler reports `MaterialTheme` or the `SimpleDateFormat` imports as now-unused, remove them.

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat(strava): re-export menu on workout summary top bar

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YMbPUs4NjqmdXWPMxB3uoK"
```

---

## Manual verification (on-device, after Task 2)

1. Open a past workout from History.
2. Tap `⋮` → "Re-export to Strava".
3. Confirm the Strava connect flow launches **even if already connected**.
4. Complete auth; confirm the export runs and the toast/notification appears.
5. Confirm the workout's stored `stravaActivityId` is updated (footer button shows "Exported to Strava").
