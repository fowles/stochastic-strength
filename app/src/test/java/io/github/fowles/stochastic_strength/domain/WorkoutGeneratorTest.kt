package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WorkoutGeneratorTest {

    private fun exercise(id: Long, muscle: MuscleGroup) =
        Exercise(id = id, name = "Ex$id", primaryMuscle = muscle, equipment = Equipment.BODYWEIGHT)

    private fun fullPool(): List<Exercise> =
        MuscleGroup.entries.flatMapIndexed { gi, muscle ->
            (0..2).map { i -> exercise((gi * 3 + i + 1).toLong(), muscle) }
        }

    @Test
    fun emptyPoolReturnsEmpty() {
        val result = WorkoutGenerator.generate(WorkoutGenerator.Input(emptyList(), emptyMap()))
        assertTrue(result.isEmpty())
    }

    @Test
    fun noMuscleGroupExceedsMax() {
        val exercises = (1L..6L).map { exercise(it, MuscleGroup.CHEST) }
        val result = WorkoutGenerator.generate(WorkoutGenerator.Input(exercises, emptyMap()))
        val chestCount = result.count { it.exercise.primaryMuscle == MuscleGroup.CHEST }
        assertTrue(chestCount <= WorkoutGenerator.MAX_PER_MUSCLE)
    }

    @Test
    fun fillsDefaultExerciseCount() {
        val result = WorkoutGenerator.generate(WorkoutGenerator.Input(fullPool(), emptyMap()))
        assertEquals(WorkoutGenerator.DEFAULT_EXERCISE_COUNT, result.size)
    }

    @Test
    fun usesExistingStateSetCount() {
        val ex = exercise(1L, MuscleGroup.CHEST)
        val state = ExerciseState(exerciseId = 1L, currentSets = 4)
        val result = WorkoutGenerator.generate(WorkoutGenerator.Input(listOf(ex), mapOf(1L to state)))
        assertEquals(4, result.first().state.currentSets)
    }

    @Test
    fun deterministicWithFixedSeed() {
        val exercises = MuscleGroup.entries.flatMapIndexed { gi, muscle ->
            listOf(exercise((gi * 2 + 1).toLong(), muscle), exercise((gi * 2 + 2).toLong(), muscle))
        }
        val r1 = WorkoutGenerator.generate(WorkoutGenerator.Input(exercises, emptyMap(), Random(42)))
        val r2 = WorkoutGenerator.generate(WorkoutGenerator.Input(exercises, emptyMap(), Random(42)))
        assertEquals(r1.map { it.exercise.id }, r2.map { it.exercise.id })
    }

    @Test
    fun stopsWhenPoolSmallerThanDefault() {
        val exercises = MuscleGroup.entries.take(5).mapIndexed { i, muscle ->
            exercise((i + 1).toLong(), muscle)
        }
        val result = WorkoutGenerator.generate(WorkoutGenerator.Input(exercises, emptyMap()))
        assertEquals(5, result.size)
    }
}
