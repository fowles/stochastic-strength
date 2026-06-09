package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.WeightUnit
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
    ) = WorkoutPlanner(
        availableExercises = exercises,
        strengths = strengths,
        recentHistory = emptyMap(),
        weightUnit = WeightUnit.KG,
        locationId = null,
        random = random,
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

        val expected = WeightFormatter.round(ProgressionEngine.fromOneRepMax(baseline * 1.0f, sessionReps), WeightUnit.KG)
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
                ProgressionEngine.fromOneRepMax(baseline, sessionReps), WeightUnit.KG
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
            ProgressionEngine.fromOneRepMax(overrideBaseline, sessionReps), WeightUnit.KG
        )
        // Should use overrideBaseline, not dbBaseline
        assertTrue(abs(pe.sessionWeight - expected) < 1.0f)
        val dbExpected = WeightFormatter.round(
            ProgressionEngine.fromOneRepMax(dbBaseline, sessionReps), WeightUnit.KG
        )
        assertTrue("override baseline should produce different weight than DB baseline",
            abs(pe.sessionWeight - dbExpected) > 0.1f || overrideBaseline == dbBaseline)
    }
}
