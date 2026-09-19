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
import io.github.fowles.stochastic_strength.data.model.SavedWorkout
import io.github.fowles.stochastic_strength.data.model.SavedWorkoutExercise
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                durationSeconds = null, circuitId = 0),
            WorkoutSet(id = 2, sessionId = 9, exerciseId = 5, setNumber = 2, targetWeight = 60.5f,
                targetReps = 5, actualReps = null, feedback = null, completedAt = null, durationSeconds = 42),
        ),
        userProfile = listOf(UserProfile(id = 1, sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM,
            weightUnit = WeightUnit.LBS, preferredExerciseCount = 6, preferredRepMin = null,
            preferredRepMax = 8)),
        baselineOverrides = listOf(BaselineOverride(id = 3, sessionId = null, muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 80.25f, asOf = 0, reason = BaselineChangeReason.OVERRIDE)),
        exerciseHurtState = listOf(ExerciseHurtState(exerciseId = 5, isHurt = true, asOf = 500)),
        savedWorkouts = listOf(SavedWorkout(id = 11, name = "Push", createdAt = 1)),
        savedWorkoutExercises = listOf(
            SavedWorkoutExercise(id = 21, workoutId = 11, exerciseId = 5, position = 0, reps = 8,
                sets = 2, circuitId = 0),
        ),
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

    /** A v20 export: no `sets`/`circuitId` keys anywhere. */
    private val v20Json = """
        {"format":"stochastic-strength-backup","formatVersion":1,"dbVersion":20,"exportedAt":1,
         "tables":{"exercises":[],"knownLocations":[],"locationExcludedExercises":[],
           "workoutSessions":[],"userProfile":[],"baselineOverrides":[],"exerciseHurtState":[],
           "workoutSets":[{"id":1,"sessionId":1,"exerciseId":1,"setNumber":1,"targetWeight":40.0,
             "targetReps":5,"actualReps":5,"feedback":"RIR_2_4","completedAt":9,"durationSeconds":null}],
           "savedWorkouts":[{"id":1,"name":"Push","createdAt":1}],
           "savedWorkoutExercises":[{"id":1,"workoutId":1,"exerciseId":1,"position":0,"reps":null}]}}
    """.trimIndent()

    @Test
    fun parse_acceptsV20File_defaultingStructureFields() {
        val backup = BackupJsonParser.parse(v20Json)
        assertEquals(3, backup.savedWorkoutExercises.single().sets)
        assertNull(backup.savedWorkoutExercises.single().circuitId)
        assertNull(backup.workoutSets.single().circuitId)
    }

    @Test
    fun parse_rejectsVersionsOutsideTheWindow() {
        for (v in listOf(19, 22)) {
            val ex = assertThrows(BackupFormatException::class.java) {
                BackupJsonParser.parse("""{"format":"stochastic-strength-backup","dbVersion":$v,"tables":{}}""")
            }
            assert(ex.message!!.contains("v$v"))
        }
    }
}
