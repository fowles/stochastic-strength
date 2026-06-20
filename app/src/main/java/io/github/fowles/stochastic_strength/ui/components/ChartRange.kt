package io.github.fowles.stochastic_strength.ui.components

import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import kotlin.math.abs

/**
 * Computes a padded `[min, max]` Y-axis range so a chart never zooms in so
 * tightly that small variation looks like large swings.
 *
 * The range always extends at least [minCentralFraction] above and below the
 * central (midpoint) value, and at least [spreadFraction] of the data spread
 * beyond each end — whichever is wider. This keeps flat or low-variance series
 * from being magnified into apparent jumps while still giving wide-spread
 * series a little breathing room.
 */
fun paddedChartYRange(
    minY: Double,
    maxY: Double,
    minCentralFraction: Double = 0.10,
    spreadFraction: Double = 0.15,
): ClosedFloatingPointRange<Double> {
    val center = (minY + maxY) / 2.0
    val centralPad = abs(center) * minCentralFraction
    val spreadPad = (maxY - minY) * spreadFraction
    val low = minOf(minY - spreadPad, center - centralPad)
    val high = maxOf(maxY + spreadPad, center + centralPad)
    return low..high
}

/**
 * A [CartesianLayerRangeProvider] backed by [paddedChartYRange]. Shared by the
 * app's line charts so they all apply the same minimum-spread padding policy.
 */
fun paddedChartRangeProvider(): CartesianLayerRangeProvider =
    object : CartesianLayerRangeProvider {
        override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double =
            paddedChartYRange(minY, maxY).start

        override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double =
            paddedChartYRange(minY, maxY).endInclusive
    }
