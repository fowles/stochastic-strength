package io.github.fowles.stochastic_strength.ui.exercises

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ExerciseChartGroupingTest {

    private val msPerDay = 86_400_000L
    private val utc = ZoneOffset.UTC

    private fun set(sessionId: Long, completedAt: Long) =
        WorkoutSet(
            sessionId = sessionId,
            exerciseId = 1L,
            setNumber = 1,
            targetWeight = 100f,
            targetReps = 5,
            completedAt = completedAt,
        )

    @Test fun setsOfSameSessionCrossingMidnightShareDayKey() {
        // Session started just before midnight on day 10.
        val sessionStart = 10 * msPerDay + (msPerDay - 60_000L) // 23:59 on day 10
        val sessionStartById = mapOf(1L to sessionStart)

        val beforeMidnight = set(sessionId = 1L, completedAt = sessionStart) // day 10
        val afterMidnight = set(sessionId = 1L, completedAt = 11 * msPerDay + 60_000L) // day 11

        val keyBefore = ExerciseChartGrouping.sessionDayKey(beforeMidnight, sessionStartById, utc)
        val keyAfter = ExerciseChartGrouping.sessionDayKey(afterMidnight, sessionStartById, utc)

        assertEquals(keyBefore, keyAfter)
        assertEquals(10L, keyBefore)
    }

    @Test fun differentSessionsGetDistinctKeys() {
        val sessionStartById = mapOf(1L to 10 * msPerDay, 2L to 12 * msPerDay)
        val a = set(sessionId = 1L, completedAt = 10 * msPerDay)
        val b = set(sessionId = 2L, completedAt = 12 * msPerDay)

        assertEquals(10L, ExerciseChartGrouping.sessionDayKey(a, sessionStartById, utc))
        assertEquals(12L, ExerciseChartGrouping.sessionDayKey(b, sessionStartById, utc))
    }

    @Test fun fallsBackToCompletedAtWhenSessionUnknown() {
        val s = set(sessionId = 99L, completedAt = 7 * msPerDay + 12_345L)
        assertEquals(7L, ExerciseChartGrouping.sessionDayKey(s, emptyMap(), utc))
    }

    @Test fun bucketsByLocalCalendarDayNotUtc() {
        // Workout started 8pm on Jan 15 in New York — which is already Jan 16 in UTC.
        val ny = ZoneId.of("America/New_York")
        val startInstant = LocalDateTime.of(2026, 1, 15, 20, 0).atZone(ny).toInstant()
        val sessionStartById = mapOf(1L to startInstant.toEpochMilli())
        val s = set(sessionId = 1L, completedAt = startInstant.toEpochMilli())

        // Local-zone bucketing attributes it to Jan 15...
        assertEquals(
            LocalDate.of(2026, 1, 15).toEpochDay(),
            ExerciseChartGrouping.sessionDayKey(s, sessionStartById, ny),
        )
        // ...whereas UTC would have wrongly put it on Jan 16.
        assertEquals(
            LocalDate.of(2026, 1, 16).toEpochDay(),
            ExerciseChartGrouping.sessionDayKey(s, sessionStartById, utc),
        )
    }
}
