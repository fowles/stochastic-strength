# Workout Sets and Circuits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Saved workouts and the plan preview gain per-exercise set counts (1–10) and circuits (`2 × (curl, kickback, press)` runs curl₁ kick₁ press₁ curl₂ kick₂ press₂), persisted through sessions, backup, summary and Strava.

**Architecture:** Rows stay flat everywhere (Room, `WorkoutPlan.exercises`, view-model state), each tagged with `sets` and a nullable `circuitId`. All logic reads a derived `CircuitStructure.blocks` view in which a solo row is a block of one. One pure function, `WorkoutSequence.next(exercises, done)`, replaces the three existing copies of the "what comes next" rule; session progress is a `done: Map<exerciseId, Int>` carried on `ActiveSet`/`Resting`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room (v20 → v21), `sh.calvin.reorderable`, JUnit4 (JVM unit tests in `app/src/test`, instrumented in `app/src/androidTest`), jj for version control.

**Spec:** `docs/superpowers/specs/2026-09-19-workout-sets-and-circuits-design.md` — read it first.

## Global Constraints

- Package root: `io.github.fowles.stochastic_strength`. Source root abbreviations used below:
  `MAIN` = `app/src/main/java/io/github/fowles/stochastic_strength`,
  `TEST` = `app/src/test/java/io/github/fowles/stochastic_strength`,
  `ATEST` = `app/src/androidTest/java/io/github/fowles/stochastic_strength`.
- `sets` range is **1–10** (`CircuitStructure.MIN_SETS` / `MAX_SETS`). Default 3.
- An exercise appears **at most once** per workout/plan; `exercise.id` stays the row identity.
- Rest follows **every** set (90 s). No circuit-specific rest logic anywhere.
- `workout_sets.setNumber` stays per-exercise, 1-based, dense. Never repurpose it.
- The app has real users: the DB change is a proper `Migration(20, 21)`; no destructive fallback.
- Do **not** touch `domain/belief/`, `domain/policy/`, `BeliefConfig`, or `src/test/resources/backtest/history.json`. `BeliefScoreTest` and `BeliefPolicyBacktestTest` must stay green with no re-baseline.
- Stay inside the repo. Do not read or write `~/Library`, `~/.gradle`, `/tmp`. Do not launch an emulator; if `connectedAndroidTest` finds no device, report that and stop — do not try to start one.
- Commands (run from repo root):
  - JVM single class: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.<pkg>.<Class>"`
  - Instrumented single class: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.<pkg>.<Class>`
  - Build: `./gradlew :app:assembleDebug`
- Version control is **jj**. Commit at the end of every task with `jj commit -m "<message>"` (commits the whole working copy). End every commit message with a blank line and
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never push.
- Bugs noticed out of scope go in `CLAUDE_TODO.md` at the repo root, not into this change.
- Match surrounding code style: comment density, naming, 4-space indent, trailing commas.

## File Structure

Create:
- `MAIN/data/model/CircuitRow.kt` — the `sets` + `circuitId` trait shared by plan rows, saved entries and saved DB rows.
- `MAIN/domain/CircuitStructure.kt` — `Block`, `blocks`, `normalize`, `concat`, `equalizeRounds`, `circuitCount`.
- `MAIN/domain/CircuitEdits.kt` — `link`, `unlink`, `moveBlock`, `remove`, `setRounds`.
- `MAIN/domain/WorkoutSequence.kt` — `next`, `positionLabel`.
- `MAIN/ui/components/CircuitChrome.kt` — `CountStepper`, `LinkToggle`, `CircuitHeader` composables.
- Tests: `TEST/domain/CircuitStructureTest.kt`, `TEST/domain/CircuitEditsTest.kt`, `TEST/domain/WorkoutSequenceTest.kt`, `TEST/ui/SummaryBlocksTest.kt`, `ATEST/data/Migration20To21Test.kt`.

Modify: `SavedWorkout.kt`, `WorkoutSet.kt`, `AppDatabase.kt`, `SavedWorkoutDetail.kt`, `PlannedExercise.kt`, `WorkoutRepository.kt`, `WorkoutBackup.kt`, `BackupJson.kt`, `BackupManager.kt`, `WorkoutPlanner.kt`, `WorkoutState.kt`, `WorkoutSessionController.kt`, `WorkoutViewModel.kt`, `WorkoutScreen.kt`, `PlanPreviewContent.kt`, `ActiveSetContent.kt`, `RestingContent.kt`, `SavedWorkoutEditViewModel.kt`, `SavedWorkoutEditScreen.kt`, `SavedWorkoutsScreen.kt`, `SavedWorkoutPickerDialog.kt`, `ExercisePacingEstimator.kt`, `WorkoutSummaryData.kt`, `WorkoutSummaryContent.kt`, `ExerciseSetSection.kt`, `StravaExporter.kt`, `DebugSeeder.kt`, `CLAUDE.md`, plus the matching tests.

---

### Task 1: `CircuitRow`, model fields, `CircuitStructure`

**Files:**
- Create: `MAIN/data/model/CircuitRow.kt`, `MAIN/domain/CircuitStructure.kt`, `TEST/domain/CircuitStructureTest.kt`
- Modify: `MAIN/domain/model/PlannedExercise.kt`, `MAIN/domain/model/SavedWorkoutDetail.kt`

**Interfaces:**
- Produces:
  - `interface CircuitRow<T> { val sets: Int; val circuitId: Int?; fun withStructure(sets: Int, circuitId: Int?): T }`
  - `data class Block(val start: Int, val size: Int, val rounds: Int)` with `indices: IntRange`, `last: Int`, `isCircuit: Boolean`
  - `object CircuitStructure { MIN_SETS; MAX_SETS; blocks(rows); normalize(rows); concat(head, tail); equalizeRounds(rows); circuitCount(rows) }` — all generic over `T : CircuitRow<T>`, all take/return `List<T>` (except `blocks` → `List<Block>`, `circuitCount` → `Int`).
  - `PlannedExercise.sets: Int`, `PlannedExercise.circuitId: Int?`; `SavedWorkoutEntry.sets: Int`, `SavedWorkoutEntry.circuitId: Int?`.

- [ ] **Step 1: Write the failing test** — `TEST/domain/CircuitStructureTest.kt`

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.CircuitRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Minimal [CircuitRow] for structure tests; [tag] identifies the row across edits. */
data class TestRow(val tag: String, override val sets: Int = 3, override val circuitId: Int? = null) :
    CircuitRow<TestRow> {
    override fun withStructure(sets: Int, circuitId: Int?) = copy(sets = sets, circuitId = circuitId)
}

class CircuitStructureTest {
    private fun ids(rows: List<TestRow>) = rows.map { it.circuitId }

    @Test
    fun blocks_soloRowsAreBlocksOfOne() {
        val blocks = CircuitStructure.blocks(listOf(TestRow("a", sets = 4), TestRow("b")))
        assertEquals(listOf(Block(0, 1, 4), Block(1, 1, 3)), blocks)
    }

    @Test
    fun blocks_contiguousSharedIdIsOneCircuit_roundsIsMaxSets() {
        val rows = listOf(TestRow("a"), TestRow("b", 2, 7), TestRow("c", 1, 7), TestRow("d"))
        assertEquals(listOf(Block(0, 1, 3), Block(1, 2, 2), Block(3, 1, 3)), CircuitStructure.blocks(rows))
    }

    @Test
    fun normalize_tagReappearingAfterGapStartsNewCircuit_andSinglesCollapse() {
        val rows = listOf(TestRow("a", 3, 0), TestRow("b"), TestRow("c", 3, 0))
        assertEquals(listOf(null, null, null), ids(CircuitStructure.normalize(rows)))
    }

    @Test
    fun normalize_renumbersDenselyInListOrder() {
        val rows = listOf(
            TestRow("a", 3, 9), TestRow("b", 3, 9), TestRow("c"),
            TestRow("d", 3, 4), TestRow("e", 3, 4),
        )
        assertEquals(listOf(0, 0, null, 1, 1), ids(CircuitStructure.normalize(rows)))
    }

    @Test
    fun normalize_doesNotEqualizeSets() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 1, 0))
        assertEquals(listOf(2, 1), CircuitStructure.normalize(rows).map { it.sets })
    }

    @Test
    fun concat_shiftsTailIdsSoAdjacentCircuitsStayDistinct() {
        val head = listOf(TestRow("a", 3, 0), TestRow("b", 3, 0))
        val tail = listOf(TestRow("c", 2, 0), TestRow("d", 2, 0))
        assertEquals(listOf(0, 0, 1, 1), ids(CircuitStructure.concat(head, tail)))
    }

    @Test
    fun equalizeRounds_writesBlockMaxToEveryMember() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 1, 0), TestRow("c", 5))
        assertEquals(listOf(2, 2, 5), CircuitStructure.equalizeRounds(rows).map { it.sets })
    }

    @Test
    fun circuitCount_countsOnlyMultiMemberBlocks() {
        val rows = listOf(TestRow("a", 3, 0), TestRow("b", 3, 0), TestRow("c"))
        assertEquals(1, CircuitStructure.circuitCount(rows))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.CircuitStructureTest"`
Expected: compilation FAILS — `CircuitRow`, `CircuitStructure`, `Block` unresolved.

- [ ] **Step 3: Implement**

`MAIN/data/model/CircuitRow.kt`:
```kotlin
package io.github.fowles.stochastic_strength.data.model

/**
 * A row that takes part in workout structure: `sets` is its set count (for a circuit member, the
 * circuit's rounds); rows that are adjacent and share a non-null `circuitId` form one circuit.
 */
interface CircuitRow<T> {
    val sets: Int
    val circuitId: Int?
    fun withStructure(sets: Int, circuitId: Int?): T
}
```

`MAIN/domain/CircuitStructure.kt`:
```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.CircuitRow

/** A run of rows done round-robin. A solo row is a block of one; everything is a circuit. */
data class Block(val start: Int, val size: Int, val rounds: Int) {
    val indices: IntRange get() = start until start + size
    val last: Int get() = start + size - 1
    val isCircuit: Boolean get() = size > 1
}

object CircuitStructure {
    const val MIN_SETS = 1
    const val MAX_SETS = 10

    /** Contiguous rows sharing a non-null `circuitId` are one block; `rounds` is the largest member `sets`. */
    fun <T : CircuitRow<T>> blocks(rows: List<T>): List<Block> {
        val out = mutableListOf<Block>()
        var start = 0
        while (start < rows.size) {
            val id = rows[start].circuitId
            var end = start + 1
            if (id != null) while (end < rows.size && rows[end].circuitId == id) end++
            out += Block(start, end - start, (start until end).maxOf { rows[it].sets })
            start = end
        }
        return out
    }

    /** Solo rows get a null id; circuits are renumbered 0, 1, 2… in list order. Never touches `sets`. */
    fun <T : CircuitRow<T>> normalize(rows: List<T>): List<T> {
        var nextId = 0
        return blocks(rows).flatMap { b ->
            val id = if (b.isCircuit) nextId++ else null
            b.indices.map { i -> if (rows[i].circuitId == id) rows[i] else rows[i].withStructure(rows[i].sets, id) }
        }
    }

    /** Joins two row lists without letting `tail`'s circuit ids collide with `head`'s. */
    fun <T : CircuitRow<T>> concat(head: List<T>, tail: List<T>): List<T> {
        val shift = (head.mapNotNull { it.circuitId }.maxOrNull() ?: -1) + 1
        return normalize(head + tail.map { r -> r.circuitId?.let { r.withStructure(r.sets, it + shift) } ?: r })
    }

    /** Every member takes its block's rounds. For authored data; a live session may be uneven. */
    fun <T : CircuitRow<T>> equalizeRounds(rows: List<T>): List<T> =
        blocks(rows).flatMap { b -> b.indices.map { rows[it].withStructure(b.rounds, rows[it].circuitId) } }

    fun <T : CircuitRow<T>> circuitCount(rows: List<T>): Int = blocks(rows).count { it.isCircuit }
}
```

`MAIN/domain/model/PlannedExercise.kt` — add two constructor fields after `estimatedSeconds`, implement the trait (add import `io.github.fowles.stochastic_strength.data.model.CircuitRow`):
```kotlin
data class PlannedExercise(
    val exercise: Exercise,
    val sessionWeight: Float = 0f,
    val sessionReps: Int = 10,
    val warmupSets: List<WarmupSet> = emptyList(),
    val estimatedSeconds: Int = 0,
    override val sets: Int = DEFAULT_SETS,
    override val circuitId: Int? = null,
) : CircuitRow<PlannedExercise> {
    override fun withStructure(sets: Int, circuitId: Int?) = copy(sets = sets, circuitId = circuitId)
```
and change the companion comment to:
```kotlin
    companion object {
        /** Set count for rows the app adds on its own (generated, added, restocked). */
        const val DEFAULT_SETS = 3
    }
```

