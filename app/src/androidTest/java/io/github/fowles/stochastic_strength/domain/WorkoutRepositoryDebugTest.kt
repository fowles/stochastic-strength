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
        repository = WorkoutRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getBaselineEvents_filters_by_muscle_group_and_orders_ascending() = runBlocking {
        repository.derivedState.rebuild { mut ->
            mut.insertBaselineHistory(BaselineHistory(
                sessionId = 1L, muscleGroup = MuscleGroup.CHEST,
                previousBaseline = 100f, newBaseline = 102f,
                changeReason = BaselineChangeReason.PROGRESSION,
                timestamp = 3000L,
            ))
            mut.insertBaselineHistory(BaselineHistory(
                sessionId = 2L, muscleGroup = MuscleGroup.BACK,
                previousBaseline = 80f, newBaseline = 82f,
                changeReason = BaselineChangeReason.PROGRESSION,
                timestamp = 4000L,
            ))
            mut.insertBaselineHistory(BaselineHistory(
                sessionId = 3L, muscleGroup = MuscleGroup.CHEST,
                previousBaseline = 102f, newBaseline = 104f,
                changeReason = BaselineChangeReason.PROGRESSION,
                timestamp = 5000L,
            ))
        }

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
        repository.derivedState.rebuild { mut ->
            mut.insertCoefficientHistory(CoefficientHistory(
                exerciseId = bench.id, previousCoefficient = null, coefficient = 0.95f,
                heuristicName = "h", heuristicMetadata = null, computedAt = 3000L,
            ))
            mut.insertCoefficientHistory(CoefficientHistory(
                exerciseId = bench.id, previousCoefficient = 0.95f, coefficient = 0.92f,
                heuristicName = "h", heuristicMetadata = null, computedAt = 1000L,
            ))
            mut.insertCoefficientHistory(CoefficientHistory(
                exerciseId = squat.id, previousCoefficient = null, coefficient = 0.9f,
                heuristicName = "h", heuristicMetadata = null, computedAt = 2000L,
            ))
        }

        val events = repository.getCoefficientEvents(bench.id)

        assertEquals(2, events.size)
        assertEquals(listOf(1000L, 3000L), events.map { it.computedAt })
    }

}
