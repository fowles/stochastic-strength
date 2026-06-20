package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class WorkoutPlannerTest {

    private fun exercise(
        id: Long,
        name: String = "Ex$id",
        muscle: MuscleGroup = MuscleGroup.CHEST,
        isTimed: Boolean = false,
        equipment: Equipment = Equipment.BARBELL,
    ) = Exercise(id = id, name = name, primaryMuscle = muscle, equipment = equipment, isTimed = isTimed)

    private fun strengthsFor(vararg pairs: Pair<MuscleGroup, Float>): Map<MuscleGroup, MuscleGroupStrength> =
        pairs.associate { (muscle, baseline) -> muscle to MuscleGroupStrength(muscle, baseline) }

    private fun planner(
        exercises: List<Exercise> = emptyList(),
        strengths: Map<MuscleGroup, MuscleGroupStrength> = emptyMap(),
        random: Random = Random(0),
        recentHistory: Map<Long, List<WorkoutSet>> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
    ) = WorkoutPlanner(
        availableExercises = exercises,
        strengths = strengths,
        recentHistory = recentHistory,
        weightUnit = WeightUnit.KG,
        locationId = null,
        random = random,
        nowMs = nowMs,
    )

    private fun nearFailureSet(exerciseId: Long, completedAt: Long, feedback: SetFeedback = SetFeedback.RIR_0_1) = WorkoutSet(
        sessionId = 1L,
        exerciseId = exerciseId,
        setNumber = 3,
        targetWeight = 80f,
        targetReps = 8,
        feedback = feedback,
        completedAt = completedAt,
    )

    private fun fullPool(): List<Exercise> =
        MuscleGroup.entries.flatMapIndexed { gi, muscle ->
            (0..2).map { i ->
                val id = (gi * 3 + i + 1).toLong()
                // Use exercise names that have a coefficient so weights are non-zero.
                // "Barbell Bench Press" is the reference exercise (coeff = 1.0) for CHEST.
                if (muscle == MuscleGroup.CHEST && i == 0) exercise(id, "Barbell Bench Press", muscle)
                else exercise(id, "Ex$id", muscle)
            }
        }

    // ──────────────────────────────────────────────────────────────────────
    // generateWorkout
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun generateWorkout_producesCorrectWeightForKnownCoefficient() {
        val baseline = 100f
        val sessionReps = 5
        val ex = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val strengths = strengthsFor(MuscleGroup.CHEST to baseline)
        val p = planner(listOf(ex), strengths)

        val plan = p.generateWorkout(sessionReps)
        val pe = plan.exercises.single()

        val expected = WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(baseline * 1.0f, sessionReps), WeightUnit.KG)
        assertEquals(expected, pe.sessionWeight, 0.01f)
        assertEquals(sessionReps, pe.sessionReps)
    }

    @Test
    fun generateWorkout_timedExercisesGetZeroWeightAndSixtyReps() {
        val ex = exercise(1L, isTimed = true)
        val p = planner(listOf(ex))

        val plan = p.generateWorkout(sessionReps = 8)
        val pe = plan.exercises.single()

        assertEquals(0f, pe.sessionWeight, 0f)
        assertEquals(60, pe.sessionReps)
        assertTrue(pe.warmupSets.isEmpty())
    }

    @Test
    fun generateWorkout_unknownCoefficientProducesZeroWeight() {
        val ex = exercise(1L, name = "Some Unknown Exercise", muscle = MuscleGroup.CHEST)
        val strengths = strengthsFor(MuscleGroup.CHEST to 100f)
        val p = planner(listOf(ex), strengths)

        val plan = p.generateWorkout(5)
        assertEquals(0f, plan.exercises.single().sessionWeight, 0f)
    }

    // ──────────────────────────────────────────────────────────────────────
    // pickReplacement / pickAdditional — sessionRejectedIds exclusion
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun pickReplacement_neverReturnsSessionRejectedId() {
        val exercises = (1L..10L).map { exercise(it, muscle = MuscleGroup.CHEST) }
        val p = planner(exercises)

        // Plan has one exercise; all others are in sessionRejectedIds except exercise 10
        val inPlan = PlannedExercise(exercises[0])
        val rejected = exercises.drop(1).take(8).map { it.id }.toSet()
        val plan = WorkoutPlan(
            exercises = listOf(inPlan),
            locationId = null,
            sessionRejectedIds = rejected,
        )

        val replacement = p.pickReplacement(plan, removedIndex = 0)
        assertTrue("must not return a rejected exercise",
            replacement == null || replacement.exercise.id !in rejected)
        assertEquals("must not return the removed exercise", exercises[0].id, inPlan.exercise.id.also {
            if (replacement != null) assertTrue(replacement.exercise.id != it)
        })
    }

    @Test
    fun pickAdditional_neverReturnsExerciseAlreadyInPlan() {
        val exercises = (1L..5L).map { exercise(it, muscle = MuscleGroup.CHEST) }
        val p = planner(exercises)

        val planned = exercises.take(3).map { PlannedExercise(it) }
        val plan = WorkoutPlan(exercises = planned, locationId = null)

        val added = p.pickAdditional(plan)
        val inPlanIds = planned.map { it.exercise.id }.toSet()
        if (added != null) {
            assertTrue("pickAdditional must not return an exercise already in the plan",
                added.exercise.id !in inPlanIds)
        }
    }

    @Test
    fun pickAdditional_skipTodayExcludedViaSessionRejectedIds() {
        // exercises[0] is SKIP_TODAY — not in plan, not disliked, but in sessionRejectedIds
        val exercises = (1L..5L).map { exercise(it, muscle = MuscleGroup.entries[it.toInt() % MuscleGroup.entries.size]) }
        val p = planner(exercises)

        val skipToday = exercises[0]
        val plan = WorkoutPlan(
            exercises = emptyList(),
            locationId = null,
            sessionRejectedIds = setOf(skipToday.id),
        )

        repeat(20) {
            val added = p.pickAdditional(plan)
            if (added != null) {
                assertTrue("SKIP_TODAY exercise must not be re-selected",
                    added.exercise.id != skipToday.id)
            }
        }
    }

    @Test
    fun pickReplacement_returnsNullWhenNoValidCandidates() {
        val exercises = (1L..2L).map { exercise(it, muscle = MuscleGroup.CHEST) }
        val p = planner(exercises)

        val inPlan = PlannedExercise(exercises[0])
        // The ViewModel always adds the removed exercise to sessionRejectedIds before calling pickReplacement,
        // so both exercises are rejected here → no candidates remain.
        val plan = WorkoutPlan(
            exercises = listOf(inPlan),
            locationId = null,
            sessionRejectedIds = setOf(exercises[0].id, exercises[1].id),
        )

        assertNull(p.pickReplacement(plan, removedIndex = 0))
    }

    // ──────────────────────────────────────────────────────────────────────
    // deriveBaselineFromSessionWeight round-trip
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun deriveBaselineFromSessionWeight_roundTrip() {
        val ex = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val baseline = 100f
        val sessionReps = 5
        val pe = PlannedExercise(
            exercise = ex,
            sessionWeight = WeightFormatter.round(
                DefaultProgressionEngine.fromOneRepMax(baseline, sessionReps), WeightUnit.KG
            ),
            sessionReps = sessionReps,
        )
        val p = planner()

        val derivedBaseline = p.deriveBaselineFromSessionWeight(pe.sessionWeight, pe)
        val recomputed = p.recomputeExercise(pe, derivedBaseline)

        // Round-trip should reproduce the original session weight within one rounding unit
        assertEquals("session weight should survive derive→recompute round-trip",
            pe.sessionWeight, recomputed.sessionWeight, 1.0f)
    }

    @Test
    fun recomputeExercise_appliesNewBaselineWithCoefficient() {
        val ex = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val newBaseline = 120f  // coeff for Barbell Bench Press = 1.0
        val sessionReps = 5
        val p = planner(listOf(ex), strengthsFor(MuscleGroup.CHEST to 100f))

        val pe = PlannedExercise(exercise = ex, sessionReps = sessionReps,
            sessionWeight = WeightFormatter.round(
                DefaultProgressionEngine.fromOneRepMax(100f, sessionReps), WeightUnit.KG))

        val recomputed = p.recomputeExercise(pe, newBaseline)

        val expected = WeightFormatter.round(
            DefaultProgressionEngine.fromOneRepMax(newBaseline * 1.0f, sessionReps), WeightUnit.KG)
        assertEquals("recomputeExercise with coeff=1.0 should apply new baseline directly",
            expected, recomputed.sessionWeight, 0.01f)
        assertTrue("new weight should differ from original when baseline changed",
            recomputed.sessionWeight != pe.sessionWeight)
    }

    // ──────────────────────────────────────────────────────────────────────
    // computeWarmupSets
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun computeWarmupSets_belowThreshold_returnsEmpty() {
        val p = planner()
        assertTrue(p.computeWarmupSets(39.9f).isEmpty())
    }

    @Test
    fun computeWarmupSets_midRange_returnsTwoSets() {
        val p = planner()
        val warmups = p.computeWarmupSets(50f)
        assertEquals(2, warmups.size)
    }

    @Test
    fun computeWarmupSets_heavy_returnsThreeSets() {
        val p = planner()
        val warmups = p.computeWarmupSets(60f)
        assertEquals(3, warmups.size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // strengthOverrides via constructor
    // ──────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────
    // Recently-failed muscle group exclusion
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun generateWorkout_excludesWeightedExerciseForMuscleGroupWithTwoRir01WithinTwoDays() {
        val now = 1_000_000_000L
        val oneDayAgo = now - 24 * 60 * 60 * 1000L
        val chestEx = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val shoulderEx = exercise(2L, "Ex2", MuscleGroup.SHOULDERS)
        val p = planner(
            exercises = listOf(chestEx, shoulderEx),
            recentHistory = mapOf(chestEx.id to listOf(
                nearFailureSet(chestEx.id, oneDayAgo),
                nearFailureSet(chestEx.id, oneDayAgo - 60_000L),
            )),
            nowMs = now,
        )
        repeat(10) {
            val plan = p.generateWorkout(5)
            assertTrue("chest must be excluded when 2+ RIR_0_1 within 2 days",
                plan.exercises.none { it.exercise.primaryMuscle == MuscleGroup.CHEST })
        }
    }

    @Test
    fun generateWorkout_doesNotExcludeForSingleRir01WithinTwoDays() {
        val now = 1_000_000_000L
        val oneDayAgo = now - 24 * 60 * 60 * 1000L
        val chestEx = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val p = planner(
            exercises = listOf(chestEx),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
            recentHistory = mapOf(chestEx.id to listOf(nearFailureSet(chestEx.id, oneDayAgo))),
            nowMs = now,
        )
        val plan = p.generateWorkout(5)
        assertTrue("chest may appear with only one RIR_0_1 set",
            plan.exercises.any { it.exercise.primaryMuscle == MuscleGroup.CHEST })
    }

    @Test
    fun generateWorkout_doesNotExcludeMuscleGroupWhoseFailureIsOlderThanTwoDays() {
        val now = 1_000_000_000L
        val threeDaysAgo = now - 3L * 24 * 60 * 60 * 1000L
        val chestEx = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val p = planner(
            exercises = listOf(chestEx),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
            recentHistory = mapOf(chestEx.id to listOf(
                nearFailureSet(chestEx.id, threeDaysAgo),
                nearFailureSet(chestEx.id, threeDaysAgo - 60_000L),
            )),
            nowMs = now,
        )
        val plan = p.generateWorkout(5)
        assertTrue("chest may appear when failures were more than 2 days ago",
            plan.exercises.any { it.exercise.primaryMuscle == MuscleGroup.CHEST })
    }

    @Test
    fun generateWorkout_excludesMuscleGroupWithTooHardFeedbackWithinTwoDays() {
        val now = 1_000_000_000L
        val oneDayAgo = now - 24 * 60 * 60 * 1000L
        val chestEx = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val shoulderEx = exercise(2L, "Ex2", MuscleGroup.SHOULDERS)
        val p = planner(
            exercises = listOf(chestEx, shoulderEx),
            recentHistory = mapOf(chestEx.id to listOf(nearFailureSet(chestEx.id, oneDayAgo, SetFeedback.TOO_HARD))),
            nowMs = now,
        )
        repeat(10) {
            val plan = p.generateWorkout(5)
            assertTrue("chest must be excluded when TOO_HARD within 2 days",
                plan.exercises.none { it.exercise.primaryMuscle == MuscleGroup.CHEST })
        }
    }

    @Test
    fun generateWorkout_doesNotExcludeMuscleGroupWithOnlyNonFailureFeedback() {
        val now = 1_000_000_000L
        val oneDayAgo = now - 24 * 60 * 60 * 1000L
        val chestEx = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val easySet = WorkoutSet(
            sessionId = 1L, exerciseId = chestEx.id, setNumber = 3,
            targetWeight = 80f, targetReps = 8,
            feedback = SetFeedback.RIR_2_4, completedAt = oneDayAgo,
        )
        val p = planner(
            exercises = listOf(chestEx),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
            recentHistory = mapOf(chestEx.id to listOf(easySet)),
            nowMs = now,
        )
        val plan = p.generateWorkout(5)
        assertTrue("chest may appear when recent feedback was RIR_2_4, not failure",
            plan.exercises.any { it.exercise.primaryMuscle == MuscleGroup.CHEST })
    }

    @Test
    fun generateWorkout_bodWeightExerciseNotExcludedEvenWhenMuscleGroupFailed() {
        val now = 1_000_000_000L
        val oneDayAgo = now - 24 * 60 * 60 * 1000L
        val weightedChest = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val bodyweightChest = exercise(2L, "Ex2", MuscleGroup.CHEST, equipment = Equipment.BODYWEIGHT)
        val p = planner(
            exercises = listOf(weightedChest, bodyweightChest),
            recentHistory = mapOf(weightedChest.id to listOf(
                nearFailureSet(weightedChest.id, oneDayAgo, SetFeedback.TOO_HARD),
            )),
            nowMs = now,
        )
        val plan = p.generateWorkout(5)
        assertTrue("bodyweight chest exercise must not be excluded by recent-failure rule",
            plan.exercises.any { it.exercise.id == bodyweightChest.id })
        assertTrue("weighted chest must be excluded when TOO_HARD",
            plan.exercises.none { it.exercise.id == weightedChest.id })
    }

    @Test
    fun pickAdditional_excludesMuscleGroupWorkedToFailureWithinTwoDays() {
        val now = 1_000_000_000L
        val oneDayAgo = now - 24 * 60 * 60 * 1000L
        val chestEx = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val shoulderEx = exercise(2L, "Ex2", MuscleGroup.SHOULDERS)
        val p = planner(
            exercises = listOf(chestEx, shoulderEx),
            recentHistory = mapOf(chestEx.id to listOf(
                nearFailureSet(chestEx.id, oneDayAgo, SetFeedback.TOO_HARD),
            )),
            nowMs = now,
        )
        val plan = WorkoutPlan(exercises = emptyList(), locationId = null)
        repeat(20) {
            val added = p.pickAdditional(plan)
            if (added != null) {
                assertTrue("pickAdditional must not suggest a recently-failed muscle group",
                    added.exercise.primaryMuscle != MuscleGroup.CHEST)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // strengthOverrides via constructor
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun plannerBuiltWithOverride_usesOverrideBaseline() {
        val ex = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val dbBaseline = 100f
        val overrideBaseline = 120f
        val sessionReps = 5

        val strengths = strengthsFor(MuscleGroup.CHEST to overrideBaseline)
        val p = planner(listOf(ex), strengths)

        val plan = p.generateWorkout(sessionReps)
        val pe = plan.exercises.single()

        val expected = WeightFormatter.round(
            DefaultProgressionEngine.fromOneRepMax(overrideBaseline, sessionReps), WeightUnit.KG
        )
        // Should use overrideBaseline, not dbBaseline
        assertTrue(abs(pe.sessionWeight - expected) < 1.0f)
        val dbExpected = WeightFormatter.round(
            DefaultProgressionEngine.fromOneRepMax(dbBaseline, sessionReps), WeightUnit.KG
        )
        assertTrue("override baseline should produce different weight than DB baseline",
            abs(pe.sessionWeight - dbExpected) > 0.1f || overrideBaseline == dbBaseline)
    }
}
