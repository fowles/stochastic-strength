package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
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
    fun applyManualBaselineOverrides_writesBaselineOverrideRow() = runBlocking {
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))

        repository.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.BACK to 90f))

        val overrides = db.baselineOverrideDao().getForSession(sessionId)
        assertEquals(1, overrides.size)
        with(overrides[0]) {
            assertEquals(MuscleGroup.BACK, muscleGroup)
            assertEquals(90f, baselineWeight)
            assertEquals(sessionId, this.sessionId)
            assertEquals(1000L, asOf)
        }
        // Must NOT write to muscle_group_strength or baseline_history — those are derived.
        val snap = repository.derivedState.snapshot()
        assertTrue(snap.allMuscleGroupStrengths().isEmpty())
        assertTrue(snap.allBaselineHistory().isEmpty())
    }

    @Test
    fun applyManualBaselineOverrides_doesNotWriteHistoryOrStrength() = runBlocking {
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        val repo = WorkoutRepository(db)

        repo.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.CHEST to 120f))

        // Only the baseline_override input row should exist — no derived writes.
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
        val ex1 = exercises.first { it.name == "Barbell Bench Press" }
        val ex2 = exercises.first { it.name == "Machine Chest Press" }
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 100f, asOf = 0L,
        ))
        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = 1000L, endTime = 2000L)
        )
        db.workoutSetDao().insert(
            WorkoutSet(sessionId = sessionId, exerciseId = ex1.id, setNumber = 1,
                targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS,
                completedAt = 1500L)
        )
        db.workoutSetDao().insert(
            WorkoutSet(sessionId = sessionId, exerciseId = ex2.id, setNumber = 1,
                targetWeight = 60f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS,
                completedAt = 1600L)
        )

        repository.finishSession(sessionId, exerciseReductions = emptyMap())

        val logs = repository.derivedState.snapshot().allBaselineHistory()
            .filter { it.changeReason == BaselineChangeReason.PROGRESSION && it.sessionId == sessionId }
        assertEquals("two exercises in same muscle group should produce one log entry", 1, logs.size)
        assertEquals(MuscleGroup.CHEST, logs[0].muscleGroup)
        assertTrue("combined good feedback should increase baseline", logs[0].newBaseline > 100f)
        // Both exercise feedbacks must appear in the log
        assertEquals("RIR_5_PLUS,RIR_5_PLUS", logs[0].feedbacks)
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
    fun detrainingReduction_lowersBaselineAndTagsHistory() = runBlocking {
        // Seed initial baselines and a completed session so replay has a timeline.
        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)
        val chestBefore = repository.getMuscleGroupStrengths()
            .first { it.muscleGroup == MuscleGroup.CHEST }.baselineWeight

        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = 1_000L, endTime = 2_000L)
        )
        // Detrain CHEST to 80% of its current baseline for this session.
        repository.applyDetrainingReduction(
            sessionId,
            mapOf(MuscleGroup.CHEST to chestBefore * 0.8f),
        )
        repository.replayDerivedState()

        val chestAfter = repository.getMuscleGroupStrengths()
            .first { it.muscleGroup == MuscleGroup.CHEST }.baselineWeight
        assertEquals(chestBefore * 0.8f, chestAfter, 0.01f)

        val events = repository.getBaselineEvents(MuscleGroup.CHEST)
        val detrainEvent = events.first { it.changeReason == BaselineChangeReason.DETRAIN }
        assertEquals(sessionId, detrainEvent.sessionId)
        assertEquals(chestBefore * 0.8f, detrainEvent.newBaseline, 0.01f)
    }

    @Test
    fun manualOverride_winsOverDetrain_inSameSession() = runBlocking {
        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)
        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = 1_000L, endTime = 2_000L)
        )
        repository.applyDetrainingReduction(sessionId, mapOf(MuscleGroup.CHEST to 50f))
        repository.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.CHEST to 70f))
        repository.replayDerivedState()

        val chest = repository.getMuscleGroupStrengths()
            .first { it.muscleGroup == MuscleGroup.CHEST }.baselineWeight
        assertEquals(70f, chest, 0.01f)
    }

    @Test
    fun seedInitialWeights_writesBaselineOverrideInitialsAndPopulatesMuscleGroupStrength() = runBlocking {
        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)

        // baseline_override initials must be written for every muscle with a positive starting weight.
        val initials = db.baselineOverrideDao().getInitials()
        assertTrue("expected at least one baseline_override initial", initials.isNotEmpty())
        assertTrue("all initials must be sessionId=null", initials.all { it.sessionId == null })

        // replay must have populated derived muscle_group_strength for those same muscles.
        val strengths = repository.derivedState.snapshot().allMuscleGroupStrengths().associateBy { it.muscleGroup }
        for (initial in initials) {
            assertEquals(
                "muscle_group_strength must match the initial for ${initial.muscleGroup}",
                initial.baselineWeight,
                strengths[initial.muscleGroup]?.baselineWeight,
            )
        }
    }
}
