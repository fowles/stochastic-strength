# Derived State Schema & Replay-Based Backfill Design

## Goal

Make derived workout state (per-muscle baseline, per-exercise coefficient, and their
history tables) a pure function of recorded inputs, so that the launch-time
backfill can run from any state and converge to the same result.

The path is a one-shot schema migration that cleanly separates **inputs**
(user-authored facts) from **derived state** (rebuilt by replay), followed by a
replay routine that wipes derived tables and rebuilds them by walking sessions
in order.

## Problem

Today's `recomputeDerivedState` is not idempotent in two ways:

1. **`recomputeCoefficients` always inserts a row** in `coefficient_change_log`
   per exercise per call, even when the new coefficient equals the latest stored
   value. Repeated calls bloat the table.
2. **`EstCoefConsensusHeuristic.damp` walks the current coefficient ~20% of the
   way toward the heuristic's target on each call**, stopping only when the step
   falls under `minRelativeChange`. With no new data, repeated calls keep moving
   the coefficient. The `SeedNormalizer` can re-trigger as cumulative drift
   crosses its 2 kg / 5 lb threshold, compounding the shift.

The current schema also gets in the way of a cleaner model:

- `baseline_change_log` mixes user intent (`MANUAL_OVERRIDE`) with derived
  output (`PROGRESSION`, `NORMALIZATION`).
- `Exercise.hurtFlag` is both auto-set by `applySessionProgression` and toggled
  by the user, so a replay that re-runs progression would re-set a user-cleared
  flag.
- The initial baseline (replay starting point) isn't stored anywhere; it's
  recomputed from `StartingWeights` whenever needed, which couples replay to the
  current `user_profile.strengthLevel`.
- `derivedStateVersion` is a band-aid for "we know `recomputeDerivedState`
  isn't safe to run again."

## Non-Goals

- No change to the user-facing planning, set-recording, or summary flows.
- No change to `EstCoefConsensusHeuristic`'s math beyond letting it derive its
  recency-decay "now" from the input rather than from `System.currentTimeMillis`.
- No event-sourced log for `hurtFlag` (just an input column with an `asOf`).
- No introduction of a DI framework or test-double infrastructure.

## End-State Schema

### Inputs (user-authored; never written by replay)

```
user_profile           id, sex, strengthLevel, weightUnit, preferredExerciseCount

exercise               id, name, primaryMuscle, secondaryMuscles, equipment,
                       seedCoefficient, isDisliked, isTimed, isUnilateral, ...
                       (hurtFlag column removed)

workout_session        id, startTime, endTime, locationId, stravaActivityId, ...

workout_set            id, sessionId, exerciseId, setNumber, targetWeight,
                       targetReps, actualReps, feedback, completedAt,
                       durationSeconds

baseline_override      id INTEGER PK AUTOINCREMENT,
                       sessionId INTEGER NULLABLE,   -- NULL = initial seed
                       muscleGroup TEXT NOT NULL,
                       baselineWeight REAL NOT NULL,
                       asOf INTEGER NOT NULL

exercise_hurt_state    exerciseId INTEGER PK,
                       isHurt INTEGER NOT NULL,
                       asOf INTEGER NOT NULL
```

Rules:

- `baseline_override` with `sessionId = NULL` is the **initial** baseline for a
  muscle — at most one such row per muscle. Replay seeds `muscle_group_strength`
  from these rows.
- `baseline_override` with `sessionId = N` is a **user adjustment at session N**.
  At most one row per `(sessionId, muscleGroup)` pair. Replay applies these
  before running that session's progression.
- `exercise_hurt_state` rows are written by two live paths only: a HURT
  feedback recorded during a set, or the user toggling the flag from the
  exercise detail screen. Replay never writes this table.
