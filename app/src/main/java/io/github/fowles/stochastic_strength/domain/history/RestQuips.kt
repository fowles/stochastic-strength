package io.github.fowles.stochastic_strength.domain.history

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import kotlin.random.Random

/**
 * Occasional quip for the rest-timer screen. Deliberately scarce: at ~15 rests per
 * workout, 4% averages under one sighting per workout, so no repeat-avoidance
 * state is needed. [upcomingMuscles] is the muscle set of the exercise the rest
 * precedes; null means this is the final rest (the Done screen's HighlightCard
 * follows immediately, so never quip there).
 */
object RestQuips {
    const val QUIP_PROBABILITY = 0.04f

    fun pick(upcomingMuscles: Set<MuscleGroup>?, random: Random): String? {
        if (upcomingMuscles == null) return null
        if (random.nextFloat() >= QUIP_PROBABILITY) return null
        val eligible = HistoryHighlight.QUIPS.filter {
            it.muscle == null || it.muscle in upcomingMuscles
        }
        return eligible.random(random).text
    }
}
