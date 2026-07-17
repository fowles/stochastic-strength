package io.github.fowles.stochastic_strength.domain.progression

/**
 * Pure sparkline transforms for the exercises list. No Android, no DB.
 */
object ExerciseSparklines {
    /** Default lookback for the exercises-list sparklines: ~6 months. */
    const val DEFAULT_WINDOW_MS: Long = 182L * 24 * 3600 * 1000

    /**
     * Reduces every exercise's merged 1RM series to the bare values in the input's session/time
     * order, keeping only points in `max(nowMs − windowMs, firstPerformed) .. nowMs`.
     *
     * The window floor is raised to the exercise's first actually-performed set time
     * ([firstPerformedById]) because the merged series carries sibling-driven points from before a
     * lift's own debut (pooling records a point for every exercise in a touched muscle) — those
     * leading points are not this lift's progress and must not be drawn. An exercise absent from
     * [firstPerformedById] (never performed itself) is dropped entirely.
     *
     * Series with fewer than 2 surviving points are dropped — a sparkline needs at least two points
     * to have a shape — so sparse/new lifts get no entry (their row shows nothing).
     */
    fun windowValues(
        seriesById: Map<Long, List<ProgressionPoint>>,
        firstPerformedById: Map<Long, Long>,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): Map<Long, List<Float>> {
        val cutoff = nowMs - windowMs
        return seriesById.mapNotNull { (id, points) ->
            val firstPerformed = firstPerformedById[id] ?: return@mapNotNull null
            val floor = maxOf(cutoff, firstPerformed)
            val values = points.filter { it.timestampMs in floor..nowMs }.map { it.value }
            if (values.size < 2) null else id to values
        }.toMap()
    }

    /**
     * Maps [values] to vertical offsets in `[0, 1]`: min → 0, max → 1 (a flat series → all 0.5). The
     * renderer flips these to y-coordinates. Returns empty for fewer than 2 values.
     */
    fun normalize(values: List<Float>): List<Float> {
        if (values.size < 2) return emptyList()
        val min = values.min()
        val max = values.max()
        val span = max - min
        if (span <= 0f) return values.map { 0.5f }
        return values.map { (it - min) / span }
    }
}
