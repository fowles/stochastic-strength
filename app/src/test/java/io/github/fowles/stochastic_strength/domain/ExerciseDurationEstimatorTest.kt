package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseDurationEstimatorTest {

    private fun session(id: Long, startTime: Long) =
        WorkoutSession(id = id, startTime = startTime, endTime = startTime + 60_000L)

    private fun set(
        sessionId: Long,
        exerciseId: Long,
        setNumber: Int,
        completedAt: Long?,
        feedback: SetFeedback? = SetFeedback.RIR_2_4,
    ) = WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = 80f,
        targetReps = 8,
        feedback = feedback,
        completedAt = completedAt,
    )

    @Test
    fun `empty input yields no learned values`() {
        val estimator = ExerciseDurationEstimator.build(
            sessionsNewestFirst = emptyList(),
            setsBySessionId = emptyMap(),
        )
        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `single appearance with predecessor returns wall-clock from predecessor end to last set`() {
        // Session at T=0; exercise 1 last set at T+60s; exercise 2 first set at T+120s, last at T+360s.
        // For exercise 2, predecessorEnd = 60_000, end = 360_000, duration = 300s.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L),
            set(10L, exerciseId = 2L, setNumber = 1, completedAt = 120_000L),
            set(10L, exerciseId = 2L, setNumber = 2, completedAt = 240_000L),
            set(10L, exerciseId = 2L, setNumber = 3, completedAt = 360_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertEquals(300, estimator.secondsFor(2L))
    }

    @Test
    fun `first exercise in session uses session startTime as predecessor`() {
        // session startTime = 1000; exercise 1's last set at 1000 + 240_000 ms → 240s.
        val sessions = listOf(session(id = 10L, startTime = 1_000L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 1_000L + 80_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 1_000L + 160_000L),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 1_000L + 240_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertEquals(240, estimator.secondsFor(1L))
    }

    @Test
    fun `appearance with HURT feedback in any set is skipped`() {
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 120_000L, feedback = SetFeedback.RIR_2_4),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 180_000L, feedback = SetFeedback.HURT),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `appearance with any null completedAt is skipped`() {
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 120_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = null),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 360_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `appearance shorter than MIN_SECONDS is skipped`() {
        // 40s wall-clock from session start, below the 60s floor.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 10_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 25_000L),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 40_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `appearance longer than MAX_SECONDS is skipped`() {
        // 1500s wall-clock from session start, above the 1200s ceiling.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 500_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 1_000_000L),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 1_500_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `multiple appearances are averaged and rounded`() {
        // Two sessions, each with one appearance of exercise 1.
        // Session 20: 300s. Session 10: 360s. Mean = 330.
        val sessions = listOf(
            session(id = 20L, startTime = 1_000_000L),
            session(id = 10L, startTime = 0L),
        )
        val sets = mapOf(
            20L to listOf(
                set(20L, exerciseId = 1L, setNumber = 1, completedAt = 1_000_000L + 100_000L),
                set(20L, exerciseId = 1L, setNumber = 2, completedAt = 1_000_000L + 200_000L),
                set(20L, exerciseId = 1L, setNumber = 3, completedAt = 1_000_000L + 300_000L),
            ),
            10L to listOf(
                set(10L, exerciseId = 1L, setNumber = 1, completedAt = 120_000L),
                set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L),
                set(10L, exerciseId = 1L, setNumber = 3, completedAt = 360_000L),
            ),
        )

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertEquals(330, estimator.secondsFor(1L))
    }

    @Test
    fun `only the most recent MAX_APPEARANCES are kept per exercise`() {
        // 11 sessions, exercise 1 appears once in each. Session N has duration 100 + N seconds.
        // Newest-first: durations 111, 110, 109, ..., 102 → 10 newest = 111..102, mean = 106.5 → 107.
        val sessions = (1..11).map { n ->
            session(id = n.toLong(), startTime = (n * 10_000_000L))
        }.sortedByDescending { it.startTime }

        val sets = (1..11).associate { n ->
            val start = n * 10_000_000L
            val durationMs = (100 + n) * 1000L
            n.toLong() to listOf(
                set(n.toLong(), exerciseId = 1L, setNumber = 1, completedAt = start + durationMs / 3),
                set(n.toLong(), exerciseId = 1L, setNumber = 2, completedAt = start + 2 * durationMs / 3),
                set(n.toLong(), exerciseId = 1L, setNumber = 3, completedAt = start + durationMs),
            )
        }

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        // 10 newest durations: 111..102 inclusive. Sum = (111+102)*10/2 = 1065. Mean = 106.5 → 107.
        assertEquals(107, estimator.secondsFor(1L))
    }

    @Test
    fun `predecessor uses max completedAt before this exercise's first set across all exercises`() {
        // Session startTime = 0. Exercise 1's sets at 60_000 and 120_000.
        // Exercise 2 starts at 130_000. Predecessor for exercise 2 = 120_000 (exercise 1's last).
        // Exercise 2's last set at 130_000 + 200_000 = 330_000. Duration = 210s.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 120_000L),
            set(10L, exerciseId = 2L, setNumber = 1, completedAt = 130_000L),
            set(10L, exerciseId = 2L, setNumber = 2, completedAt = 220_000L),
            set(10L, exerciseId = 2L, setNumber = 3, completedAt = 330_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertEquals(210, estimator.secondsFor(2L))
    }
}
