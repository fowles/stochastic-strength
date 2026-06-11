package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeLog
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.CoefficientComputationInput
import io.github.fowles.stochastic_strength.domain.CoefficientHeuristic
import io.github.fowles.stochastic_strength.domain.CoefficientResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun applySessionProgression_logs_PROGRESSION_row() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(
                name = "Barbell Bench Press",
                primaryMuscle = MuscleGroup.CHEST,
                equipment = Equipment.BARBELL,
            )
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        db.workoutSetDao().insert(
            WorkoutSet(
                sessionId = sessionId,
                exerciseId = exerciseId,
                setNumber = 1,
                targetWeight = 80f,
                targetReps = 5,
                feedback = SetFeedback.RIR_2_4,
            )
        )

        repository.applySessionProgression(sessionId)

        val logs = db.baselineChangeLogDao().getForSession(sessionId)
        assertEquals(1, logs.size)
        with(logs[0]) {
            assertEquals(MuscleGroup.CHEST, muscleGroup)
            assertEquals(100f, previousBaseline)
            assertTrue(newBaseline > 100f)
            assertEquals(BaselineChangeReason.PROGRESSION, changeReason)
            assertEquals("RIR_2_4", feedbacks)
            assertEquals(5, sessionReps)
            assertNull(minReductionFraction)
        }
    }

    @Test
    fun applyManualBaselineOverrides_logs_MANUAL_OVERRIDE_row() = runBlocking {
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.BACK, 80f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))

        repository.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.BACK to 90f))

        val logs = db.baselineChangeLogDao().getForSession(sessionId)
        assertEquals(1, logs.size)
        with(logs[0]) {
            assertEquals(MuscleGroup.BACK, muscleGroup)
            assertEquals(80f, previousBaseline)
            assertEquals(90f, newBaseline)
            assertEquals(BaselineChangeReason.MANUAL_OVERRIDE, changeReason)
            assertEquals(sessionId, this.sessionId)
            assertNull(feedbacks)
        }
    }

    @Test
    fun applySessionProgression_setsHurtFlagWhenFeedbackIsHurt() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        db.workoutSetDao().insert(
            WorkoutSet(
                sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
                targetWeight = 80f, targetReps = 5, feedback = SetFeedback.HURT,
            )
        )

        repository.applySessionProgression(sessionId)

        val exercise = db.exerciseDao().getById(exerciseId)
        assertTrue("hurtFlag must be set when any set has HURT feedback", exercise!!.hurtFlag)
    }

    @Test
    fun applySessionProgression_aggregatesExercisesInSameMuscleGroupIntoOneLogEntry() = runBlocking {
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
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        db.workoutSetDao().insert(
            WorkoutSet(sessionId = sessionId, exerciseId = ex1.id, setNumber = 1,
                targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
        )
        db.workoutSetDao().insert(
            WorkoutSet(sessionId = sessionId, exerciseId = ex2.id, setNumber = 1,
                targetWeight = 60f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
        )

        repository.applySessionProgression(sessionId)

        val logs = db.baselineChangeLogDao().getForSession(sessionId)
        assertEquals("two exercises in same muscle group should produce one log entry", 1, logs.size)
        assertEquals(MuscleGroup.CHEST, logs[0].muscleGroup)
        assertTrue("combined good feedback should increase baseline", logs[0].newBaseline > 100f)
        // Both exercise feedbacks must appear in the log
        assertEquals("RIR_5_PLUS,RIR_5_PLUS", logs[0].feedbacks)
    }

    @Test
    fun applySessionProgression_capsBaselineWhenExerciseReductionProvided() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        db.workoutSetDao().insert(
            WorkoutSet(sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
                targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
        )

        // 10% reduction cap: new baseline must not exceed 100 × (1 − 0.10) = 90 kg
        repository.applySessionProgression(sessionId, exerciseReductions = mapOf(exerciseId to 0.10f))

        val strength = db.muscleGroupStrengthDao().get(MuscleGroup.CHEST)!!
        assertTrue(
            "10% reduction cap should hold baseline at or below 90 kg, got ${strength.baselineWeight}",
            strength.baselineWeight <= 90.5f
        )
        val log = db.baselineChangeLogDao().getForSession(sessionId).single()
        assertEquals(0.10f, log.minReductionFraction!!, 0.001f)
    }

    @Test
    fun buildCoefficientInput_assembles_snapshots_from_sets_and_baseline_log() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(
                name = "Barbell Bench Press",
                primaryMuscle = MuscleGroup.CHEST,
                equipment = Equipment.BARBELL,
            )
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 5000L))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId,
            exerciseId = exerciseId,
            setNumber = 1,
            targetWeight = 80f,
            targetReps = 5,
            feedback = SetFeedback.RIR_2_4,
        ))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId,
            exerciseId = exerciseId,
            setNumber = 2,
            targetWeight = 75f,
            targetReps = 5,
            feedback = SetFeedback.TOO_HARD,
        ))
        db.baselineChangeLogDao().insert(
            BaselineChangeLog(
                sessionId = sessionId,
                muscleGroup = MuscleGroup.CHEST,
                previousBaseline = 100f,
                newBaseline = 95f,
                changeReason = BaselineChangeReason.PROGRESSION,
                timestamp = 5000L,
            )
        )

        val input = repository.buildCoefficientInput()

        assertEquals(1, input.history.size)
        val snap = input.history.first()
        assertEquals(exerciseId, snap.exerciseId)
        assertEquals(sessionId, snap.sessionId)
        assertEquals(5000L, snap.sessionTime)
        assertEquals(5, snap.targetReps)
        assertEquals(100f, snap.muscleBaseline, 0.001f)
        assertEquals(2, snap.sets.size)
        assertEquals(80f, snap.sets[0].targetWeight, 0.001f)
        assertEquals(SetFeedback.RIR_2_4, snap.sets[0].feedback)
        assertEquals(75f, snap.sets[1].targetWeight, 0.001f)
        assertEquals(SetFeedback.TOO_HARD, snap.sets[1].feedback)
        assertEquals(1.0f, input.currentCoefficients[exerciseId]!!, 0.001f)
    }

    private suspend fun seedChestSession(startTime: Long = 1000L): Pair<Long, Long> {
        db.exerciseDao().insertAll(listOf(
            Exercise(
                name = "Barbell Bench Press",
                primaryMuscle = MuscleGroup.CHEST,
                equipment = Equipment.BARBELL,
            )
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = startTime))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId,
            exerciseId = exerciseId,
            setNumber = 1,
            targetWeight = 80f,
            targetReps = 5,
            feedback = SetFeedback.RIR_2_4,
        ))
        db.baselineChangeLogDao().insert(
            BaselineChangeLog(
                sessionId = sessionId,
                muscleGroup = MuscleGroup.CHEST,
                previousBaseline = 100f,
                newBaseline = 102f,
                changeReason = BaselineChangeReason.PROGRESSION,
                timestamp = startTime,
            )
        )
        return exerciseId to sessionId
    }

    @Test
    fun recomputeCoefficients_writes_log_row_with_null_previousCoefficient_on_first_run() = runBlocking {
        val (exerciseId, _) = seedChestSession()
        val testHeuristic = object : CoefficientHeuristic {
            override val name = "test-heuristic"
            override fun compute(input: CoefficientComputationInput) =
                input.history.map { CoefficientResult(it.exerciseId, 0.9f, "meta") }
        }
        val repo = WorkoutRepository(db, heuristics = listOf(testHeuristic))

        repo.recomputeCoefficients()

        val logs = db.coefficientChangeLogDao().getLatestPerExercise()
        assertEquals(1, logs.size)
        assertEquals(exerciseId, logs.first().exerciseId)
        assertEquals(0.9f, logs.first().coefficient, 0.001f)
        assertNull(logs.first().previousCoefficient)
        assertEquals("test-heuristic", logs.first().heuristicName)
        assertEquals("meta", logs.first().heuristicMetadata)
    }

    @Test
    fun recomputeCoefficients_second_run_populates_previousCoefficient() = runBlocking {
        val (exerciseId, _) = seedChestSession()
        val heuristic1 = object : CoefficientHeuristic {
            override val name = "h1"
            override fun compute(input: CoefficientComputationInput) =
                input.history.map { CoefficientResult(it.exerciseId, 0.9f) }
        }
        WorkoutRepository(db, heuristics = listOf(heuristic1)).recomputeCoefficients()

        val heuristic2 = object : CoefficientHeuristic {
            override val name = "h2"
            override fun compute(input: CoefficientComputationInput) =
                input.history.map { CoefficientResult(it.exerciseId, 0.95f) }
        }
        WorkoutRepository(db, heuristics = listOf(heuristic2)).recomputeCoefficients()

        val latest = db.coefficientChangeLogDao().getLatestPerExercise()
        assertEquals(1, latest.size)
        assertEquals(0.95f, latest.first().coefficient, 0.001f)
        assertEquals(0.9f, latest.first().previousCoefficient!!, 0.001f)
    }

    @Test
    fun applySessionProgression_triggers_coefficient_recompute() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(
                name = "Barbell Bench Press",
                primaryMuscle = MuscleGroup.CHEST,
                equipment = Equipment.BARBELL,
            )
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId,
            exerciseId = exerciseId,
            setNumber = 1,
            targetWeight = 80f,
            targetReps = 5,
            feedback = SetFeedback.RIR_2_4,
        ))
        val testHeuristic = object : CoefficientHeuristic {
            override val name = "test"
            override fun compute(input: CoefficientComputationInput) =
                input.history.map { CoefficientResult(it.exerciseId, 0.85f) }
        }
        val repo = WorkoutRepository(db, heuristics = listOf(testHeuristic))

        repo.applySessionProgression(sessionId)

        val logs = db.coefficientChangeLogDao().getLatestPerExercise()
        assertEquals(1, logs.size)
        assertEquals(exerciseId, logs.first().exerciseId)
        assertEquals(0.85f, logs.first().coefficient, 0.001f)
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
}