- `exercise_hurt_state` row lifecycle: a row is present iff the exercise has
  ever been marked. `isHurt` is the *current* state — `true` for marked,
  `false` for "marked at some point, then explicitly cleared." `asOf` is the
  timestamp of the most recent state change. "Never marked" is represented by
  the absence of a row, not by `isHurt = false`. Toggling off updates the
  existing row (UPSERT with `isHurt = false`, new `asOf`) rather than deleting,
  so a future feature can distinguish "never marked" from "cleared." Reads
  treat `no row` and `isHurt = false` identically as "not currently hurt."

### Derived (wiped and rebuilt by replay)

```
muscle_group_strength  muscleGroup PK, baselineWeight       -- "current" cache

baseline_history       id INTEGER PK AUTOINCREMENT,
                       sessionId INTEGER NULLABLE,
                       muscleGroup TEXT NOT NULL,
                       previousBaseline REAL NOT NULL,
                       newBaseline REAL NOT NULL,
                       changeReason TEXT NOT NULL,
                          -- enum: INITIAL | OVERRIDE | PROGRESSION | NORMALIZATION
                       feedbacks TEXT NULLABLE,
                       sessionReps INTEGER NULLABLE,
                       minReductionFraction REAL NULLABLE,
                       timestamp INTEGER NOT NULL

coefficient_history    id INTEGER PK AUTOINCREMENT,
                       exerciseId INTEGER NOT NULL,
                       previousCoefficient REAL NULLABLE,
                       coefficient REAL NOT NULL,
                       heuristicName TEXT NOT NULL,
                       heuristicMetadata TEXT NULLABLE,
                       computedAt INTEGER NOT NULL,
                       INDEX(exerciseId)
```

`baseline_history` and `coefficient_history` are renames of the existing
`baseline_change_log` and `coefficient_change_log` tables, with two changes:

- `MANUAL_OVERRIDE` is no longer a valid `changeReason` (those rows move to
  `baseline_override` as input).
- `INITIAL` is added as a `changeReason` for the seed rows written at the start
  of replay.

## Replay Algorithm

```
suspend fun WorkoutRepository.replayDerivedState() = db.withTransaction {
    baselineHistoryDao.deleteAll()
    coefficientHistoryDao.deleteAll()
    muscleGroupStrengthDao.deleteAll()

    // 1. Read the static, large inputs ONCE.
    val snapshot = ReplaySnapshot.loadStaticFromDb(db, coefficientSource)
    val initials = baselineOverrideDao.getInitials()           // sessionId IS NULL
    val overridesBySession = baselineOverrideDao.getNonInitials().groupBy { it.sessionId!! }

    // 2. Seed initials into muscle_group_strength + snapshot + baseline_history.
    for (init in initials) {
        snapshot.currentBaselines[init.muscleGroup] = init.baselineWeight
        muscleGroupStrengthDao.upsert(MuscleGroupStrength(init.muscleGroup, init.baselineWeight))
        baselineHistoryDao.insert(BaselineHistory(
            sessionId = null, muscleGroup = init.muscleGroup,
            previousBaseline = 0f, newBaseline = init.baselineWeight,
            changeReason = BaselineChangeReason.INITIAL, timestamp = init.asOf,
        ))
    }

    // 3. Walk sessions in (endTime, id) order.
    val sessions = workoutSessionDao.getAll()
        .filter { it.endTime != null }
        .sortedWith(compareBy({ it.endTime!! }, { it.id }))
    for (session in sessions) {
        overridesBySession[session.id]?.forEach { o ->
            val prev = snapshot.currentBaselines[o.muscleGroup] ?: 0f
            snapshot.currentBaselines[o.muscleGroup] = o.baselineWeight
            muscleGroupStrengthDao.upsert(MuscleGroupStrength(o.muscleGroup, o.baselineWeight))
            baselineHistoryDao.insert(BaselineHistory(
                sessionId = session.id, muscleGroup = o.muscleGroup,
                previousBaseline = prev, newBaseline = o.baselineWeight,
                changeReason = BaselineChangeReason.OVERRIDE, timestamp = o.asOf,
            ))
        }
        applySessionProgression(session.id, snapshot, asOf = session.endTime!!)
        // writes PROGRESSION baseline_history rows; updates snapshot.currentBaselines
        //   and snapshot.progressionBaselines
        // calls recomputeCoefficients(snapshot, asOf) which writes coefficient_history
        //   rows from snapshot.filteredCoefficientInput(asOf) and updates
        //   snapshot.currentCoefficients
        // calls applyBaselineNormalization(snapshot, asOf, session.id) which writes
        //   NORMALIZATION baseline_history rows + coefficient_history rows from
        //   snapshot.filteredNormalizationInput(asOf) and updates both maps
    }
}
```

