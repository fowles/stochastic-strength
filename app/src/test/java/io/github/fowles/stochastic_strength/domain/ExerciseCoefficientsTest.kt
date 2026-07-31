package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import org.junit.Assert.assertEquals
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

    @Test
    fun byNameIsCompressedGuesses() {
        assertEquals(
            CoefficientCompression.compressAll(CoefficientGuesses.raw, ExerciseCoefficients.LAMBDA),
            ExerciseCoefficients.byName,
        )
    }

    @Test
    fun everyGuessSurvivesAsAKey() {
        // No exercise is dropped by the generator; anchors are preserved.
        assertEquals(CoefficientGuesses.raw.keys, ExerciseCoefficients.byName.keys)
        for ((name, g) in CoefficientGuesses.raw) {
            if (g == 0f) assertEquals(0f, ExerciseCoefficients.byName.getValue(name), 0f)
            if (g == 1f) assertEquals(1f, ExerciseCoefficients.byName.getValue(name), 0f)
        }
    }
}