`MAIN/domain/model/SavedWorkoutDetail.kt` — replace the entry class (add the `CircuitRow` import):
```kotlin
/** One row of a saved workout with its exercise resolved. `reps == null` = session decides. */
data class SavedWorkoutEntry(
    val exercise: Exercise,
    val reps: Int?,
    override val sets: Int = PlannedExercise.DEFAULT_SETS,
    override val circuitId: Int? = null,
) : CircuitRow<SavedWorkoutEntry> {
    override fun withStructure(sets: Int, circuitId: Int?) = copy(sets = sets, circuitId = circuitId)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.CircuitStructureTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(circuits): CircuitRow trait and CircuitStructure blocks/normalize

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `CircuitEdits`

**Files:**
- Create: `MAIN/domain/CircuitEdits.kt`, `TEST/domain/CircuitEditsTest.kt`

**Interfaces:**
- Consumes: `CircuitRow<T>`, `CircuitStructure.blocks/normalize/MIN_SETS/MAX_SETS`, `TestRow` (from `CircuitStructureTest.kt`, same package).
- Produces: `object CircuitEdits { link(rows, i); unlink(rows, i); moveBlock(rows, fromBlock, toBlock); remove(rows, i); setRounds(rows, i, n) }` — all `(List<T>, …) -> List<T>`, all return a normalized list, all return the input unchanged on out-of-range indices. `i` is a **row** index; `fromBlock`/`toBlock` are **block** indices. `setRounds` on a solo row sets its `sets` (the spec's `setSets` — one function covers both).

- [ ] **Step 1: Write the failing test** — `TEST/domain/CircuitEditsTest.kt`

```kotlin
package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitEditsTest {
    private fun shape(rows: List<TestRow>) = rows.map { Triple(it.tag, it.sets, it.circuitId) }

    @Test
    fun link_twoSolos_makesCircuitWithUpperRowsSets() {
        val out = CircuitEdits.link(listOf(TestRow("a", 4), TestRow("b", 2), TestRow("c")), 0)
        assertEquals(listOf(Triple("a", 4, 0), Triple("b", 4, 0), Triple("c", 3, null)), shape(out))
    }

    @Test
    fun link_soloAboveCircuit_joinsAndAdoptsCircuitRounds() {
        val rows = listOf(TestRow("a", 5), TestRow("b", 2, 0), TestRow("c", 2, 0))
        assertEquals(listOf(2, 2, 2), CircuitEdits.link(rows, 0).map { it.sets })
        assertEquals(listOf(0, 0, 0), CircuitEdits.link(rows, 0).map { it.circuitId })
    }

    @Test
    fun link_circuitAboveSolo_joinsAndAdoptsCircuitRounds() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0), TestRow("c", 5))
        assertEquals(listOf(2, 2, 2), CircuitEdits.link(rows, 1).map { it.sets })
    }

    @Test
    fun link_twoCircuits_mergeWithUpperRounds() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0), TestRow("c", 4, 1), TestRow("d", 4, 1))
        val out = CircuitEdits.link(rows, 1)
        assertEquals(listOf(0, 0, 0, 0), out.map { it.circuitId })
        assertEquals(listOf(2, 2, 2, 2), out.map { it.sets })
    }

    @Test
    fun link_insideOneBlock_orOutOfRange_isNoOp() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0))
        assertEquals(rows, CircuitEdits.link(rows, 0))
        assertEquals(rows, CircuitEdits.link(rows, 1))
    }

    @Test
    fun unlink_splitsAndSinglesBecomeSoloKeepingRounds() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0), TestRow("c", 2, 0))
        val out = CircuitEdits.unlink(rows, 0)
        assertEquals(listOf(Triple("a", 2, null), Triple("b", 2, 0), Triple("c", 2, 0)), shape(out))
    }

    @Test
    fun unlink_betweenBlocks_isNoOp() {
        val rows = listOf(TestRow("a"), TestRow("b"))
        assertEquals(rows, CircuitEdits.unlink(rows, 0))
    }

    @Test
    fun moveBlock_movesWholeCircuit_andNeverChangesMembership() {
        val rows = listOf(TestRow("a"), TestRow("b", 2, 0), TestRow("c", 2, 0), TestRow("d"))
        val out = CircuitEdits.moveBlock(rows, fromBlock = 1, toBlock = 0)
        assertEquals(listOf("b", "c", "a", "d"), out.map { it.tag })
        assertEquals(listOf(0, 0, null, null), out.map { it.circuitId })
    }

    @Test
    fun moveBlock_staleNonContiguousTagsDoNotMergeWhenMadeAdjacent() {
        // "a" and "c" carry the same stale id but are not a circuit; moving "b" away must not fuse them.
        val rows = listOf(TestRow("a", 3, 0), TestRow("b"), TestRow("c", 3, 0))
        val out = CircuitEdits.moveBlock(rows, fromBlock = 1, toBlock = 2)
        assertEquals(listOf(null, null, null), out.map { it.circuitId })
    }

    @Test
    fun remove_downToOneMember_dissolvesTheCircuit() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0))
        assertEquals(listOf(Triple("b", 2, null)), shape(CircuitEdits.remove(rows, 0)))
    }

    @Test
    fun setRounds_writesEveryMember_andClampsToRange() {
        val rows = listOf(TestRow("a", 2, 0), TestRow("b", 2, 0), TestRow("c"))
        assertEquals(listOf(10, 10, 3), CircuitEdits.setRounds(rows, 1, 99).map { it.sets })
        assertEquals(listOf(2, 2, 1), CircuitEdits.setRounds(rows, 2, 0).map { it.sets })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.CircuitEditsTest"`
Expected: compilation FAILS — `CircuitEdits` unresolved.

- [ ] **Step 3: Implement** — `MAIN/domain/CircuitEdits.kt`

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.CircuitRow

/**
 * The editing vocabulary for workout structure, shared by the saved-workout editor and the plan
 * preview. Row arguments are row indices; [moveBlock] takes block indices. Every result is
 * normalized. Drags never change membership — only [link] and [unlink] do.
 */
object CircuitEdits {
    private fun <T : CircuitRow<T>> freshId(rows: List<T>): Int =
        (rows.mapNotNull { it.circuitId }.maxOrNull() ?: -1) + 1

    /** Joins row [i] and row [i]+1 (the rows either side of a block boundary) into one circuit. */
    fun <T : CircuitRow<T>> link(rows0: List<T>, i: Int): List<T> {
        if (i < 0 || i + 1 >= rows0.size) return rows0
        val rows = CircuitStructure.normalize(rows0)
        val blocks = CircuitStructure.blocks(rows)
        val upper = blocks.first { i in it.indices }
        val lower = blocks.first { i + 1 in it.indices }
        if (upper == lower) return rows0
        // A solo joining a circuit adopts the circuit's rounds; otherwise the upper block wins.
        val rounds = if (!upper.isCircuit && lower.isCircuit) lower.rounds else upper.rounds
        val id = freshId(rows)
        return CircuitStructure.normalize(rows.mapIndexed { idx, r ->
            if (idx in upper.indices || idx in lower.indices) r.withStructure(rounds, id) else r
        })
    }

    /** Splits a circuit between row [i] and row [i]+1. A side left with one member becomes solo. */
    fun <T : CircuitRow<T>> unlink(rows0: List<T>, i: Int): List<T> {
        if (i < 0 || i + 1 >= rows0.size) return rows0
        val rows = CircuitStructure.normalize(rows0)
        val block = CircuitStructure.blocks(rows).first { i in it.indices }
        if (i + 1 !in block.indices) return rows0
        val id = freshId(rows)
        return CircuitStructure.normalize(rows.mapIndexed { idx, r ->
            if (idx in (i + 1)..block.last) r.withStructure(r.sets, id) else r
        })
    }

    fun <T : CircuitRow<T>> moveBlock(rows0: List<T>, fromBlock: Int, toBlock: Int): List<T> {
        val rows = CircuitStructure.normalize(rows0)
        val blocks = CircuitStructure.blocks(rows).toMutableList()
        if (fromBlock !in blocks.indices || toBlock !in blocks.indices) return rows0
        blocks.add(toBlock, blocks.removeAt(fromBlock))
        return CircuitStructure.normalize(blocks.flatMap { b -> b.indices.map { rows[it] } })
    }

    fun <T : CircuitRow<T>> remove(rows: List<T>, i: Int): List<T> =
        if (i !in rows.indices) rows
        else CircuitStructure.normalize(CircuitStructure.normalize(rows).filterIndexed { idx, _ -> idx != i })

    /** Sets the rounds of row [i]'s block — for a solo row, simply its set count. */
    fun <T : CircuitRow<T>> setRounds(rows0: List<T>, i: Int, n: Int): List<T> {
        if (i !in rows0.indices) return rows0
        val rows = CircuitStructure.normalize(rows0)
        val block = CircuitStructure.blocks(rows).first { i in it.indices }
        val v = n.coerceIn(CircuitStructure.MIN_SETS, CircuitStructure.MAX_SETS)
        return rows.mapIndexed { idx, r -> if (idx in block.indices) r.withStructure(v, r.circuitId) else r }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.CircuitEditsTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(circuits): CircuitEdits link/unlink/moveBlock/remove/setRounds

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `WorkoutSequence`

**Files:**
- Create: `MAIN/domain/WorkoutSequence.kt`, `TEST/domain/WorkoutSequenceTest.kt`

**Interfaces:**
- Consumes: `PlannedExercise.sets/circuitId`, `CircuitStructure.blocks`.
- Produces:
  - `WorkoutSequence.Step(exerciseIndex: Int, setIndex: Int)`
  - `WorkoutSequence.next(exercises: List<PlannedExercise>, done: Map<Long, Int>): Step?` — `done` is completed working sets keyed by `exercise.id`; `null` means the workout is finished.
  - `WorkoutSequence.positionLabel(exercises: List<PlannedExercise>, exerciseIndex: Int, setIndex: Int): String` — `"Set 2 of 4"` or `"Round 2 of 2"`.

- [ ] **Step 1: Write the failing test** — `TEST/domain/WorkoutSequenceTest.kt`

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.WorkoutSequence.Step
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutSequenceTest {
    private fun pe(id: Long, sets: Int = 3, circuitId: Int? = null) = PlannedExercise(
        exercise = Exercise(id = id, name = "E$id", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.DUMBBELL),
        sets = sets, circuitId = circuitId,
    )

    /** Walks the sequence to the end, returning exercise ids in performance order. */
    private fun walk(exercises: List<PlannedExercise>, start: Map<Long, Int> = emptyMap()): List<Long> {
        val done = start.toMutableMap()
        val order = mutableListOf<Long>()
        while (true) {
            val step = WorkoutSequence.next(exercises, done) ?: return order
            val id = exercises[step.exerciseIndex].exercise.id
            assertEquals("setIndex must equal done count", done[id] ?: 0, step.setIndex)
            order += id
            done[id] = step.setIndex + 1
        }
    }

    @Test
    fun soloRows_runEachExercisesSetsContiguously() {
        assertEquals(listOf(1L, 1L, 2L, 2L, 2L), walk(listOf(pe(1, sets = 2), pe(2, sets = 3))))
    }

    @Test
    fun circuit_interleavesMembersInSlotOrderPerRound() {
        val plan = listOf(pe(1, 2, 0), pe(2, 2, 0), pe(3, 2, 0))
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), walk(plan))
    }

    @Test
    fun mixedBlocks_finishOneBlockBeforeTheNext() {
        val plan = listOf(pe(1, 1), pe(2, 2, 0), pe(3, 2, 0), pe(4, 1))
        assertEquals(listOf(1L, 2L, 3L, 2L, 3L, 4L), walk(plan))
    }

    @Test
    fun memberEndedEarly_dropsOutOfLaterRounds() {
        // HURT / end-exercise marks a member done: done == sets.
        val plan = listOf(pe(1, 3, 0), pe(2, 3, 0))
        assertEquals(listOf(1L, 1L), walk(plan, start = mapOf(1L to 1, 2L to 3)))
    }

    @Test
    fun swappedInMemberWithFewerSets_keepsItsSlotOrder() {
        // Round 1 done by 1, 9(original), 3. 9 was swapped for 2, which owes the remaining 2 of 3 rounds.
        val plan = listOf(pe(1, 3, 0), pe(9, 3, 0), pe(2, 2, 0), pe(3, 3, 0))
        val start = mapOf(1L to 1, 9L to 3, 3L to 1)
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), walk(plan, start))
    }

    @Test
    fun next_isNullWhenEverythingIsDone() {
        assertNull(WorkoutSequence.next(listOf(pe(1, 1)), mapOf(1L to 1)))
        assertNull(WorkoutSequence.next(emptyList(), emptyMap()))
    }

    @Test
    fun next_reportsIndexAndSetIndex() {
        val plan = listOf(pe(1, 2, 0), pe(2, 2, 0))
        assertEquals(Step(exerciseIndex = 1, setIndex = 0), WorkoutSequence.next(plan, mapOf(1L to 1)))
    }

    @Test
    fun positionLabel_soloSaysSet_circuitSaysRound() {
        val plan = listOf(pe(1, 4), pe(2, 2, 0), pe(3, 2, 0))
        assertEquals("Set 2 of 4", WorkoutSequence.positionLabel(plan, 0, 1))
        assertEquals("Round 2 of 2", WorkoutSequence.positionLabel(plan, 2, 1))
    }

    @Test
    fun positionLabel_swappedInMemberCountsRoundsFromTheCircuitsTotal() {
        val plan = listOf(pe(1, 3, 0), pe(2, 2, 0)) // 2 joined at round 2 of 3
        assertEquals("Round 2 of 3", WorkoutSequence.positionLabel(plan, 1, 0))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutSequenceTest"`
Expected: compilation FAILS — `WorkoutSequence` unresolved.

- [ ] **Step 3: Implement** — `MAIN/domain/WorkoutSequence.kt`

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.domain.model.PlannedExercise

/**
 * The one rule for what comes next in a workout. Position is never stored: it is derived from the
 * plan and how many working sets each exercise has completed, so undo, swap, HURT and end-exercise
 * are just edits to those two inputs.
 */
object WorkoutSequence {
    data class Step(val exerciseIndex: Int, val setIndex: Int)

    /**
     * The first block with work left; within it, the member with the most sets remaining (earliest
     * on ties). For an authored circuit that is round-robin in slot order; it also keeps slot order
     * once a swap or an early end has left members uneven.
     */
    fun next(exercises: List<PlannedExercise>, done: Map<Long, Int>): Step? {
        fun remaining(i: Int) = exercises[i].sets - (done[exercises[i].exercise.id] ?: 0)
        for (block in CircuitStructure.blocks(exercises)) {
            val pick = block.indices.filter { remaining(it) > 0 }.maxByOrNull { remaining(it) } ?: continue
            return Step(pick, done[exercises[pick].exercise.id] ?: 0)
        }
        return null
    }

    /** "Set 2 of 4" for a solo row, "Round 2 of 2" for a circuit member. Display copy only. */
    fun positionLabel(exercises: List<PlannedExercise>, exerciseIndex: Int, setIndex: Int): String {
        val block = CircuitStructure.blocks(exercises).first { exerciseIndex in it.indices }
        val sets = exercises[exerciseIndex].sets
        return if (block.isCircuit) "Round ${block.rounds - sets + setIndex + 1} of ${block.rounds}"
        else "Set ${setIndex + 1} of $sets"
    }
}
```
(`maxByOrNull` returns the first maximal element, which is the "earliest on ties" rule.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutSequenceTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(circuits): WorkoutSequence.next and positionLabel

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Room v21 — columns, migration, schema

**Files:**
- Modify: `MAIN/data/model/SavedWorkout.kt`, `MAIN/data/model/WorkoutSet.kt`, `MAIN/data/AppDatabase.kt`, `ATEST/data/MigrationTest.kt`, `ATEST/data/SavedWorkoutDaoTest.kt`
- Create: `ATEST/data/Migration20To21Test.kt`, generated `app/schemas/io.github.fowles.stochastic_strength.data.AppDatabase/21.json`

**Interfaces:**
- Produces: `SavedWorkoutExercise.sets: Int` (default 3), `SavedWorkoutExercise.circuitId: Int?`, `WorkoutSet.circuitId: Int?`, `AppDatabase.MIGRATION_20_21` (`internal`), DB `version = 21`. `SavedWorkoutExercise` implements `CircuitRow<SavedWorkoutExercise>`.

- [ ] **Step 1: Write the failing migration test** — `ATEST/data/Migration20To21Test.kt`

```kotlin
package io.github.fowles.stochastic_strength.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration20To21Test {
    private val dbName = "migration-20-21-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate20To21_addsStructureColumns_andKeepsExistingRowsAsThreeStraightSets() {
        helper.createDatabase(dbName, 20).use { v20 ->
            v20.execSQL("INSERT INTO saved_workout (name, createdAt) VALUES ('Push', 5)")
            v20.execSQL(
                "INSERT INTO saved_workout_exercise (workoutId, exerciseId, position, reps) VALUES (1, 7, 0, NULL)"
            )
            v20.execSQL(
                "INSERT INTO workout_sets (sessionId, exerciseId, setNumber, targetWeight, targetReps) " +
                    "VALUES (1, 7, 1, 40.0, 5)"
            )
        }
        val v21 = helper.runMigrationsAndValidate(dbName, 21, true, AppDatabase.MIGRATION_20_21)
        v21.query("SELECT sets, circuitId FROM saved_workout_exercise").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
            assertTrue(c.isNull(1))
        }
        v21.query("SELECT circuitId FROM workout_sets").use { c ->
            assertTrue(c.moveToFirst()); assertTrue(c.isNull(0))
        }
        v21.close()
    }
}
```

- [ ] **Step 2: Implement entities + migration**

`MAIN/data/model/SavedWorkout.kt` — replace `SavedWorkoutExercise` (add imports `androidx.room.ColumnInfo`):
```kotlin
/**
 * One row of a [SavedWorkout]. `reps == null` means "use the session's rep pick". Adjacent rows
 * sharing a non-null [circuitId] form a circuit whose rounds are [sets].
 */
