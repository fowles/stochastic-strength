package io.github.fowles.stochastic_strength.ui.exercises

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import java.time.Instant
import java.time.ZoneId

/** Groups workout sets into chart day-buckets by the anchor time of their session. */
object ExerciseChartGrouping {

    /**
     * Local-date day index (days since the epoch, in [zone]) of the session this
     * set belongs to.
     *
     * Sets are bucketed by their session's single anchor time in [sessionTimeById]
     * (the caller passes session end times), not by each set's own completion
     * time, so a workout that crosses midnight stays on one day. Day boundaries
     * follow [zone] (the phone's timezone). Falls back to the set's own
     * completion time if its session has no entry.
     */
    fun sessionDayKey(set: WorkoutSet, sessionTimeById: Map<Long, Long>, zone: ZoneId): Long {
        val anchor = sessionTimeById[set.sessionId] ?: set.completedAt ?: 0L
        return Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate().toEpochDay()
    }
}
