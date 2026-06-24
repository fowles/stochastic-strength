# History Export / Import

**Date:** 2026-06-24
**Status:** Approved, pending implementation

## Problem

To debug issues reported by production users, the developer needs a way to obtain a
user's full app state and reproduce it locally. Today there is no mechanism to get a
user's data off their device, nor to load it onto another device.

## Goal

Add an export/import mechanism behind a `...` overflow menu in the History screen:

- **Export** writes a single self-describing JSON file containing all source-of-truth
  tables, suitable for a prod user to send to the developer.
- **Import** loads such a file in one of two modes:
  - **Destructive** — wipe current data and load the file verbatim (exact reproduction
    of the user's state).
  - **Additive** — merge the file's workout sessions/sets into the current history.

## Non-goals

- Cross-version backup migration. A backup's `dbVersion` must match the app's current
  Room version; otherwise import is refused with a clear message. This is a developer
  debugging tool and the developer controls both ends.
- Exporting derived state. Derived projections (`MuscleGroupStrength`, baseline/coefficient
  history, per-exercise `ExerciseEstimate`) are rebuilt by `replayDerivedState()` after
  import, consistent with the in-memory-derived-state architecture.

## File format

JSON produced via `org.json` (`JSONObject`/`JSONArray`) — matching the existing
`StravaJsonBuilder`, so no new dependency is added. Top-level envelope:

```json
{
  "format": "stochastic-strength-backup",
  "formatVersion": 1,
  "dbVersion": 17,
  "exportedAt": 1719230000000,
  "tables": {
    "exercises": [...],
    "knownLocations": [...],
    "locationExcludedExercises": [...],
    "workoutSessions": [...],
    "workoutSets": [...],
    "userProfile": [...],
    "baselineOverrides": [...],
    "exerciseHurtState": [...],
    "exerciseStrengthOverrides": [...]
  }
}
```

Each table array is a faithful dump of its rows, including primary-key ids. The set of
tables is exactly the durable input tables of the app (everything that is a source of
truth, not derived).

- `format` and `formatVersion` identify the file shape.
- `dbVersion` is the current Room schema version (17). On import, a mismatch is refused
  with a message such as: *"This export is from DB v16 but the app is on v17. Update the
  app, or re-export."*
- `exportedAt` is the export wall-clock time (epoch ms), informational only.

## Components

New package `domain/backup/`:

- **`WorkoutBackup.kt`** — plain in-memory data holder: one `List<Entity>` per table, plus
  `formatVersion`, `dbVersion`, `exportedAt`. No framework dependencies; trivially testable.
- **`BackupJsonBuilder.kt`** — `WorkoutBackup -> String`. Serializes each entity field
  explicitly (mirrors `StravaJsonBuilder`'s explicit style), handling nullable columns.
- **`BackupJsonParser.kt`** — `String -> WorkoutBackup`. Validates `format` and `dbVersion`;
  throws a typed `BackupFormatException` (with a human-readable message) on malformed input
  or version mismatch.
- **`BackupManager.kt`** — orchestration over `AppDatabase` + `WorkoutRepository`:
  - `suspend fun export(): WorkoutBackup` — reads every input table via its DAO.
  - `suspend fun importDestructive(backup: WorkoutBackup)` — within a single Room
    `@Transaction`: delete all rows from every input table, then insert the backup's rows
    verbatim (primary-key ids preserved). After the transaction, call
    `repository.replayDerivedState()`.
  - `suspend fun importAdditive(backup: WorkoutBackup): AdditiveResult` — see below.

`BackupManager` is owned as a lazy singleton on `StochasticStrengthApp`
(`app.backupManager`), the same way `workoutRepository` is. ViewModels read it from there.

### DAO additions

Add `getAll()` and `deleteAll()` query methods to the input-table DAOs that lack them
(`ExerciseDao`, `KnownLocationDao`, `LocationExcludedExerciseDao`,
`UserProfileDao`, `BaselineOverrideDao`, `ExerciseHurtStateDao`,
`ExerciseStrengthOverrideDao`). `WorkoutSessionDao`/`WorkoutSetDao` already have `getAll()`;
add `deleteAll()` where missing. Inserts use the existing `@Insert` methods; Room honors a
non-zero explicit primary-key id, so destructive import preserves ids.

### Additive import semantics

Only **workout sessions and sets** are merged. Profile, baseline/strength overrides,
hurt-state, and exercise-library/location settings are left untouched.

References are resolved by **name**, not id:

1. Build a `name -> localExerciseId` map from the local exercise library.
2. For any imported exercise referenced by an imported set but absent locally, create it
   (insert the imported exercise's definition, get a fresh local id) and add it to the map.
   Locations referenced by imported sessions are resolved the same way by location name;
   missing ones are created. A session with no/unknown location keeps a null location.
3. Insert each imported session with a **fresh** local id; remap its sets' `sessionId` to
   the new id and `exerciseId` via the name map.
4. After inserting, call `repository.replayDerivedState()`.

`AdditiveResult` reports counts: sessions added, exercises created, locations created, and
any rows skipped (with reason).

## UI & file I/O

- **`BackTopAppBar`** gains an optional `actions: @Composable RowScope.() -> Unit = {}`
  slot (default empty, so existing call sites are unaffected).
- **`HistoryScreen`** passes an overflow `IconButton(Icons.Default.MoreVert)` opening a
  `DropdownMenu` with **Export history** and **Import history** items.
- File access uses the **Storage Access Framework** (no `FileProvider` needed):
  - Export: `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"))`,
    with a suggested filename like `stochastic-strength-YYYYMMDD.json`.
  - Import: `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())`
    filtered to `arrayOf("application/json")`.
  - The composable hands the returned `Uri` to the `HistoryViewModel`, which streams to/from
    it via `application.contentResolver` on `Dispatchers.IO`.
- **Import flow:** after a file is chosen, an `AlertDialog` offers **Additive** /
  **Destructive** / **Cancel**. Choosing **Destructive** requires a second confirmation
  ("This will erase all current data and replace it."). Results (success summary or an error
  message, including version-mismatch) are surfaced via toast/snackbar. On success the
  ViewModel reloads its history state.

### HistoryViewModel additions

- `exportTo(uri: Uri)` — `backupManager.export()`, serialize, write to the uri's
  `OutputStream`. Report success/failure.
- `importFrom(uri: Uri, mode: ImportMode)` — read the uri's `InputStream`, parse, dispatch
  to `importDestructive`/`importAdditive`, then refresh state. Catch `BackupFormatException`
  and surface its message.

## Error handling

- Malformed JSON or wrong `format`/`dbVersion` → `BackupFormatException` → user-facing
  message; no DB mutation occurs (parse happens fully before any write).
- Destructive import runs inside a single transaction: a failure rolls back, leaving the
  current data intact.
- File I/O errors (cancelled picker, unreadable uri) are caught and reported; cancelling the
  SAF picker is a no-op.

## Testing

- **JVM unit tests:**
  - `BackupJsonBuilder` + `BackupJsonParser` round-trip: every table populated, including
    nullable fields and an empty-DB case; bytes survive serialize → parse → serialize.
  - Parser rejects a wrong `format` string and a mismatched `dbVersion` with
    `BackupFormatException`.
- **Instrumented tests** (in-memory Room, matching existing project practice):
  - Destructive import reproduces all input rows with identical ids and rebuilds derived
    state via replay.
  - Additive import: remaps exercises by name, creates missing exercises/locations, assigns
    fresh session ids, leaves profile/overrides/hurt-state untouched, and reports correct
    counts.
  - Both modes leave the derived state consistent with a fresh replay.