@Entity(tableName = "saved_workout_exercise", indices = [Index("workoutId")])
data class SavedWorkoutExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: Long,
    val position: Int,
    val reps: Int?,
    @ColumnInfo(defaultValue = "3") override val sets: Int = 3,
    override val circuitId: Int? = null,
) : CircuitRow<SavedWorkoutExercise> {
    override fun withStructure(sets: Int, circuitId: Int?) = copy(sets = sets, circuitId = circuitId)
}
```
(The `@ColumnInfo(defaultValue = "3")` is required: Room validates the migrated column's `DEFAULT 3` against the entity.)

`MAIN/data/model/WorkoutSet.kt` — add as the last field:
```kotlin
    /** Session-local circuit tag; null for a straight set (and for all history before DB v21). */
    val circuitId: Int? = null,
```

`MAIN/data/AppDatabase.kt` — `version = 21`; after `MIGRATION_19_20` add:
```kotlin
        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `saved_workout_exercise` ADD COLUMN `sets` INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE `saved_workout_exercise` ADD COLUMN `circuitId` INTEGER")
                db.execSQL("ALTER TABLE `workout_sets` ADD COLUMN `circuitId` INTEGER")
            }
        }
```
and append `MIGRATION_20_21` to the `.addMigrations(...)` list in `buildDatabase`.

- [ ] **Step 3: Generate the v21 schema**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL and a new `app/schemas/io.github.fowles.stochastic_strength.data.AppDatabase/21.json`. Verify with `ls app/schemas/*/21.json`. This file is committed.

- [ ] **Step 4: Update the forward-migration lists in `MigrationTest`**

In `ATEST/data/MigrationTest.kt`, every `.addMigrations(... AppDatabase.MIGRATION_19_20)` and every `runMigrationsAndValidate(..., AppDatabase.MIGRATION_19_20)` that migrates to the *current* version must end with `, AppDatabase.MIGRATION_20_21`, and any literal target version `20` passed to `runMigrationsAndValidate` in those same calls becomes `21`. Find them with Grep pattern `MIGRATION_19_20` in that file (6 sites at the time of writing). Do not change `Migration19To20Test`, which deliberately stops at 20.

- [ ] **Step 5: Extend the DAO round-trip test**

In `ATEST/data/SavedWorkoutDaoTest.kt` add (use the file's existing `db`/`dao` fixture names):
```kotlin
    @Test
    fun exerciseRows_roundTripSetsAndCircuitId() = runBlocking {
        val workoutId = dao.insert(SavedWorkout(name = "Arms", createdAt = 1L))
        dao.insertExerciseRows(listOf(
            SavedWorkoutExercise(workoutId = workoutId, exerciseId = 1, position = 0, reps = 5, sets = 2, circuitId = 0),
            SavedWorkoutExercise(workoutId = workoutId, exerciseId = 2, position = 1, reps = null),
        ))
        val rows = dao.getExerciseRows(workoutId)
        assertEquals(listOf(2, 3), rows.map { it.sets })
        assertEquals(listOf(0, null), rows.map { it.circuitId })
    }
```

- [ ] **Step 6: Run the instrumented tests**

Run each:
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.data.Migration20To21Test`
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.data.MigrationTest`
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.data.SavedWorkoutDaoTest`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat(db): v21 adds sets/circuitId to saved rows and circuitId to workout_sets

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Repository — structure through save, load, and save-session-as-workout

**Files:**
- Modify: `MAIN/domain/WorkoutRepository.kt:314-355`, `ATEST/domain/SavedWorkoutRepositoryTest.kt`

**Interfaces:**
- Consumes: `CircuitStructure.normalize/equalizeRounds/MIN_SETS/MAX_SETS`, `SavedWorkoutEntry.sets/circuitId`, `SavedWorkoutExercise.sets/circuitId`, `WorkoutSet.circuitId`.
- Produces: `getSavedWorkout`/`observeSavedWorkouts` return normalized entries carrying `sets`/`circuitId`; `saveWorkout` persists them (normalized); `saveSessionAsWorkout` rebuilds sets + circuits. Signatures unchanged.

- [ ] **Step 1: Write the failing tests** — add to `ATEST/domain/SavedWorkoutRepositoryTest.kt` (reuse the file's existing fixture for `db`, `repository`, and its way of inserting exercises; the three exercise ids below are whatever that fixture yields — bind them to `a`, `b`, `c` as `Exercise` values)

```kotlin
    @Test
    fun saveWorkout_roundTripsSetsAndCircuits_normalized() = runBlocking {
        val id = repository.saveWorkout(null, "Arms", listOf(
            SavedWorkoutEntry(a, reps = 5, sets = 2, circuitId = 7),
            SavedWorkoutEntry(b, reps = 5, sets = 2, circuitId = 7),
            SavedWorkoutEntry(c, reps = null, sets = 4),
        ))
        val entries = repository.getSavedWorkout(id)!!.entries
        assertEquals(listOf(2, 2, 4), entries.map { it.sets })
        assertEquals(listOf(0, 0, null), entries.map { it.circuitId })
    }

    @Test
    fun getSavedWorkout_circuitThatLosesAMemberCollapsesToSolo() = runBlocking {
        val id = repository.saveWorkout(null, "Pair", listOf(
            SavedWorkoutEntry(a, reps = null, sets = 2, circuitId = 0),
            SavedWorkoutEntry(b, reps = null, sets = 2, circuitId = 0),
        ))
        db.exerciseDao().delete(b)   // use the DAO's existing delete; if it is deleteById, call that
        val entries = repository.getSavedWorkout(id)!!.entries
        assertEquals(listOf(a.id), entries.map { it.exercise.id })
        assertEquals(listOf<Int?>(null), entries.map { it.circuitId })
        assertEquals(listOf(2), entries.map { it.sets })
    }

    @Test
    fun saveSessionAsWorkout_rebuildsSetCountsAndCircuits() = runBlocking {
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1L))
        var t = 1000L
        suspend fun log(ex: Exercise, setNumber: Int, circuit: Int?) = db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = ex.id, setNumber = setNumber, targetWeight = 20f,
            targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = t++, circuitId = circuit,
        ))
        // 2 × (a, b) where b was cut short in round 2, then 4 straight sets of c.
        log(a, 1, 0); log(b, 1, 0); log(a, 2, 0)
        repeat(4) { log(c, it + 1, null) }

        val id = repository.saveSessionAsWorkout(sessionId, "From session")
        val entries = repository.getSavedWorkout(id)!!.entries
        assertEquals(listOf(a.id, b.id, c.id), entries.map { it.exercise.id })
        assertEquals("a circuit's rounds are its longest member's", listOf(2, 2, 4), entries.map { it.sets })
        assertEquals(listOf(0, 0, null), entries.map { it.circuitId })
    }
```
(If the exercise DAO has no delete method, grep `ExerciseDao` for the delete it does have — the existing test `observeSavedWorkouts_dropsRowsWhoseExerciseIsGone` in this same file already removes an exercise; copy its call.)

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.domain.SavedWorkoutRepositoryTest`
Expected: the three new tests FAIL (sets come back 3 / circuitId null).

- [ ] **Step 3: Implement** — in `MAIN/domain/WorkoutRepository.kt`

Replace `toDetail`:
```kotlin
    private fun SavedWorkout.toDetail(rows: List<SavedWorkoutExercise>, byId: Map<Long, Exercise>) =
        SavedWorkoutDetail(
            id = id,
            name = name,
            // Normalized after the drop, so a circuit that lost a member is still a valid structure.
            entries = CircuitStructure.normalize(
                rows.sortedBy { it.position }.mapNotNull { r ->
                    byId[r.exerciseId]?.let { SavedWorkoutEntry(it, r.reps, r.sets, r.circuitId) }
                }
            ),
        )
```
In `saveWorkout`, replace the `insertExerciseRows` call:
```kotlin
        dao.insertExerciseRows(CircuitStructure.normalize(entries).mapIndexed { i, e ->
            SavedWorkoutExercise(
                workoutId = workoutId, exerciseId = e.exercise.id, position = i, reps = e.reps,
                sets = e.sets.coerceIn(CircuitStructure.MIN_SETS, CircuitStructure.MAX_SETS),
                circuitId = e.circuitId,
            )
        })
```
Replace `saveSessionAsWorkout`:
```kotlin
    /**
     * Captures a completed session as a saved workout: exercises in order of first set, each with
     * the first set's target reps, its logged set count, and its circuit. A circuit's rounds are its
     * longest member's count, so a member cut short (HURT, ended early) doesn't shrink the circuit.
     */
    suspend fun saveSessionAsWorkout(sessionId: Long, name: String): Long {
        val setsByExercise = db.workoutSetDao().getSetsForSession(sessionId)
            .sortedWith(compareBy({ it.completedAt ?: Long.MAX_VALUE }, { it.id }))
            .groupBy { it.exerciseId } // first-appearance order
        val byId = db.exerciseDao().getByIds(setsByExercise.keys.toList()).associateBy { it.id }
        val entries = setsByExercise.mapNotNull { (exerciseId, rows) ->
            byId[exerciseId]?.let {
                SavedWorkoutEntry(
                    exercise = it, reps = rows.first().targetReps,
                    sets = rows.size.coerceIn(CircuitStructure.MIN_SETS, CircuitStructure.MAX_SETS),
                    circuitId = rows.first().circuitId,
                )
            }
        }
        return saveWorkout(null, name, CircuitStructure.equalizeRounds(CircuitStructure.normalize(entries)))
    }
