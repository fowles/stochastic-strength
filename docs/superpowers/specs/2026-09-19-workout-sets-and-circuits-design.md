# Workout sets and circuits — design

Date: 2026-09-19. Status: approved in brainstorming; pending spec review.

## Goal

Saved workouts and the plan preview gain two kinds of structure:

1. **Per-exercise set count** (1–10; today a global `PlannedExercise.DEFAULT_SETS = 3`).
2. **Circuits**: several exercises performed in sequence per round, e.g.
   `2 × (curl ×5, tricep kickback ×5, overhead press ×5)` runs
   curl₁ kick₁ press₁ curl₂ kick₂ press₂.

## Decisions (from brainstorming)

- **Rest after every set.** A circuit changes only the *order* of sets; the 90 s rest, feedback
  flow, and warmups are as today.
- **Full editing in both** the saved-workout editor and the plan preview.
- **One appearance per exercise** per workout/plan stays true: an exercise is either a solo row or a
  member of exactly one circuit. `exercise.id` remains the row identity.
- **Link toggle UI**: a ⛓ between adjacent rows joins/splits circuits.
- **Dragging moves a whole circuit**; drags never change membership.
- **Finished sessions remember circuits** via a column on `workout_sets`.
- **Swap gives remaining sets only** — for solo rows too (behavior change from today's fresh full
  count).
- **Architecture A**: flat tagged rows in storage / plan / view-model state; all order-and-progress
  logic reads a derived `blocks` view in which *everything is a circuit* (a solo row is a block of
  one).

## 1. Data model and migration

### Room v20 → v21

One `Migration`, `ALTER TABLE … ADD COLUMN` only:

| Table | Column | Meaning |
|---|---|---|
| `saved_workout_exercise` | `sets INTEGER NOT NULL DEFAULT 3` | Solo: set count. Circuit member: the circuit's rounds. |
| `saved_workout_exercise` | `circuitId INTEGER` (nullable) | Rows of one workout sharing a non-null value form a circuit. |
| `workout_sets` | `circuitId INTEGER` (nullable) | Circuit tag within the session; null = solo. All history is null. |

- `circuitId` values are small workout-/session-local ints, renumbered 0,1,2… on every save. No
  cross-workout meaning, no FK. Null means "its own block" — storage does **not** force a
  circuit id onto solo rows (no invented backfill for history or `history.json`).
- `workout_sets.setNumber` stays per-exercise, 1-based, dense. In a circuit it equals the round
  number. Undo, summary, `ActualRepsBackfill`, progression series, and BeliefFold rank are unchanged.
- `sets` is a plain non-null Int, range 1–10 (no "session default": there is no session sets slider).

### Domain

- `SavedWorkoutEntry(exercise, reps: Int?, sets: Int = 3, circuitId: Int? = null)`
- `PlannedExercise(..., sets: Int = DEFAULT_SETS, circuitId: Int? = null)`
- `DEFAULT_SETS` survives only as the default for generated / added / restocked rows. Its 12
  current read sites move to `planned.sets` or `WorkoutSequence`.

### `CircuitStructure` (pure, domain)

- `normalize(rows)`: a circuit is a **contiguous** run of rows sharing a `circuitId`; a tag that
  reappears after a gap starts a new circuit; a single-member circuit becomes solo (`null`); ids are
  renumbered densely in list order. It does **not** equalize `sets` (see §2 swap).
- `blocks(rows): List<Block>` where `Block(members, rounds)`; `rounds = max(member.sets)`; a solo
  row is a block of one. This is the only structure logic reads.
- Applied on every load, save, and plan mutation, so rows that vanish (deleted exercise in
  `toDetail`, unmatched name in additive import, append dedupe) leave a valid structure.

### Backup

- `WorkoutBackup.DB_VERSION = 21`. Parser accepts `dbVersion in 20..21`; reads `sets` with
  `optInt(…, 3)` and `circuitId` as optional (precedent: `isAsymmetric`). `<20` and `>21` still
  rejected with the existing message. `FORMAT_VERSION` stays 1. Existing export files keep working.
- Backtest `history.json` is untouched (absent `circuitId` = all solo).

## 2. Sequence and state machine

### `domain/WorkoutSequence`

```kotlin
data class Step(val exerciseIndex: Int, val setIndex: Int)
fun next(plan: WorkoutPlan, done: Map<Long, Int>): Step?   // null → finished
```

