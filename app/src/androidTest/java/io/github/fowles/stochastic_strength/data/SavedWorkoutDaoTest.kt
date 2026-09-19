package io.github.fowles.stochastic_strength.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.model.SavedWorkout
import io.github.fowles.stochastic_strength.data.model.SavedWorkoutExercise
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedWorkoutDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun exerciseRows_comeBackInPositionOrder_withNullRepsPreserved() = runBlocking {
        val dao = db.savedWorkoutDao()
        val id = dao.insert(SavedWorkout(name = "Push", createdAt = 1L))
        dao.insertExerciseRows(listOf(
            SavedWorkoutExercise(workoutId = id, exerciseId = 30L, position = 2, reps = null),
            SavedWorkoutExercise(workoutId = id, exerciseId = 10L, position = 0, reps = 5),
            SavedWorkoutExercise(workoutId = id, exerciseId = 20L, position = 1, reps = 12),
        ))
        val rows = dao.getExerciseRows(id)
        assertEquals(listOf(10L, 20L, 30L), rows.map { it.exerciseId })
        assertEquals(listOf(5, 12, null), rows.map { it.reps })
    }

    @Test
    fun deleteById_removesWorkoutAndItsRows() = runBlocking {
        val dao = db.savedWorkoutDao()
        val keep = dao.insert(SavedWorkout(name = "Keep", createdAt = 1L))
        val drop = dao.insert(SavedWorkout(name = "Drop", createdAt = 2L))
        dao.insertExerciseRows(listOf(
            SavedWorkoutExercise(workoutId = keep, exerciseId = 1L, position = 0, reps = null),
            SavedWorkoutExercise(workoutId = drop, exerciseId = 2L, position = 0, reps = null),
        ))
        dao.deleteExerciseRows(drop)
        dao.deleteById(drop)
        assertNull(dao.getById(drop))
        assertTrue(dao.getExerciseRows(drop).isEmpty())
        assertEquals(1, dao.getExerciseRows(keep).size)
        assertEquals(listOf("Keep"), dao.getAll().map { it.name })
    }

    @Test
    fun exerciseRows_roundTripSetsAndCircuitId() = runBlocking {
        val dao = db.savedWorkoutDao()
        val workoutId = dao.insert(SavedWorkout(name = "Arms", createdAt = 1L))
        dao.insertExerciseRows(listOf(
            SavedWorkoutExercise(workoutId = workoutId, exerciseId = 1, position = 0, reps = 5, sets = 2, circuitId = 0),
            SavedWorkoutExercise(workoutId = workoutId, exerciseId = 2, position = 1, reps = null),
        ))
        val rows = dao.getExerciseRows(workoutId)
        assertEquals(listOf(2, 3), rows.map { it.sets })
        assertEquals(listOf(0, null), rows.map { it.circuitId })
    }
}
