# Baseline Change Log — Design Spec

**Date:** 2026-06-10  
**Status:** Approved

## Goal

Add an append-only audit log for every write to `muscle_group_strength`. This is the first incremental step toward per-user exercise coefficient tuning: the log's `previousBaseline` field is the missing link that, combined with existing `workout_sets` data and `ExerciseCoefficients.byName`, makes it possible to evaluate whether a coefficient was accurate for a given session and eventually replay history with different coefficients.

## Background

Two code paths currently write to `muscle_group_strength` with no audit trail:

1. **`WorkoutSessionController.startFirstExercise`** — applies `plan.strengthOverrides` (pre-session manual weight adjustments) via direct `muscleGroupStrengthDao().upsert()` calls.
2. **`WorkoutRepository.applySessionProgression`** — applies post-session baseline updates driven by set feedback and mid-session TOO_HARD reductions (passed in as `exerciseReductions`).

Both silently overwrite the single mutable row per muscle group.

## Schema

New Room entity: `BaselineChangeLog`, table `baseline_change_log`.

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `id` | `LONG` | no | autoGenerate PK |
| `sessionId` | `LONG` | no | always present — manual overrides fire after the session row is inserted |
| `muscleGroup` | `TEXT` | no | via existing `MuscleGroup` converter |
| `previousBaseline` | `FLOAT` | no | baseline before this write |
| `newBaseline` | `FLOAT` | no | baseline after this write |
| `changeReason` | `TEXT` | no | `MANUAL_OVERRIDE` or `PROGRESSION` |
| `feedbacks` | `TEXT` | yes | comma-separated `SetFeedback` names; `PROGRESSION` rows only |
| `sessionReps` | `INT` | yes | `PROGRESSION` rows only |
| `minReductionFraction` | `FLOAT` | yes | `PROGRESSION` rows only; only written when > 0 |
| `timestamp` | `LONG` | no | epoch millis |

`feedbacks` is a plain comma-separated string (no converter needed; human-readable in SQL). `changeReason` uses a new `BaselineChangeReason` enum with the existing `v.name`/`valueOf` converter pattern.

DB version bumps **7 → 8** with a `CREATE TABLE` migration.

## Write Sites

### `applySessionProgression` (WorkoutRepository)

Already reads `current` baseline before each upsert. After computing `newBaseline`, insert a `PROGRESSION` log row:
- `previousBaseline = current.baselineWeight`
- `newBaseline` = the computed value
- `feedbacks` = aggregated feedback list for that muscle group, joined as `","` 
- `sessionReps` = the session rep count
- `minReductionFraction` = the value if > 0, else null

### `startFirstExercise` → new `applyManualBaselineOverrides` (WorkoutRepository)

The direct DAO calls in `WorkoutSessionController.startFirstExercise` move into a new repository method:

```kotlin
suspend fun applyManualBaselineOverrides(sessionId: Long, overrides: Map<MuscleGroup, Float>)
```

For each entry in `overrides`:
1. Read current baseline from `muscleGroupStrengthDao` (use `0f` if no row exists yet)
2. Upsert the new value
3. Insert a `MANUAL_OVERRIDE` log row

`WorkoutSessionController` calls this method instead of the raw DAO loop. This centralises all writes to `muscle_group_strength` through `WorkoutRepository`.

## DAO

New `BaselineChangeLogDao`:

```kotlin
@Insert suspend fun insert(entry: BaselineChangeLog)
@Insert suspend fun insertAll(entries: List<BaselineChangeLog>)
@Query("SELECT * FROM baseline_change_log ORDER BY timestamp ASC")
suspend fun getAll(): List<BaselineChangeLog>
@Query("SELECT * FROM baseline_change_log WHERE sessionId = :sessionId")
suspend fun getForSession(sessionId: Long): List<BaselineChangeLog>
```

`AppDatabase` gains `fun baselineChangeLogDao(): BaselineChangeLogDao` and `BaselineChangeLog::class` in its `@Database` annotation.

## Files Changed

| File | Change |
|------|--------|
| `data/model/BaselineChangeReason.kt` | new enum |
| `data/model/BaselineChangeLog.kt` | new Room entity |
| `data/dao/BaselineChangeLogDao.kt` | new DAO |
| `data/Converters.kt` | add `BaselineChangeReason` converters |
| `data/AppDatabase.kt` | add entity + DAO accessor + MIGRATION_7_8 + bump version to 8 |
| `domain/WorkoutRepository.kt` | log in `applySessionProgression`; add `applyManualBaselineOverrides` |
| `ui/workout/WorkoutSessionController.kt` | call `applyManualBaselineOverrides` instead of raw DAO loop |

## Testing

Two JVM unit tests in `WorkoutRepositoryTest` using Room's in-memory database:

1. **`applySessionProgression logs PROGRESSION row`** — seed a `MuscleGroupStrength`, run a session with known feedbacks, call `applySessionProgression`, assert one log row with correct `previousBaseline`, `newBaseline`, `feedbacks`, `sessionReps`, and `changeReason = PROGRESSION`.

2. **`applyManualBaselineOverrides logs MANUAL_OVERRIDE row`** — seed a baseline, call the method with an override, assert one log row with correct before/after values, `changeReason = MANUAL_OVERRIDE`, and the correct `sessionId`.

## Non-Goals

- No UI for viewing the log (developer SQL analysis only at this stage).
- No `getForMuscleGroup` DAO query (easily done at query time in SQL).
- No per-user coefficient table yet — that is the next step this log enables.