Take the first block (list order) with any member where `done < sets`. Within it pick the member
with the **most remaining sets** (`sets − done`), earliest on ties. For any authored circuit this is
round-robin in slot order; after swap / HURT / end-exercise leave members uneven it still preserves
slot order. Solo rows behave exactly as today. No `circuitId == null` branches.

### State

`ActiveSet` and `Resting` gain `done: Map<Long, Int>` (completed working sets per exercise id).
They keep `exerciseIndex`; `setIndex == done[ex]`; `totalSets = plannedExercise.sets`.

### Transitions

- **recordFeedback**: insert row as today with `setNumber = setIndex + 1` and
  `circuitId = planned.circuitId`; `done[ex]++`. HURT sets `done[ex] = sets` (member drops out of
  remaining rounds). Always → `Resting`, including after the final set.
- **advanceAfterRest**: `next(plan, done)` → `ActiveSet` (`warmupSetIndex = 0` when `setIndex == 0`
  and warmups exist — each circuit member warms up right before its round-1 set) or
  `finishWorkout`.
- **Undo**: delete the row, `done[ex] = row.setNumber − 1`, return to that `ActiveSet`.
- **Too-hard weight reduction**: `moreSetsForThisExercise = done[ex] < sets`; reduced weight
  carries into later rounds.
- **End exercise**: no logged sets → remove the row and normalize (a 2-circuit collapses to solo);
  logged → `done[ex] = sets`.
- **Swap** (one rule for every block): no logged sets → replace in place, inheriting `sets` and
  `circuitId`. Logged sets → original stays as an ended member (`done = sets`), replacement is
  inserted right after it in the same block with `sets = original.sets − done[original]`,
  `done = 0`. Warmups and dense `setNumber` work unchanged. No replacement found → as today
  (remove, or end if logged). **Behavior change**: a solo swap after 2 of 3 sets now gives 1 set
  of the replacement, not 3.
- **Staged actions**: `nextExerciseActiveSet` / commit targets derive "what's after this" from
  `next(...)` on the post-action plan and `done`, not `exerciseIndex + 1`.
- "Members share `sets`" is an **authoring** property kept by edit operations (§3), not a runtime
  invariant.

### One advance rule

The notification `upNextLabel`, `RestingContent`'s next-up card and remaining list
(`sets − done` per row), and the rest-quip muscle lookup all call `WorkoutSequence.next`. The three
existing copies of the rule are deleted.

### Labels

Block of one: "Set 2 of 4". Circuit member: "Round 2 of 2"; rest card "Next: Tricep kickback ·
Round 1". This `members.size > 1` check is display copy only.

### Duration

`DurationCalculator` receives `numSets = planned.sets` at the three planner sites. No circuit-specific
rest accounting (rest follows every set).

## 3. Editor and plan-preview UI

### List shape

The lazy list is **one item per block**; the existing reorderable `LazyColumn` reorders blocks.
Item key = smallest exercise id in the block (stable under block drag and member reorder).

- Solo block: today's row, with `sets − n +` and `reps − n +` (generalize `RepsStepper` →
  `CountStepper`; name on line 1, steppers on line 2).
- Circuit block: a card — header (drag handle · "Circuit" · `rounds − n +`) and a `Column` of
  member rows (reps stepper only). Dragging the header moves the whole circuit.
- Member order within a circuit: per-member handle + nested `ReorderableColumn`
  (sh.calvin.reorderable), constrained to the card. **Unverified** that nesting inside a lazy item
  works — first UI task is a spike; fallback is ↑/↓ buttons on member rows.
- ⛓ link toggles: one at the bottom of each block (links its last row to the next block's first
  row → merge / pull a solo in); one between members (tap → split). Drags never change membership.
- Swipe-to-remove (editor) and swipe-to-replace with the 4 s auto-skip (preview) stay per row,
  including inside a card.

### `domain/CircuitEdits` (pure; shared by both view models)

Over anything with `sets` + `circuitId`:

- `link(i)`: join row *i* with *i+1*. Neither in a circuit → new circuit, rounds = upper row's
  sets. One in a circuit → the other joins and adopts its rounds. Both → merge, upper's rounds win.
- `unlink(i)`: split there; a side left with one member becomes solo with `sets = rounds`.
- `moveBlock(from, to)`, `moveWithin(block, from, to)`, `remove(i)`.
- `setRounds(i, n)` writes `n` to every member of the block; `setSets(i, n)` for solo rows.
- Each ends in `CircuitStructure.normalize`.

