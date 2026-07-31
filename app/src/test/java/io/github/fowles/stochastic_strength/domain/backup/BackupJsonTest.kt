package io.github.fowles.stochastic_strength.domain.backup

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
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
}