```

- [ ] **Step 4: Run to verify they pass**

Run the same command as Step 2. Expected: whole class PASSES, including the pre-existing `saveSessionAsWorkout_ordersByFirstSet_andRecordsFirstSetTargetReps`.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(saved-workouts): persist sets and circuits; rebuild them from a session

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Backup — new fields, v20..v21 window, additive import keeps structure valid

**Files:**
- Modify: `MAIN/domain/backup/WorkoutBackup.kt`, `MAIN/domain/backup/BackupJson.kt:87-93,110-115,129-135,183-189,213-217`, `MAIN/domain/backup/BackupManager.kt:131-137`, `ATEST/domain/backup/BackupJsonTest.kt`, `ATEST/domain/backup/BackupManagerTest.kt`

**Interfaces:**
- Produces: `WorkoutBackup.DB_VERSION = 21`, `WorkoutBackup.MIN_DB_VERSION = 20`. JSON keys `"sets"` and `"circuitId"` on `savedWorkoutExercises`, `"circuitId"` on `workoutSets`.

- [ ] **Step 1: Write the failing tests** — add to `ATEST/domain/backup/BackupJsonTest.kt`

```kotlin
    /** A v20 export: no `sets`/`circuitId` keys anywhere. */
    private val v20Json = """
        {"format":"stochastic-strength-backup","formatVersion":1,"dbVersion":20,"exportedAt":1,
         "tables":{"exercises":[],"knownLocations":[],"locationExcludedExercises":[],
           "workoutSessions":[],"userProfile":[],"baselineOverrides":[],"exerciseHurtState":[],
           "workoutSets":[{"id":1,"sessionId":1,"exerciseId":1,"setNumber":1,"targetWeight":40.0,
             "targetReps":5,"actualReps":5,"feedback":"RIR_2_4","completedAt":9,"durationSeconds":null}],
           "savedWorkouts":[{"id":1,"name":"Push","createdAt":1}],
           "savedWorkoutExercises":[{"id":1,"workoutId":1,"exerciseId":1,"position":0,"reps":null}]}}
    """.trimIndent()

    @Test
    fun parse_acceptsV20File_defaultingStructureFields() {
        val backup = BackupJsonParser.parse(v20Json)
        assertEquals(3, backup.savedWorkoutExercises.single().sets)
        assertNull(backup.savedWorkoutExercises.single().circuitId)
        assertNull(backup.workoutSets.single().circuitId)
    }

    @Test
    fun parse_rejectsVersionsOutsideTheWindow() {
        for (v in listOf(19, 22)) {
            val ex = assertThrows(BackupFormatException::class.java) {
                BackupJsonParser.parse("""{"format":"stochastic-strength-backup","dbVersion":$v,"tables":{}}""")
            }
            assert(ex.message!!.contains("v$v"))
        }
    }
```
Also extend the existing `roundTrip_preservesEveryField`: in its fixture give one `SavedWorkoutExercise` `sets = 2, circuitId = 0` and one `WorkoutSet` `circuitId = 0` (the test's whole-object equality then covers the new fields). Add imports `org.junit.Assert.assertNull` / `assertEquals` if missing.

Add to `ATEST/domain/backup/BackupManagerTest.kt` (model it on the existing `additiveImport_remapsSavedWorkoutExercisesByName_andSkipsSameName` — same fixture style; the backup must contain a saved workout with a 2-member circuit where one member's exercise name does **not** exist locally and cannot be created, exactly the situation that test already constructs for a dropped row):
```kotlin
    @Test
    fun additiveImport_circuitThatLosesAMember_importsAsSolo() = runBlocking {
        // Arrange exactly as additiveImport_remapsSavedWorkoutExercisesByName_andSkipsSameName does
        // for its unresolvable row, but tag both backup rows sets = 2, circuitId = 0.
        // Act: manager.importAdditive(backup)
        // Assert on the imported workout's rows:
        val rows = db.savedWorkoutDao().getAllExerciseRows()
        assertEquals(1, rows.size)
        assertNull(rows.single().circuitId)
        assertEquals(2, rows.single().sets)
    }
```
Write the Arrange/Act lines concretely by copying that existing test's body; only the two tags and the assertions differ. If that existing test has no unresolvable row (every name resolves), build one by pointing a backup row at an `exerciseId` absent from `backup.exercises`.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.domain.backup.BackupJsonTest`
Expected: `parse_acceptsV20File…` FAILS with "This export is from DB v20 but the app is on v21"; round-trip fails on the new fields.

- [ ] **Step 3: Implement**

`WorkoutBackup.kt` companion:
```kotlin
        const val DB_VERSION = 21
        /** Oldest export this build can read; fields added since are defaulted by the parser. */
        const val MIN_DB_VERSION = 20
```
`BackupJson.kt`:
- `setObj`: append `"circuitId" to s.circuitId,` to the `obj(...)` pairs.
- `savedWorkoutExerciseObj`: append `"sets" to r.sets, "circuitId" to r.circuitId,`.
- Version gate:
```kotlin
        if (dbVersion !in WorkoutBackup.MIN_DB_VERSION..WorkoutBackup.DB_VERSION) {
```
  (message unchanged).
- `set(o)`: add `circuitId = o.intOrNull("circuitId"),` — `JSONObject.isNull` is true for an absent key, so v20 files yield null.
- `savedWorkoutExercise(o)`: add `sets = o.optInt("sets", 3), circuitId = o.intOrNull("circuitId"),`.

`BackupManager.kt` additive import — replace the `.mapIndexed { i, r -> r.copy(position = i) }` tail:
```kotlin
                    .let { CircuitStructure.normalize(it) } // a dropped member must not leave a 1-row circuit
                    .mapIndexed { i, r -> r.copy(position = i) }
```
(import `io.github.fowles.stochastic_strength.domain.CircuitStructure`). Destructive import stays verbatim.

Update the existing `parse_rejectsWrongDbVersion` only if it now fails (it uses v16, still outside the window — it should pass untouched).

- [ ] **Step 4: Run to verify they pass**

Run `BackupJsonTest` and `BackupManagerTest` (same command form). Expected: both classes PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(backup): export sets/circuits; accept v20 files with defaults

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Planner — set-count-aware duration and explicit structure

**Files:**
- Modify: `MAIN/domain/WorkoutPlanner.kt:96-97,118-140,256-287`, `TEST/domain/WorkoutPlannerTest.kt`

**Interfaces:**
- Consumes: `PlannedExercise.sets/circuitId`.
- Produces:
  - `WorkoutPlanner.planExplicit(exercise: Exercise, reps: Int?, plan: WorkoutPlan, sets: Int = PlannedExercise.DEFAULT_SETS, circuitId: Int? = null): PlannedExercise`
  - `WorkoutPlanner.restampDuration(pe: PlannedExercise): PlannedExercise` — recomputes `estimatedSeconds` from `pe.sets`; call after anything changes a row's `sets`.
  - `withWeight` / `recomputeExercise` preserve `pe.sets`/`pe.circuitId` (they already `copy`) and price duration from `pe.sets`.

