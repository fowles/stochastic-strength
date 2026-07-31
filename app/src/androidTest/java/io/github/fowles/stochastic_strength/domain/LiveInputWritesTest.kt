package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveInputWritesTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WorkoutRepository(db)
        runBlocking {
            db.userProfileDao().insert(
                UserProfile(
                    sex = Sex.MALE,
                    strengthLevel = StrengthLevel.MEDIUM,
                    weightUnit = WeightUnit.KG,
                )
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun applySessionProgression_doesNotMutateHurtState() = runBlocking {
        // Pre-seed: hurt explicitly cleared to false.
        db.exerciseDao().insert(
            Exercise(
                id = BENCH_EXERCISE_ID,
                name = "Barbell Bench Press",
                primaryMuscle = MuscleGroup.CHEST,
                equipment = Equipment.BARBELL,
            )
        )
        db.exerciseHurtStateDao().upsert(
            ExerciseHurtState(exerciseId = BENCH_EXERCISE_ID, isHurt = false, asOf = 0L)
        )
        db.exerciseStrengthOverrideDao().insert(
            ExerciseStrengthOverride(
                sessionId = null,
                exerciseId = BENCH_EXERCISE_ID,
                e1rm = 80f,
                asOf = 0,
            )
        )

        // A completed session with HURT feedback — sent through the replay path
        // (not via the live recordFeedback path that writes exercise_hurt_state).
        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = 1_700_000_000_000L, endTime = 1_700_000_001_000L)
        )
        db.workoutSetDao().insert(
            WorkoutSet(
                sessionId = sessionId,
                exerciseId = BENCH_EXERCISE_ID,
                setNumber = 1,
                targetWeight = 80f,
                targetReps = 5,
                actualReps = null,
                feedback = SetFeedback.HURT,
                completedAt = 1_700_000_000_500L,
            )
        )

        repository.replayDerivedState()

        val state = db.exerciseHurtStateDao().get(BENCH_EXERCISE_ID)
        assertEquals(
            "replay must not mutate exercise_hurt_state; user cleared it explicitly",
            false,
            state?.isHurt,
        )
    }

    companion object {
        private const val BENCH_EXERCISE_ID = 100L
    }
}
