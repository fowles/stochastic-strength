package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
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
        repository = WorkoutRepository(db, baselineHeuristic = FakeBaselineHeuristic())
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Sync the in-memory store from the current DAO contents (mirrors what replayDerivedState does). */
    private suspend fun syncStore() {
        repository.derivedState.rebuild { scratch ->
            db.muscleGroupStrengthDao().getAll().forEach { scratch.upsertMuscleGroupStrength(it) }
            db.baselineHistoryDao().getAll().forEach { scratch.insertBaselineHistory(it) }
            db.coefficientHistoryDao().getAll().forEach { scratch.insertCoefficientHistory(it) }
        }
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
        db.coefficientHistoryDao().insert(
            CoefficientHistory(
                exerciseId = exerciseId,
                previousCoefficient = 1.0f,
                coefficient = 0.85f,
                heuristicName = "test-heuristic",
                heuristicMetadata = "metadata-string",
                computedAt = 5000L,
            )
        )
        syncStore()

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

    @Test
    fun getRecentCoefficientChanges_returns_newest_first_limited() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Squat",                primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
            Exercise(name = "Deadlift",             primaryMuscle = MuscleGroup.HAMSTRINGS, equipment = Equipment.BARBELL),
        ))
        val exercises = db.exerciseDao().getAll()
        val bench = exercises.first { it.name == "Barbell Bench Press" }
        val squat = exercises.first { it.name == "Squat" }
        val dead = exercises.first { it.name == "Deadlift" }
        db.coefficientHistoryDao().insert(CoefficientHistory(
            exerciseId = bench.id, previousCoefficient = 1.0f, coefficient = 0.95f,
            heuristicName = "h", heuristicMetadata = null, computedAt = 1000L,
        ))
        db.coefficientHistoryDao().insert(CoefficientHistory(
            exerciseId = squat.id, previousCoefficient = 1.0f, coefficient = 0.90f,
            heuristicName = "h", heuristicMetadata = null, computedAt = 3000L,
        ))
        db.coefficientHistoryDao().insert(CoefficientHistory(
            exerciseId = dead.id, previousCoefficient = 1.0f, coefficient = 0.92f,
            heuristicName = "h", heuristicMetadata = null, computedAt = 2000L,
        ))
        syncStore()

        val recent = repository.getRecentCoefficientChanges(limit = 2)

        assertEquals(2, recent.size)
        assertEquals(listOf("Squat", "Deadlift"), recent.map { it.exerciseName })
        assertEquals(1.0f, recent[0].previousCoefficient!!, 0.001f)
    }

    @Test
    fun getRecentCoefficientChanges_populates_metadata_preview_with_truncation() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val bench = db.exerciseDao().getAll().single()
        val longMeta = "x".repeat(200) + "\n" + "y".repeat(50)
        db.coefficientHistoryDao().insert(CoefficientHistory(
            exerciseId = bench.id, previousCoefficient = 1.0f, coefficient = 0.9f,
            heuristicName = "h", heuristicMetadata = longMeta, computedAt = 1000L,
        ))
        syncStore()

        val row = repository.getRecentCoefficientChanges(limit = 2).single()

        // First 80 chars of the flattened metadata
        assertEquals("x".repeat(80), row.heuristicMetadataPreview)
    }

    @Test
    fun getBaselineEvents_filters_by_muscle_group_and_orders_ascending() = runBlocking {
        db.baselineHistoryDao().insert(BaselineHistory(
            sessionId = 1L, muscleGroup = MuscleGroup.CHEST,
            previousBaseline = 100f, newBaseline = 102f,
            changeReason = BaselineChangeReason.PROGRESSION,
            timestamp = 3000L,
        ))
        db.baselineHistoryDao().insert(BaselineHistory(
            sessionId = 2L, muscleGroup = MuscleGroup.BACK,
            previousBaseline = 80f, newBaseline = 82f,
            changeReason = BaselineChangeReason.PROGRESSION,
            timestamp = 4000L,
        ))
        db.baselineHistoryDao().insert(BaselineHistory(
            sessionId = 3L, muscleGroup = MuscleGroup.CHEST,
            previousBaseline = 102f, newBaseline = 104f,
            changeReason = BaselineChangeReason.PROGRESSION,
            timestamp = 5000L,
        ))
        syncStore()

        val events = repository.getBaselineEvents(MuscleGroup.CHEST)

        assertEquals(2, events.size)
        assertEquals(listOf(3000L, 5000L), events.map { it.timestamp })
    }

    @Test
    fun getCoefficientEvents_returns_events_for_exercise_ascending() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Squat",                primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
        ))
        val exercises = db.exerciseDao().getAll()
        val bench = exercises.first { it.name == "Barbell Bench Press" }
        val squat = exercises.first { it.name == "Squat" }
        db.coefficientHistoryDao().insert(CoefficientHistory(
            exerciseId = bench.id, previousCoefficient = null, coefficient = 0.95f,
            heuristicName = "h", heuristicMetadata = null, computedAt = 3000L,
        ))
        db.coefficientHistoryDao().insert(CoefficientHistory(
            exerciseId = bench.id, previousCoefficient = 0.95f, coefficient = 0.92f,
            heuristicName = "h", heuristicMetadata = null, computedAt = 1000L,
        ))
        db.coefficientHistoryDao().insert(CoefficientHistory(
            exerciseId = squat.id, previousCoefficient = null, coefficient = 0.9f,
            heuristicName = "h", heuristicMetadata = null, computedAt = 2000L,
        ))
        syncStore()

        val events = repository.getCoefficientEvents(bench.id)

        assertEquals(2, events.size)
        assertEquals(listOf(1000L, 3000L), events.map { it.computedAt })
    }

    @Test
    fun getSeedCoefficient_returns_default_from_coefficient_source() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val bench = db.exerciseDao().getAll().single()

        val seed = repository.getSeedCoefficient(bench)

        // ExerciseCoefficients seeds Barbell Bench Press at 1.0
        assertEquals(1.0f, seed!!, 0.001f)
    }
}