Idempotence relies on three properties:

1. **Initial state is a deterministic function of inputs** — read from
   `baseline_override` rows with `sessionId IS NULL`.
2. **Session order is deterministic** — `(endTime, id)` lex sort.
3. **Each session step is deterministic given the prior derived state and
   `asOf`** — `applySessionProgression` reads input tables filtered to entries
   at or before `asOf` (sessions with `endTime ≤ asOf`, sets with `completedAt
   ≤ asOf`) plus the just-rebuilt derived state.
   `EstCoefConsensusHeuristic` derives its recency-decay clock from the maximum
   session time in its filtered input, so it has no implicit wall-clock
   dependency.
   The `currentCoefficients` map evolves across iterations because each step
   reads it from the growing `coefficient_history`.

## Behavioral Changes Required

### 1. Heuristic derives "now" from its input; replay filters input per session

`EstCoefConsensusHeuristic` currently takes a `now: () -> Long` constructor
parameter defaulting to `System::currentTimeMillis`, used for the recency
exponential decay in `computeH1`. Remove the parameter. The heuristic instead
derives its "now" from the input itself: the maximum session time across the
sessions it observes.

Concretely, in `EstCoefConsensusHeuristic.compute`, compute
`val now = input.sessionTimes.values.max()` (or return empty if the input has
no sessions), and use that in place of `now()` calls.

In addition, replay sees a filtered view per iteration: each session's step
sees only sessions and sets at or before that point in time. The filter rules:

- Sessions visible at `asOf`: those with `endTime ≤ asOf` (or `startTime ≤
  asOf` for sessions with no `endTime`, defensively).
- Sets visible at `asOf`: those with `completedAt ≤ asOf` (sets with null
  `completedAt` are included only if their session is visible).

This makes each per-session replay iteration see exactly the data that existed
up to that session's completion. The heuristic's proposal `T_N` for session N
is what it would have computed at the time, and `coefficient_history`
reconstructs the historical trajectory: each row reflects the coefficient as
of that session, given the data available then. Same for normalization rows.
Replay is deterministic and idempotent (the filter is a pure function of the
input tables), and the coefficient/baseline history tables are semantically
faithful — they show the user's actual training journey rather than a
numerical convergence curve.

#### Build-once, filter-in-memory

Naively rebuilding the input from DB inside the per-session loop is O(sessions
× sets) DB reads. For a heavy user (~200 sessions, ~2000 sets) that's a few
seconds inside the replay transaction. Replay runs at every session end, so
that cost would land on every workout — too slow.

Instead, replay loads its inputs **once** at the top of the transaction, holds
a `ReplaySnapshot` in memory for the duration of the loop, and updates it
incrementally as each session's step writes derived rows:

```
class ReplaySnapshot {
    // Static for the duration of replay (read once at top):
    val allSets: List<WorkoutSet>
    val allSessionTimes: Map<Long, Long>        // sessionId → startTime
    val exerciseMuscle: Map<Long, MuscleGroup>
    val seedCoefficients: Map<Long, Float>      // exerciseId → seed
    val allExercisesForNorm: List<Exercise>     // for SeedNormalizer's per-exercise lookup

    // Dynamic, updated incrementally as replay walks sessions:
    val currentCoefficients: MutableMap<Long, Float>      // exerciseId → current
    val currentBaselines: MutableMap<MuscleGroup, Float>  // muscle → current
    val progressionBaselines:
        MutableMap<Pair<Long, MuscleGroup>, Float>        // (sessionId, muscle) → previousBaseline

    fun filteredCoefficientInput(asOf: Long): CoefficientComputationInput
    fun filteredNormalizationInput(asOf: Long): BaselineNormalizationInput
}
```

