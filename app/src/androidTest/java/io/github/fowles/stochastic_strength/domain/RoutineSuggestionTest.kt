package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [WorkoutRepository.suggestRoutineWorkout] over real session history. */
@RunWith(AndroidJUnit4::class)
class RoutineSuggestionTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: WorkoutRepository
    private lateinit var bench: Exercise
    private lateinit var squat: Exercise
    private lateinit var row: Exercise

    private var clock = 1_000L

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = WorkoutRepository(db)
        bench = insertExercise("Bench", MuscleGroup.CHEST)
        squat = insertExercise("Squat", MuscleGroup.QUADS)
        row = insertExercise("Row", MuscleGroup.BACK)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertExercise(name: String, muscle: MuscleGroup): Exercise {
        val id = db.exerciseDao().insert(Exercise(name = name, primaryMuscle = muscle, equipment = Equipment.BARBELL))
        return db.exerciseDao().getById(id)!!
    }

    private suspend fun saved(name: String, vararg exercises: Exercise): Long =
        repo.saveWorkout(null, name, exercises.map { SavedWorkoutEntry(it, null) })

    /** A completed session logging one set of each exercise, later than every earlier call. */
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

    @Test
    fun alternatingHistorySuggestsTheWorkoutThatIsDue() = runBlocking {
        val push = saved("Push", bench)
        saved("Pull", row)
        session(bench)
        session(row)
        session(bench)
        session(row)
        assertEquals(push, repo.suggestRoutineWorkout()?.id)
    }

    @Test
    fun repeatedHistorySuggestsTheSameWorkout() = runBlocking {
        val full = saved("Full", bench, squat)
        session(squat, bench)   // order within a session is irrelevant
        session(bench, squat)
        assertEquals(full, repo.suggestRoutineWorkout()?.id)
    }

    @Test
    fun sessionMatchingNoSavedWorkoutSuggestsNothing() = runBlocking {
        saved("Push", bench)
        session(bench)
        session(bench, squat, row)
        assertNull(repo.suggestRoutineWorkout())
    }

    @Test
    fun noHistorySuggestsNothing() = runBlocking {
        saved("Push", bench)
        assertNull(repo.suggestRoutineWorkout())
    }

    @Test
    fun openSessionIsNotHistory() = runBlocking {
        val push = saved("Push", bench)
        session(bench)
        session(bench)
        // The workout the user is about to start is already open; it must not read as a session.
        db.workoutSessionDao().insert(WorkoutSession(startTime = clock, endTime = null))
        assertEquals(push, repo.suggestRoutineWorkout()?.id)
    }

    @Test
    fun suggestionCarriesTheWorkoutsEntries() = runBlocking {
        saved("Push", bench, squat)
        session(bench, squat)
        session(bench, squat)
        val suggestion = repo.suggestRoutineWorkout()!!
        assertEquals("Push", suggestion.name)
        assertEquals(listOf(bench.id, squat.id), suggestion.entries.map { it.exercise.id })
    }
}
