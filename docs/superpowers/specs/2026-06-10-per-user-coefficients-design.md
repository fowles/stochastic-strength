# Per-User Exercise Coefficients — Design Spec

**Date:** 2026-06-10  
**Status:** Approved

## Goal

Replace the global hardcoded `ExerciseCoefficients` with a system where per-exercise coefficients slowly adapt to the user's actual performance history. Coefficients update incrementally after each session and can be fully recomputed from history when heuristics change. Multiple pluggable heuristics are supported; cross-exercise reasoning (e.g. comparing related exercises within a muscle group over time) is a first-class concern.

## Background

`ExerciseCoefficients` is a global `object` with hardcoded `Map<String, Float>` values. The `CoefficientSource` interface and constructor injection on `WorkoutRepository` and `WorkoutPlanner` were introduced as DI seams for this feature. The `baseline_change_log` table (DB version 8) captures `previousBaseline` per session — the missing link needed to reconstruct what baseline was in effect during any given session, enabling coefficient derivation from history.

## Section 1: Data Model

New append-only entity `CoefficientChangeLog`, table `coefficient_change_log`.

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `id` | `LONG` | no | autoGenerate PK |
| `exerciseId` | `LONG` | no | indexed |
| `previousCoefficient` | `FLOAT` | yes | null on first write |
| `coefficient` | `FLOAT` | no | derived value |
| `heuristicName` | `TEXT` | no | which heuristic produced this |
| `heuristicMetadata` | `TEXT` | yes | heuristic-defined diagnostic string |
| `computedAt` | `LONG` | no | epoch millis |

Append-only — every coefficient update appends a new row. `previousCoefficient` is inline for quick diff reads. The current coefficient for an exercise is the row with the highest `id` for that `exerciseId`.

DB version bumps **8 → 9** with a `CREATE TABLE` migration.

## Section 2: Heuristic Interface

```kotlin
data class SetSnapshot(
    val targetWeight: Float,
    val feedback: SetFeedback?,
)

data class ExerciseSessionSnapshot(
    val exerciseId: Long,
    val sessionId: Long,
    val sessionTime: Long,        // epoch millis; history list is ordered ascending by this
    val targetReps: Int,
    val muscleBaseline: Float,    // previousBaseline from baseline_change_log PROGRESSION row
    val sets: List<SetSnapshot>,  // ordered by set number; weight may vary across sets
)

data class CoefficientComputationInput(
    val history: List<ExerciseSessionSnapshot>,  // all exercises, ascending by sessionTime
    val currentCoefficients: Map<Long, Float>,   // exerciseId → current coefficient (user or global fallback)
)

data class CoefficientResult(
    val exerciseId: Long,
    val coefficient: Float,
    val metadata: String? = null,
)

interface CoefficientHeuristic {
    val name: String
    fun compute(input: CoefficientComputationInput): List<CoefficientResult>
}
```

One `ExerciseSessionSnapshot` per session per exercise. The repository aggregates sets within a session (including mid-session weight reductions) into `List<SetSnapshot>`. `exerciseId` on the snapshot means heuristics can flatten `history` into a single list and still identify each exercise. Heuristics that want per-exercise history use `history.filter { it.exerciseId == id }`.

## Section 3: `UserCoefficientSource`

```kotlin
class UserCoefficientSource(
    private val userCoefficients: Map<Long, Float>,  // exerciseId → coefficient
    private val fallback: CoefficientSource = ExerciseCoefficients,
) : CoefficientSource {
    override fun get(exercise: Exercise): Float? =
        userCoefficients[exercise.id] ?: fallback.get(exercise)
}
```

`WorkoutRepository.buildPlanner` calls `db.coefficientChangeLogDao().getLatestPerExercise()`, builds the map, and constructs `UserCoefficientSource`. The existing `CoefficientSource` constructor parameter on `WorkoutRepository` is retained for test injection.

## Section 4: Update Flow

Both paths call the same method — the only difference is the trigger.