`filteredCoefficientInput(asOf)` and `filteredNormalizationInput(asOf)` return
a `CoefficientComputationInput` / `BaselineNormalizationInput` shaped exactly
like today's `buildCoefficientInput` / `buildNormalizationInput` outputs, but
sourced from the snapshot:

- `sets` ← `allSets.filter { (it.completedAt ?: 0) ≤ asOf }`
- `sessionTimes` ← `allSessionTimes.filterValues { it ≤ asOf }`
- `baselines` ← the in-memory `progressionBaselines` map (only contains
  sessions already processed, all of which are ≤ asOf by construction)
- `currentCoefficients` ← the in-memory `currentCoefficients` map
- `exerciseMuscle`, `allExercises` ← the static fields

Filter cost per iteration: one linear pass over `allSets` (~2000 entries) and
`allSessionTimes` (~200 entries) — sub-millisecond. Total replay cost for
heavy users: tens to low-hundreds of milliseconds in steady state, dominated
by the writes themselves and one-time DB reads at the top.

#### Function shape changes

- `WorkoutRepository.buildCoefficientInput()` and `buildNormalizationInput()`
  are kept for any non-replay callers, but split so the construction logic is
  reusable: the DB-read parts move into helpers that fill in a
  `ReplaySnapshot`, and the existing public functions just call them and
  return the unfiltered input. (After this change there are no non-replay
  callers, so these become candidates for deletion in a follow-up; keeping
  them in this change keeps the diff focused.)
- `recomputeCoefficients` and `applyBaselineNormalization` gain a snapshot
  parameter and use it instead of calling the builders directly:
  - `recomputeCoefficients(snapshot, asOf)` reads
    `snapshot.filteredCoefficientInput(asOf)`, writes coefficient_history
    rows, mutates `snapshot.currentCoefficients` in place to match the writes.
  - `applyBaselineNormalization(snapshot, asOf, sessionId)` reads
    `snapshot.filteredNormalizationInput(asOf)`, writes the
    NORMALIZATION + coefficient_history rows, mutates
    `snapshot.currentBaselines` and `snapshot.currentCoefficients` to match.
- `applySessionProgression(sessionId, snapshot, asOf)` similarly takes the
  snapshot, mutates `snapshot.currentBaselines` and
  `snapshot.progressionBaselines` as it writes PROGRESSION rows.

### 2. `applySessionProgression` becomes the per-session step that replay loops

`applySessionProgression(sessionId, snapshot, asOf)` does exactly the work
that needs to run *once per session*:

- Compute progression baseline for each muscle affected by the session's
  feedback. Read `current` from `snapshot.currentBaselines[muscle]`. Write a
  PROGRESSION row to `baseline_history`, upsert `muscle_group_strength`,
  update `snapshot.currentBaselines[muscle]` and
  `snapshot.progressionBaselines[(sessionId, muscle)]`.
- Call `recomputeCoefficients(snapshot, asOf)`.
- Call `applyBaselineNormalization(snapshot, asOf, sessionId)`.

Critically it **no longer calls `replayDerivedState` (nor the old
`recomputeDerivedState`)** — that would be cyclic, since `replayDerivedState`
calls `applySessionProgression` in a loop. The old internal call chain
`applySessionProgression → recomputeDerivedState → recomputeCoefficients /
applyBaselineNormalization` flattens to `applySessionProgression →
recomputeCoefficients + applyBaselineNormalization` directly.

It also no longer mutates `exercise.hurtFlag` (see §3). The legacy
`actualReps` backfill (`ActualRepsBackfill`) continues to run from
`DerivedStateBackfill` as today — it writes input data (`workout_set.actualReps`)
and is idempotent (skips rows that already have a value), so it's safe to run
on every launch.

