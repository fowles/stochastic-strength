package io.github.fowles.stochastic_strength.ui.exercises

import io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionData
import io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint
import io.github.fowles.stochastic_strength.ui.debug.components.timestampToLocalEpochDay
import java.time.ZoneId

/** One plotted point, keyed to the start of its local day (the chart's x-grid). */
data class ChartPoint(val dateMs: Long, val weightKg: Float)

/**
 * The exercise-detail chart's three series, read straight off the belief pipeline: the pooled
 * estimate line plus the observed dots the fold consumed. The debug progression chart plots the
 * same [ExerciseProgressionData], so the two views agree point for point.
 */
internal data class ExerciseChartSeries(
    /** The pooled (merged) estimated-1RM trend. Empty for an exercise the pool never covers. */
    val estimate: List<ChartPoint>,
    val own: List<ChartPoint>,
    val siblings: List<ChartPoint>,
)

/** Buckets pipeline points onto the chart's local-day x-grid, keeping order and duplicates. */
private fun List<ProgressionPoint>.toChartPoints(zone: ZoneId): List<ChartPoint> =
    map { ChartPoint(dateMs = timestampToLocalEpochDay(it.timestampMs, zone) * 86_400_000L, weightKg = it.value) }

internal fun exerciseChartSeries(data: ExerciseProgressionData, zone: ZoneId): ExerciseChartSeries =
    ExerciseChartSeries(
        estimate = data.series.merged.toChartPoints(zone),
        own = data.series.ownObservations.toChartPoints(zone),
        siblings = data.series.siblingObservations.toChartPoints(zone),
    )
