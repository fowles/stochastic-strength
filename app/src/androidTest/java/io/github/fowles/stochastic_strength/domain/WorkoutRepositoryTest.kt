package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryTest {

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
    fun applyManualExerciseOverrides_writesExerciseStrengthOverrideRow() = runBlocking {
        val exerciseId = 200L
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))

        repository.applyManualExerciseOverrides(sessionId, mapOf(exerciseId to 90f))

        val overrides = db.exerciseStrengthOverrideDao().getForSession(sessionId)
        assertEquals(1, overrides.size)
        with(overrides[0]) {
            assertEquals(exerciseId, this.exerciseId)
            assertEquals(90f, e1rm)
            assertEquals(sessionId, this.sessionId)
            assertEquals(1000L, asOf)
            assertEquals(BaselineChangeReason.OVERRIDE, reason)
        }
        // Must NOT write to muscle_group_strength or baseline_history — those are derived.
        // (The session has no endTime, so replay derives nothing from it.)
        val snap = repository.derivedState.snapshot()
        assertTrue(snap.allMuscleGroupStrengths().isEmpty())
        assertTrue(snap.allBaselineHistory().isEmpty())
    }

    @Test
    fun applyManualExerciseOverrides_doesNotWriteHistoryOrStrength() = runBlocking {
        val exerciseId = 200L
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        val repo = WorkoutRepository(db)

        repo.applyManualExerciseOverrides(sessionId, mapOf(exerciseId to 120f))

        // Only the exercise_strength_override input row should exist — no derived writes.
        val snap = repo.derivedState.snapshot()
        val rows = snap.allBaselineHistory()
        assertTrue("expected no baseline_history rows", rows.isEmpty())
        assertTrue(snap.allMuscleGroupStrengths().isEmpty())
    }

    @Test
    fun finishSession_aggregatesExercisesInSameMuscleGroupIntoOneLogEntry() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Machine Chest Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.MACHINE),
        ))
        val exercises = db.exerciseDao().getActive()
        val ex1 = exercises.first { it.name == "Barbell Bench Press" }   // seed coef 1.00
        val ex2 = exercises.first { it.name == "Machine Chest Press" }   // seed coef 0.90
        // Per-exercise initial seeds at the same muscle level (100): bench E=100, machine E=90.
        db.exerciseStrengthOverrideDao().insert(ExerciseStrengthOverride(
            sessionId = null, exerciseId = ex1.id, e1rm = 100f, asOf = 0L,
        ))
        db.exerciseStrengthOverrideDao().insert(ExerciseStrengthOverride(
            sessionId = null, exerciseId = ex2.id, e1rm = 90f, asOf = 0L,
        ))
        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = 1000L, endTime = 2000L)
        )
        db.workoutSetDao().insert(
            WorkoutSet(sessionId = sessionId, exerciseId = ex1.id, setNumber = 1,
                targetWeight = 80f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_5_PLUS,
                completedAt = 1500L)
        )
        db.workoutSetDao().insert(
            WorkoutSet(sessionId = sessionId, exerciseId = ex2.id, setNumber = 1,
                targetWeight = 65f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_5_PLUS,
                completedAt = 1600L)
        )

        repository.finishSession()

        // The display projection still aggregates both CHEST exercises into ONE muscle-level
        // PROGRESSION log entry (writeLevelUpdate is per-muscle), and easy feedback drives the
        // CHEST level up.
        val logs = repository.derivedState.snapshot().allBaselineHistory()
            .filter { it.changeReason == BaselineChangeReason.PROGRESSION && it.sessionId == sessionId }
        assertEquals("two exercises in same muscle group should produce one log entry", 1, logs.size)
        assertEquals(MuscleGroup.CHEST, logs[0].muscleGroup)
        assertTrue("combined good feedback should increase CHEST level", logs[0].newBaseline > 100f)

        // Both exercises actually contributed: each per-exercise estimate moved up from its seed.
        val estimates = repository.derivedState.snapshot().exerciseBeliefs()
        assertTrue("bench estimate should rise above its 100 seed",
            (estimates[ex1.id]?.e1rm ?: 0f) > 100f)
        assertTrue("machine-press estimate should rise above its 90 seed",
            (estimates[ex2.id]?.e1rm ?: 0f) > 90f)
    }

    @Test
    fun buildPlanner_excludesExercisesMarkedForLocation() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Push-Up",             primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BODYWEIGHT),
        ))
        val exercises = db.exerciseDao().getActive()
        val barbellId = exercises.first { it.name == "Barbell Bench Press" }.id

        val locationId = db.knownLocationDao().insert(KnownLocation(name = "Home", latitude = 0.0, longitude = 0.0))
        db.locationExcludedExerciseDao().insert(LocationExcludedExercise(locationId, barbellId))

        val planner = repository.buildPlanner(locationId = locationId, weightUnit = WeightUnit.KG)

        assertFalse("excluded exercise must not appear in planner",
            planner.availableExercises.any { it.id == barbellId })
        assertTrue("non-excluded exercise must be available",
            planner.availableExercises.any { it.name == "Push-Up" })
    }

    @Test
    fun detrainingReduction_lowersEstimateAndTagsOverrideRow() = runBlocking {
        // Seed a single CHEST exercise's per-exercise initial estimate (the replay starting point).
        val benchId = db.exerciseDao().insert(Exercise(
            name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL,
        ))
        db.exerciseStrengthOverrideDao().insert(ExerciseStrengthOverride(
            sessionId = null, exerciseId = benchId, e1rm = 100f, asOf = 0L,
        ))
        repository.replayDerivedState()
        val before = repository.derivedState.snapshot().exerciseBeliefs()[benchId]!!.e1rm
        assertEquals(100f, before, 0.01f)

        // Detrain the exercise to 80% of its current estimate, applied at a completed session.
        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = 1_000L, endTime = 2_000L)
        )
        repository.applyDetrainingReduction(sessionId, mapOf(benchId to before * 0.8f))

        // The detrain adjustment lands as a per-exercise override row tagged DETRAIN…
        val rows = db.exerciseStrengthOverrideDao().getForSession(sessionId)
        val detrain = rows.first { it.reason == BaselineChangeReason.DETRAIN }
        assertEquals(benchId, detrain.exerciseId)
        assertEquals(before * 0.8f, detrain.e1rm, 0.01f)

        // …and applyDetrainingReduction re-ran replay, so the live estimate is now the reduced value.
        val after = repository.derivedState.snapshot().exerciseBeliefs()[benchId]!!.e1rm
        assertEquals(before * 0.8f, after, 0.01f)
    }

    @Test
    fun manualOverride_winsOverDetrain_inSameSession() = runBlocking {
        val benchId = db.exerciseDao().insert(Exercise(
            name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL,
        ))
        db.exerciseStrengthOverrideDao().insert(ExerciseStrengthOverride(
            sessionId = null, exerciseId = benchId, e1rm = 100f, asOf = 0L,
        ))
        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = 1_000L, endTime = 2_000L)
        )
        // Detrain first, then a manual override at the same session: the override is applied
        // last during replay, so it wins.
        repository.applyDetrainingReduction(sessionId, mapOf(benchId to 50f))
        repository.applyManualExerciseOverrides(sessionId, mapOf(benchId to 70f))

        val estimate = repository.derivedState.snapshot().exerciseBeliefs()[benchId]!!.e1rm
        assertEquals(70f, estimate, 0.01f)
    }

    @Test
    fun buildPlanner_reducesPrescriptionAfterALayoff() = runBlocking {
        val benchId = db.exerciseDao().insert(Exercise(
            name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL,
        ))
        db.exerciseStrengthOverrideDao().insert(ExerciseStrengthOverride(
            sessionId = null, exerciseId = benchId, e1rm = 1000f, asOf = 0L,
        ))
        repository.replayDerivedState()

        // A completed session 3 weeks ago is the only session on record: the comeback
        // prescription should be eased down by the inferred detraining curve.
        val threeWeeksAgo = System.currentTimeMillis() - 3L * DetrainingModel.WEEK_MILLIS
        db.workoutSessionDao().insert(WorkoutSession(startTime = threeWeeksAgo, endTime = threeWeeksAgo))

        val afterLayoffPlan = repository.buildPlanner(locationId = null, weightUnit = WeightUnit.KG)
            .generateWorkout(repMin = 5, repMax = 5)
        val afterLayoff = afterLayoffPlan.exercises.first { it.exercise.id == benchId }.sessionWeight

        // A fresh (yesterday) completed session supersedes the stale one -> no decay.
        val yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        db.workoutSessionDao().insert(WorkoutSession(startTime = yesterday, endTime = yesterday))

        val freshPlan = repository.buildPlanner(locationId = null, weightUnit = WeightUnit.KG)
            .generateWorkout(repMin = 5, repMax = 5)
        val fresh = freshPlan.exercises.first { it.exercise.id == benchId }.sessionWeight

        // 3 weeks off -> 15% reduction (retention 0.85), independent of the seed magnitude.
        assertEquals(fresh * 0.85f, afterLayoff, fresh * 0.02f)
    }

    @Test
    fun seedInitialWeights_writesExerciseInitialsThatSeedTheEstimateMap() = runBlocking {
        // seedInitialWeights writes one per-exercise initial per loaded exercise present in the DB.
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
        ))

        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)

        // Per-exercise initials must be written for every loaded exercise with a positive seed.
        val initials = db.exerciseStrengthOverrideDao().getInitials()
        assertTrue("expected at least one exercise_strength_override initial", initials.isNotEmpty())
        assertTrue("all initials must be sessionId=null", initials.all { it.sessionId == null })
        assertTrue("all initials must carry a positive e1rm", initials.all { it.e1rm > 0f })

        // replay seeds the live planner's estimate map from those initials (the replay starting
        // point). This is what the per-exercise contract reads for the weight calc.
        val estimates = repository.derivedState.snapshot().exerciseBeliefs()
        for (initial in initials) {
            assertEquals(
                "estimate for exercise ${initial.exerciseId} must seed from its initial e1rm",
                initial.e1rm,
                estimates[initial.exerciseId]?.e1rm ?: 0f,
                0.01f,
            )
        }

        // Cold-start display parity: seeding also fills muscle_group_strength (projected from the
        // seeded estimates) so the History strength grid is non-empty before the first workout.
        // Under the bare per-exercise migration this grid was empty until a session was replayed.
        val strengths = repository.derivedState.snapshot().allMuscleGroupStrengths()
        assertTrue(
            "expected CHEST muscle_group_strength populated at seed time; got $strengths",
            strengths.any { it.muscleGroup == MuscleGroup.CHEST && it.baselineWeight > 0f },
        )
        assertTrue(
            "expected QUADS muscle_group_strength populated at seed time; got $strengths",
            strengths.any { it.muscleGroup == MuscleGroup.QUADS && it.baselineWeight > 0f },
        )
    }
}