### 3. Hurt-flag side effect moves out of progression

When recording a set with HURT feedback, the live set-recording path writes
`exercise_hurt_state.isHurt = true, asOf = set.completedAt` directly.
`ExerciseDetailViewModel.toggleHurtFlag` writes to `exercise_hurt_state`
instead of `exercise`.

`applySessionProgression` no longer mutates `exercise_hurt_state`. Replay can
safely re-run it for every session.

### 4. Manual override write path

`WorkoutSessionController.startSession` calls
`repository.applyManualBaselineOverrides(sessionId, plan.strengthOverrides)`.

New semantics: this writes only `baseline_override` rows (input) with
`asOf = session.startTime` for replay-stability. It no longer touches
`muscle_group_strength` or any history table. Those are derived; they get
populated when `replayDerivedState` next iterates and sees the override row.

Behavior note: if the user cancels the session before completing it (so
`endTime` stays null), the override row exists but is never applied by replay
(which filters to `endTime != null`). This matches the user's intent: an
override for "this workout" without a completed workout doesn't stick. The
override row stays in the DB and would apply if the same session is later
resumed and completed (its sessionId would then have an endTime). Pre-migration
behavior wrote the override straight to `muscle_group_strength` so it stuck
regardless; the new behavior is the intended cleanup.

### 5. `recomputeDerivedState` is removed; callers split

The two existing callers of `recomputeDerivedState` get different replacements:

- The internal call inside `applySessionProgression` is removed (the work it
  used to trigger now happens inline; see §2).
- `DerivedStateBackfill` calls `replayDerivedState()` directly.
- The live session-end pipeline (currently invokes `applySessionProgression`)
  switches to calling `replayDerivedState()`. Replay will iterate all sessions,
  including the one just finished, and emit identical derived state in one
  shot.

Performance note: end-of-session now triggers a full replay rather than an
incremental coefficient recompute. With the build-once snapshot from §1,
expected cost for a heavy user (~200 sessions, ~2000 sets) is in the tens to
low hundreds of milliseconds inside the replay transaction — DB reads happen
once; per-iteration work is in-memory filtering and the writes themselves.

### 6. `DerivedStateBackfill` simplifies

```kotlin
class DerivedStateBackfill(
    private val database: AppDatabase,
    private val repository: WorkoutRepository,
) {
    suspend fun run() {
        val profile = database.userProfileDao().getProfile() ?: return
        ActualRepsBackfill(database, profile.weightUnit).run()
        repository.replayDerivedState()
    }
}
```

No version counter. Both steps are idempotent: `ActualRepsBackfill` already
skips rows with non-null `actualReps`; `replayDerivedState` is deterministic
from inputs.

### 7. Serialization

A `Mutex` on `WorkoutRepository` serializes `replayDerivedState` calls so the
launch-time backfill and a session-end progression cannot interleave their
clear-and-rebuild transactions.

## Migration v11 → v12 (Rewritten In-Place)

The currently in-tree `MIGRATION_11_12` is replaced. Schema version stays at 12;
the v12 schema JSON file in `app/schemas/` is regenerated to match the new
end-state schema. No `derivedStateVersion` column is added at any point.

