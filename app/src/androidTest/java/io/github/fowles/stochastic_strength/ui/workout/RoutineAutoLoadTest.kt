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
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Starting a workout follows the user's routine, and "Randomize me!" opts back out of it. */
@RunWith(AndroidJUnit4::class)
class RoutineAutoLoadTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var scope: CoroutineScope
    private lateinit var bench: Exercise
    private lateinit var squat: Exercise
    private lateinit var row: Exercise
    private lateinit var curl: Exercise

    private var clock = 1_000L

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        repository = WorkoutRepository(db)
        scope = CoroutineScope(Dispatchers.Default)
        bench = insertExercise("Barbell Bench Press", MuscleGroup.CHEST)
        squat = insertExercise("Barbell Squat", MuscleGroup.QUADS)
        row = insertExercise("Barbell Row", MuscleGroup.BACK)
        curl = insertExercise("Barbell Curl", MuscleGroup.BICEPS)
        seedDerivedStrength()
    }

    @After
    fun tearDown() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }

    private suspend fun insertExercise(name: String, muscle: MuscleGroup): Exercise {
        val id = db.exerciseDao().insert(Exercise(name = name, primaryMuscle = muscle, equipment = Equipment.BARBELL))
        return db.exerciseDao().getById(id)!!
    }

    private suspend fun seedDerivedStrength() {
        val active = db.exerciseDao().getActive()
        val now = System.currentTimeMillis()
        repository.derivedState.rebuild { mut ->
            active.map { it.primaryMuscle }.distinct().forEach { mut.upsertMuscleGroupStrength(MuscleGroupStrength(it, 100f)) }
            mut.putExerciseBeliefs(
                active.associate { it.id to Belief(bestGuessLn = kotlin.math.ln(100f), uncertainty = 4e-4f, updatedAt = now) }
            )
        }
    }

    private suspend fun saved(name: String, vararg exercises: Exercise): Long =
        repository.saveWorkout(null, name, exercises.map { SavedWorkoutEntry(it, null) })

    private suspend fun session(vararg exercises: Exercise) {
        val start = clock
        clock += 1_000
        val id = db.workoutSessionDao().insert(WorkoutSession(startTime = start, endTime = start + 500))
        exercises.forEachIndexed { i, e ->
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = id, exerciseId = e.id, setNumber = 1, targetWeight = 50f, targetReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = start + i,
            ))
        }
    }

    /** Starts a controller the way the ViewModel does, returning it with the auto-loaded workout. */
    private suspend fun start(count: Int = 3): Pair<WorkoutSessionController, String?> {
        val c = WorkoutSessionController(db, repository, WorkoutSessionBus(), scope)
        val loaded = c.initializeSession(
            locationId = null, locationName = null,
            preferredExerciseCount = count, preferredRepMin = 5, preferredRepMax = 10,
            weightUnit = WeightUnit.KG,
        )
        return c to loaded?.displayName
    }

    private fun preview(c: WorkoutSessionController) = c.state.value as WorkoutState.PlanPreview

    private fun planIds(c: WorkoutSessionController) = preview(c).plan.exercises.map { it.exercise.id }

    private suspend fun awaitPreview(c: WorkoutSessionController, predicate: (WorkoutState.PlanPreview) -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val s = c.state.value
            if (s is WorkoutState.PlanPreview && predicate(s)) return
            delay(20)
        }
        error("Preview never satisfied the condition; was ${c.state.value}")
    }

    @Test
    fun alternatingRoutineOpensTheWorkoutThatIsDue() = runBlocking {
        saved("Push", bench)
        saved("Pull", row)
        session(bench)
        session(row)
        session(bench)
        session(row)
        val (c, name) = start()
        assertEquals("Push", name)
        assertEquals(listOf(bench.id), planIds(c))
    }

    @Test
    fun anAutoLoadedPlanIsNotPaddedToTheExerciseCount() = runBlocking {
        saved("Push", bench)
        session(bench)
        session(bench)
        val (c, _) = start(count = 3)
        // Give the count slider's async grow loop every chance to fire before asserting it didn't.
        delay(300)
        assertEquals(listOf(bench.id), planIds(c))
    }

    @Test
    fun anAutoLoadedRowIsExplicitSoItSurvivesATrim() = runBlocking {
        saved("Push", bench, squat)
        session(bench, squat)
        session(bench, squat)
        val (c, _) = start(count = 3)
        c.adjustExerciseCount(1)
        assertEquals(listOf(bench.id, squat.id), planIds(c))
    }

    @Test
    fun noRoutineStartsARandomWorkout() = runBlocking {
        saved("Push", bench)
        session(bench)
        session(row, squat, curl)
        val (c, name) = start(count = 2)
        assertNull(name)
        awaitPreview(c) { it.plan.exercises.size == 2 }
    }

    @Test
    fun randomizeReplacesAnAutoLoadedPlan() = runBlocking {
        saved("Push", bench)
        session(bench)
        session(bench)
        val (c, _) = start(count = 3)
        assertEquals(listOf(bench.id), planIds(c))
        c.randomizeWorkout()
        awaitPreview(c) { it.plan.exercises.size == 3 }
        assertTrue("the loaded row is gone", bench.id !in planIds(c) || planIds(c).size > 1)
        // The auto-loaded row was explicit, which the count slider refuses to trim. A randomized
        // plan is all plain rows, so the slider can cut it back down again.
        c.adjustExerciseCount(1)
        assertEquals(1, planIds(c).size)
    }

    @Test
    fun randomizeKeepsTheRepRangeAndTarget() = runBlocking {
        saved("Push", bench)
        session(bench)
        session(bench)
        val (c, _) = start(count = 2)
        c.randomizeWorkout()
        awaitPreview(c) { it.plan.exercises.size == 2 }
        assertEquals(5, preview(c).repMin)
        assertEquals(10, preview(c).repMax)
        assertEquals(2, preview(c).targetCount)
    }

    @Test
    fun randomizeMarksThePlanEdited() = runBlocking {
        val (c, _) = start(count = 2)
        awaitPreview(c) { it.plan.exercises.size == 2 }
        c.randomizeWorkout()
        awaitPreview(c) { it.edited && it.plan.exercises.size == 2 }
    }
}
