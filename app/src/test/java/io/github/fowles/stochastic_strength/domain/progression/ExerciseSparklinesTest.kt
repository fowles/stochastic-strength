package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSparklinesTest {
    private val day = 24L * 3600 * 1000
    private fun p(daysAgo: Long, v: Float, now: Long) = ProgressionPoint(now - daysAgo * day, v)
    // Performed well before the window so first-performed trimming is out of the way unless tested.
    private fun performedLongAgo(now: Long) = mapOf(1L to now - 10_000L * day)

    @Test fun windowValues_keepsInWindowValuesInOrder() {
        val now = 1_000_000_000_000L
        val series = mapOf(
            1L to listOf(p(200, 100f, now), p(30, 110f, now), p(1, 120f, now)),
        )
        val out = ExerciseSparklines.windowValues(series, performedLongAgo(now), now, windowMs = 182L * day)
        // The 200-days-ago point is outside the 182-day window and is dropped.
        assertEquals(listOf(110f, 120f), out[1L])
    }

    @Test fun windowValues_dropsSeriesWithFewerThanTwoInWindowPoints() {
        val now = 1_000_000_000_000L
        val series = mapOf(
            1L to listOf(p(200, 100f, now), p(1, 120f, now)),  // only 1 in-window
            2L to emptyList(),                                  // none
        )
        val first = mapOf(1L to now - 10_000L * day, 2L to now - 10_000L * day)
        val out = ExerciseSparklines.windowValues(series, first, now, windowMs = 182L * day)
        assertTrue(out.isEmpty())
    }

    @Test fun windowValues_excludesFuturePoints() {
        val now = 1_000_000_000_000L
        val series = mapOf(1L to listOf(p(10, 100f, now), p(-5, 130f, now), p(2, 120f, now)))
        val out = ExerciseSparklines.windowValues(series, performedLongAgo(now), now, windowMs = 182L * day)
        // The point 5 days in the FUTURE (daysAgo = -5) is excluded; two valid points remain.
        assertEquals(listOf(100f, 120f), out[1L])
    }

    @Test fun windowValues_dropsPointsBeforeFirstPerformed() {
        val now = 1_000_000_000_000L
        // Three in-window sibling-informed points; the exercise was first performed itself 20 days ago.
        val series = mapOf(1L to listOf(p(60, 100f, now), p(40, 105f, now), p(10, 120f, now)))
        val first = mapOf(1L to now - 20 * day)
        val out = ExerciseSparklines.windowValues(series, first, now, windowMs = 182L * day)
        // Only the 10-days-ago point is at/after first-performed — that leaves 1 point, so dropped.
        assertTrue(out.isEmpty())
    }

    @Test fun windowValues_keepsFromFirstPerformedOnward() {
        val now = 1_000_000_000_000L
        val series = mapOf(1L to listOf(p(60, 100f, now), p(40, 105f, now), p(10, 120f, now)))
        val first = mapOf(1L to now - 50 * day)
        val out = ExerciseSparklines.windowValues(series, first, now, windowMs = 182L * day)
        // 60-days-ago precedes first-performed (50) and is dropped; 40 and 10 remain.
        assertEquals(listOf(105f, 120f), out[1L])
    }

    @Test fun windowValues_dropsExerciseNeverPerformedItself() {
        val now = 1_000_000_000_000L
        val series = mapOf(1L to listOf(p(30, 100f, now), p(10, 120f, now)))  // sibling-only points
        val out = ExerciseSparklines.windowValues(series, firstPerformedById = emptyMap(), nowMs = now, windowMs = 182L * day)
        assertTrue(out.isEmpty())
    }

    @Test fun normalize_mapsMinToZeroMaxToOne() {
        assertEquals(listOf(0f, 0.5f, 1f), ExerciseSparklines.normalize(listOf(10f, 20f, 30f)))
    }

    @Test fun normalize_flatSeriesIsAllHalf() {
        assertEquals(listOf(0.5f, 0.5f, 0.5f), ExerciseSparklines.normalize(listOf(42f, 42f, 42f)))
    }

    @Test fun normalize_tooFewValuesIsEmpty() {
        assertTrue(ExerciseSparklines.normalize(listOf(5f)).isEmpty())
        assertTrue(ExerciseSparklines.normalize(emptyList()).isEmpty())
    }
}
