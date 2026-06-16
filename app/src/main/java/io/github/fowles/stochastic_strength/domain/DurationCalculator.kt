package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.domain.model.WarmupSet

object DurationCalculator {
    // Mirrors WorkoutSessionController.REST_SECONDS — kept here to avoid a domain → ui dep.
    const val REST_SECONDS = 90
    const val WARMUP_REST_SECONDS = 30
    const val DEFAULT_SECONDS_PER_REP = 3.0f

    fun estimate(
        exercise: Exercise,
        sessionReps: Int,
        numSets: Int,
        warmupSets: List<WarmupSet>,
        secondsPerRep: Float?,
    ): Int {
        if (exercise.isTimed) {
            return numSets * (sessionReps + REST_SECONDS)
        }
        val perRep = secondsPerRep ?: DEFAULT_SECONDS_PER_REP
        val sides = if (exercise.isUnilateral) 2 else 1
        val workPerSet = perRep * sessionReps * sides
        val workingTime = numSets * (workPerSet + REST_SECONDS)

        val warmupWork = warmupSets.sumOf { (it.reps * perRep * sides).toDouble() }.toFloat()
        val warmupRest = warmupSets.size * WARMUP_REST_SECONDS
        val plateChangeTime = plateChangeSec(exercise.equipment) * (warmupSets.size + 1)

        return (workingTime + warmupWork + warmupRest + plateChangeTime).toInt()
    }

    fun plateChangeSec(equipment: Equipment): Int = when (equipment) {
        Equipment.BARBELL -> 25
        Equipment.DUMBBELL -> 8
        Equipment.KETTLEBELL -> 5
        Equipment.MACHINE -> 5
        Equipment.CABLE_MACHINE -> 5
        Equipment.BODYWEIGHT -> 0
        Equipment.BAND -> 0
    }
}