### Plan preview

Same chrome and `CircuitEdits` via `WorkoutSessionController`. The static "3 sets × 10" becomes an
inline sets stepper; weight ± stays. Per-row reps stays out of preview (rep-range slider still
reprices every row). Header shows `Σ sets`.

- Count-slider trim removes tail rows (a circuit left with one member collapses); restock adds solo
  3-set rows.
- Location refresh, reprice, swipe-replace preserve `sets` / `circuitId`.
- **Load** carries `sets` / `circuitId` through `planExplicit`. **Append** offsets incoming circuit
  ids past the kept rows' ids so an adjacent kept circuit never merges with a loaded one.
- **"Save as workout…"** writes `sets` + `circuitId` (`reps` still null, as today).

### Library / picker

Subtitle "5 exercises · 1 circuit" when any circuit exists. `SavedWorkoutNaming` unchanged. Editor
helper text updated.

## 4. Downstream

- **Summary** (`WorkoutSummaryData`): first-appearance exercise order as today; a consecutive run of
  exercises sharing a `circuitId` renders under "Circuit · N rounds", member sections say "Round n".
- **Strava**: JSON unchanged (emits in id order → interleaved, faithful). `buildDescription` gets
  the same circuit grouping ("Circuit ×2" heading, members beneath).
- **`ExercisePacingEstimator`**: skip any pair where either row has `circuitId != null` (the gap
  contains other exercises' work). Circuit-only exercises fall back to default sec/rep.
- **`saveSessionAsWorkout`**: per exercise `reps` = first set's `targetReps` (as today), `sets` =
  logged row count (coerced 1–10), `circuitId` from the rows; a circuit's rounds = max over its
  members (a HURT-shortened member doesn't shrink it); first-set order; normalize.
- **Belief / policy / backtest**: no code change. Rank is per-exercise and id-ordered; the gate
  stays green with no re-baseline. Caveat, not acted on: rank counts only an exercise's own sets, so
  same-muscle circuit members don't fatigue each other in the model — already true of back-to-back
  same-muscle exercises today.
- **Unchanged**: `ActualRepsBackfill`, progression series builder, `PolicyFacts`, history quips
  text.
- **Housekeeping**: `DebugSeeder` uses `planned.sets`; CLAUDE.md (saved workouts, state machine, DB
  v21) updated.

## Testing

Pure JVM:
- `CircuitStructureTest` — contiguity, gap splits, single-member collapse, renumbering, `blocks`.
- `CircuitEditsTest` — link (new / join / merge), unlink, moveBlock, moveWithin, remove dissolves,
  setRounds writes all members.
- `WorkoutSequenceTest` — solo only, circuit interleave, mixed blocks, uneven `sets`/`done` after
  swap / HURT / end keeps slot order, finish.
- `ExercisePacingEstimatorTest` — circuit pairs skipped. `StravaDescriptionTest` — circuit grouping.
  Summary grouping test. `DurationCalculator`/planner tests for `planned.sets`.

Instrumented:
- `Migration20To21Test` (columns added, defaults, rows kept) + `MigrationTest` forward lists.
- `SavedWorkoutDaoTest` / `SavedWorkoutRepositoryTest` — round-trip `sets` + `circuitId`;
  dropped-exercise row leaves valid structure; `saveSessionAsWorkout` rebuilds sets + circuits.
- `BackupJsonTest` / `BackupManagerTest` — v20 file parses with defaults, v21 round-trips, v19/v22
  rejected, additive import keeps structure valid.
- `WorkoutSessionControllerTest` — circuit order end-to-end; HURT mid-circuit; undo across members;
  end-exercise and swap inside a circuit; solo swap gives remaining sets; `sets = 1` and `sets = 5`;
  `circuitId` written on logged rows; load carries structure; append offsets ids; trim collapses;
  `saveCurrentPlan` writes structure. Existing tests stay green (only the swap-with-logged-sets
  expectation may need its set count updated).
- `SavedWorkoutsViewModelsTest` — steppers and link edits feed `hasUnsavedChanges`; structure
  round-trips through save.

Gate: `BeliefScoreTest` and `BeliefPolicyBacktestTest` unchanged and green.

## Out of scope

- Back-to-back (no-rest) supersets or configurable rest per circuit.
- The same exercise appearing twice in a workout.
- Per-row reps editing in plan preview.
- Generated plans containing circuits or non-default set counts.
- Modeling cross-exercise fatigue inside circuits.
