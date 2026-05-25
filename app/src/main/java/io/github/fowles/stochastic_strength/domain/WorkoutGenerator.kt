package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import kotlin.random.Random

object WorkoutGenerator {
    const val SECONDS_PER_SET = 135  // 90s rest + 45s work
    const val TARGET_SECONDS = 2700  // 45 minutes
    const val MAX_PER_MUSCLE = 2

    data class Input(
        val exercises: List<Exercise>,
        val states: Map<Long, ExerciseState>,
        val random: Random = Random.Default,
    )

    fun pickReplacement(input: Input, currentExercises: List<PlannedExercise>): PlannedExercise? {
        val muscleCounts = currentExercises.groupingBy { it.exercise.primaryMuscle }.eachCount()
        val preferred = input.exercises.filter { (muscleCounts[it.primaryMuscle] ?: 0) < MAX_PER_MUSCLE }
        val pick = preferred.ifEmpty { input.exercises }.randomOrNull(input.random) ?: return null
        return PlannedExercise(
            exercise = pick,
            state = input.states[pick.id] ?: ExerciseState(exerciseId = pick.id),
        )
    }

    fun generate(input: Input): List<PlannedExercise> {
        val shuffled = input.exercises.shuffled(input.random)
        val muscleCount = mutableMapOf<MuscleGroup, Int>()
        val result = mutableListOf<PlannedExercise>()
        var timeUsed = 0

        for (exercise in shuffled) {
            if ((muscleCount[exercise.primaryMuscle] ?: 0) >= MAX_PER_MUSCLE) continue

            val state = input.states[exercise.id] ?: ExerciseState(exerciseId = exercise.id)
            result.add(PlannedExercise(exercise = exercise, state = state))
            muscleCount[exercise.primaryMuscle] = (muscleCount[exercise.primaryMuscle] ?: 0) + 1
            timeUsed += state.currentSets * SECONDS_PER_SET

            if (timeUsed >= TARGET_SECONDS) break
        }

        return result
    }
}
