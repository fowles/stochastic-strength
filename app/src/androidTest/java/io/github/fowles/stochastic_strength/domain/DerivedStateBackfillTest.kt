package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DerivedStateBackfillTest {

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
            heuristic = EstCoefConsensusHeuristic(),
            normalizer = SeedNormalizer(),
            baselineHeuristic = FakeBaselineHeuristic(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedProfile() {
        db.userProfileDao().insert(
            UserProfile(
                sex = Sex.MALE,
                strengthLevel = StrengthLevel.MEDIUM,
                weightUnit = WeightUnit.KG,
            )
        )
    }

    private suspend fun seedExerciseAndSession(): Pair<Long, Long> {
        db.exerciseDao().insertAll(listOf(
            Exercise(
                name = "Bench Press",
                primaryMuscle = MuscleGroup.CHEST,
                equipment = Equipment.BARBELL,
            )
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1_700_000_000_000L))
        return exerciseId to sessionId
    }

    private suspend fun seedFullReplayData() {
        // exercise + initial baseline_override + completed session with sets
        // so that replay produces baseline_history rows.
        val exerciseId = 100L
        db.exerciseDao().insert(Exercise(
            id = exerciseId, name = "Bench", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL))
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 80f, asOf = 0))
        val sessionStart = 1_700_000_000_000L
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(
            startTime = sessionStart, endTime = sessionStart + 1000))
        repeat(3) { i ->
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = sessionId, exerciseId = exerciseId, setNumber = i + 1,
                targetWeight = 80f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = sessionStart + i * 100L))
        }
    }

    private fun BaselineHistory.toComparable() = listOf(
        sessionId, muscleGroup, previousBaseline, newBaseline, changeReason,
        feedbacks, sessionReps, minReductionFraction, timestamp,
    )

    @Test
    fun run_noProfile_isNoOp() = runBlocking {
        // No profile inserted — runner returns without throwing.
        DerivedStateBackfill(db, repository).run()
        assertNull(db.userProfileDao().getProfile())
    }

    @Test
    fun run_withProfile_executesActualRepsBackfill() = runBlocking {
        seedProfile()
        val (exerciseId, sessionId) = seedExerciseAndSession()
        // A RIR set with null actualReps is the canary: ActualRepsBackfill assigns targetReps to it.
        val setId = db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId,
            exerciseId = exerciseId,
            setNumber = 1,
            targetWeight = 80f,
            targetReps = 5,
            actualReps = null,
            feedback = SetFeedback.RIR_2_4,
            completedAt = 1_700_000_000_000L,
        ))

        DerivedStateBackfill(db, repository).run()

        val refreshed = db.workoutSetDao().getAll().first { it.id == setId }
        // ActualRepsBackfill should have set actualReps = targetReps = 5
        assert(refreshed.actualReps == 5) { "ActualRepsBackfill should have set actualReps; got ${refreshed.actualReps}" }
    }

    @Test
    fun run_runsActualRepsBackfillAndReplay() = runBlocking {
        seedProfile()
        seedFullReplayData()

        DerivedStateBackfill(db, repository).run()

        val baselines = repository.derivedState.snapshot().allBaselineHistory()
        assertTrue("expected replay to produce baseline_history rows", baselines.isNotEmpty())
    }

    @Test
    fun run_isIdempotent() = runBlocking {
        seedProfile()
        seedFullReplayData()
        DerivedStateBackfill(db, repository).run()
        val baselines1 = repository.derivedState.snapshot().allBaselineHistory().map { it.toComparable() }
        DerivedStateBackfill(db, repository).run()
        val baselines2 = repository.derivedState.snapshot().allBaselineHistory().map { it.toComparable() }
        assertEquals(baselines1, baselines2)
    }
}