- [ ] **Step 1: Write the failing test** — add to `TEST/domain/WorkoutPlannerTest.kt` (use the file's existing planner-construction helper; the existing test ``generated plan stamps estimated seconds using learned secondsPerRep`` shows how a planner and an exercise are built — reuse that setup, binding the planner to `planner`, an exercise to `exercise`, and an empty plan to `plan`)

```kotlin
    @Test
    fun `planExplicit carries sets and circuit and prices duration from sets`() {
        val three = planner.planExplicit(exercise, reps = 8, plan = plan)
        val five = planner.planExplicit(exercise, reps = 8, plan = plan, sets = 5, circuitId = 2)
        assertEquals(5, five.sets)
        assertEquals(2, five.circuitId)
        assertTrue("5 sets must take longer than 3", five.estimatedSeconds > three.estimatedSeconds)
    }

    @Test
    fun `restampDuration follows a changed set count`() {
        val three = planner.planExplicit(exercise, reps = 8, plan = plan)
        val one = planner.restampDuration(three.copy(sets = 1))
        assertTrue(one.estimatedSeconds < three.estimatedSeconds)
        assertEquals(three.sessionWeight, one.sessionWeight)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`
Expected: compilation FAILS — no `sets` parameter, no `restampDuration`.

- [ ] **Step 3: Implement** — in `MAIN/domain/WorkoutPlanner.kt`

Add one private helper and use it at all three existing `DurationCalculator.estimate` sites (this removes the three `PlannedExercise.DEFAULT_SETS` reads):
```kotlin
    private fun durationOf(pe: PlannedExercise, reps: Int, warmups: List<WarmupSet>): Int =
        DurationCalculator.estimate(
            exercise = pe.exercise,
            sessionReps = reps,
            numSets = pe.sets,
            warmupSets = warmups,
            secondsPerRep = pacingEstimator.secondsPerRep(pe.exercise.id),
        )

    /** Re-prices a row's duration after its set count changed; weight and warmups are untouched. */
    fun restampDuration(pe: PlannedExercise): PlannedExercise =
        pe.copy(estimatedSeconds = durationOf(pe, pe.sessionReps, pe.warmupSets))
```
- In `recomputeExercise`: `estimatedSeconds = durationOf(pe, pe.sessionReps, warmups),` and delete the now-unused local `perRep`.
- In `withWeight` timed branch: `estimatedSeconds = durationOf(pe, timedReps, emptyList()),`; weighted branch: `estimatedSeconds = durationOf(pe, sessionReps, warmups),`; delete the now-unused local `perRep`.
- Replace `planExplicit`:
```kotlin
    fun planExplicit(
        exercise: Exercise,
        reps: Int?,
        plan: WorkoutPlan,
        sets: Int = PlannedExercise.DEFAULT_SETS,
        circuitId: Int? = null,
    ): PlannedExercise =
        withWeight(PlannedExercise(exercise = exercise, sets = sets, circuitId = circuitId), reps ?: plan.sessionReps)
```
(Keep its existing KDoc.)

- [ ] **Step 4: Run to verify it passes**

Run the Step 2 command. Expected: whole `WorkoutPlannerTest` PASSES (existing duration tests unchanged, since default `sets` is 3).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(planner): price duration from per-row sets; planExplicit carries structure

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Controller — plan-preview structure operations

**Files:**
- Modify: `MAIN/ui/workout/WorkoutSessionController.kt:152-317`, `MAIN/ui/workout/WorkoutViewModel.kt:193`, `ATEST/ui/workout/WorkoutSessionControllerTest.kt`

**Interfaces:**
- Consumes: `CircuitEdits.*`, `CircuitStructure.normalize/concat/blocks`, `WorkoutPlanner.planExplicit(…, sets, circuitId)`, `WorkoutPlanner.restampDuration`.
- Produces (controller, each mirrored by a one-line `WorkoutViewModel` delegate of the same name and parameters):
  - `moveExercise(from: Int, to: Int)` — **now block indices** (identical to row indices when there are no circuits).
  - `linkExercises(rowIndex: Int)`, `unlinkExercises(rowIndex: Int)`
  - `setExerciseSets(exerciseId: Long, sets: Int)` — sets the rounds of that row's block.
  - `loadSavedWorkout`/`appendSavedWorkout` carry structure; `saveCurrentPlan` writes it; trim / swipe-replace / location refresh keep it valid.

- [ ] **Step 1: Write the failing tests** — add to `ATEST/ui/workout/WorkoutSessionControllerTest.kt` (these use the file's existing `previewFixture`, `preview`, `awaitPreview`)

```kotlin
    @Test
    fun linkExercises_makesCircuit_setExerciseSetsWritesEveryMember_andDurationFollows() = runBlocking {
        val f = previewFixture(count = 3)
        val before = preview(f.controller).plan.estimatedDurationSeconds
        f.controller.linkExercises(0)
        val linked = preview(f.controller).plan.exercises
        assertEquals(listOf(0, 0, null), linked.map { it.circuitId })

        f.controller.setExerciseSets(linked[1].exercise.id, 1)
        val p = preview(f.controller)
        assertEquals(listOf(1, 1, 3), p.plan.exercises.map { it.sets })
        assertTrue("fewer sets must shorten the estimate", p.plan.estimatedDurationSeconds < before)
        assertTrue(p.edited)

        f.controller.unlinkExercises(0)
        assertEquals(listOf(null, null, null), preview(f.controller).plan.exercises.map { it.circuitId })
        f.db.close()
    }

    @Test
    fun moveExercise_movesAWholeCircuit() = runBlocking {
        val f = previewFixture(count = 3)
        f.controller.linkExercises(1) // rows 1+2 are a circuit; blocks = [row0], [row1,row2]
        val ids = preview(f.controller).plan.exercises.map { it.exercise.id }
        f.controller.moveExercise(1, 0)
        val after = preview(f.controller).plan.exercises
        assertEquals(listOf(ids[1], ids[2], ids[0]), after.map { it.exercise.id })
        assertEquals(listOf(0, 0, null), after.map { it.circuitId })
        f.db.close()
    }

    @Test
    fun lowerCount_trimmingACircuitToOneMember_collapsesItToSolo() = runBlocking {
        val f = previewFixture(count = 3)
        f.controller.linkExercises(1)
        f.controller.adjustExerciseCount(2)
        awaitPreviewSize(f.controller, 2)
        assertEquals(listOf(null, null), preview(f.controller).plan.exercises.map { it.circuitId })
        f.db.close()
    }

    @Test
    fun saveThenLoad_roundTripsStructure_andAppendKeepsCircuitsDistinct() = runBlocking {
        val f = previewFixture(count = 2)
        f.controller.linkExercises(0)
        f.controller.setExerciseSets(preview(f.controller).plan.exercises[0].exercise.id, 2)
        val saved = f.controller.saveCurrentPlan("Pair")!!
        assertEquals(listOf(2, 2), saved.entries.map { it.sets })
        assertEquals(listOf(0, 0), saved.entries.map { it.circuitId })

        // A second saved circuit built from the third exercise plus one of the first two would
        // violate one-per-plan, so append a circuit-free workout and check the kept circuit survives.
        val third = f.db.exerciseDao().getActive().first { ex -> saved.entries.none { it.exercise.id == ex.id } }
        val soloId = f.repo.saveWorkout(null, "Solo", listOf(SavedWorkoutEntry(third, reps = null, sets = 5)))

        // Break the structure first, so the load is what restores it.
        f.controller.unlinkExercises(0)
        assertEquals(listOf(null, null), preview(f.controller).plan.exercises.map { it.circuitId })
        f.controller.loadSavedWorkout(saved.id)
        awaitPreview(f.controller) { p -> p.plan.exercises.map { it.circuitId } == listOf(0, 0) }
        assertEquals(listOf(2, 2), preview(f.controller).plan.exercises.map { it.sets })

        f.controller.appendSavedWorkout(soloId)
        awaitPreviewSize(f.controller, 3)
        val p = preview(f.controller).plan.exercises
        assertEquals(listOf(0, 0, null), p.map { it.circuitId })
        assertEquals(listOf(2, 2, 5), p.map { it.sets })
        f.db.close()
    }

    @Test
    fun replace_inACircuit_replacementInheritsTheSlot() = runBlocking {
        val f = previewFixture(count = 2) // third exercise is the only replacement candidate
        f.controller.linkExercises(0)
        f.controller.setExerciseSets(preview(f.controller).plan.exercises[0].exercise.id, 2)
        val victim = preview(f.controller).plan.exercises[1].exercise.id
        f.controller.replaceExercise(victim, ExerciseRemovalReason.SKIP_TODAY)
        awaitPreview(f.controller) { p -> p.plan.exercises.none { it.exercise.id == victim } }
        val p = preview(f.controller).plan.exercises
        assertEquals(2, p.size)
        assertEquals(listOf(0, 0), p.map { it.circuitId })
        assertEquals(listOf(2, 2), p.map { it.sets })
        f.db.close()
    }
```
Also rename-and-keep the existing `saveCurrentPlan_writesOrderWithNullReps`: it must still pass unchanged.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest`
Expected: compilation FAILS — `linkExercises`, `unlinkExercises`, `setExerciseSets` unresolved.

- [ ] **Step 3: Implement** — in `WorkoutSessionController.kt` (add imports `io.github.fowles.stochastic_strength.domain.CircuitEdits`, `io.github.fowles.stochastic_strength.domain.CircuitStructure`)

Add one private helper used by every structure edit:
```kotlin
    /** Applies a structure edit to the preview rows and re-prices durations (rounds may have changed). */
    private fun editStructure(edit: (List<PlannedExercise>) -> List<PlannedExercise>) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val p = planner ?: return
        val edited = edit(preview.plan.exercises)
        if (edited == preview.plan.exercises) return
        setState(preview.copy(plan = preview.plan.copy(exercises = edited.map(p::restampDuration)), edited = true))
    }

    /** A replacement row takes over the structural slot of the row it replaces. */
    private fun PlannedExercise.inSlotOf(old: PlannedExercise, p: WorkoutPlanner): PlannedExercise =
        p.restampDuration(withStructure(old.sets, old.circuitId))
```
Replace `moveExercise` and add the three new operations:
```kotlin
    /** Indices are block indices: a circuit moves as a unit, and a drag never changes membership. */
    fun moveExercise(from: Int, to: Int) = editStructure { CircuitEdits.moveBlock(it, from, to) }

    fun linkExercises(rowIndex: Int) = editStructure { CircuitEdits.link(it, rowIndex) }

    fun unlinkExercises(rowIndex: Int) = editStructure { CircuitEdits.unlink(it, rowIndex) }

    fun setExerciseSets(exerciseId: Long, sets: Int) = editStructure { rows ->
        CircuitEdits.setRounds(rows, rows.indexOfFirst { it.exercise.id == exerciseId }, sets)
    }
```
`replaceExercise` — replace the two lines that build `newExercises`:
```kotlin
            val old = updatedPlan.exercises[currentIndex]
            val newExercises = if (replacement != null)
                updatedPlan.exercises.toMutableList().also { it[currentIndex] = replacement.inSlotOf(old, p) }
            else CircuitEdits.remove(updatedPlan.exercises, currentIndex)
```
`adjustExerciseCount` trim branch: `val trimmed = CircuitStructure.normalize(current.take(targetCount))`.

`applySavedWorkout` — replace the `kept`/`loaded`/`newPlan` lines:
```kotlin
            val kept = if (append) basePlan.exercises.filter { it.exercise.id !in loadedIds } else emptyList()
            val loaded = entries.map { p.planExplicit(it.exercise, it.reps, basePlan, it.sets, it.circuitId) }
            explicitIds += loadedIds
            val newPlan = basePlan.copy(
                // concat keeps a kept circuit and a loaded one distinct even when their ids collide.
                exercises = CircuitStructure.concat(CircuitStructure.normalize(kept), loaded),
                sessionRejectedIds = basePlan.sessionRejectedIds - loadedIds,
            )
```
`saveCurrentPlan` — entries become:
```kotlin
            entries = preview.plan.exercises.map { SavedWorkoutEntry(it.exercise, reps = null, it.sets, it.circuitId) },
```
and update its KDoc first line to `/** Saves the current preview rows — order, sets and circuits — with no pinned reps. Null if not on the preview. */`.

`onLocationRefreshed` — inside the `while` loop replace the body that builds `updated`:
```kotlin
                    val replacement = freshPlanner.pickReplacement(plan, i)
                    val updated = if (replacement != null)
                        plan.exercises.toMutableList().also { it[i] = replacement.inSlotOf(plan.exercises[i], freshPlanner) }
                    else CircuitEdits.remove(plan.exercises, i).also { i-- }
                    plan = plan.copy(exercises = updated)
```

`WorkoutViewModel.kt` — next to the existing `fun moveExercise(from: Int, to: Int) = controller.moveExercise(from, to)` add:
```kotlin
    fun linkExercises(rowIndex: Int) = controller.linkExercises(rowIndex)
    fun unlinkExercises(rowIndex: Int) = controller.unlinkExercises(rowIndex)
    fun setExerciseSets(exerciseId: Long, sets: Int) = controller.setExerciseSets(exerciseId, sets)
```

- [ ] **Step 4: Run to verify they pass**

Run the Step 2 command. Expected: whole class PASSES, including the pre-existing `moveExercise_swapsExerciseOrder`, `loadSavedWorkout_replacesRows_clearsOverrides_keepsTarget`, `appendSavedWorkout_keepsExisting_andReplacesDuplicateWithLoadedRow`.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(workout): plan-preview link/unlink/sets; load, append, save carry structure

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: Controller — in-session sequencing on `done` + `WorkoutSequence`

**Files:**
- Modify: `MAIN/ui/workout/WorkoutState.kt:12-51`, `MAIN/ui/workout/WorkoutSessionController.kt:131-150,345-365,388-491,557-689,722-768,791-844`, `ATEST/ui/workout/WorkoutSessionControllerTest.kt`

**Interfaces:**
- Consumes: `WorkoutSequence.next/positionLabel`, `CircuitEdits.remove`, `PlannedExercise.sets/circuitId`, `WorkoutSet.circuitId`, Task 8's `linkExercises`/`setExerciseSets`.
- Produces:
  - `WorkoutState.ActiveSet.done: Map<Long, Int>` (default `emptyMap()`), `WorkoutState.Resting.done: Map<Long, Int>` (default `emptyMap()`). Invariant: `ActiveSet.setIndex == done[plannedExercise.exercise.id] ?: 0`.
  - `ActiveSet.totalSets` = `plannedExercise.sets`; new `ActiveSet.positionLabel: String`.
  - For a staged rest, `Resting.done` is the **commit target's** `done`.

- [ ] **Step 1: Write the failing tests** — add to `ATEST/ui/workout/WorkoutSessionControllerTest.kt`

Helpers (controller-parameterized, unlike the older `toWorkingSet`):
```kotlin
    private suspend fun awaitActive(
        c: WorkoutSessionController, timeoutMs: Long = 2000,
        predicate: (WorkoutState.ActiveSet) -> Boolean = { true },
    ): WorkoutState.ActiveSet {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = c.state.value
            if (s is WorkoutState.ActiveSet && predicate(s)) return s
            delay(20)
        }
        error("No matching ActiveSet; was ${c.state.value}")
    }

    private suspend fun awaitLoggedRest(c: WorkoutSessionController, timeoutMs: Long = 2000): WorkoutState.Resting {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = c.state.value
            if (s is WorkoutState.Resting && s.staged == null) return s
            delay(20)
        }
        error("No logged-set rest; was ${c.state.value}")
    }

    /** Walks any warmups, logs one working set, and leaves the controller resting. Returns the exercise id. */
    private suspend fun performSet(c: WorkoutSessionController, feedback: SetFeedback = SetFeedback.RIR_2_4): Long {
        var s = awaitActive(c)
        while (s.warmupSetIndex != null) {
            c.completeWarmupSet()
            s = when (val now = c.state.value) {
                is WorkoutState.Resting -> { c.skipRest(); awaitActive(c) { it.warmupSetIndex == null } }
                else -> now as WorkoutState.ActiveSet
            }
        }
        val id = s.plannedExercise.exercise.id
        c.recordFeedback(feedback)
        awaitLoggedRest(c)
        return id
    }

    /** Preview of 3 rows → rows 0+1 linked as a [rounds]-round circuit, row 2 solo with 1 set → started. */
    private suspend fun circuitSession(rounds: Int): Pair<PreviewFixture, List<Long>> {
        val f = previewFixture(count = 3)
        f.controller.linkExercises(0)
        val rows = preview(f.controller).plan.exercises
        f.controller.setExerciseSets(rows[0].exercise.id, rounds)
        f.controller.setExerciseSets(rows[2].exercise.id, 1)
        f.controller.startFirstExercise()
        awaitActive(f.controller)
        return f to rows.map { it.exercise.id }
    }