```sql
-- 1. user_profile: drop actualRepsBackfilled (added in v11), recreate-table
CREATE TABLE user_profile_new (
    id INTEGER NOT NULL,
    sex TEXT NOT NULL,
    strengthLevel TEXT NOT NULL,
    weightUnit TEXT NOT NULL,
    preferredExerciseCount INTEGER,
    PRIMARY KEY(id)
);
INSERT INTO user_profile_new (id, sex, strengthLevel, weightUnit, preferredExerciseCount)
    SELECT id, sex, strengthLevel, weightUnit, preferredExerciseCount FROM user_profile;
DROP TABLE user_profile;
ALTER TABLE user_profile_new RENAME TO user_profile;

-- 2. baseline_override (new input table)
CREATE TABLE baseline_override (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sessionId INTEGER,
    muscleGroup TEXT NOT NULL,
    baselineWeight REAL NOT NULL,
    asOf INTEGER NOT NULL
);

-- 3. Move MANUAL_OVERRIDE rows from baseline_change_log into baseline_override.
--    Migration uses the existing log timestamp (wall-clock at original write
--    time) for asOf, rather than the session's startTime. Going forward, the
--    live `applyManualBaselineOverrides` path will use `session.startTime`
--    (see §4). The mismatch is harmless: `baseline_override.asOf` is used
--    only as the `timestamp` field stamped on the derived OVERRIDE
--    history-row at replay time, for display ordering. Replay derivation
--    itself doesn't read asOf.
INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf)
    SELECT sessionId, muscleGroup, newBaseline, timestamp
    FROM baseline_change_log
    WHERE changeReason = 'MANUAL_OVERRIDE';

-- 4. Synthesize initial baselines per muscle.
--    For muscles that appear in baseline_change_log, take the earliest row's
--    previousBaseline (the value before the first recorded change).
INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf)
    SELECT NULL, b1.muscleGroup, b1.previousBaseline, 0
    FROM baseline_change_log b1
    WHERE b1.id = (
        SELECT MIN(b2.id) FROM baseline_change_log b2
        WHERE b2.muscleGroup = b1.muscleGroup
    );

--    For muscles that have a current baseline but no log rows, copy current.
INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf)
    SELECT NULL, muscleGroup, baselineWeight, 0
    FROM muscle_group_strength
    WHERE muscleGroup NOT IN (SELECT DISTINCT muscleGroup FROM baseline_change_log);

-- 5. exercise_hurt_state (new input table)
CREATE TABLE exercise_hurt_state (
    exerciseId INTEGER PRIMARY KEY NOT NULL,
    isHurt INTEGER NOT NULL,
    asOf INTEGER NOT NULL
);
INSERT INTO exercise_hurt_state (exerciseId, isHurt, asOf)
    SELECT id, hurtFlag, 0 FROM exercises WHERE hurtFlag = 1;

-- 6. exercises: drop hurtFlag (recreate-table). Preserve every other column.
CREATE TABLE exercises_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    -- ... all current columns except hurtFlag, in their current order/types ...
);
INSERT INTO exercises_new (...) SELECT (...) FROM exercises;
DROP TABLE exercises;
ALTER TABLE exercises_new RENAME TO exercises;

-- 7. Remove migrated MANUAL_OVERRIDE rows from the (about-to-be-renamed) history.
DELETE FROM baseline_change_log WHERE changeReason = 'MANUAL_OVERRIDE';

-- 8. Rename derived-state tables.
ALTER TABLE baseline_change_log RENAME TO baseline_history;
ALTER TABLE coefficient_change_log RENAME TO coefficient_history;
```

After migration completes, the planner still has a working
`muscle_group_strength` table (untouched), and the renamed history tables are
readable but reflect the pre-migration derived state. The next launch's
`DerivedStateBackfill.run()` calls `replayDerivedState`, which wipes and
rebuilds the derived tables from the now-cleanly-separated inputs.

The exact column list for the `exercises_new` recreate is determined by the
v11 `exercises` schema minus `hurtFlag`. The implementation should preserve
columns in source-of-truth order and types.

## Net Code Changes

### Data layer

- New `BaselineOverride` entity + DAO with `getInitials()`, `getNonInitials()`,
  `upsertInitial(muscle, baseline, asOf)`, `upsertForSession(sessionId, muscle,
  baseline, asOf)`.
- New `ExerciseHurtState` entity + DAO with `get(exerciseId)`, `setHurt(...)`,
  `clearHurt(...)`.
