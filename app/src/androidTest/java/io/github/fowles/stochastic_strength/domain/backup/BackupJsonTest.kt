package io.github.fowles.stochastic_strength.domain.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupJsonTest {

    private fun sampleBackup() = WorkoutBackup(
        formatVersion = WorkoutBackup.FORMAT_VERSION,
        dbVersion = WorkoutBackup.DB_VERSION,
        exportedAt = 1_719_000_000_000L,
        exercises = listOf(
            Exercise(id = 5, name = "Bench Press", primaryMuscle = MuscleGroup.CHEST,
                secondaryMuscles = listOf(MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS),
                equipment = Equipment.BARBELL, isDisliked = true, isUnilateral = false, isTimed = false),
        ),
        knownLocations = listOf(KnownLocation(id = 2, name = "Home", latitude = 1.5, longitude = -3.25)),
        locationExcludedExercises = listOf(LocationExcludedExercise(locationId = 2, exerciseId = 5)),
        workoutSessions = listOf(
            WorkoutSession(id = 9, locationId = 2, startTime = 100, endTime = 200, stravaActivityId = null),
            WorkoutSession(id = 10, locationId = null, startTime = 300, endTime = null, stravaActivityId = 77),
        ),
        workoutSets = listOf(
            WorkoutSet(id = 1, sessionId = 9, exerciseId = 5, setNumber = 1, targetWeight = 60.5f,
                targetReps = 5, actualReps = 4, feedback = SetFeedback.RIR_2_4, completedAt = 150,
                durationSeconds = null),
            WorkoutSet(id = 2, sessionId = 9, exerciseId = 5, setNumber = 2, targetWeight = 60.5f,
                targetReps = 5, actualReps = null, feedback = null, completedAt = null, durationSeconds = 42),
        ),
        userProfile = listOf(UserProfile(id = 1, sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM,
            weightUnit = WeightUnit.LBS, preferredExerciseCount = 6, preferredRepMin = null,
            preferredRepMax = 8)),
        baselineOverrides = listOf(BaselineOverride(id = 3, sessionId = null, muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 80.25f, asOf = 0, reason = BaselineChangeReason.OVERRIDE)),
        exerciseHurtState = listOf(ExerciseHurtState(exerciseId = 5, isHurt = true, asOf = 500)),
    )

    @Test
    fun roundTrip_preservesEveryField() {
        val original = sampleBackup()
        val parsed = BackupJsonParser.parse(BackupJsonBuilder.build(original))
        assertEquals(original, parsed)
    }

    @Test
    fun roundTrip_emptyTables() {
        val empty = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0, exercises = emptyList(), knownLocations = emptyList(),
            locationExcludedExercises = emptyList(), workoutSessions = emptyList(), workoutSets = emptyList(),
            userProfile = emptyList(), baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
        )
        assertEquals(empty, BackupJsonParser.parse(BackupJsonBuilder.build(empty)))
    }

    @Test
    fun parse_rejectsWrongFormat() {
        val ex = assertThrows(BackupFormatException::class.java) {
            BackupJsonParser.parse("""{"format":"something-else","dbVersion":17,"tables":{}}""")
        }
        assert(ex.message!!.contains("Unrecognized"))
    }

    @Test
    fun parse_rejectsWrongDbVersion() {
        val ex = assertThrows(BackupFormatException::class.java) {
            BackupJsonParser.parse("""{"format":"stochastic-strength-backup","dbVersion":16,"tables":{}}""")
        }
        assert(ex.message!!.contains("v16"))
    }
}
