# Re-export to Strava — Design

**Date:** 2026-07-19

## Goal

Add a `⋮` overflow menu to the per-workout view (`SummaryScreen`, reached from
History via `summary/{sessionId}`) with a single item, **"Re-export to Strava"**.
Selecting it clears the stored Strava OAuth token and forces the workout through
the full Strava connect + export flow again — even if the workout was already
exported and even if Strava is currently connected.

## Context

- The "Strava cookie" is the OAuth token held in `StravaTokenStore`
  (`clearTokens()` already exists).
- `SummaryScreen` already has an "Export to Strava" button in its footer, wired
  through `SummaryViewModel` → `StravaExportController` → `StravaExporter`.
- The normal `StravaExportController.export()` already routes through the connect
  flow when `exporter.isAuthenticated()` is false: it emits `NeedsAuth`, which
  the screen's existing `LaunchedEffect` turns into a browser launch, and
  `onResumed → onResumedWaitingForAuth` finishes the export on return.
- The normal Export button disables itself after a `Success` state; the menu item
  bypasses that because it drives the controller directly.
- The screen currently has **no** `TopAppBar` — just a bare `Scaffold`. The
  workout date is rendered in the content `header` slot.

## Decisions (from brainstorming)

- **Placement:** add a `TopAppBar`; date becomes its title, `⋮` overflow in its
  actions.
- **Confirmation:** none — tapping the item acts immediately.
- **Visibility:** always shown, regardless of prior export state.

## Changes

1. **`StravaExporter`** — expose the token clear:
   ```kotlin
   fun clearTokens() = tokenStore.clearTokens()
   ```

2. **`StravaExportController`** — add:
   ```kotlin
   fun reexport(sessionId: Long, weightUnit: WeightUnit) {
       exporter.clearTokens()
       export(sessionId, weightUnit)   // now unauthenticated → NeedsAuth → connect flow
   }
   ```
   Reuses all existing export/auth plumbing. A successful re-export overwrites the
   session's stored `stravaActivityId` via the existing `updateStravaActivityId`
   call.

3. **`SummaryViewModel`** — add `onReexportToStrava()` delegating to
   `stravaController.reexport(sessionId, weightUnit)`, mirroring
   `onExportToStrava()`.

4. **`SummaryScreen`** — wrap in `Scaffold(topBar = { TopAppBar(...) })`:
   - Title: the workout date label (moved out of the content `header` slot to
     avoid duplication; the header slot's date `Text` is removed).
   - Actions: `IconButton(Icons.Default.MoreVert)` toggling a `DropdownMenu` with
     one `DropdownMenuItem` "Re-export to Strava" → `viewModel::onReexportToStrava`
     (also closes the menu).
   - Footer Export and Back buttons unchanged.

## Out of scope

- No confirmation dialog.
- The footer "Back" button stays; no nav-back arrow added to the app bar.
- No new pure logic (the `reexport` method is two lines of delegation). The
  controller is built from concrete, Android-coupled types (`StravaExporter`,
  `AppDatabase`) with no existing test seam, so no JVM unit test is added.
  Verification is by build + on-device pass.

## Verification

- `./gradlew :app:assembleDebug` compiles.
- On-device: open a past workout from History → `⋮` → "Re-export to Strava" →
  Strava connect flow launches even when already connected → export completes and
  the new activity id is stored.
