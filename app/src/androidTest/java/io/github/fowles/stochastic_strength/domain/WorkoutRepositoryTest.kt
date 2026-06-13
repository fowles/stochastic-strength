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
import io.github.fowles.stochastic_strength.data.model.CoefficientChangeLog
import io.github.fowles.stochastic_strength.domain.BaselineNormalizationInput
import io.github.fowles.stochastic_strength.domain.BaselineNormalizationProposal
import io.github.fowles.stochastic_strength.domain.BaselineNormalizer
import io.github.fowles.stochastic_strength.domain.CoefficientComputationInput
import io.github.fowles.stochastic_strength.domain.CoefficientHeuristic
import io.github.fowles.stochastic_strength.domain.CoefficientResult
import io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristic
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.SeedNormalizer
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
    fun buildCoefficientInput_populates_sets_sessionTimes_exerciseMuscle_baselines_and_currentCoefficients() = runBlocking {
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

        assertEquals(2, input.sets.size)
        val firstSet = input.sets.first { it.setNumber == 1 }
        val secondSet = input.sets.first { it.setNumber == 2 }
        assertEquals(exerciseId, firstSet.exerciseId)
        assertEquals(sessionId, firstSet.sessionId)
        assertEquals(80f, firstSet.targetWeight, 0.001f)
        assertEquals(SetFeedback.RIR_2_4, firstSet.feedback)
        assertEquals(75f, secondSet.targetWeight, 0.001f)
        assertEquals(SetFeedback.TOO_HARD, secondSet.feedback)
        assertEquals(5000L, input.sessionTimes[sessionId])
        assertEquals(MuscleGroup.CHEST, input.exerciseMuscle[exerciseId])
        assertEquals(100f, input.baselines[sessionId to MuscleGroup.CHEST]!!, 0.001f)
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
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
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
                input.sets.map { it.exerciseId }.distinct()
                    .map { CoefficientResult(it, 0.9f, "meta") }
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
                input.sets.map { it.exerciseId }.distinct()
                    .map { CoefficientResult(it, 0.9f) }
        }
        WorkoutRepository(db, heuristics = listOf(heuristic1)).recomputeCoefficients()

        val heuristic2 = object : CoefficientHeuristic {
            override val name = "h2"
            override fun compute(input: CoefficientComputationInput) =
                input.sets.map { it.exerciseId }.distinct()
                    .map { CoefficientResult(it, 0.95f) }
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
                input.sets.map { it.exerciseId }.distinct()
                    .map { CoefficientResult(it, 0.85f) }
        }
        val repo = WorkoutRepository(db, heuristics = listOf(testHeuristic))

        repo.applySessionProgression(sessionId)

        val logs = db.coefficientChangeLogDao().getLatestPerExercise()
        assertEquals(1, logs.size)
        assertEquals(exerciseId, logs.first().exerciseId)
        assertEquals(0.85f, logs.first().coefficient, 0.001f)
    }

    @Test
    fun recomputeCoefficients_firstHeuristicWinsWhenBothEmitResultForSameExercise() = runBlocking {
        val (exerciseId, _) = seedChestSession()
        val heuristic1 = object : CoefficientHeuristic {
            override val name = "first"
            override fun compute(input: CoefficientComputationInput) =
                input.sets.map { it.exerciseId }.distinct()
                    .map { CoefficientResult(it, 0.75f, "meta1") }
        }
        val heuristic2 = object : CoefficientHeuristic {
            override val name = "second"
            override fun compute(input: CoefficientComputationInput) =
                input.sets.map { it.exerciseId }.distinct()
                    .map { CoefficientResult(it, 0.85f, "meta2") }
        }
        val repo = WorkoutRepository(db, heuristics = listOf(heuristic1, heuristic2))

        repo.recomputeCoefficients()

        val logs = db.coefficientChangeLogDao().getLatestPerExercise()
        assertEquals(1, logs.size)
        assertEquals(exerciseId, logs.first().exerciseId)
        assertEquals(0.75f, logs.first().coefficient, 0.001f)
        assertEquals("first", logs.first().heuristicName)
    }

    @Test
    fun applySessionProgression_baselineLogTimestampMatchesLatestSetCompletedAt() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val startMs = 1_700_000_000_000L
        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = startMs, endTime = startMs + 60 * 60_000L)
        )
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = startMs + 5 * 60_000L,
        ))
        val lastSetMs = startMs + 15 * 60_000L
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = exerciseId, setNumber = 2,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = lastSetMs,
        ))

        repository.applySessionProgression(sessionId)

        val log = db.baselineChangeLogDao().getForSession(sessionId).single()
        assertEquals(lastSetMs, log.timestamp)
    }

    @Test
    fun applySessionProgression_baselineLogFallsBackToSessionEndTime_whenSetsLackCompletedAt() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val startMs = 1_700_000_000_000L
        val endMs = startMs + 60 * 60_000L
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = startMs, endTime = endMs))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = null,
        ))

        repository.applySessionProgression(sessionId)

        val log = db.baselineChangeLogDao().getForSession(sessionId).single()
        assertEquals(endMs, log.timestamp)
    }

    @Test
    fun applySessionProgression_coefficientLogUsesSessionTriggerTime() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val startMs = 1_700_000_000_000L
        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = startMs, endTime = startMs + 60 * 60_000L)
        )
        val lastSetMs = startMs + 20 * 60_000L
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = lastSetMs,
        ))
        val testHeuristic = object : CoefficientHeuristic {
            override val name = "test"
            override fun compute(input: CoefficientComputationInput) =
                input.sets.map { it.exerciseId }.distinct()
                    .map { CoefficientResult(it, 0.85f) }
        }
        val repo = WorkoutRepository(db, heuristics = listOf(testHeuristic))

        repo.applySessionProgression(sessionId)

        val log = db.coefficientChangeLogDao().getLatestPerExercise().single()
        assertEquals(lastSetMs, log.computedAt)
    }

    @Test
    fun recomputeCoefficients_standaloneUsesLatestSetCompletedAt() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        val startMs = 1_700_000_000_000L
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = startMs))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = startMs + 5 * 60_000L,
        ))
        val lastSetMs = startMs + 25 * 60_000L
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = exerciseId, setNumber = 2,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = lastSetMs,
        ))
        val testHeuristic = object : CoefficientHeuristic {
            override val name = "test"
            override fun compute(input: CoefficientComputationInput) =
                input.sets.map { it.exerciseId }.distinct()
                    .map { CoefficientResult(it, 0.9f) }
        }
        val repo = WorkoutRepository(db, heuristics = listOf(testHeuristic))

        repo.recomputeCoefficients()

        val log = db.coefficientChangeLogDao().getLatestPerExercise().single()
        assertEquals(lastSetMs, log.computedAt)
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

    private fun fakeNormalizer(name: String, proposals: List<BaselineNormalizationProposal>) =
        object : BaselineNormalizer {
            override val name: String = name
            override fun compute(input: BaselineNormalizationInput) = proposals
        }

    @Test
    fun applyBaselineNormalization_writesNothing_whenNoNormalizersRegistered() = runBlocking {
        seedChestSession()
        val repo = WorkoutRepository(db, normalizers = emptyList())

        repo.applyBaselineNormalization(asOf = 1_000L, sessionId = 1L)

        val baselineRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(0, baselineRows.size)
    }

    @Test
    fun applyBaselineNormalization_writesNothing_whenBelowThreshold() = runBlocking {
        val (_, sessionId) = seedChestSession()
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        // m = 0.99 -> new baseline ≈ 101.01 -> rounded to 101 -> |101-100|=1kg < 2kg threshold
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.99f, metadata = "test")
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyBaselineNormalization(asOf = 2_000L, sessionId = sessionId)

        val baselineRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(0, baselineRows.size)
        // baseline unchanged
        assertEquals(100f, db.muscleGroupStrengthDao().get(MuscleGroup.CHEST)!!.baselineWeight)
    }

    @Test
    fun applyBaselineNormalization_writesBaselineAndCoefficientLogs_whenAboveThreshold() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val benchId = db.exerciseDao().getActive().first { it.name == "Barbell Bench Press" }.id
        val inclineId = db.exerciseDao().getActive().first { it.name == "Incline Barbell Bench Press" }.id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        // m = 0.90 -> raw new = 100 / 0.90 ≈ 111.11 -> rounded to 111 -> 11 kg > 2 kg threshold
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = "n=2, m=0.9000")
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyBaselineNormalization(asOf = 3_000L, sessionId = sessionId)

        val baselineRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(1, baselineRows.size)
        with(baselineRows[0]) {
            assertEquals(MuscleGroup.CHEST, muscleGroup)
            assertEquals(100f, previousBaseline)
            assertTrue("new baseline should be greater than old (m<1 raises baseline)", newBaseline > 100f)
            assertEquals(sessionId, this.sessionId)
            assertEquals(3_000L, timestamp)
        }
        val coefRows = db.coefficientChangeLogDao().getAll()
            .filter { it.heuristicName == "baseline_normalization" }
        // Both chest exercises (bench + incline) have defined seed coefficients, so both get scaled.
        assertEquals(2, coefRows.size)
        assertTrue(coefRows.any { it.exerciseId == benchId })
        assertTrue(coefRows.any { it.exerciseId == inclineId })
        assertEquals("n=2, m=0.9000", coefRows[0].heuristicMetadata)
    }

    @Test
    fun applyBaselineNormalization_preservesSessionWeightWithinRoundingTolerance() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val benchId = db.exerciseDao().getActive().first { it.name == "Barbell Bench Press" }.id
        val inclineId = db.exerciseDao().getActive().first { it.name == "Incline Barbell Bench Press" }.id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))

        // Capture seed coefficients (these become the "current" coefficients used by the runner).
        val benchSeed = ExerciseCoefficients.byName.getValue("Barbell Bench Press")
        val inclineSeed = ExerciseCoefficients.byName.getValue("Incline Barbell Bench Press")
        val benchWeightBefore = 100f * benchSeed
        val inclineWeightBefore = 100f * inclineSeed

        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyBaselineNormalization(asOf = 4_000L, sessionId = sessionId)

        val newBaseline = db.muscleGroupStrengthDao().get(MuscleGroup.CHEST)!!.baselineWeight
        val coefs = db.coefficientChangeLogDao().getLatestPerExercise().associateBy { it.exerciseId }
        val benchWeightAfter = newBaseline * coefs.getValue(benchId).coefficient
        val inclineWeightAfter = newBaseline * coefs.getValue(inclineId).coefficient
        // Session weights are preserved exactly (mEffective is derived from the rounded baseline).
        assertEquals(benchWeightBefore, benchWeightAfter, 1e-3f)
        assertEquals(inclineWeightBefore, inclineWeightAfter, 1e-3f)
    }

    @Test
    fun applyBaselineNormalization_scalesUnobservedExercisesInGroup() = runBlocking {
        // Setup: two exercises in CHEST, only one is "observed" (has a WorkoutSet).
        // The runner doesn't look at the input set / output set distinction directly — it just scales
        // every CHEST exercise with a defined coefficient. That's what we're asserting.
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val benchId = db.exerciseDao().getActive().first { it.name == "Barbell Bench Press" }.id
        val inclineId = db.exerciseDao().getActive().first { it.name == "Incline Barbell Bench Press" }.id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        // Only bench has any WorkoutSet on record.
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = benchId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
        ))

        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        WorkoutRepository(db, normalizers = listOf(normalizer))
            .applyBaselineNormalization(asOf = 5_000L, sessionId = sessionId)

        val coefRows = db.coefficientChangeLogDao().getAll()
            .filter { it.heuristicName == "baseline_normalization" }
        assertEquals(2, coefRows.size)
        assertTrue(coefRows.any { it.exerciseId == benchId })
        assertTrue(coefRows.any { it.exerciseId == inclineId })
    }

    @Test
    fun recomputeDerivedState_runsCoefficientHeuristicsAndNormalizers() = runBlocking {
        val (exerciseId, sessionId) = seedChestSession()
        // Heuristic always emits 0.85 for the seeded exercise.
        val heuristic = object : CoefficientHeuristic {
            override val name = "test-heuristic"
            override fun compute(input: CoefficientComputationInput) =
                input.sets.map { it.exerciseId }.distinct().map { CoefficientResult(it, 0.85f) }
        }
        // Normalizer emits a proposal that clears the 2 kg threshold (m=0.90 on baseline 100 → new=111).
        val normalizer = fakeNormalizer("test-normalizer", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = "test")
        ))
        val repo = WorkoutRepository(db,
            heuristics = listOf(heuristic),
            normalizers = listOf(normalizer),
        )

        repo.recomputeDerivedState(asOf = 6_000L, sessionId = sessionId)

        val heuristicRows = db.coefficientChangeLogDao().getAll()
            .filter { it.heuristicName == "test-heuristic" }
        assertEquals(1, heuristicRows.size)
        val normRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(1, normRows.size)
    }

    @Test
    fun recomputeDerivedState_fallsBackToMostRecentSession_whenSessionIdNotProvided() = runBlocking {
        val (_, latestSessionId) = seedChestSession()
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.recomputeDerivedState(asOf = 7_000L, sessionId = null)

        val rows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(1, rows.size)
        assertEquals(latestSessionId, rows[0].sessionId)
    }

    @Test
    fun recomputeDerivedState_isNoOp_whenNoSessionsExistAndSessionIdNotProvided() = runBlocking {
        // Empty DB — no sessions, no exercises, nothing.
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.recomputeDerivedState(asOf = 8_000L, sessionId = null)

        val rows = db.baselineChangeLogDao().getAll()
        assertEquals(0, rows.size)
    }

    @Test
    fun applySessionProgression_triggersNormalizationViaDerivedState() = runBlocking {
        // End-to-end: progression + heuristics + normalizer in one applySessionProgression call.
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
        ))
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applySessionProgression(sessionId)

        val rows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(1, rows.size)
        assertEquals(sessionId, rows[0].sessionId)
    }

    @Test
    fun applyManualBaselineOverrides_doesNotTriggerNormalization() = runBlocking {
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.50f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.CHEST to 120f))

        // Only the MANUAL_OVERRIDE row should exist — no NORMALIZATION row.
        val rows = db.baselineChangeLogDao().getAll()
        assertEquals(1, rows.size)
        assertEquals(BaselineChangeReason.MANUAL_OVERRIDE, rows[0].changeReason)
    }

    @Test
    fun recomputeDerivedState_realStack_writesBothCoefficientHeuristicAndNormalizationLogs() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val benchId = db.exerciseDao().getActive().first { it.name == "Barbell Bench Press" }.id
        val inclineId = db.exerciseDao().getActive().first { it.name == "Incline Barbell Bench Press" }.id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))

        // Use realistic timestamps so the est-coef heuristic's recency decay doesn't collapse evidence
        // weight to zero. Sessions must be recent relative to System.currentTimeMillis(). We also need
        // enough cumulative evidence weight (minEvidenceWeight = 1.5f). Each RIR_2_4 set contributes
        // ~confidence * recency; across three recent sessions this clears the threshold.
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60_000L

        // Pre-seed inflated current coefficients (well above seed) by writing logs directly. This simulates
        // the state that arises from drift accumulated over many real sessions, which would otherwise take
        // a long sequence of sessions to produce in a test.
        db.coefficientChangeLogDao().insert(CoefficientChangeLog(
            exerciseId = benchId, coefficient = 1.20f, heuristicName = "preseed",
            heuristicMetadata = null, computedAt = now - 30 * day,
        ))
        db.coefficientChangeLogDao().insert(CoefficientChangeLog(
            exerciseId = inclineId, coefficient = 1.05f, heuristicName = "preseed",
            heuristicMetadata = null, computedAt = now - 30 * day,
        ))

        // Seed three recent sessions with sets that have feedback. The est-coef heuristic accumulates
        // evidence weight across sessions; with three sessions the total clears minEvidenceWeight = 1.5.
        // SeedNormalizer sees both exercises as observed via workoutSetDao.getAll().
        //
        // targetWeight choices are deliberate: bench at 80 kg gives estCoef < currentCoef (1.20), while
        // incline at 105 kg gives estCoef > currentCoef (1.05). Opposite signals prevent H2 consensus
        // suppression (which fires when both exercises drift the same direction past ln(1.05)).
        for (daysAgo in listOf(10L, 5L, 2L)) {
            val start = now - daysAgo * day
            val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = start, endTime = start + 60 * 60_000L))
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = sessionId, exerciseId = benchId, setNumber = 1,
                targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
                completedAt = start + 30 * 60_000L,
            ))
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = sessionId, exerciseId = inclineId, setNumber = 1,
                targetWeight = 105f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
                completedAt = start + 30 * 60_000L,
            ))
            // The est-coef heuristic looks up baselines via BaselineChangeLog(PROGRESSION) keyed by
            // (sessionId, muscleGroup). Without this row the heuristic finds no baseline and emits nothing.
            db.baselineChangeLogDao().insert(BaselineChangeLog(
                sessionId = sessionId, muscleGroup = MuscleGroup.CHEST,
                previousBaseline = 100f, newBaseline = 102f,
                changeReason = BaselineChangeReason.PROGRESSION, timestamp = start + 30 * 60_000L,
            ))
        }

        val repo = WorkoutRepository(db,
            heuristics = listOf(EstCoefConsensusHeuristic()),
            normalizers = listOf(SeedNormalizer()),
        )

        repo.recomputeDerivedState()

        // Both kinds of writes must show up — this is the regression guard for "backfill ran one pass but
        // not the other".
        val coefHeuristicRows = db.coefficientChangeLogDao().getAll()
            .filter { it.heuristicName == "est-coef-consensus" }
        assertTrue("expected at least one est-coef-consensus row, got 0",
            coefHeuristicRows.isNotEmpty())
        val normRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertTrue("expected at least one NORMALIZATION row, got 0",
            normRows.isNotEmpty())
    }
}
