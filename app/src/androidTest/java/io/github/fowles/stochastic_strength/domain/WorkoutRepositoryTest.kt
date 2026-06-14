package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
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
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
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

    // TODO Task 22 (Phase 6): re-enable when applySessionProgression is called via finishSession
    // which builds the snapshot and asOf from live session context. Old signature removed in Phase 4.
    // @Test
    // fun applySessionProgression_logs_PROGRESSION_row() = runBlocking { ... }

    @Test
    fun applyManualBaselineOverrides_logs_OVERRIDE_row() = runBlocking {
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.BACK, 80f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))

        repository.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.BACK to 90f))

        val logs = db.baselineHistoryDao().getForSession(sessionId)
        assertEquals(1, logs.size)
        with(logs[0]) {
            assertEquals(MuscleGroup.BACK, muscleGroup)
            assertEquals(80f, previousBaseline)
            assertEquals(90f, newBaseline)
            assertEquals(BaselineChangeReason.OVERRIDE, changeReason)
            assertEquals(sessionId, this.sessionId)
            assertNull(feedbacks)
        }
    }

    // TODO Task 18 (Phase 5): applySessionProgression no longer sets hurtFlag — verify
    // via exercise_hurt_state table instead. Commented out until Phase 5 wires the live path.
    // @Test
    // fun applySessionProgression_setsHurtFlagWhenFeedbackIsHurt() ...

    // TODO Task 22 (Phase 6): re-enable when live session-end path uses replayDerivedState.
    // Old applySessionProgression(sessionId) signature removed in Phase 4.
    // @Test
    // fun applySessionProgression_aggregatesExercisesInSameMuscleGroupIntoOneLogEntry() ...
    // @Test
    // fun applySessionProgression_capsBaselineWhenExerciseReductionProvided() ...

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
        db.baselineHistoryDao().insert(
            BaselineHistory(
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
        db.baselineHistoryDao().insert(
            BaselineHistory(
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

    // TODO Task 23 (Phase 7): re-enable these tests once recomputeCoefficients is re-exposed
    // or replaced with a testable standalone entry point. Signature changed in Phase 4 to
    // recomputeCoefficients(snapshot, asOf) — old no-arg form removed.
    // @Test fun recomputeCoefficients_writes_log_row_with_null_previousCoefficient_on_first_run() ...
    // @Test fun recomputeCoefficients_second_run_populates_previousCoefficient() ...
    // @Test fun recomputeCoefficients_firstHeuristicWinsWhenBothEmitResultForSameExercise() ...

    // TODO Task 22 (Phase 6): re-enable when live session-end path calls snapshot-aware progression.
    // @Test fun applySessionProgression_triggers_coefficient_recompute() ...

    // TODO Task 22 (Phase 6): re-enable timestamp-checks after live session-end path is wired.
    // applySessionProgression now takes explicit asOf; old timestamp-derivation removed.
    // @Test fun applySessionProgression_baselineLogTimestampMatchesLatestSetCompletedAt() ...
    // @Test fun applySessionProgression_baselineLogFallsBackToSessionEndTime_whenSetsLackCompletedAt() ...
    // @Test fun applySessionProgression_coefficientLogUsesSessionTriggerTime() ...

    // TODO Task 23 (Phase 7): re-enable once standalone recomputeCoefficients is re-exposed.
    // @Test fun recomputeCoefficients_standaloneUsesLatestSetCompletedAt() ...

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

    // TODO Task 23 (Phase 7): re-enable applyBaselineNormalization tests once the function is
    // re-exposed with a testable entry point (currently requires a ReplaySnapshot parameter).
    // Signature changed in Phase 4 to applyBaselineNormalization(snapshot, asOf, sessionId).
    // @Test fun applyBaselineNormalization_writesNothing_whenNoNormalizersRegistered() ...
    // @Test fun applyBaselineNormalization_writesNothing_whenBelowThreshold() ...
    // @Test fun applyBaselineNormalization_writesBaselineAndCoefficientLogs_whenAboveThreshold() ...
    // @Test fun applyBaselineNormalization_preservesSessionWeightWithinRoundingTolerance() ...
    // @Test fun applyBaselineNormalization_scalesUnobservedExercisesInGroup() ...

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

        val heuristicRows = db.coefficientHistoryDao().getAll()
            .filter { it.heuristicName == "test-heuristic" }
        assertEquals(1, heuristicRows.size)
        val normRows = db.baselineHistoryDao().getAll()
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

        val rows = db.baselineHistoryDao().getAll()
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

        val rows = db.baselineHistoryDao().getAll()
        assertEquals(0, rows.size)
    }

    // TODO Task 22 (Phase 6): re-enable once live session-end calls snapshot-aware progression.
    // applySessionProgression(sessionId) old signature removed in Phase 4.
    // @Test fun applySessionProgression_triggersNormalizationViaDerivedState() ...

    @Test
    fun applyManualBaselineOverrides_doesNotTriggerNormalization() = runBlocking {
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.50f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.CHEST to 120f))

        // Only the OVERRIDE row should exist — no NORMALIZATION row.
        val rows = db.baselineHistoryDao().getAll()
        assertEquals(1, rows.size)
        assertEquals(BaselineChangeReason.OVERRIDE, rows[0].changeReason)
    }

    // TODO Task 22 (Phase 6): re-enable once live session-end calls snapshot-aware progression.
    // applySessionProgression(sessionId) old signature removed in Phase 4.
    // @Test fun applySessionProgression_withDriftedCoefficients_writesNormalizationRow() ...

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
        db.coefficientHistoryDao().insert(CoefficientHistory(
            exerciseId = benchId, coefficient = 1.20f, heuristicName = "preseed",
            heuristicMetadata = null, computedAt = now - 30 * day,
        ))
        db.coefficientHistoryDao().insert(CoefficientHistory(
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
            // The est-coef heuristic looks up baselines via BaselineHistory(PROGRESSION) keyed by
            // (sessionId, muscleGroup). Without this row the heuristic finds no baseline and emits nothing.
            db.baselineHistoryDao().insert(BaselineHistory(
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
        val coefHeuristicRows = db.coefficientHistoryDao().getAll()
            .filter { it.heuristicName == "est-coef-consensus" }
        assertTrue("expected at least one est-coef-consensus row, got 0",
            coefHeuristicRows.isNotEmpty())
        val normRows = db.baselineHistoryDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertTrue("expected at least one NORMALIZATION row, got 0",
            normRows.isNotEmpty())
    }
}
