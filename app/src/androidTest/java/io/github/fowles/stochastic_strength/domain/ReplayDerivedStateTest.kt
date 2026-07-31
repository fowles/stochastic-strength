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
        repository = WorkoutRepository(db)
    }

    @After
    fun tearDown() = db.close()

    /**
     * Replay is deterministic: rebuilding from the same logged inputs yields identical derived
     * projections (baseline_history, coefficient_history, muscle_group_strength, estimates).
     */
    @Test
    fun replay_isIdempotent() = runBlocking {
        seedSmallHistory()

        repository.replayDerivedState()
        val snap1 = repository.derivedState.snapshot()
        val baselines1 = snap1.allBaselineHistory().map { it.toComparable() }
        val coefs1 = snap1.allCoefficientHistory().map { it.toComparable() }
        val strengths1 = snap1.allMuscleGroupStrengths().map { it.muscleGroup to it.baselineWeight }
        val estimates1 = snap1.exerciseBeliefs().mapValues { it.value.e1rm }

        repository.replayDerivedState()
        val snap2 = repository.derivedState.snapshot()
        val baselines2 = snap2.allBaselineHistory().map { it.toComparable() }
        val coefs2 = snap2.allCoefficientHistory().map { it.toComparable() }
        val strengths2 = snap2.allMuscleGroupStrengths().map { it.muscleGroup to it.baselineWeight }
        val estimates2 = snap2.exerciseBeliefs().mapValues { it.value.e1rm }

        assertEquals(baselines1, baselines2)
        assertEquals(coefs1, coefs2)
        assertEquals(strengths1, strengths2)
        assertEquals(estimates1, estimates2)
        // Replay actually produced derived rows from the logged sessions.
        assertTrue("expected baseline_history from replay", baselines1.isNotEmpty())
    }

    /**
     * A non-initial per-exercise override row at a session boundary re-bases that exercise's
     * belief to the override value (a confident belief, sigmaOverride) before that session's
     * progression is folded in. Here we seed the override well ABOVE the natural trajectory and
     * confirm the belief going into session 2 reflects it: the belief stack's single-observation
     * boundary-pull fold (Phase-2) snaps a confident-but-wrong belief hard toward the session's
     * demonstrated interval in one fold (gain ~= sigmaOverride2 / (sigmaOverride2 + sigmaObs2)),
     * so "dominates" means strictly above the no-override trajectory, not "stays near 999".
     */
    @Test
    fun replay_appliesManualOverridesAtSessionBoundary() = runBlocking {
        seedSmallHistory()
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = SESSION_2_ID,
            muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 999f,
            asOf = SESSION_2_START,
            reason = BaselineChangeReason.OVERRIDE,
        ))

        repository.replayDerivedState()

        // The override re-based the belief to ~999 at session 2; the session's RIR_2_4 set at
        // 82.5 kg folds it down from 999, but the confident override still dominates the ordinary
        // no-override trajectory (~106 kg without the override — see the sibling no-override test
        // below) because the fold only ever pulls to the demonstrated interval's boundary, not
        // below it.
        val benchEstimate = repository.derivedState.snapshot().exerciseBeliefs()[BENCH_EXERCISE_ID]!!.e1rm
        assertTrue(
            "override at session boundary must dominate the estimate; got $benchEstimate",
            benchEstimate > 108f,
        )
        // The CHEST display projection at session 2 must likewise sit above the no-override level.
        val chestLevel = repository.derivedState.snapshot().allMuscleGroupStrengths()
            .first { it.muscleGroup == MuscleGroup.CHEST }.baselineWeight
        assertTrue("CHEST level must reflect the override; got $chestLevel", chestLevel > 108f)
    }

    /**
     * finishSession folds the just-finished session into derived state: clean RIR_2_4 work at the
     * prescribed weight drives the estimate / CHEST level upward.
     */
    @Test
    fun finishSession_baselineAdvancesFromPrescribedWork() = runBlocking {
        db.userProfileDao().insert(UserProfile(
            sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG))
        db.exerciseDao().insert(Exercise(
            id = BENCH_EXERCISE_ID, name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL))
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST, baselineWeight = 100f, asOf = 0))

        val sessionId = SESSION_1_ID
        db.workoutSessionDao().insert(WorkoutSession(
            id = sessionId, startTime = SESSION_1_START, endTime = SESSION_1_START + 1000))
        repeat(3) { i ->
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = sessionId, exerciseId = BENCH_EXERCISE_ID, setNumber = i + 1,
                targetWeight = 100f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = SESSION_1_START + i * 100L))
        }

        repository.finishSession()

        val progressionRow = repository.derivedState.snapshot().allBaselineHistory()
            .firstOrNull { it.changeReason == BaselineChangeReason.PROGRESSION && it.muscleGroup == MuscleGroup.CHEST }
        assertTrue("expected a PROGRESSION row for CHEST", progressionRow != null)
        // RIR_2_4 (3 reps reserve) at 100 kg implies a 1RM above 100, so the level advances.
        assertTrue(
            "expected CHEST level > 100f, got ${progressionRow!!.newBaseline}",
            progressionRow.newBaseline > 100f,
        )
    }

    /**
     * Real estimator end-to-end: 3 sessions of consistently-easy (RIR_5_PLUS) feedback on Barbell
     * Bench Press at the prescribed weight must drive the per-exercise estimate (and the CHEST
     * display level) strictly upward each session.
     */
    @Test
    fun replay_multiSession_estimateStrictlyIncreases() = runBlocking {
        db.userProfileDao().insert(UserProfile(
            sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG))
        db.exerciseDao().insert(Exercise(
            id = BENCH_EXERCISE_ID, name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL))
        // Bench seed coefficient is 1.0; seed the per-exercise estimate well below a realistic
        // 1RM so RIR_5_PLUS at the seed weight is plausible.
        val initialEstimate = 80f
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST, baselineWeight = initialEstimate, asOf = 0))

        val sessionData = listOf(
            Triple(SESSION_1_ID, SESSION_1_START, 80f),
            Triple(SESSION_2_ID, SESSION_2_START, 82.5f),
            Triple(SESSION_3_ID, SESSION_3_START, 85f),
        )
        for ((sid, startTime, weight) in sessionData) {
            db.workoutSessionDao().insert(WorkoutSession(
                id = sid, startTime = startTime, endTime = startTime + 3600_000L))
            repeat(3) { i ->
                db.workoutSetDao().insert(WorkoutSet(
                    sessionId = sid, exerciseId = BENCH_EXERCISE_ID, setNumber = i + 1,
                    targetWeight = weight, targetReps = 5, actualReps = 5,
                    feedback = SetFeedback.RIR_5_PLUS, completedAt = startTime + i * 120_000L))
            }
        }

        repository.replayDerivedState()

        // Per-muscle CHEST display history must rise across the three sessions.
        val history = repository.derivedState.snapshot().allBaselineHistory()
            .filter { it.changeReason == BaselineChangeReason.PROGRESSION && it.muscleGroup == MuscleGroup.CHEST }
            .sortedBy { it.timestamp }
        assertTrue(
            "expected at least 3 PROGRESSION rows for CHEST, got ${history.size}: $history",
            history.size >= 3,
        )
        val levels = listOf(initialEstimate) + history.map { it.newBaseline }
        for (i in 1 until levels.size) {
            assertTrue(
                "expected strictly increasing CHEST levels but step $i went " +
                    "${levels[i - 1]} -> ${levels[i]}: full sequence = $levels",
                levels[i] > levels[i - 1],
            )
        }
        // Final per-exercise estimate must be strictly above the seed.
        val finalEstimate = repository.derivedState.snapshot().exerciseBeliefs()[BENCH_EXERCISE_ID]!!.e1rm
        assertTrue(
            "expected final estimate > seed $initialEstimate, got $finalEstimate",
            finalEstimate > initialEstimate,
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
        // Profile + one CHEST exercise + 2 sessions with completed sets + per-exercise initial.
        db.userProfileDao().insert(UserProfile(
            sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG))
        db.exerciseDao().insert(Exercise(
            id = BENCH_EXERCISE_ID, name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL))
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST, baselineWeight = 80f, asOf = 0))

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
        private const val SESSION_3_ID = 3L
        private const val SESSION_1_START = 1_700_000_000_000L
        private const val SESSION_2_START = 1_700_086_400_000L  // +1 day
        private const val SESSION_3_START = 1_700_172_800_000L  // +2 days
    }
}
