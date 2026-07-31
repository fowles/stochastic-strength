package io.github.fowles.stochastic_strength.domain

/**
 * Pure detraining math: how far to ease baselines down after a layoff.
 *
 * Suggested reduction is 5% per whole week off, capped at 50%. Detraining only kicks in
 * once the gap reaches [MIN_WEEKS_OFF] full weeks — shorter breaks (including a normal
 * weekly training cadence) leave prescriptions untouched.
 */
object DetrainingModel {
    const val WEEK_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    const val PER_WEEK: Float = 0.05f
    const val MAX_FRACTION: Float = 0.50f

    /** `semantic`: detraining window opens at two full weeks (14 days) off, not one. */
    const val MIN_WEEKS_OFF: Int = 2

    fun weeksOff(lastEndTime: Long, now: Long): Int =
        ((now - lastEndTime) / WEEK_MILLIS).toInt().coerceAtLeast(0)

    fun suggestedFraction(weeksOff: Int): Float =
        (PER_WEEK * weeksOff).coerceIn(0f, MAX_FRACTION)

    fun qualifies(weeksOff: Int): Boolean = weeksOff >= MIN_WEEKS_OFF

    fun reduce(baseline: Float, fraction: Float): Float = baseline * (1f - fraction)

    /**
     * Multiplicative fresh-1RM retention across an idle gap of [gapMillis] — the inferred
     * detraining factor. `1f` until the gap reaches [MIN_WEEKS_OFF] weeks; then drops
     * [PER_WEEK] per whole week, floored at `1 - MAX_FRACTION`. Applied prospectively to the
     * comeback prescription; the set log self-corrects the belief afterward.
     */
    fun retention(gapMillis: Long): Float {
        val weeks = (gapMillis / WEEK_MILLIS).toInt().coerceAtLeast(0)
        if (!qualifies(weeks)) return 1f
        return 1f - suggestedFraction(weeks)
    }
}
