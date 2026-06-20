package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.domain.BaselineNormalizationInput
import io.github.fowles.stochastic_strength.domain.BaselineNormalizationProposal
import io.github.fowles.stochastic_strength.domain.BaselineNormalizer
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

    // TODO Task 22 (Phase 6): re-enable when applySessionProgression is called via finishSession
    // which builds the snapshot and asOf from live session context. Old signature removed in Phase 4.
    // @Test
    // fun applySessionProgression_logs_PROGRESSION_row() = runBlocking { ... }

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
        assertTrue(db.muscleGroupStrengthDao().getAll().isEmpty())
        assertTrue(db.baselineHistoryDao().getAll().isEmpty())
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

    // TODO Task 22 (Phase 6): re-enable once live session-end calls snapshot-aware progression.
    // applySessionProgression(sessionId) old signature removed in Phase 4.
    // @Test fun applySessionProgression_triggersNormalizationViaDerivedState() ...

    @Test
    fun applyManualBaselineOverrides_doesNotWriteHistoryOrStrength() = runBlocking {
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.50f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.CHEST to 120f))

        // Only the baseline_override input row should exist — no derived writes.
        val rows = db.baselineHistoryDao().getAll()
        assertTrue("expected no baseline_history rows; normalization must not trigger", rows.isEmpty())
        assertTrue(db.muscleGroupStrengthDao().getAll().isEmpty())
    }

    // TODO Task 22 (Phase 6): re-enable once live session-end calls snapshot-aware progression.
    // applySessionProgression(sessionId) old signature removed in Phase 4.
    // @Test fun applySessionProgression_withDriftedCoefficients_writesNormalizationRow() ...
}
