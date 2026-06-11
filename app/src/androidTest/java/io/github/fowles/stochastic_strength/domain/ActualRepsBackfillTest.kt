package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActualRepsBackfillTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedExercise(): Long {
        db.exerciseDao().insertAll(listOf(
            Exercise(
                name = "Bench Press",
                primaryMuscle = MuscleGroup.CHEST,
                equipment = Equipment.BARBELL,
            )
        ))
        return db.exerciseDao().getActive().first().id
    }

    private suspend fun seedSession(): Long =
        db.workoutSessionDao().insert(WorkoutSession(startTime = 1_700_000_000_000L))

    private suspend fun insertSet(
        sessionId: Long,
        exerciseId: Long,
        setNumber: Int,
        targetWeight: Float,
        targetReps: Int = 5,
        feedback: SetFeedback?,
        actualReps: Int? = null,
    ): Long = db.workoutSetDao().insert(WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = targetWeight,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
        completedAt = 1_700_000_000_000L,
    ))

    private suspend fun readSet(id: Long): WorkoutSet =
        db.workoutSetDao().getAll().first { it.id == id }

    @Test
    fun rir_nonTooHard_setsActualRepsEqualToTargetReps() = runBlocking {
        val ex = seedExercise()
        val session = seedSession()
        val id = insertSet(session, ex, 1, targetWeight = 80f, feedback = SetFeedback.RIR_2_4)

        ActualRepsBackfill(db, WeightUnit.KG).run()

        assertEquals(5, readSet(id).actualReps)
    }

    @Test
    fun hurt_leavesActualRepsNull() = runBlocking {
        val ex = seedExercise()
        val session = seedSession()
        val id = insertSet(session, ex, 1, targetWeight = 80f, feedback = SetFeedback.HURT)

        ActualRepsBackfill(db, WeightUnit.KG).run()

        assertNull(readSet(id).actualReps)
    }

    @Test
    fun nullFeedback_leavesActualRepsNull() = runBlocking {
        val ex = seedExercise()
        val session = seedSession()
        val id = insertSet(session, ex, 1, targetWeight = 80f, feedback = null)

        ActualRepsBackfill(db, WeightUnit.KG).run()

        assertNull(readSet(id).actualReps)
    }

    @Test
    fun tooHard_lastSetOfExercise_leavesActualRepsNull() = runBlocking {
        val ex = seedExercise()
        val session = seedSession()
        // setNumber 3 with TOO_HARD, no setNumber 4 follows → cannot infer
        val id = insertSet(session, ex, 3, targetWeight = 80f, feedback = SetFeedback.TOO_HARD)

        ActualRepsBackfill(db, WeightUnit.KG).run()

        assertNull(readSet(id).actualReps)
    }

    @Test
    fun tooHard_followedByScaledDrop_recoversCompletedReps() = runBlocking {
        val ex = seedExercise()
        val session = seedSession()
        val target = 5
        val expectedCompleted = 2
        val from = 80f
        val to = WeightFormatter.round(
            DefaultProgressionEngine.scaleReps(from, from = expectedCompleted, to = target),
            WeightUnit.KG,
        )

        val id1 = insertSet(session, ex, 1, targetWeight = from, targetReps = target, feedback = SetFeedback.TOO_HARD)
        insertSet(session, ex, 2, targetWeight = to, targetReps = target, feedback = SetFeedback.RIR_2_4)

        ActualRepsBackfill(db, WeightUnit.KG).run()

        assertEquals(expectedCompleted, readSet(id1).actualReps)
    }

    @Test
    fun tooHard_unmatchedWeightDrop_leavesActualRepsNull() = runBlocking {
        val ex = seedExercise()
        val session = seedSession()
        // 1 kg drop: WeightFormatter.round(predicted) rounds to 2.5 kg increments,
        // so a predicted near 79f rounds to 80f which is 1.0f away from 79f — outside 0.5f tolerance
        val id1 = insertSet(session, ex, 1, targetWeight = 80f, feedback = SetFeedback.TOO_HARD)
        insertSet(session, ex, 2, targetWeight = 79f, feedback = SetFeedback.RIR_2_4)

        ActualRepsBackfill(db, WeightUnit.KG).run()

        assertNull(readSet(id1).actualReps)
    }

    @Test
    fun alreadyPopulated_isNotOverwritten() = runBlocking {
        val ex = seedExercise()
        val session = seedSession()
        val id = insertSet(session, ex, 1, targetWeight = 80f, feedback = SetFeedback.RIR_2_4, actualReps = 99)

        ActualRepsBackfill(db, WeightUnit.KG).run()

        assertEquals(99, readSet(id).actualReps)
    }

    @Test
    fun rerun_isIdempotent() = runBlocking {
        val ex = seedExercise()
        val session = seedSession()
        val id = insertSet(session, ex, 1, targetWeight = 80f, feedback = SetFeedback.RIR_2_4)

        ActualRepsBackfill(db, WeightUnit.KG).run()
        val firstRun = readSet(id).actualReps
        ActualRepsBackfill(db, WeightUnit.KG).run()
        val secondRun = readSet(id).actualReps

        assertEquals(firstRun, secondRun)
        assertEquals(5, firstRun)
    }
}