```
Tests:
```kotlin
    @Test
    fun circuit_runsRoundRobin_thenTheNextBlock_andTagsLoggedRows() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2)
        val order = mutableListOf<Long>()
        repeat(5) { order += performSet(f.controller); f.controller.skipRest() }
        assertEquals(listOf(ids[0], ids[1], ids[0], ids[1], ids[2]), order)

        val rows = f.db.workoutSetDao().getSetsForSession(
            f.db.workoutSessionDao().getAll().single().id
        )
        assertEquals(listOf(1, 1, 2, 2, 1), rows.map { it.setNumber })
        assertEquals(listOf(0, 0, 0, 0, null), rows.map { it.circuitId })
        f.db.close()
    }

    @Test
    fun soloRow_honoursItsSetCount() = runBlocking {
        val f = previewFixture(count = 1)
        f.controller.setExerciseSets(preview(f.controller).plan.exercises[0].exercise.id, 5)
        f.controller.startFirstExercise()
        repeat(4) { performSet(f.controller); f.controller.skipRest() }
        val last = awaitActive(f.controller)
        assertEquals(4, last.setIndex)
        assertEquals(5, last.totalSets)
        assertEquals("Set 5 of 5", last.positionLabel)
        f.db.close()
    }

    @Test
    fun hurtMidCircuit_dropsThatMemberFromLaterRounds() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2)
        performSet(f.controller, SetFeedback.HURT); f.controller.skipRest() // ids[0] out
        val order = mutableListOf<Long>()
        repeat(3) { order += performSet(f.controller); f.controller.skipRest() }
        assertEquals(listOf(ids[1], ids[1], ids[2]), order)
        f.db.close()
    }

    @Test
    fun undoAcrossCircuitMembers_returnsToTheSetJustLogged() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2)
        performSet(f.controller); f.controller.skipRest()      // ids[0] round 1
        performSet(f.controller)                               // ids[1] round 1, now resting
        f.controller.undoLastSet()
        val back = awaitActive(f.controller) { it.plannedExercise.exercise.id == ids[1] }
        assertEquals(0, back.setIndex)
        assertEquals("Round 1 of 2", back.positionLabel)
        assertEquals(1, back.done[ids[0]])
        assertEquals(0, back.done[ids[1]] ?: 0)
        f.db.close()
    }

    @Test
    fun endExercise_inACircuitWithLoggedSets_finishesOnlyThatMember() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2)
        performSet(f.controller); f.controller.skipRest()      // ids[0] r1
        performSet(f.controller); f.controller.skipRest()      // ids[1] r1
        awaitActive(f.controller) { it.plannedExercise.exercise.id == ids[0] && it.setIndex == 1 }
        f.controller.endCurrentExercise()
        f.controller.skipRest()                                 // commit the staged end
        val next = awaitActive(f.controller) { it.plannedExercise.exercise.id != ids[0] }
        assertEquals(ids[1], next.plannedExercise.exercise.id)
        assertEquals(1, next.setIndex)
        f.db.close()
    }

    @Test
    fun swap_withLoggedSets_givesTheReplacementOnlyTheRemainingSets() = runBlocking {
        toWorkingSet()
        controller.recordFeedback(SetFeedback.RIR_2_4)
        awaitState<WorkoutState.Resting>()
        controller.skipRest()
        val active = awaitState<WorkoutState.ActiveSet>() // set 2 of 3
        controller.swapCurrentExercise(ExerciseRemovalReason.SKIP_TODAY)
        val target = awaitState<WorkoutState.Resting>().staged!!.commitTarget!!
        assertEquals(1, target.exerciseIndex)
        assertEquals(0, target.setIndex)
        assertEquals("1 done of 3 → 2 owed", 2, target.plannedExercise.sets)
        assertEquals(3, target.done[active.plannedExercise.exercise.id])
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest`
Expected: compilation FAILS — `positionLabel`, `done` unresolved.

- [ ] **Step 3: Implement `WorkoutState`**

```kotlin
    data class ActiveSet(
        val plan: WorkoutPlan,
        val exerciseIndex: Int,
        val setIndex: Int,
        val sessionId: Long,
        val warmupSetIndex: Int? = null,
        val timerSecondsRemaining: Int? = null,
        /** Completed working sets per exercise id. [setIndex] is always this exercise's entry. */
        val done: Map<Long, Int> = emptyMap(),
    ) : WorkoutState {
        val plannedExercise: PlannedExercise get() = plan.exercises[exerciseIndex]
        val totalSets: Int get() = plannedExercise.sets
        val positionLabel: String get() = WorkoutSequence.positionLabel(plan.exercises, exerciseIndex, setIndex)
        val currentWarmupSet get() = warmupSetIndex?.let { plannedExercise.warmupSets[it] }
    }
```
(import `io.github.fowles.stochastic_strength.domain.WorkoutSequence`). In `Resting`, add after `restQuip`:
```kotlin
        /** Progress once this rest ends: after the logged set, or — for a staged rest — the commit target's. */
        val done: Map<Long, Int> = emptyMap(),
```

- [ ] **Step 4: Implement the controller** (import `io.github.fowles.stochastic_strength.domain.WorkoutSequence`)

Add the single "where are we" helper:
```kotlin
    /** The set [WorkoutSequence] says is next, warmups first when it is that exercise's first. Null = finished. */
    private fun activeSetFor(plan: WorkoutPlan, done: Map<Long, Int>, sessionId: Long): WorkoutState.ActiveSet? {
        val step = WorkoutSequence.next(plan.exercises, done) ?: return null
        val ex = plan.exercises[step.exerciseIndex]
        return WorkoutState.ActiveSet(
            plan = plan,
            exerciseIndex = step.exerciseIndex,
            setIndex = step.setIndex,
            sessionId = sessionId,
            warmupSetIndex = if (step.setIndex == 0 && ex.warmupSets.isNotEmpty()) 0 else null,
            done = done,
        )
    }
```
- `startFirstExercise`: delete `val firstExercise`; replace the `setState(WorkoutState.ActiveSet(...))` with `activeSetFor(plan, emptyMap(), sessionId)?.let(::setState)`.
- `completeWarmupSet`: add `done = current.done,` to the `commitTarget` constructor.
- `recordFeedback`:
  - add `circuitId = planned.circuitId,` to the `WorkoutSet(...)`;
  - after the HURT upsert, replace the `completedSetIndex` line and the `Resting(...)`'s quip argument:
```kotlin
            val isHurt = feedback == SetFeedback.HURT
            val completedSetIndex = if (isHurt) current.totalSets - 1 else current.setIndex
            // HURT ends the exercise: it drops out of any remaining rounds.
            val done = current.done + (planned.exercise.id to if (isHurt) planned.sets else current.setIndex + 1)
```
    and in `Resting(...)`: `restQuip = RestQuips.pick(upcomingMusclesAfterRest(current.plan, done), Random.Default),` plus `done = done,`.
- `upcomingMusclesAfterRest` becomes:
```kotlin
    private fun upcomingMusclesAfterRest(plan: WorkoutPlan, done: Map<Long, Int>): Set<MuscleGroup>? {
        val step = WorkoutSequence.next(plan.exercises, done) ?: return null
        val exercise = plan.exercises[step.exerciseIndex].exercise
        return setOf(exercise.primaryMuscle) + exercise.secondaryMuscles
    }
```
- `undoLastSet`, inside `scope.launch`:
```kotlin
            val row = database.workoutSetDao().getById(resting.currentSetRowId)
            val exerciseId = restoredPlan.exercises[resting.exerciseIndex].exercise.id
            val setIndex = row?.let { it.setNumber - 1 } ?: resting.completedSetIndex
            database.workoutSetDao().deleteById(resting.currentSetRowId)
            setState(WorkoutState.ActiveSet(
                plan = restoredPlan,
                exerciseIndex = resting.exerciseIndex,
                setIndex = setIndex,
                sessionId = resting.sessionId,
                done = resting.done + (exerciseId to setIndex),
            ))
```
- `reduceExerciseWeight`: move `val exercise = …` above and use
  `val moreSetsForThisExercise = (resting.done[exercise.exercise.id] ?: 0) < exercise.sets`.
- `setActiveSetWeight`: add `done = current.done,` to its `commitTarget`.
- `swapCurrentExercise` — replace from `val exercises = …` through `val commitTarget = …`:
```kotlin
        val old = current.plannedExercise
        val loggedSets = current.done[original.id] ?: 0
        var done = current.done
        val exercises: List<PlannedExercise> = when {
            replacement == null && hasLogged -> {
                done = done + (original.id to old.sets) // keep original, advance past it
                rejectedPlan.exercises
            }
            replacement == null -> CircuitEdits.remove(rejectedPlan.exercises, i)
            hasLogged -> {
                // The replacement owes only what the original had left, in the same block.
                done = done + (original.id to old.sets)
                rejectedPlan.exercises.toMutableList().also {
                    it.add(i + 1, replacement.withStructure(old.sets - loggedSets, old.circuitId))
                }
            }
            else -> rejectedPlan.exercises.toMutableList().also {
                it[i] = replacement.withStructure(old.sets, old.circuitId)
            }
        }
        val newPlan = rejectedPlan.copy(exercises = exercises)
        val commitTarget = activeSetFor(newPlan, done, current.sessionId)
```
  and delete the now-unused `val replacement = replacementRaw` alias only if you also rename `replacementRaw` → `replacement`; otherwise leave it.
- `endCurrentExercise` — replace the `commitTarget` computation:
```kotlin
        val id = current.plannedExercise.exercise.id
        val commitTarget = if (hasLogged) {
            activeSetFor(current.plan, current.done + (id to current.plannedExercise.sets), current.sessionId)
        } else {
            val trimmed = CircuitEdits.remove(current.plan.exercises, i)
            activeSetFor(current.plan.copy(exercises = trimmed), current.done, current.sessionId)
        }
```
- Delete `nextExerciseActiveSet` (no callers remain; confirm with Grep).
- `stageRest`: add `done = target?.done ?: current.done,` to the `Resting(...)`.
- `advanceAfterRest` — replace everything after the `if (staged != null) { … }` block:
```kotlin
        activeSetFor(current.plan, current.done, current.sessionId)?.let(::setState)
            ?: finishWorkout(current.plan, current.sessionId)
```
- `deriveNotificationState`:
  - both `setLabel = "Set ${state.setIndex + 1} of ${state.totalSets}"` become `setLabel = state.positionLabel`;
  - the non-staged `Resting` branches become:
```kotlin
                else -> when (val step = WorkoutSequence.next(plan.exercises, state.done)) {
                    null -> "Last set — almost done!"
                    else -> {
                        val name = plan.exercises[step.exerciseIndex].exercise.name
                        val position = WorkoutSequence.positionLabel(plan.exercises, step.exerciseIndex, step.setIndex)
                            .substringBefore(" of")
                        when {
                            step.exerciseIndex == state.exerciseIndex -> "Next: $position · $name"
                            plan.exercises[step.exerciseIndex].circuitId != null -> "Next: $name · $position"
                            else -> "Next: $name"
                        }
                    }
                }
```
    (restructure the `when` so the staged case stays first and this is its `else`).
- Remove the now-unused `PlannedExercise` import only if nothing else in the file uses it (it still does — `editStructure`).

- [ ] **Step 5: Run to verify they pass**

Run the Step 2 command. Expected: whole class PASSES. The one pre-existing test whose *meaning* changed, `swap_hasLoggedSets_keepsOriginalAndInsertsAfter`, asserts only indices and size, so it passes unchanged. `finalSetTransitionsThroughResting`, `undoFromResting_deletesRowIncludingActualReps`, `tooHardOnFinalSetOfExercise_doesNotChangeWeight` and the `endExercise_*`/`swap_*` tests must all still pass — if one fails, the cause is a missed `done =` on a constructed `ActiveSet`.

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat(workout): sequence sets through WorkoutSequence; circuits, per-row sets, remaining-sets swap

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: Active-set and rest screens read the sequence

**Files:**
- Modify: `MAIN/ui/workout/ActiveSetContent.kt:57,105`, `MAIN/ui/workout/RestingContent.kt:61-63,163-237,328-385`

**Interfaces:**
- Consumes: `ActiveSet.positionLabel`, `Resting.done`, `WorkoutSequence.next`.
- Produces: no new API. After this task `RestingContent.kt` and `ActiveSetContent.kt` contain no `DEFAULT_SETS` reference.

This task is Compose-only; it is verified by the build plus a manual emulator check (Step 3).

- [ ] **Step 1: Implement `ActiveSetContent.kt`**

Both occurrences of `"Set ${state.setIndex + 1} of ${state.totalSets}"` → `state.positionLabel`.

- [ ] **Step 2: Implement `RestingContent.kt`** (import `io.github.fowles.stochastic_strength.domain.WorkoutSequence`)

- Delete `val totalSets = PlannedExercise.DEFAULT_SETS` and `val nextSet = …` at the top; add
  `val nextStep = WorkoutSequence.next(plan.exercises, state.done)`.
- In the card `Box`: replace the `moreSetsForThisExercise` and `nextExercise` definitions with
```kotlin
            val moreSetsForThisExercise =
                (state.done[plannedExercise.exercise.id] ?: 0) < plannedExercise.sets
            // In a circuit the next set usually belongs to a different exercise even with sets left here.
            val nextExercise = nextStep?.takeIf { it.exerciseIndex != state.exerciseIndex }
                ?.let { plan.exercises[it.exerciseIndex] }
```
  and the last `when` branch condition `!moreSetsForThisExercise && nextExercise != null ->` becomes `nextExercise != null ->`, with `val warmup = nextExercise.warmupSets.firstOrNull().takeIf { nextStep?.setIndex == 0 }`.
- Replace the `RemainingExerciseList(...)` call:
```kotlin
        val commitTarget = state.staged?.commitTarget
        RemainingExerciseList(
            exercises = (commitTarget?.plan ?: plan).exercises,
            done = state.done,
            currentExerciseIndex = commitTarget?.exerciseIndex ?: nextStep?.exerciseIndex ?: -1,
            modifier = Modifier.weight(0.2f),
        )
```
  (keep the explanatory comment above it, trimmed to: "For a staged action the rest precedes the commit-target exercise, so 'in progress' follows the commit target.")
- Rewrite `RemainingExerciseList`'s signature and row derivation:
```kotlin
@Composable
private fun RemainingExerciseList(
    exercises: List<PlannedExercise>,
    done: Map<Long, Int>,
    currentExerciseIndex: Int,
    modifier: Modifier = Modifier,
) {
    val inProgressIndex = currentExerciseIndex
```
  keep the scroll `LaunchedEffect` and the `LazyColumn`/sticky header as they are, and replace the per-row block:
```kotlin
        exercises.forEachIndexed { i, planned ->
            val remaining = planned.sets - (done[planned.exercise.id] ?: 0)
            val progress = when {
                remaining <= 0 -> ExerciseProgress.COMPLETED
                i == inProgressIndex -> ExerciseProgress.IN_PROGRESS
                else -> ExerciseProgress.PENDING
            }
            val detail = when {
                remaining <= 0 -> "done"
                remaining < planned.sets || i == inProgressIndex -> "$remaining left"
                else -> "${planned.sets} sets"
            }
            item(key = planned.exercise.id) {
                RemainingExerciseRow(name = planned.exercise.name, detail = detail, progress = progress)
            }
        }
```
- Remove the `PlannedExercise` import only if unused (it is still the list's element type — keep it).

- [ ] **Step 3: Build and check on the emulator**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
Run Grep for `DEFAULT_SETS` in `MAIN/ui/workout/RestingContent.kt` and `ActiveSetContent.kt` → no matches.
If an emulator is already running, install and start a workout; confirm a plain 3-set exercise still reads "Set 1 of 3", rests show "N left", and the final rest shows no "Next up" card. (Circuits become reachable from the UI in Task 12.) If no emulator is running, say so in the task report — do not start one.

- [ ] **Step 4: Commit**

```bash
jj commit -m "feat(workout): rest and active-set screens derive next-up and remaining from the sequence

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 11: Shared chrome + saved-workout editor

**Files:**
- Create: `MAIN/ui/components/CircuitChrome.kt`
- Modify: `MAIN/ui/savedworkouts/SavedWorkoutEditViewModel.kt:79-99`, `MAIN/ui/savedworkouts/SavedWorkoutEditScreen.kt:116-142,166-254`, `MAIN/ui/components/SavedWorkoutPickerDialog.kt:28,70`, `MAIN/ui/savedworkouts/SavedWorkoutsScreen.kt:104`, `ATEST/ui/savedworkouts/SavedWorkoutsViewModelsTest.kt`

**Interfaces:**
- Consumes: `CircuitEdits.*`, `CircuitStructure.blocks/circuitCount/MIN_SETS/MAX_SETS`.
- Produces:
  - `@Composable fun CountStepper(value: Int, range: IntRange, onChange: (Int) -> Unit, fewerDescription: String, moreDescription: String, modifier: Modifier = Modifier, dimAtMin: Boolean = false)`
  - `@Composable fun LinkToggle(linked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun CircuitHeader(rounds: Int, onRoundsChange: (Int) -> Unit, dragHandleModifier: Modifier, modifier: Modifier = Modifier)`
  - `fun workoutSubtitle(entries: List<SavedWorkoutEntry>): String`
  - Editor VM: `move(fromBlock: Int, toBlock: Int)` (now block indices), `link(rowIndex: Int)`, `unlink(rowIndex: Int)`, `setSets(exerciseId: Long, sets: Int)`; `removeExercise` normalizes.

- [ ] **Step 1: Write the failing VM tests** — add to `ATEST/ui/savedworkouts/SavedWorkoutsViewModelsTest.kt` (use the file's existing way of building a `SavedWorkoutEditViewModel` for `NEW_WORKOUT_ID` and of waiting for `allExercises`; the existing `hasUnsavedChanges_tracksEditsAndSaves` shows both — bind the VM to `vm` and three exercise ids to `a`, `b`, `c`)

```kotlin
    @Test
    fun structureEdits_trackUnsavedChanges_andRoundTripThroughSave() = runBlocking {
        vm.addExercise(a); vm.addExercise(b); vm.addExercise(c)
        vm.link(0)
        vm.setSets(b, 2)
        assertEquals(listOf(2, 2, 3), vm.state.value.entries.map { it.sets })
        assertEquals(listOf(0, 0, null), vm.state.value.entries.map { it.circuitId })
        assertTrue(vm.hasUnsavedChanges())

        vm.move(1, 0) // block 1 (solo c) above block 0 (the circuit)
        assertEquals(listOf(c, a, b), vm.state.value.entries.map { it.exercise.id })

        vm.removeExercise(a)
        assertEquals("a circuit of one is a solo row", listOf(null, null), vm.state.value.entries.map { it.circuitId })

        vm.save()
        // wait for the save the same way the existing tests in this file do, then:
        val saved = repository.observeSavedWorkouts().first().single()
        assertEquals(listOf(3, 2), saved.entries.map { it.sets })
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.ui.savedworkouts.SavedWorkoutsViewModelsTest`
Expected: compilation FAILS — `link`, `setSets` unresolved.

- [ ] **Step 3: Implement the VM** — in `SavedWorkoutEditViewModel.kt` (import `io.github.fowles.stochastic_strength.domain.CircuitEdits`)

```kotlin
    private fun editEntries(edit: (List<SavedWorkoutEntry>) -> List<SavedWorkoutEntry>) {
        _state.value = _state.value.copy(entries = edit(_state.value.entries))
    }

    fun removeExercise(exerciseId: Long) = editEntries { rows ->
        CircuitEdits.remove(rows, rows.indexOfFirst { it.exercise.id == exerciseId })
    }

    /** Block indices: a circuit moves as a unit. */
    fun move(fromBlock: Int, toBlock: Int) = editEntries { CircuitEdits.moveBlock(it, fromBlock, toBlock) }

    fun link(rowIndex: Int) = editEntries { CircuitEdits.link(it, rowIndex) }

    fun unlink(rowIndex: Int) = editEntries { CircuitEdits.unlink(it, rowIndex) }

    /** Sets the rounds of the row's block — for a solo row, its set count. */
    fun setSets(exerciseId: Long, sets: Int) = editEntries { rows ->
        CircuitEdits.setRounds(rows, rows.indexOfFirst { it.exercise.id == exerciseId }, sets)
    }
```
(these replace the old `removeExercise` and `move`; `setReps` and `addExercise` stay).

- [ ] **Step 4: Implement `CircuitChrome.kt`**

```kotlin
package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.domain.CircuitStructure

/** Material 3's disabled-content alpha, so a dimmed value matches the disabled "−" beside it. */
private const val DISABLED_ALPHA = 0.38f

/** Inline − n + control. With [dimAtMin] the minimum reads as "off" rather than as a live number. */
@Composable
fun CountStepper(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    fewerDescription: String,
    moreDescription: String,
    modifier: Modifier = Modifier,
    dimAtMin: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = fewerDescription)
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
                .copy(alpha = if (dimAtMin && value == range.first) DISABLED_ALPHA else 1f),
            modifier = Modifier.widthIn(min = 24.dp),
        )
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, contentDescription = moreDescription)
        }
    }
}

/** The ⛓ between two adjacent rows: filled when they are in one circuit, outlined when they are not. */
@Composable
fun LinkToggle(linked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(
                if (linked) Icons.Filled.Link else Icons.Filled.LinkOff,
                contentDescription = if (linked) "Split the circuit here" else "Link into a circuit",
                tint = if (linked) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Top edge of a circuit card: the handle that drags the whole circuit, and its rounds. */
@Composable
fun CircuitHeader(
    rounds: Int,
    onRoundsChange: (Int) -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.DragIndicator,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = dragHandleModifier.padding(start = 4.dp, end = 8.dp).size(24.dp),
        )
        Text(
            "Circuit",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text("rounds", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CountStepper(
            value = rounds,
            range = CircuitStructure.MIN_SETS..CircuitStructure.MAX_SETS,
            onChange = onRoundsChange,
            fewerDescription = "One round fewer",
            moreDescription = "One round more",
        )
    }
}
```

- [ ] **Step 5: Implement the editor screen** — in `SavedWorkoutEditScreen.kt`

Helper text becomes:
```kotlin
                "Drag to reorder · swipe left to remove · ⛓ links rows into a circuit · reps 0 = session default",
```
Replace the `reorderState` + `LazyColumn` block:
```kotlin
            val blocks = remember(state.entries) { CircuitStructure.blocks(state.entries) }
            val lazyListState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
                viewModel.move(from.index, to.index)
            }
            LazyColumn(state = lazyListState, modifier = Modifier.weight(1f)) {
                // One item per block, so a drag carries a whole circuit. The smallest member id is a
                // key that survives the drag.
                items(blocks, key = { b -> b.indices.minOf { state.entries[it].exercise.id } }) { block ->
                    val key = block.indices.minOf { state.entries[it].exercise.id }
                    ReorderableItem(reorderState, key = key) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp, label = "dragElevation")
                        Column(modifier = Modifier.animateItem().graphicsLayer { shadowElevation = elevation.toPx() }) {
                            val first = state.entries[block.start]
                            if (block.isCircuit) CircuitHeader(
                                rounds = block.rounds,
                                onRoundsChange = { viewModel.setSets(first.exercise.id, it) },
                                dragHandleModifier = Modifier.draggableHandle(),
                            )
                            for (i in block.indices) {
                                val entry = state.entries[i]
                                key(entry.exercise.id) {
                                    EntryRow(
                                        entry = entry,
                                        // Members have no handle: unlink, reorder, relink.
                                        dragHandleModifier = if (block.isCircuit) null else Modifier.draggableHandle(),
                                        onRemove = { viewModel.removeExercise(entry.exercise.id) },
                                        onRepsChange = { reps -> viewModel.setReps(entry.exercise.id, reps) },
                                        onSetsChange = { sets -> viewModel.setSets(entry.exercise.id, sets) },
                                    )
                                }
                                if (i != state.entries.lastIndex) LinkToggle(
                                    linked = i != block.last,
                                    onClick = { if (i != block.last) viewModel.unlink(i) else viewModel.link(i) },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
```
(imports: `androidx.compose.runtime.key`, `androidx.compose.runtime.remember`, `io.github.fowles.stochastic_strength.domain.CircuitStructure`, `io.github.fowles.stochastic_strength.ui.components.CircuitHeader`, `CountStepper`, `LinkToggle`).

`EntryRow` — new signature and body changes:
```kotlin
@Composable
private fun EntryRow(
    entry: SavedWorkoutEntry,
    /** Null for a circuit member: the circuit's header is the only handle. */
    dragHandleModifier: Modifier?,
    onRemove: () -> Unit,
    onRepsChange: (Int?) -> Unit,
    onSetsChange: (Int) -> Unit,
) {
```
Inside the inner `Row`, replace the handle `Icon`, and replace `RepsStepper(...)` with a two-line layout:
```kotlin
            if (dragHandleModifier != null) Icon(
                Icons.Filled.DragIndicator,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier.padding(start = 4.dp, end = 8.dp).size(24.dp),
            ) else Spacer(Modifier.width(36.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.exercise.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A circuit member's set count is the circuit's rounds, shown on the header.
                    if (dragHandleModifier != null) {
                        StepperLabel("sets")
                        CountStepper(
                            value = entry.sets,
                            range = CircuitStructure.MIN_SETS..CircuitStructure.MAX_SETS,
                            onChange = onSetsChange,
                            fewerDescription = "One set fewer",
                            moreDescription = "One set more",
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    StepperLabel("reps")
                    // 0 is not a rep count but "leave it to the session", i.e. a null override.
                    CountStepper(
                        value = entry.reps ?: 0,
                        range = 0..MAX_REPS,
                        onChange = { onRepsChange(it.takeIf { r -> r > 0 }) },
                        fewerDescription = "One rep fewer",
                        moreDescription = "One rep more",
                        dimAtMin = true,
                    )
                }
            }
```
with
```kotlin
@Composable
private fun StepperLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
```
Delete the old `RepsStepper`, the old "Session default reps" subtitle, and the file-local `DISABLED_ALPHA` (now in `CircuitChrome.kt`). Keep `MAX_REPS`. Add imports `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.width`.

- [ ] **Step 6: Library and picker subtitle**

In `SavedWorkoutPickerDialog.kt`, under `exerciseCountLabel` add:
```kotlin
/** "5 exercises", plus " · 1 circuit" when the workout has any. */
fun workoutSubtitle(entries: List<SavedWorkoutEntry>): String {
    val base = exerciseCountLabel(entries.size)
    return when (val circuits = CircuitStructure.circuitCount(entries)) {
        0 -> base
        1 -> "$base · 1 circuit"
        else -> "$base · $circuits circuits"
    }
}
```
and change both call sites (`SavedWorkoutPickerDialog.kt:70`, `SavedWorkoutsScreen.kt:104`) from `exerciseCountLabel(w.entries.size)` to `workoutSubtitle(w.entries)`.

- [ ] **Step 7: Run tests and build**

Run the Step 2 command → whole class PASSES. Run `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
If an emulator is already running: open Workouts → +, add three exercises, tap the ⛓ between the first two, confirm the card + rounds stepper appear, drag the card by its header below the third row, tap the filled ⛓ to split, Done, reopen and confirm it persisted. If none is running, say so in the report.

- [ ] **Step 8: Commit**

```bash
jj commit -m "feat(saved-workouts): editor sets steppers, link toggles, whole-circuit drag

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 12: Plan-preview UI

**Files:**
- Modify: `MAIN/ui/workout/PlanPreviewContent.kt:62-222,224-344`, `MAIN/ui/workout/WorkoutScreen.kt:~135`

**Interfaces:**
- Consumes: `WorkoutViewModel.moveExercise/linkExercises/unlinkExercises/setExerciseSets` (Task 8), `CircuitHeader`/`CountStepper`/`LinkToggle` (Task 11), `CircuitStructure.blocks`.
- Produces: `PlanPreviewContent` gains parameters `onLink: (rowIndex: Int) -> Unit`, `onUnlink: (rowIndex: Int) -> Unit`, `onSetSets: (exerciseId: Long, sets: Int) -> Unit`; `onMove` now receives **block** indices.

Compose-only; verified by the build and a manual check.

- [ ] **Step 1: Implement `PlanPreviewContent`**

- Add the three parameters after `onMove`.
- `val totalSets = plan.exercises.sumOf { it.sets }`.
- Helper-text line becomes `"Swipe left to reject · ⛓ links rows into a circuit"`.
- Replace the `LazyColumn` with the block-per-item form (same shape as the editor):
```kotlin
        val blocks = remember(plan.exercises) { CircuitStructure.blocks(plan.exercises) }
        LazyColumn(state = lazyListState, modifier = Modifier.weight(1f)) {
            items(blocks, key = { b -> b.indices.minOf { plan.exercises[it].exercise.id } }) { block ->
                val key = block.indices.minOf { plan.exercises[it].exercise.id }
                ReorderableItem(reorderState, key = key) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp, label = "dragElevation")
                    Column(modifier = Modifier.animateItem().graphicsLayer { shadowElevation = elevation.toPx() }) {
                        if (block.isCircuit) CircuitHeader(
                            rounds = block.rounds,
                            onRoundsChange = { onSetSets(plan.exercises[block.start].exercise.id, it) },
                            dragHandleModifier = Modifier.draggableHandle(),
                        )
                        for (i in block.indices) {
                            val planned = plan.exercises[i]
                            key(planned.exercise.id) {
                                ExercisePreviewRow(
                                    planned = planned,
                                    weightUnit = weightUnit,
                                    dragHandleModifier = if (block.isCircuit) null else Modifier.draggableHandle(),
                                    onReplace = { reason -> onReplace(planned.exercise.id, reason) },
                                    onWeightDecrement = if (planned.sessionWeight > 0f) {
                                        { onAdjustWeight(planned.exercise.id, -2.5f) }
                                    } else null,
                                    onWeightIncrement = if (planned.sessionWeight > 0f) {
                                        { onAdjustWeight(planned.exercise.id, +2.5f) }
                                    } else null,
                                    onTap = { onExerciseTap(planned.exercise.id) },
                                    onSetsChange = { onSetSets(planned.exercise.id, it) },
                                    flag = state.rowFlags[planned.exercise.id],
                                )
                            }
                            if (i != plan.exercises.lastIndex) LinkToggle(
                                linked = i != block.last,
                                onClick = { if (i != block.last) onUnlink(i) else onLink(i) },
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
```
- `ExercisePreviewRow`: `dragHandleModifier: Modifier?` (null = circuit member) and new `onSetsChange: (Int) -> Unit`. Render the handle only when non-null, else `Spacer(Modifier.width(36.dp))`. Replace the `detail` text with:
```kotlin
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dragHandleModifier != null) CountStepper(
                            value = planned.sets,
                            range = CircuitStructure.MIN_SETS..CircuitStructure.MAX_SETS,
                            onChange = onSetsChange,
                            fewerDescription = "One set fewer",
                            moreDescription = "One set more",
                        )
                        val detail = buildString {
                            append(if (dragHandleModifier != null) "sets × $repsLabel" else repsLabel)
                            if (onWeightDecrement == null && weightLabel != null) append(" · $weightLabel")
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
```
- Remove the `PlannedExercise` import if now unused; add imports for `key`, `CircuitStructure`, `CircuitHeader`, `CountStepper`, `LinkToggle`, `Spacer`/`width` as needed.

- [ ] **Step 2: Wire `WorkoutScreen.kt`**

Beside `onMove = viewModel::moveExercise,` add:
```kotlin
                        onLink = viewModel::linkExercises,
                        onUnlink = viewModel::unlinkExercises,
                        onSetSets = viewModel::setExerciseSets,
```

- [ ] **Step 3: Build and check**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. Grep `DEFAULT_SETS` under `MAIN/ui/` → no matches.
If an emulator is already running: on plan preview link two rows, set rounds to 2, confirm the header's set total and minutes change; start the workout and confirm "Round 1 of 2" alternates between the two exercises with a rest after each, then "Round 2 of 2". Swipe-replace a circuit member on the preview and confirm the replacement stays inside the card. If none is running, say so in the report.

- [ ] **Step 4: Commit**

```bash
jj commit -m "feat(workout): plan preview sets steppers, link toggles, whole-circuit drag

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 13: Downstream — pacing, summary, Strava description

**Files:**
- Modify: `MAIN/domain/ExercisePacingEstimator.kt:58-64`, `MAIN/ui/WorkoutSummaryData.kt:31-60`, `MAIN/ui/WorkoutSummaryContent.kt:52-66`, `MAIN/ui/ExerciseSetSection.kt:17,35`, `MAIN/domain/strava/StravaExporter.kt:171-192`
- Test: `TEST/domain/ExercisePacingEstimatorTest.kt`, `TEST/domain/strava/StravaDescriptionTest.kt`, create `TEST/ui/SummaryBlocksTest.kt`

**Interfaces:**
- Produces:
  - `SummaryExercise(name, exerciseId, sets, circuitId: Int? = null)`
  - `data class SummaryBlock(val exercises: List<SummaryExercise>)` with `isCircuit: Boolean`, `rounds: Int`
  - `fun summaryBlocks(exercises: List<SummaryExercise>): List<SummaryBlock>`; `WorkoutSummaryData.blocks: List<SummaryBlock>`
  - `ExerciseSetSection(name, sets, weightUnit, setWord: String = "Set")`

- [ ] **Step 1: Write the failing tests**

`TEST/domain/ExercisePacingEstimatorTest.kt` — add (the file's `set(...)` helper builds a `WorkoutSet`; `.copy(circuitId = 0)` tags it):
```kotlin
    @Test
    fun circuitSets_areSkipped_becauseTheGapHoldsOtherExercisesWork() {
        // Same numbers as singlePair_returnsExpectedSecondsPerRep, but inside a circuit.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 8).copy(circuitId = 0),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L, targetReps = 8).copy(circuitId = 0),
        ))
        val estimator = ExercisePacingEstimator.build(sessions, sets, mapOf(1L to exercise(1L)))
        assertNull(estimator.secondsPerRep(1L))
    }
```
(add `import org.junit.Assert.assertNull` if missing).

`TEST/domain/strava/StravaDescriptionTest.kt` — add:
```kotlin
    @Test
    fun circuitMembersListUnderOneCircuitHeading_withItsRounds() {
        val circuitSets = listOf(
            set(1L, 1).copy(circuitId = 0), set(2L, 1).copy(circuitId = 0),
            set(1L, 2).copy(circuitId = 0), set(2L, 2).copy(circuitId = 0),
            set(3L, 1),
        )
        val desc = StravaExporter.buildDescription("", circuitSets, exercises, 0L, WeightUnit.KG)
        val circuit = desc.indexOf("Circuit ×2")
        assertTrue("heading present", circuit >= 0)
        assertEquals("one heading for the whole circuit", circuit, desc.lastIndexOf("Circuit ×"))
        assertTrue(circuit < desc.indexOf("Bench Press"))
        assertTrue(desc.indexOf("Bench Press") < desc.indexOf("Squat"))
        assertTrue(desc.indexOf("Squat") < desc.indexOf("Deadlift"))
    }
```

`TEST/ui/SummaryBlocksTest.kt`:
```kotlin
package io.github.fowles.stochastic_strength.ui

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryBlocksTest {
    private fun ex(id: Long, setCount: Int, circuitId: Int?) = SummaryExercise(
        name = "E$id", exerciseId = id, circuitId = circuitId,
        sets = (1..setCount).map { SummarySet(it, 20f, 5, 5, SetFeedback.RIR_2_4) },
    )

    @Test
    fun consecutiveExercisesSharingACircuitIdFormOneBlock_roundsIsTheLongestMember() {
        val blocks = summaryBlocks(listOf(ex(1, 2, 0), ex(2, 1, 0), ex(3, 3, null)))
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), blocks.map { b -> b.exercises.map { it.exerciseId } })
        assertEquals(listOf(true, false), blocks.map { it.isCircuit })
        assertEquals(2, blocks[0].rounds)
    }

    @Test
    fun soloExercisesAreNeverGrouped() {
        assertEquals(2, summaryBlocks(listOf(ex(1, 3, null), ex(2, 3, null))).size)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run:
`./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ExercisePacingEstimatorTest" --tests "io.github.fowles.stochastic_strength.domain.strava.StravaDescriptionTest" --tests "io.github.fowles.stochastic_strength.ui.SummaryBlocksTest"`
Expected: `SummaryBlocksTest` fails to compile; the pacing test FAILS (returns 11.25); the Strava test FAILS (no heading).

- [ ] **Step 3: Implement**

`ExercisePacingEstimator.appearanceAverage` — after the HURT `continue`:
```kotlin
                // In a circuit the gap between two sets of one exercise also holds the other members' work.
                if (prev.circuitId != null || curr.circuitId != null) continue
```

`WorkoutSummaryData.kt`:
```kotlin
data class SummaryExercise(
    val name: String,
    val exerciseId: Long,
    val sets: List<SummarySet>,
    val circuitId: Int? = null,
)

/** Exercises shown together: one solo exercise, or the members of a circuit. */
data class SummaryBlock(val exercises: List<SummaryExercise>) {
    val isCircuit: Boolean get() = exercises.size > 1
    val rounds: Int get() = exercises.maxOf { it.sets.size }
}

/** Groups consecutive exercises that share a non-null circuit id. */
fun summaryBlocks(exercises: List<SummaryExercise>): List<SummaryBlock> {
    val out = mutableListOf<MutableList<SummaryExercise>>()
    for (ex in exercises) {
        val open = out.lastOrNull()
        if (ex.circuitId != null && open?.last()?.circuitId == ex.circuitId) open.add(ex) else out += mutableListOf(ex)
    }
    return out.map(::SummaryBlock)
}
```
add to `WorkoutSummaryData`: `val blocks: List<SummaryBlock> get() = summaryBlocks(exercises)`; and in `loadWorkoutSummary` pass `circuitId = setsByExercise[id]?.firstOrNull()?.circuitId,` to `SummaryExercise(...)`.

`ExerciseSetSection.kt`: signature `fun ExerciseSetSection(name: String, sets: List<SummarySet>, weightUnit: WeightUnit, setWord: String = "Set")`, and `text = "$setWord ${set.setNumber}: $weightLabel",`.

`WorkoutSummaryContent.kt` — replace the `summary.exercises.forEach { ex -> … }` block:
```kotlin
            summary.blocks.forEach { block ->
                if (block.isCircuit) Text(
                    "Circuit · ${block.rounds} rounds",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                block.exercises.forEach { ex ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (onExerciseTap != null) Modifier.clickable { onExerciseTap(ex.exerciseId) }
                                else Modifier
                            ),
                    ) {
                        ExerciseSetSection(
                            ex.name, ex.sets, summary.weightUnit,
                            setWord = if (block.isCircuit) "Round" else "Set",
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }
```

`StravaExporter.buildDescription` — inside the `for ((id, exerciseSets) in setsByExercise)` loop, track the open circuit (declare `var openCircuit: Int? = null` before the loop) and emit the heading before the exercise name:
```kotlin
                val circuit = exerciseSets.first().circuitId
                if (circuit != null && circuit != openCircuit) {
                    val rounds = setsByExercise.values.filter { it.first().circuitId == circuit }.maxOf { it.size }
                    sb.append("Circuit ×$rounds\n")
                }
                openCircuit = circuit
```
Place these lines **after** the `val exercise = exerciseById[id] ?: continue` line. Update the function's KDoc sentence to "…then each exercise (in workout order — [sets] is grouped by first appearance, circuit members under one "Circuit ×N" heading), then duration and footer."

- [ ] **Step 4: Run to verify they pass**

Run the Step 2 command. Expected: all three classes PASS (including `exercisesListInWorkoutOrderNotMapOrder`).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(circuits): summary and Strava group circuits; pacing skips circuit gaps

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 14: Housekeeping and full verification

**Files:**
- Modify: `app/src/debug/**/DebugSeeder.kt:62,65`, `CLAUDE.md`

- [ ] **Step 1: `DebugSeeder`**

Find it with Glob `app/src/debug/**/DebugSeeder.kt`. It loops `PlannedExercise.DEFAULT_SETS` times per planned exercise; change both reads to that planned exercise's `.sets` (the loop variable holding the `PlannedExercise` is in scope at both lines). If the seeder builds sets from a bare `Exercise` rather than a `PlannedExercise`, leave `DEFAULT_SETS` — it is then the legitimate "rows the app adds on its own" default.

- [ ] **Step 2: Confirm the remaining `DEFAULT_SETS` reads are all defaults**

Grep `DEFAULT_SETS` across `app/src/main` and `app/src/debug`. Allowed survivors: the declaration and default parameter values in `PlannedExercise.kt`, `SavedWorkoutDetail.kt`, `WorkoutPlanner.planExplicit`, and (per Step 1) possibly `DebugSeeder`. Anything else is a missed site — fix it to read `planned.sets` or `WorkoutSequence`.

- [ ] **Step 3: Update `CLAUDE.md`**

- "Workout state machine": after the state list add —
  "Position is derived, never stored: `ActiveSet`/`Resting` carry `done` (completed working sets per exercise id) and `WorkoutSequence.next(plan.exercises, done)` is the single rule for what comes next (controller advance, notification label, rest screen). Rest follows every set."
- "Saved workouts and explicit control": change "(DB v20)" to "(DB v20; structure columns v21)" and append —
  "Rows carry `sets` (1–10) and a nullable `circuitId`; adjacent rows sharing an id are a circuit done round-robin (`2 × (curl, kickback, press)`), with `sets` as its rounds. Storage, `WorkoutPlan.exercises` and view-model state stay flat; logic reads `CircuitStructure.blocks`, where a solo row is a block of one. `CircuitEdits` (link / unlink / moveBlock / remove / setRounds) is the shared editing vocabulary for the editor and the plan preview: ⛓ toggles change membership, drags move whole blocks and never change membership. `workout_sets.circuitId` records the structure of a finished session (summary, Strava description, save-as-workout); `setNumber` stays per-exercise and dense. A mid-exercise swap gives the replacement only the remaining sets."
- "Progression system": replace "All exercises use a fixed `PlannedExercise.DEFAULT_SETS` (3) sets." with "`PlannedExercise.DEFAULT_SETS` (3) is only the default for rows the app adds; each row carries its own `sets`."
- "Database": "version 20" → "version 21".

- [ ] **Step 4: Full verification**

Run, in order, and read the output of each:
1. `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL; confirm `BeliefScoreTest` and `BeliefPolicyBacktestTest` ran and passed (no re-baseline).
2. `./gradlew :app:lint` → no new errors.
3. `./gradlew :app:connectedAndroidTest` → all PASS. If no device is attached, report that this step could not run; do not start an emulator.

- [ ] **Step 5: Commit**

```bash
jj commit -m "chore(circuits): seeder uses row sets; CLAUDE.md documents sets and circuits

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```
