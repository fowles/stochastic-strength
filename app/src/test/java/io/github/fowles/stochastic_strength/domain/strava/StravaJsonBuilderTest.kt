package io.github.fowles.stochastic_strength.domain.strava

import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import org.junit.Assert.fail
import org.junit.Test

class StravaJsonBuilderTest {
    @Test
    fun allLibraryExercisesHaveJsonMapping() {
        val missing = ExerciseLibrary.exercises
            .filter { StravaJsonBuilder.exerciseNameToJsonType(it.name) == "TOTAL_BODY_GENERIC" }
            .map { it.name }
        if (missing.isNotEmpty()) {
            fail("Exercises falling through to generic in StravaJsonBuilder:\n" + missing.joinToString("\n"))
        }
    }
}
