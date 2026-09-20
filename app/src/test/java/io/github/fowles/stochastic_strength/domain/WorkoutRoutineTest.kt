package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutRoutineTest {
    /** Saved workouts A, B, C, each a distinct set of exercise ids. */
    private val a = WorkoutRoutine.Candidate(id = 10L, exerciseIds = setOf(1L, 2L, 3L))
    private val b = WorkoutRoutine.Candidate(id = 20L, exerciseIds = setOf(4L, 5L))
    private val c = WorkoutRoutine.Candidate(id = 30L, exerciseIds = setOf(6L, 7L, 8L))
    private val saved = listOf(a, b, c)

    /** Sessions newest-first, spelled oldest-to-newest for readability. */
    private fun history(vararg oldestFirst: WorkoutRoutine.Candidate?) =
        oldestFirst.reversed().map { it?.exerciseIds ?: setOf(99L) }

    private fun predict(vararg oldestFirst: WorkoutRoutine.Candidate?) =
        WorkoutRoutine.nextWorkoutId(recentSessions = history(*oldestFirst), saved = saved)

    @Test
    fun `repeating one workout predicts it`() {
        assertEquals(a.id, predict(a, a))
    }

    @Test
    fun `alternating pair predicts the other`() {
        assertEquals(b.id, predict(a, b, a))
    }

    @Test
    fun `three-day rotation predicts the next in the cycle`() {
        assertEquals(b.id, predict(a, b, c, a))
    }

    @Test
    fun `two different workouts with no repeat yet stays random`() {
        assertNull(predict(a, b))
    }

    @Test
    fun `a single session stays random`() {
        assertNull(predict(a))
    }

    @Test
    fun `no history stays random`() {
        assertNull(predict())
    }

    @Test
    fun `an unlabeled latest session stays random`() {
        assertNull(predict(a, b, a, null))
    }

    @Test
    fun `an unlabeled session truncates the run it sits in`() {
        // Only `a, b, a` is visible past the gap, which is enough on its own.
        assertEquals(b.id, predict(c, c, c, null, a, b, a))
    }

    @Test
    fun `an unlabeled session can truncate the run below a pattern`() {
        assertNull(predict(a, b, a, b, null, a, b))
    }

    @Test
    fun `a broken pattern stays random`() {
        assertNull(predict(a, b, c, b))
    }

    @Test
    fun `the shortest confirmed period wins`() {
        // Consistent with period 1 and (vacuously) with longer ones; period 1 decides.
        assertEquals(a.id, predict(a, a, a))
    }

    @Test
    fun `a period longer than three is not a pattern`() {
        val d = WorkoutRoutine.Candidate(id = 40L, exerciseIds = setOf(9L))
        assertNull(WorkoutRoutine.nextWorkoutId(history(a, b, c, d, a), saved + d))
    }

    @Test
    fun `only the last two cycles are examined`() {
        // An ancient inconsistency, older than the two cycles a period is judged over, must not
        // veto a live pattern.
        assertEquals(b.id, predict(c, a, a, b, a, b, a))
    }

    @Test
    fun `a session is labeled by its exercise set regardless of order or repeats`() {
        val shuffled = listOf(listOf(3L, 1L, 2L, 1L).toSet(), a.exerciseIds)
        assertEquals(a.id, WorkoutRoutine.nextWorkoutId(shuffled, saved))
    }

    @Test
    fun `a session with extra exercises matches nothing`() {
        assertNull(WorkoutRoutine.nextWorkoutId(listOf(a.exerciseIds + 42L, a.exerciseIds), saved))
    }

    @Test
    fun `identical saved workouts resolve to the lowest id`() {
        val twin = WorkoutRoutine.Candidate(id = 5L, exerciseIds = a.exerciseIds)
        assertEquals(twin.id, WorkoutRoutine.nextWorkoutId(history(a, a), saved + twin))
    }

    @Test
    fun `empty sessions are skipped rather than breaking the run`() {
        assertEquals(b.id, WorkoutRoutine.nextWorkoutId(
            listOf(a.exerciseIds, emptySet(), b.exerciseIds, a.exerciseIds),
            saved,
        ))
    }
}