**`buildCoefficientInput(): CoefficientComputationInput`** (private, suspend):
1. Load all active exercises to build an `exerciseId → primaryMuscle` map; load all `workout_sets`; for each set look up `muscleGroup` via the map, then join against `baseline_change_log` PROGRESSION rows on `sessionId` + `muscleGroup` to get `muscleBaseline` per session per exercise
2. Build `ExerciseSessionSnapshot` list sorted ascending by `sessionTime`
3. Load `getLatestPerExercise()` from the log; fall back to `ExerciseCoefficients` for exercises with no user row; populate `currentCoefficients`

**`recomputeCoefficients()` (suspend)**:
1. Call `buildCoefficientInput()`
2. For each heuristic in `heuristics`, call `compute(input)` — collect all results
3. For each `exerciseId` that appears in any result, call `mergeHeuristicResults(candidates)` to select the final value
4. Upsert each merged result to `coefficient_change_log` (read previous row first to populate `previousCoefficient`)

**`mergeHeuristicResults(candidates: List<CoefficientResult>): CoefficientResult?`** — private function, default strategy: first non-null wins (heuristic list order is priority order). Returns null if candidates is empty; no upsert occurs for that exercise.

**Incremental**: `applySessionProgression` calls `recomputeCoefficients()` at the end.

**Rescan**: `recomputeCoefficients()` called directly — identical operation, explicit trigger.

## Section 5: Integration

**`WorkoutRepository` changes:**
- New constructor param: `heuristics: List<CoefficientHeuristic> = listOf()` — no default heuristic defined in this spec; heuristics are added separately and the system is inert until at least one is provided
- `buildPlanner` constructs `UserCoefficientSource` from latest log rows
- New private `buildCoefficientInput()`
- New `suspend fun recomputeCoefficients()`
- `applySessionProgression` calls `recomputeCoefficients()` at the end

**New DAO — `CoefficientChangeLogDao`:**
```kotlin
@Insert suspend fun insert(entry: CoefficientChangeLog)
@Query("SELECT * FROM coefficient_change_log ORDER BY computedAt ASC")
suspend fun getAll(): List<CoefficientChangeLog>
@Query("SELECT * FROM coefficient_change_log WHERE id IN (SELECT MAX(id) FROM coefficient_change_log GROUP BY exerciseId)")
suspend fun getLatestPerExercise(): List<CoefficientChangeLog>
```

**`StochasticStrengthApp`**: no changes — `WorkoutRepository` is constructed fresh per `WorkoutViewModel`; heuristics come from the default parameter.

## Section 6: Testing

**Unit tests (JVM):**
- `UserCoefficientSource` — user coefficient takes priority over global fallback; falls back when no user row exists
- `mergeHeuristicResults` — first-wins with one candidate; first-wins with multiple; null when list is empty
- `CoefficientHeuristic` implementations — pure functions, tested with hand-constructed `CoefficientComputationInput`

**Instrumented tests (Room in-memory):**
- `buildCoefficientInput` — seed `workout_sets` and `baseline_change_log` PROGRESSION rows; assert snapshots are assembled correctly, ordered by `sessionTime`, with correct `muscleBaseline` from the join
- `recomputeCoefficients` — seed history, run with a test heuristic returning a known result; assert log row has correct `coefficient`, null `previousCoefficient` on first run, populated `previousCoefficient` on second run
- `applySessionProgression` triggers coefficient update — complete a session end-to-end; assert a coefficient log row exists afterward

## Files Changed

| File | Change |
|------|--------|
| `data/model/CoefficientChangeLog.kt` | new Room entity |
| `data/dao/CoefficientChangeLogDao.kt` | new DAO |
| `data/AppDatabase.kt` | add entity + DAO accessor + MIGRATION_8_9 + bump version to 9 |
| `domain/CoefficientHeuristic.kt` | new — interface + input/result types |
| `domain/UserCoefficientSource.kt` | new — `CoefficientSource` backed by DB with global fallback |
| `domain/WorkoutRepository.kt` | add `heuristics` param, `buildCoefficientInput`, `recomputeCoefficients`, wire into `applySessionProgression` and `buildPlanner` |
| `test/.../UserCoefficientSourceTest.kt` | new unit test |
| `androidTest/.../WorkoutRepositoryTest.kt` | add three new instrumented tests |
