package io.github.fowles.stochastic_strength.domain

/**
 * Decides whether the user is following a routine — AA, ABAB, ABCABC — and therefore which saved
 * workout "start a workout" should open with. Random is the default and the answer whenever no
 * routine is evident.
 *
 * Sessions carry no record of the saved workout they came from, so a session is labeled by
 * content: its set of exercise ids, matched against a saved workout's. That makes the heuristic
 * work on history that predates it, at the cost of not recognizing a session the user edited
 * after loading — which simply reads as "not part of a routine", and falls back to random.
 */
object WorkoutRoutine {

    /** The longest routine recognized: a three-day rotation. */
    private const val MAX_PERIOD = 3

    /** A saved workout reduced to what labeling needs. */
    data class Candidate(val id: Long, val exerciseIds: Set<Long>)

    /**
     * The saved workout to open with, or null for a random workout.
     *
     * @param recentSessions completed sessions **newest first**, each as the set of distinct
     *   exercise ids it logged. Sessions that logged nothing are ignored.
     * @param saved every saved workout the user has.
     */
    fun nextWorkoutId(recentSessions: List<Set<Long>>, saved: List<Candidate>): Long? {
        // A run ends at the first session that matches no saved workout: a random day means the
        // user is not following a routine right now, and the default is random.
        val run = recentSessions
            .filter { it.isNotEmpty() }
            .map { session -> label(session, saved) }
            .takeWhile { it != null }
            .filterNotNull()

        for (period in 1..MAX_PERIOD) {
            // Judge a period over its last two cycles, no further back: an inconsistency older
            // than that belongs to a routine the user has already moved on from.
            val window = run.take(period * 2)
            val pairs = window.size - period
            // Fewer than two occurrences of the period is no evidence of repetition at all — the
            // first two sessions of a would-be alternation are indistinguishable from two
            // unrelated days.
            if (pairs < 1) continue
            if ((0 until pairs).all { window[it] == window[it + period] }) {
                // One full period back from the newest session is the one now due.
                return window[period - 1]
            }
        }
        return null
    }

    /** The saved workout whose exercises are exactly this session's, lowest id wins a tie. */
    private fun label(session: Set<Long>, saved: List<Candidate>): Long? =
        saved.filter { it.exerciseIds == session }.minByOrNull { it.id }?.id
}
