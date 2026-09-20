package io.github.fowles.stochastic_strength.ui.debug.components

import androidx.compose.ui.graphics.Color
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

data class DebugChartPoint(val timestampMs: Long, val value: Float)

/**
 * Converts a wall-clock timestamp to an epoch-day index anchored in [zone].
 *
 * The chart x-axis must agree with the event-row dates (which are formatted
 * in the system default zone), so the index must reflect the *local*
 * calendar day rather than UTC.
 */
internal fun timestampToLocalEpochDay(timestampMs: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate().toEpochDay()

/**
 * Renders an epoch-day index using [sdf], whose [SimpleDateFormat.timeZone]
 * must match the zone used to produce the index.
 */
internal fun epochDayLabel(epochDay: Long, sdf: SimpleDateFormat): String =
    sdf.format(Date(LocalDate.ofEpochDay(epochDay).atStartOfDay(sdf.timeZone.toZoneId()).toInstant().toEpochMilli()))

/**
 * Builds the floating marker label shown when the user holds a point.
 * Combines the x-axis label with each line-series y value at that point.
 *
 * Lines whose color is [excludeColor] are omitted from the value list — used to
 * drop a drawn trend line (e.g. the prescribed-target line) so the label reports
 * only the plotted data points. When that leaves no values, only the x label is
 * shown.
 */
internal fun formatLineMarkerLabel(
    targets: List<CartesianMarker.Target>,
    xLabel: (Double) -> String,
    yLabel: (Double) -> String,
    excludeColor: Color? = null,
): CharSequence {
    val target = targets.firstOrNull() ?: return ""
    val x = xLabel(target.x)
    val points = (target as? LineCartesianLayerMarkerTarget)?.points ?: return x
    val ys = points.filter { excludeColor == null || it.color != excludeColor }.map { yLabel(it.entry.y) }
    return if (ys.isEmpty()) x else "$x • ${ys.joinToString(" / ")}"
}
