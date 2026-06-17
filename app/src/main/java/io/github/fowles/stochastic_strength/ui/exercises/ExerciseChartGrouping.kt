package io.github.fowles.stochastic_strength.ui.exercises

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import java.time.Instant
import java.time.ZoneId

/** Groups workout sets into chart day-buckets by the start time of their session. */
object ExerciseChartGrouping {

    /**
     * Local-date day index (days since the epoch, in [zone]) of the session this
     * set belongs to.
     *
     * Sets are bucketed by when their workout *started*, not by each set's own
     * completion time, so a workout that crosses midnight stays a single point.
     * Day boundaries follow [zone] (the phone's timezone), so a workout shows up
     * on the calendar day the user actually trained. Falls back to the set's own
     * completion time if its session start is unknown.
     */
    fun sessionDayKey(set: WorkoutSet, sessionStartById: Map<Long, Long>, zone: ZoneId): Long {
        val anchor = sessionStartById[set.sessionId] ?: set.completedAt ?: 0L
        return Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate().toEpochDay()
    }
}
