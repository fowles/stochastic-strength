package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.policy.PolicyState
import io.github.fowles.stochastic_strength.domain.policy.PooledBelief
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WorkoutPlannerOverrideTest {

    private fun ex(id: Long, name: String) = Exercise(
        id = id, name = name, primaryMuscle = MuscleGroup.CHEST, secondaryMuscles = emptyList(),
        equipment = Equipment.BARBELL, isDisliked = false, isUnilateral = false, isTimed = false,
    )

    // Live projector output for the two lifts (Barbell Bench 100, Incline 68 = 0.85*80-ish).
    private val prescribed = mapOf(1L to 100f, 2L to 68f)

    private fun planner(overrides: Map<Long, Float>) = WorkoutPlanner(
        availableExercises = listOf(ex(1, "Barbell Bench Press"), ex(2, "Incline Barbell Bench Press")),
        policy = PrescriptionPolicy(
            pooled = prescribed.mapValues { (_, e1rm) -> PooledBelief(e1rm, 0f) },
            state = PolicyState.EMPTY,
            config = EstimatorConfig(uncertaintyZ = 0f, overloadDelta = 0f, fatiguePerSet = 0f),
            progressionEngine = DefaultProgressionEngine,
            weightUnit = WeightUnit.KG,
            nowMs = 0L,
        ),
        weightUnit = WeightUnit.KG,
        locationId = null,
        random = Random(1),
        exerciseE1rmOverrides = overrides,
    )

    @Test
    fun exerciseOverrideAffectsOnlyThatExercise() {
        val base = planner(emptyMap())
        // Override exercise 1's e1rm to 120; exercise 2 must be unchanged vs no-override planner.
        val overridden = planner(mapOf(1L to 120f))
        val w1NoOverride = base.weightForExerciseTest(ex(1, "Barbell Bench Press"), 5)
        val w1Override = overridden.weightForExerciseTest(ex(1, "Barbell Bench Press"), 5)
        val w2NoOverride = base.weightForExerciseTest(ex(2, "Incline Barbell Bench Press"), 5)
        val w2Override = overridden.weightForExerciseTest(ex(2, "Incline Barbell Bench Press"), 5)
        assertTrue("override raises ex1", w1Override > w1NoOverride)
        assertEquals("ex2 untouched by ex1 override", w2NoOverride, w2Override, 1e-3f)
    }
}
