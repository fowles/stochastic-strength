package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
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
import org.junit.Assert.assertNull
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
            heuristics = listOf(EstCoefConsensusHeuristic()),
            normalizers = listOf(SeedNormalizer()),
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

    // TODO Task 21 (Phase 6): Tests for derivedStateVersion/CURRENT_VERSION are rewritten here.
    // The below tests are commented out because UserProfile.derivedStateVersion is dropped in Phase 3.
    // Phase 6 rewrites DerivedStateBackfill to use replay; tests will be updated accordingly.

    // @Test
    // fun run_atVersion0_bumpsToCurrentAndExecutesActualRepsBackfill() ...

    // @Test
    // fun run_atCurrentVersion_isNoOpAndDoesNotRewriteActualReps() ...

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
}