- Rename `BaselineChangeLog` → `BaselineHistory`; rename `BaselineChangeLogDao`
  → `BaselineHistoryDao`. Add `INITIAL`, `OVERRIDE` to `BaselineChangeReason`
  (remove `MANUAL_OVERRIDE`).
- Rename `CoefficientChangeLog` → `CoefficientHistory`; rename
  `CoefficientChangeLogDao` → `CoefficientHistoryDao`.
- Update `AppDatabase` `entities = […]`, swap the migration, regenerate v12
  schema JSON.
- Remove `Exercise.hurtFlag` field.
- Remove `UserProfile.derivedStateVersion` (never lands).

### Domain layer

- New `WorkoutRepository.replayDerivedState()` (the algorithm above).
- Remove `WorkoutRepository.recomputeDerivedState`; call sites switch to
  `replayDerivedState`.
- `applyManualBaselineOverrides` writes only `baseline_override` rows (input)
  with `asOf = session.startTime` for replay-stability.
- `applySessionProgression` no longer mutates `exercise.hurtFlag`. Signature
  becomes `applySessionProgression(sessionId, snapshot, asOf)`.
- `EstCoefConsensusHeuristic` loses its `now: () -> Long` constructor parameter;
  `compute` derives its "now" from `input.sessionTimes.values.max()`. Callers
  no longer pass a clock; tests that need a specific clock control it via the
  session times they put in the input.
- New `ReplaySnapshot` class in the domain layer with the shape described in
  §1. Static fields populated by `ReplaySnapshot.loadStaticFromDb(db,
  coefficientSource)`; dynamic maps mutated by the replay loop.
- `recomputeCoefficients(snapshot, asOf)` and
  `applyBaselineNormalization(snapshot, asOf, sessionId)` use
  `snapshot.filteredCoefficientInput(asOf)` /
  `snapshot.filteredNormalizationInput(asOf)` for their inputs and mutate the
  snapshot's dynamic maps to reflect the writes.
- `WorkoutRepository.buildCoefficientInput()` and `buildNormalizationInput()`
  stay only as long as they have callers; given session-end now goes through
  replay, they end up with zero non-test callers and can be removed in this
  change (or in an immediate follow-up; either is fine, but the spec assumes
  removal here so the design surface is smaller).
- `Mutex` on `WorkoutRepository` to serialize replays.
- `DerivedStateBackfill` collapses to the two-line `run()` shown above. Remove
  `CURRENT_VERSION` and the `when` arm.

### UI layer

- `WorkoutSessionController.recordFeedback` (or its set-recording helper) writes
  `exercise_hurt_state.isHurt = true, asOf = set.completedAt` when feedback ==
  HURT.
- `ExerciseDetailViewModel.toggleHurtFlag` writes to `exercise_hurt_state`
  instead of `exercise`.
- Exercise list/detail screens read `isHurt` from `exercise_hurt_state` (joined
  in DAO query or read separately).

## Test Plan

### Migration tests (instrumented)

- `migrate11To12_dropsActualRepsBackfilled`: v11 seeded with a `user_profile`
  row → after migration, column gone.
- `migrate11To12_movesManualOverridesIntoOverrideTable`: v11 with a
  MANUAL_OVERRIDE row in `baseline_change_log` → after migration, that row is
  in `baseline_override` (with the same sessionId, muscle, weight, timestamp →
  asOf) and gone from `baseline_history`.
- `migrate11To12_synthesizesInitialBaselineFromHistory`: v11 with a chain of
  PROGRESSION rows → after migration, `baseline_override` has one
  `sessionId = NULL` row per muscle whose weight equals the earliest log row's
  `previousBaseline`.
- `migrate11To12_synthesizesInitialBaselineFromCurrentWhenNoHistory`: v11 with
  a `muscle_group_strength` row but no log entries for that muscle → after
  migration, `baseline_override` has a `sessionId = NULL` row matching the
  current baseline.
- `migrate11To12_migratesHurtFlag`: v11 with `hurtFlag = 1` on an exercise →
  after migration, `exercise_hurt_state` has a row for that exercise; `hurtFlag`
  column is gone from `exercises`.
