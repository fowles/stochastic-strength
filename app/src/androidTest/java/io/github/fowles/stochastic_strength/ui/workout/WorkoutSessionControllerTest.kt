package io.github.fowles.stochastic_strength.ui.workout

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.FakeBaselineHeuristic
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutSessionControllerTest {

    private lateinit var db: AppDatabase
    private lateinit var bus: WorkoutSessionBus
    private lateinit var scope: CoroutineScope
    private lateinit var controller: WorkoutSessionController
    private lateinit var repository: WorkoutRepository

    @Before
    fun setUp() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            db.userProfileDao().insert(
                UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
            )
            db.exerciseDao().insertAll(listOf(
                Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
                Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
            ))
            bus = WorkoutSessionBus()
            scope = CoroutineScope(Dispatchers.Default)
            repository = WorkoutRepository(db, baselineHeuristic = FakeBaselineHeuristic())
            repository.derivedState.rebuild { mut ->
                mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
                mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.QUADS, 100f))
            }
            controller = WorkoutSessionController(db, repository, bus, scope)
            controller.initializeSession(
                locationId = null,
                locationName = null,
                preferredExerciseCount = 1,
                preferredRepMin = 5,
                preferredRepMax = 10,
                weightUnit = WeightUnit.KG,
            )
            controller.adjustExerciseCount(1)
            awaitStateNotLoading()
            controller.startFirstExercise()
            awaitState<WorkoutState.ActiveSet>()
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend inline fun <reified T : WorkoutState> awaitState(timeoutMs: Long = 2000): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = controller.state.value
            if (s is T) return s
            delay(20)
        }
        error("State did not become ${T::class.simpleName} within $timeoutMs ms; was ${controller.state.value}")
    }

    private suspend fun awaitStateNotLoading() {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && controller.state.value is WorkoutState.Loading) {
            delay(20)
        }
    }

    @Test
    fun finalSetTransitionsThroughResting() = runBlocking<Unit> {
        repeat(3) { setIndex ->
            controller.recordFeedback(SetFeedback.RIR_2_4)
            awaitState<WorkoutState.Resting>()
            delay(100)
            assertEquals(setIndex, db.workoutSetDao().getAll().last().setNumber - 1)
            controller.skipRest()
        }
        awaitState<WorkoutState.Done>()
    }

    @Test
    fun rir24_setsActualRepsEqualToTargetReps() = runBlocking {
        controller.recordFeedback(SetFeedback.RIR_2_4)
        awaitState<WorkoutState.Resting>()
        delay(100)
        val sets = db.workoutSetDao().getAll()
        assertEquals(1, sets.size)
        assertEquals(sets[0].targetReps, sets[0].actualReps)
    }

    @Test
    fun hurt_leavesActualRepsNull() = runBlocking {
        controller.recordFeedback(SetFeedback.HURT)
        awaitState<WorkoutState.Resting>()
        delay(100)
        val sets = db.workoutSetDao().getAll()
        assertEquals(1, sets.size)
        assertNull(sets[0].actualReps)
    }

    @Test
    fun tooHard_initialActualRepsNull_thenSetByReduceExerciseWeight() = runBlocking {
        controller.recordFeedback(SetFeedback.TOO_HARD)
        val resting = awaitState<WorkoutState.Resting>()
        delay(100)
        val before = db.workoutSetDao().getAll().single()
        assertNull(before.actualReps)
        assertTrue(resting.currentSetRowId > 0)

        controller.reduceExerciseWeight(2)
        delay(100)
        val after = db.workoutSetDao().getAll().single()
        assertEquals(2, after.actualReps)
    }

    @Test
    fun tooHardOnNonFinalSet_appliesWeightReduction() = runBlocking {
        val before = (controller.state.value as WorkoutState.ActiveSet).plannedExercise.sessionWeight
        controller.recordFeedback(SetFeedback.TOO_HARD)
        awaitState<WorkoutState.Resting>()
        controller.reduceExerciseWeight(2)
        delay(50)
        val resting = controller.state.value as WorkoutState.Resting
        val after = resting.plan.exercises[resting.exerciseIndex].sessionWeight
        assertTrue("expected weight to drop from $before, got $after", after < before)
    }

    @Test
    fun tooHardOnFinalSetOfExercise_doesNotChangeWeight() = runBlocking {
        controller.recordFeedback(SetFeedback.RIR_2_4); awaitState<WorkoutState.Resting>(); controller.skipRest(); awaitState<WorkoutState.ActiveSet>()
        controller.recordFeedback(SetFeedback.RIR_2_4); awaitState<WorkoutState.Resting>(); controller.skipRest(); awaitState<WorkoutState.ActiveSet>()
        val before = (controller.state.value as WorkoutState.ActiveSet).plannedExercise.sessionWeight

        controller.recordFeedback(SetFeedback.TOO_HARD)
        val resting = awaitState<WorkoutState.Resting>()
        controller.reduceExerciseWeight(2)
        delay(50)
        val updated = controller.state.value as WorkoutState.Resting
        val after = updated.plan.exercises[updated.exerciseIndex].sessionWeight
        assertEquals("weight should be unchanged on final set of exercise", before, after)
        delay(100)
        val sets = db.workoutSetDao().getAll().sortedBy { it.setNumber }
        assertEquals(2, sets.last().actualReps)
    }

    @Test
    fun undoFromResting_deletesRowIncludingActualReps() = runBlocking {
        controller.recordFeedback(SetFeedback.RIR_2_4)
        awaitState<WorkoutState.Resting>()
        delay(100)
        assertEquals(1, db.workoutSetDao().getAll().size)
        controller.undoLastSet()
        awaitState<WorkoutState.ActiveSet>()
        delay(100)
        assertEquals(0, db.workoutSetDao().getAll().size)
    }
}
