package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplayDerivedStateTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WorkoutRepository(
            db,
            progressionControllerFactory = { FakeProgressionController() },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun replay_isIdempotent() = runBlocking {
        seedSmallHistory()

        repository.replayDerivedState()
        val snap1 = repository.derivedState.snapshot()
        val baselines1 = snap1.allBaselineHistory().map { it.toComparable() }
        val coefs1 = snap1.allCoefficientHistory().map { it.toComparable() }
        val strengths1 = snap1.allMuscleGroupStrengths().map { it.muscleGroup to it.baselineWeight }

        repository.replayDerivedState()
        val snap2 = repository.derivedState.snapshot()
        val baselines2 = snap2.allBaselineHistory().map { it.toComparable() }
        val coefs2 = snap2.allCoefficientHistory().map { it.toComparable() }
        val strengths2 = snap2.allMuscleGroupStrengths().map { it.muscleGroup to it.baselineWeight }

        assertEquals(baselines1, baselines2)
        assertEquals(coefs1, coefs2)
        assertEquals(strengths1, strengths2)
    }

    @Test
    fun replay_appliesManualOverridesAtSessionBoundary() = runBlocking {
        seedSmallHistory()
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = SESSION_2_ID,
            muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 999f,
            asOf = SESSION_2_START,
        ))

        repository.replayDerivedState()

        // The OVERRIDE row for session 2 should record the override.
        val allHistory = repository.derivedState.snapshot().allBaselineHistory()
        val overrides = allHistory
            .filter { it.changeReason == BaselineChangeReason.OVERRIDE && it.muscleGroup == MuscleGroup.CHEST }
        assertEquals(1, overrides.size)
        assertEquals(999f, overrides[0].newBaseline)

        // The PROGRESSION row for session 2 should see previousBaseline = 999f.
        val progressionForSession2 = allHistory
            .firstOrNull {
                it.changeReason == BaselineChangeReason.PROGRESSION &&
                    it.sessionId == SESSION_2_ID && it.muscleGroup == MuscleGroup.CHEST
            }
        assertNotNull("expected a PROGRESSION row for session 2 CHEST", progressionForSession2)
        assertEquals(999f, progressionForSession2!!.previousBaseline)
    }

    @Test
    fun finishSession_advancesBaselineWhenSetsArePresent() = runBlocking {
        // Seed: one CHEST exercise, initial baseline 100f, one completed session with
        // RIR_2_4 feedback. The FakeProgressionController always moves baseline by upFactor (1.05)
        // when sets are present; exerciseReductions is now a dead param.
        db.userProfileDao().insert(UserProfile(
            sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG))
        db.exerciseDao().insert(Exercise(
            id = BENCH_EXERCISE_ID, name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL))
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 100f, asOf = 0))

        val sessionId = SESSION_1_ID
        db.workoutSessionDao().insert(WorkoutSession(
            id = sessionId, startTime = SESSION_1_START, endTime = SESSION_1_START + 1000))
        repeat(3) { i ->
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = sessionId, exerciseId = BENCH_EXERCISE_ID, setNumber = i + 1,
                targetWeight = 100f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = SESSION_1_START + i * 100L))
        }

        repository.finishSession(sessionId, exerciseReductions = emptyMap())

        val progressionRow = repository.derivedState.snapshot().allBaselineHistory()
            .firstOrNull { it.changeReason == BaselineChangeReason.PROGRESSION && it.muscleGroup == MuscleGroup.CHEST }
        assertNotNull("expected a PROGRESSION row for CHEST", progressionRow)
        // FakeProgressionController moves baseline by 1.05x regardless of reductions.
        assertTrue(
            "expected new baseline > 100f from FakeProgressionController, got ${progressionRow!!.newBaseline}",
            progressionRow.newBaseline > 100f,
        )
    }

    // ----- helpers below: seed minimal but realistic histories -----

    private fun BaselineHistory.toComparable() = listOf(
        sessionId, muscleGroup, previousBaseline, newBaseline, changeReason,
        feedbacks, sessionReps, minReductionFraction, timestamp,
    )

    private fun CoefficientHistory.toComparable() = listOf(
        exerciseId, previousCoefficient, coefficient, heuristicName, heuristicMetadata, computedAt,
    )

    private suspend fun seedSmallHistory() {
        // Profile + one CHEST exercise + 2 sessions with completed sets + initial baseline override.
        db.userProfileDao().insert(UserProfile(
            sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG))
        db.exerciseDao().insert(Exercise(
            id = BENCH_EXERCISE_ID, name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL))
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 80f, asOf = 0))

        db.workoutSessionDao().insert(WorkoutSession(
            id = SESSION_1_ID, startTime = SESSION_1_START, endTime = SESSION_1_START + 1000))
        repeat(3) { i ->
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = SESSION_1_ID, exerciseId = BENCH_EXERCISE_ID, setNumber = i + 1,
                targetWeight = 80f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = SESSION_1_START + i * 100L))
        }

        db.workoutSessionDao().insert(WorkoutSession(
            id = SESSION_2_ID, startTime = SESSION_2_START, endTime = SESSION_2_START + 1000))
        repeat(3) { i ->
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = SESSION_2_ID, exerciseId = BENCH_EXERCISE_ID, setNumber = i + 1,
                targetWeight = 82.5f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = SESSION_2_START + i * 100L))
        }
    }

    companion object {
        private const val BENCH_EXERCISE_ID = 100L
        private const val SESSION_1_ID = 1L
        private const val SESSION_2_ID = 2L
        private const val SESSION_1_START = 1_700_000_000_000L
        private const val SESSION_2_START = 1_700_086_400_000L  // +1 day
    }
}