- `migrate11To12_renamesHistoryTables`: assert `baseline_change_log` and
  `coefficient_change_log` no longer exist; `baseline_history` and
  `coefficient_history` do.

### Replay determinism (instrumented)

- `replay_isIdempotent`: seed a small history. Call `replayDerivedState` twice.
  Assert row sets in `muscle_group_strength`, `baseline_history`,
  `coefficient_history` are byte-equal between runs.
- `replay_producesExpectedShape`: a 3-session history with mixed feedback;
  assert `baseline_history` contains exactly one INITIAL row per touched muscle,
  zero or more OVERRIDE rows (from seeded overrides), and PROGRESSION /
  NORMALIZATION rows matching `applySessionProgression` semantics.
- `replay_appliesManualOverridesAtSessionBoundary`: seed an override at
  session N → after replay, baseline at the start of session N's progression
  equals the override value, and the resulting PROGRESSION row's
  `previousBaseline` reflects it.
- `replay_reconstructsHistoricalTrajectory`: seed a 5-session history with two
  distinct training phases (e.g., sessions 1–2 with high TOO_HARD failures,
  sessions 3–5 with confident RIR signals). After replay, `coefficient_history`
  rows for the affected exercise must show the coefficient dipping during
  phase 1 and recovering during phase 2 — not a single monotonic curve from
  seed to the final value. Confirms the per-iteration `asOf` filter is
  actually shaping the input.

### Live-flow correctness (instrumented)

- `recordHurtFeedback_writesHurtState`: simulate the session-recording path
  with a HURT feedback set; assert `exercise_hurt_state` row appears.
- `applySessionProgression_doesNotMutateHurtState`: pre-seed hurt state, run
  progression for a session containing HURT feedback (separately recorded);
  assert `exercise_hurt_state` is unchanged (the write happens earlier in the
  pipeline now).
- `manualOverride_endToEnd`: simulate `applyManualBaselineOverrides` →
  `applySessionProgression` → assert derived state matches expected.

### Existing test updates

- `DerivedStateBackfillTest`: remove version-arm tests, replace with "runs
  ActualRepsBackfill + replay; running twice keeps state stable; first run
  rebuilds derived tables from a v12-empty starting point."
- Any test referencing `BaselineChangeLog`, `CoefficientChangeLog`,
  `recomputeDerivedState`, `hurtFlag` on `Exercise`, or `MANUAL_OVERRIDE` rows:
  update for renames and shape changes.

## Risks and Mitigations

- **Heuristic now-parameterization causes small shifts vs current state.**
  First replay after migration computes each session's coefficients with that
  session's endTime as the heuristic's clock, instead of wall-clock-at-call.
  For each session-end live call, the wall clock was a few seconds after
  `endTime` — close. For older sessions, the absolute gap is larger but those
  sessions also contribute less to current state via recency decay. The
  expected drift in current coefficients is small (well under the
  `minRelativeChange = 0.005` threshold for most cases), and acceptable per
  the "best-effort snapshot, then replay" choice. Filtered replay further
  bounds this by reproducing the historical damping trajectory rather than
  collapsing to the all-data target.
- **Replay cost on session end.** The build-once `ReplaySnapshot` (§1) keeps
  DB reads O(1) at the top of replay and per-iteration work in-memory; total
  replay cost for heavy users is in the tens to low hundreds of milliseconds.
  If this regresses past acceptable bounds, the next lever is a "tail-only"
  replay that processes only sessions newer than a stored watermark; that
  requires a new column and is out of scope here.
- **The synthesized initial baseline may not match `StartingWeights` for some
  users.** That's intentional: the actual first observed baseline (from the
  log) is more truthful than the recomputed seed.
- **Concurrent replays.** A launch-time backfill and a session-end progression
  could try to replay simultaneously. Serialized by a `Mutex` on
  `WorkoutRepository`.
