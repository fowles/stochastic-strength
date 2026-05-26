package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
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
        val result = WorkoutGenerator.generate(WorkoutGenerator.Input(emptyList()))
        assertTrue(result.isEmpty())
    }

    @Test
    fun noMuscleGroupExceedsMax() {
        val exercises = (1L..6L).map { exercise(it, MuscleGroup.CHEST) }
        val result = WorkoutGenerator.generate(WorkoutGenerator.Input(exercises))
        val chestCount = result.count { it.exercise.primaryMuscle == MuscleGroup.CHEST }
        assertTrue(chestCount <= WorkoutGenerator.MAX_PER_MUSCLE)
    }

    @Test
    fun fillsDefaultExerciseCount() {
        val result = WorkoutGenerator.generate(WorkoutGenerator.Input(fullPool()))
        assertEquals(WorkoutGenerator.DEFAULT_EXERCISE_COUNT, result.size)
    }

    @Test
    fun deterministicWithFixedSeed() {
        val exercises = MuscleGroup.entries.flatMapIndexed { gi, muscle ->
            listOf(exercise((gi * 2 + 1).toLong(), muscle), exercise((gi * 2 + 2).toLong(), muscle))
        }
        val r1 = WorkoutGenerator.generate(WorkoutGenerator.Input(exercises, Random(42)))
        val r2 = WorkoutGenerator.generate(WorkoutGenerator.Input(exercises, Random(42)))
        assertEquals(r1.map { it.exercise.id }, r2.map { it.exercise.id })
    }

    @Test
    fun stopsWhenPoolSmallerThanDefault() {
        val exercises = MuscleGroup.entries.take(5).mapIndexed { i, muscle ->
            exercise((i + 1).toLong(), muscle)
        }
        val result = WorkoutGenerator.generate(WorkoutGenerator.Input(exercises))
        assertEquals(5, result.size)
    }
}
