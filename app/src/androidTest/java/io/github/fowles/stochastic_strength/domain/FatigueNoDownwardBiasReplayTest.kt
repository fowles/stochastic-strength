package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard: a sequence of clean high-rep sessions where only the last set is a
 * near-miss TOO_HARD (actualReps = targetReps - 1) must never drive a muscle's baseline
 * *below* its seed value through the EstCoefConsensusHeuristic → SeedNormalizer path.
 *
 * Background: the old LastSet heuristic had a fatigue downward bias. After replacing it with
 * LastSetAutoregulationHeuristic (Task 3), this test guards that the coefficient → normalizer
 * pipeline cannot smuggle a net downward baseline move back in on clean sessions.
 *
 * See: spec "Open risk" section in task-4-brief.md.
 */
@RunWith(AndroidJUnit4::class)
class FatigueNoDownwardBiasReplayTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Use real EstCoefConsensusHeuristic + SeedNormalizer — the exact path under test.
        repository = WorkoutRepository(
            db,
            heuristic = EstCoefConsensusHeuristic(),
            normalizer = SeedNormalizer(),
            baselineHeuristic = FakeBaselineHeuristic(),
        )
    }

    @After
    fun tearDown() = db.close()

    /**
     * 3 sessions: each has 3 working sets at the seed weight (100 kg), targetReps=10.
     * Sets 1–2 are clean completions (RIR_2_4), set 3 is the fatigue near-miss
     * (TOO_HARD, actualReps = targetReps - 1 = 9).
     * No mid-session reductions, no HURT.
     * Expected: CHEST baseline >= 100f after replay.
     */
    @Test
    fun cleanFatigueSessions_neverDriveBaselineDown() = runBlocking {
        db.userProfileDao().insert(UserProfile(
            sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG))
        db.exerciseDao().insert(Exercise(
            id = BENCH_EXERCISE_ID,
            name = "Barbell Bench Press",
            primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL,
        ))
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null,
            muscleGroup = MuscleGroup.CHEST,
            baselineWeight = SEED_BASELINE,
            asOf = 0L,
        ))

        for (sessionIdx in 0 until 3) {
            val sessionStart = BASE_TIME + sessionIdx * DAY_MS
            val sessionId = BASE_SESSION_ID + sessionIdx
            db.workoutSessionDao().insert(WorkoutSession(
                id = sessionId,
                startTime = sessionStart,
                endTime = sessionStart + 3600_000L,
            ))
            // Sets 1 and 2: clean completions at the target.
            repeat(2) { setIdx ->
                db.workoutSetDao().insert(WorkoutSet(
                    sessionId = sessionId,
                    exerciseId = BENCH_EXERCISE_ID,
                    setNumber = setIdx + 1,
                    targetWeight = SEED_BASELINE,
                    targetReps = TARGET_REPS,
                    actualReps = TARGET_REPS,
                    feedback = SetFeedback.RIR_2_4,
                    completedAt = sessionStart + setIdx * 300_000L,
                ))
            }
            // Set 3: near-miss TOO_HARD — last set fatigue, one rep short.
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = sessionId,
                exerciseId = BENCH_EXERCISE_ID,
                setNumber = 3,
                targetWeight = SEED_BASELINE,
                targetReps = TARGET_REPS,
                actualReps = TARGET_REPS - 1,
                feedback = SetFeedback.TOO_HARD,
                completedAt = sessionStart + 600_000L,
            ))
        }

        repository.replayDerivedState()

        val baseline = repository.derivedState.snapshot()
            .allMuscleGroupStrengths()
            .firstOrNull { it.muscleGroup == MuscleGroup.CHEST }
            ?.baselineWeight
            ?: 0f

        assertTrue(
            "CHEST baseline should not drop below seed $SEED_BASELINE after clean fatigue sessions; " +
                "got $baseline",
            baseline >= SEED_BASELINE,
        )
    }

    companion object {
        private const val BENCH_EXERCISE_ID = 100L
        private const val BASE_SESSION_ID = 1L
        private const val SEED_BASELINE = 100f
        private const val TARGET_REPS = 10
        private const val BASE_TIME = 1_700_000_000_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
