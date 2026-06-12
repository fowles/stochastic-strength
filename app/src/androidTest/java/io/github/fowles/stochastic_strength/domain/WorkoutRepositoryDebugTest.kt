package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeLog
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.CoefficientChangeLog
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryDebugTest {

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
    fun getAllCoefficientRows_returns_seed_for_exercises_with_no_log() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))

        val rows = repository.getAllCoefficientRows()

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("Barbell Bench Press", row.exerciseName)
        // ExerciseCoefficients seeds Barbell Bench Press at 1.0
        assertEquals(1.0f, row.currentCoefficient, 0.001f)
        assertNull(row.computedAt)
        assertNull(row.heuristicName)
        assertNull(row.previousCoefficient)
        assertNull(row.heuristicMetadataPreview)
    }

    @Test
    fun getAllCoefficientRows_uses_log_value_when_present() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val exerciseId = db.exerciseDao().getAll().single().id
        db.coefficientChangeLogDao().insert(
            CoefficientChangeLog(
                exerciseId = exerciseId,
                previousCoefficient = 1.0f,
                coefficient = 0.85f,
                heuristicName = "test-heuristic",
                heuristicMetadata = "metadata-string",
                computedAt = 5000L,
            )
        )

        val row = repository.getAllCoefficientRows().single()

        assertEquals(0.85f, row.currentCoefficient, 0.001f)
        assertEquals(5000L, row.computedAt)
        assertEquals("test-heuristic", row.heuristicName)
        // previous and metadata preview are reserved for the "recent changes" variant
        assertNull(row.previousCoefficient)
        assertNull(row.heuristicMetadataPreview)
    }

    @Test
    fun getAllCoefficientRows_sorts_alphabetically_and_includes_disliked() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Pull-Up", primaryMuscle = MuscleGroup.BACK, equipment = Equipment.BODYWEIGHT, isDisliked = true),
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))

        val rows = repository.getAllCoefficientRows()

        assertEquals(listOf("Barbell Bench Press", "Pull-Up"), rows.map { it.exerciseName })
    }
}
