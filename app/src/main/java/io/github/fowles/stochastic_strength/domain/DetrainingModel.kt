package io.github.fowles.stochastic_strength.domain

/**
 * Pure detraining math: how far to ease baselines down after a layoff.
 *
 * Suggested reduction is 5% per whole week off, capped at 50%. A prompt is only
 * offered once the gap reaches a full week.
 */
object DetrainingModel {
    const val WEEK_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    const val PER_WEEK: Float = 0.05f
    const val MAX_FRACTION: Float = 0.50f

    fun weeksOff(lastEndTime: Long, now: Long): Int =
        ((now - lastEndTime) / WEEK_MILLIS).toInt().coerceAtLeast(0)

    fun suggestedFraction(weeksOff: Int): Float =
        (PER_WEEK * weeksOff).coerceIn(0f, MAX_FRACTION)

    fun qualifies(weeksOff: Int): Boolean = weeksOff >= 1

    fun reduce(baseline: Float, fraction: Float): Float = baseline * (1f - fraction)

    /**
     * Multiplicative fresh-1RM retention across an idle gap of [gapMillis] — the inferred
     * detraining factor. `1f` below one week; drops [PER_WEEK] per whole week, floored at
     * `1 - MAX_FRACTION`. Applied prospectively to the comeback prescription; the set log
     * self-corrects the belief afterward.
     */
    fun retention(gapMillis: Long): Float {
        val weeks = (gapMillis / WEEK_MILLIS).toInt().coerceAtLeast(0)
        return 1f - suggestedFraction(weeks)
    }
}
