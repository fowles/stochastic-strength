package io.github.fowles.stochastic_strength.domain.backup

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SavedWorkout
import io.github.fowles.stochastic_strength.data.model.SavedWorkoutExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonTest {
    @Test
    fun `isAsymmetric survives export-import round trip`() {
        val exercise = Exercise(
            id = 7L,
            name = "Landmine Press",
            primaryMuscle = MuscleGroup.SHOULDERS,
            equipment = Equipment.BARBELL,
            isAsymmetric = true,
        )
        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION,
            dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0L,
            exercises = listOf(exercise),
            knownLocations = emptyList(),
            locationExcludedExercises = emptyList(),
            workoutSessions = emptyList(),
            workoutSets = emptyList(),
            userProfile = emptyList(),
            baselineOverrides = emptyList(),
            exerciseHurtState = emptyList(),
        )
        val json = BackupJsonBuilder.build(backup)
        val restored = BackupJsonParser.parse(json)
        assertTrue(restored.exercises.single { it.name == "Landmine Press" }.isAsymmetric)
    }

    @Test
    fun `saved workouts survive round trip and default to empty when absent`() {
        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION,
            dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0L,
            exercises = emptyList(), knownLocations = emptyList(), locationExcludedExercises = emptyList(),
            workoutSessions = emptyList(), workoutSets = emptyList(), userProfile = emptyList(),
            baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
            savedWorkouts = listOf(SavedWorkout(id = 3, name = "Push", createdAt = 9L)),
            savedWorkoutExercises = listOf(
                SavedWorkoutExercise(id = 1, workoutId = 3, exerciseId = 7, position = 0, reps = 5, weight = 42.5f),
                SavedWorkoutExercise(id = 2, workoutId = 3, exerciseId = 8, position = 1, reps = null),
            ),
        )
        val restored = BackupJsonParser.parse(BackupJsonBuilder.build(backup))
        assertEquals(backup.savedWorkouts, restored.savedWorkouts)
        assertEquals(backup.savedWorkoutExercises, restored.savedWorkoutExercises)

        // A pre-saved-workouts file (no arrays) still parses.
        val legacy = BackupJsonBuilder.build(backup.copy(savedWorkouts = emptyList(), savedWorkoutExercises = emptyList()))
            .replace("\"savedWorkouts\"", "\"_gone\"").replace("\"savedWorkoutExercises\"", "\"_gone2\"")
        val parsed = BackupJsonParser.parse(legacy)
        assertTrue(parsed.savedWorkouts.isEmpty())
        assertTrue(parsed.savedWorkoutExercises.isEmpty())
    }

    @Test
    fun `missing weight key defaults to null`() {
        // A saved-workout-exercise JSON object with no "weight" key at all (older export).
        val json = """
            {"format":"stochastic-strength-backup","formatVersion":1,"dbVersion":20,"exportedAt":1,
             "tables":{"exercises":[],"knownLocations":[],"locationExcludedExercises":[],
               "workoutSessions":[],"userProfile":[],"baselineOverrides":[],"exerciseHurtState":[],
               "workoutSets":[],
               "savedWorkouts":[{"id":1,"name":"Push","createdAt":1}],
               "savedWorkoutExercises":[{"id":1,"workoutId":1,"exerciseId":1,"position":0,"reps":null}]}}
        """.trimIndent()
        val backup = BackupJsonParser.parse(json)
        assertEquals(null, backup.savedWorkoutExercises.single().weight)
    }
}
