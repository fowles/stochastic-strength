package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WorkoutRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun applySessionProgression_logs_PROGRESSION_row() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(
                name = "Barbell Bench Press",
                primaryMuscle = MuscleGroup.CHEST,
                equipment = Equipment.BARBELL,
            )
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        db.workoutSetDao().insert(
            WorkoutSet(
                sessionId = sessionId,
                exerciseId = exerciseId,
                setNumber = 1,
                targetWeight = 80f,
                targetReps = 5,
                feedback = SetFeedback.RIR_2_4,
            )
        )

        repository.applySessionProgression(sessionId)

        val logs = db.baselineChangeLogDao().getForSession(sessionId)
        assertEquals(1, logs.size)
        with(logs[0]) {
            assertEquals(MuscleGroup.CHEST, muscleGroup)
            assertEquals(100f, previousBaseline)
            assertTrue(newBaseline > 100f)
            assertEquals(BaselineChangeReason.PROGRESSION, changeReason)
            assertEquals("RIR_2_4", feedbacks)
            assertEquals(5, sessionReps)
            assertNull(minReductionFraction)
        }
    }

    @Test
    fun applyManualBaselineOverrides_logs_MANUAL_OVERRIDE_row() = runBlocking {
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.BACK, 80f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))

        repository.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.BACK to 90f))

        val logs = db.baselineChangeLogDao().getForSession(sessionId)
        assertEquals(1, logs.size)
        with(logs[0]) {
            assertEquals(MuscleGroup.BACK, muscleGroup)
            assertEquals(80f, previousBaseline)
            assertEquals(90f, newBaseline)
            assertEquals(BaselineChangeReason.MANUAL_OVERRIDE, changeReason)
            assertEquals(sessionId, this.sessionId)
            assertNull(feedbacks)
        }
    }
}
