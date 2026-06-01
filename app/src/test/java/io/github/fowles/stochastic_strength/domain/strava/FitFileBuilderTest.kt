package io.github.fowles.stochastic_strength.domain.strava

import com.garmin.fit.ExerciseCategory
import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import org.junit.Assert.fail
import org.junit.Test

class FitFileBuilderTest {
    @Test
    fun allLibraryExercisesHaveFitMapping() {
        val missing = ExerciseLibrary.exercises
            .filter { FitFileBuilder.exerciseNameToFitCategory(it.name).first == ExerciseCategory.UNKNOWN }
            .map { it.name }
        if (missing.isNotEmpty()) {
            fail("Exercises missing from FitFileBuilder.exerciseNameToFitCategory:\n" + missing.joinToString("\n"))
        }
    }
}
