package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import kotlin.random.Random

object WorkoutGenerator {
    const val DEFAULT_EXERCISE_COUNT = 6
    const val MAX_PER_MUSCLE = 2

    data class Input(
        val exercises: List<Exercise>,
        val random: Random = Random.Default,
    )

    fun pickReplacement(input: Input, currentExercises: List<PlannedExercise>): PlannedExercise? {
        val muscleCounts = currentExercises.groupingBy { it.exercise.primaryMuscle }.eachCount()
        val preferred = input.exercises.filter { (muscleCounts[it.primaryMuscle] ?: 0) < MAX_PER_MUSCLE }
        val pick = preferred.ifEmpty { input.exercises }.randomOrNull(input.random) ?: return null
        return PlannedExercise(exercise = pick)
    }

    fun generate(input: Input): List<PlannedExercise> {
        val shuffled = input.exercises.shuffled(input.random)
        val muscleCount = mutableMapOf<MuscleGroup, Int>()
        val result = mutableListOf<PlannedExercise>()

        for (exercise in shuffled) {
            if (result.size >= DEFAULT_EXERCISE_COUNT) break
            if ((muscleCount[exercise.primaryMuscle] ?: 0) >= MAX_PER_MUSCLE) continue

            result.add(PlannedExercise(exercise = exercise))
            muscleCount.merge(exercise.primaryMuscle, 1, Int::plus)
        }

        return result
    }
}
