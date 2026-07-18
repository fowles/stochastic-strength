# Asymmetric Barbell Exercises + Landmine Press — Design

**Date:** 2026-07-17
**Status:** Approved

## Problem

T-Bar Row loads all weight on one end of a bar, like a Landmine Press. Both are
`Equipment.BARBELL` today, so the app wrongly:

1. Shows a **plates-per-side breakdown** (assumes a symmetric loaded bar).
2. Generates **plates-and-quarters warmup sets** (assumes a 20 kg / 45 lb bar with
   symmetric plate math).

We want two things:

- Add **Landmine Press** as a new exercise.
- Give asymmetric barbell exercises **proper handling**: no plate breakdown, and the
  ordinary percentage-ramp warmup path.

Equipment must remain `BARBELL` so belief pooling / per-equipment τ is unchanged.

## Design

### 1. Data model

Add a persisted flag to `Exercise` (mirrors the existing `isUnilateral`):

```kotlin
data class Exercise(
    ...
    val isUnilateral: Boolean = false,
    val isAsymmetric: Boolean = false,   // single-end-loaded bar (T-Bar, Landmine)
    val isTimed: Boolean = false,
)
```

Single source of truth for the two behaviors — a helper that answers "does this lift
load a symmetric bar with plates per side?":

```kotlin
val Exercise.usesBarPlates: Boolean
    get() = equipment == Equipment.BARBELL && !isAsymmetric
```

Location TBD during planning (extension on `Exercise`, likely alongside the model or in
`WorkoutPlanner`/a shared util). Both warmup and plate-breakdown logic read it.

### 2. Behavior

Both behaviors flow from `usesBarPlates`.

**Warmup** — `WorkoutPlanner.computeWarmupSets`:

- Today: `if (exercise != null && exercise.equipment != Equipment.BARBELL) return percentageRampWarmups(weightKg)`
- Change to gate on `usesBarPlates`: an asymmetric barbell lift takes the
  `percentageRampWarmups` path (the "ordinary" non-bar ramp), not the
  plates-and-quarters bar math.

**Plate breakdown** — three UI call sites currently gate on `equipment == Equipment.BARBELL`:

- `ActiveSetContent.kt` (~line 196) — has the full `exercise`; change to `exercise.usesBarPlates`.
- `RestingContent.kt` (~line 298) — takes a bare `equipment: Equipment`; thread the
  `Exercise` (or a `usesBarPlates: Boolean`) through so it can evaluate the flag.
- `WeightAdjustDialog.kt` (~line 62) — same as RestingContent: takes `equipment: Equipment`;
  thread the flag through.

No change to `WeightFormatter.platesPerSide` itself — call sites simply stop calling it for
asymmetric lifts.

### 3. Migration (Room v17 → v18)

```sql
ALTER TABLE exercises ADD COLUMN isAsymmetric INTEGER NOT NULL DEFAULT 0;
UPDATE exercises SET isAsymmetric = 1 WHERE name = 'T-Bar Row';
```

- **T-Bar Row** already exists in every user's DB, so its flag must be set by the migration
  `UPDATE`. The startup library-sync only inserts exercises missing *by name*; it never
  updates existing rows.
- **Landmine Press** needs **no insert here**. `StochasticStrengthApp.onCreate` syncs any
  `ExerciseLibrary` exercise missing by name into existing users' DBs on next launch, and it
  will carry `isAsymmetric = true` from the seed data.

Wiring:
- Add `MIGRATION_17_18` to `AppDatabase.Companion` and register it in the `addMigrations(...)`
  list.
- Bump `@Database(version = 18)`.
- Update the `MigrationTest` forward-migration list (version bumps must keep it in sync).

### 4. Seed data

`ExerciseLibrary.kt`:
- Mark `T-Bar Row` with `isAsymmetric = true`.
- Add under BACK/SHOULDERS as appropriate:
  ```kotlin
  Exercise(
      name = "Landmine Press",
      primaryMuscle = MuscleGroup.SHOULDERS,
      secondaryMuscles = listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.CORE),
      equipment = Equipment.BARBELL,
      isAsymmetric = true,
  )
  ```
  Bilateral (`isUnilateral = false`) — a two-handed landmine press is a legitimate common
  variant and matches the total-load coefficient model.

`ExerciseCoefficients.kt` — add under SHOULDERS (reference: Overhead Press = 1.00):
```kotlin
"Landmine Press" to 0.5f,
```

### 5. Backup

`BackupJson` hand-lists Exercise fields for export/import. Add `isAsymmetric` to both the
builder (`BackupJson.kt` ~line 68) and the parser (~line 158) so it round-trips.

### 6. Testing (TDD)

- **Warmup**: an asymmetric barbell exercise produces percentage-ramp warmups, not the
  plates-and-quarters sequence; a symmetric barbell exercise is unchanged.
- **Migration**: 17 → 18 adds the column and sets `T-Bar Row.isAsymmetric = 1`; existing
  data preserved.
- **Seed**: `Landmine Press` present with `isAsymmetric = true`, `primaryMuscle = SHOULDERS`,
  coefficient `0.5`.
- **Backup**: `isAsymmetric` survives an export → import round-trip.
- Full unit suite green at the end; instrumented migration/DB tests as applicable.

## Choices made (not asked)

- Landmine Press is **bilateral** (`isUnilateral = false`).
- Landmine Press coefficient = **0.5** (between OHP 1.00 and DB Overhead Press 0.35).

## Non-goals

- No new `Equipment` enum value (would break per-equipment τ pooling).
- No change to `platesPerSide` math or the belief/progression stack.
