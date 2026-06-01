package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import org.junit.Assert.fail
import org.junit.Test

class ExerciseCoefficientsTest {
    @Test
    fun allLibraryExercisesHaveCoefficients() {
        val missing = ExerciseLibrary.exercises
            .filter { it.name !in ExerciseCoefficients.byName }
            .map { it.name }
        if (missing.isNotEmpty()) {
            fail("Exercises missing from ExerciseCoefficients.byName:\n" + missing.joinToString("\n"))
        }
    }
}
