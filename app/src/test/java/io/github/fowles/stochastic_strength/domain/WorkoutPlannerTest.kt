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
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

// Helper: convert the old per-muscle strengths into per-exercise e1rm using seed coefficients.
private fun strengthsToPrescribedE1rm(
    exercises: List<Exercise>,
    strengths: Map<MuscleGroup, MuscleGroupStrength>,
    coefficientSource: CoefficientSource,
): Map<Long, Float> =
    exercises.mapNotNull { ex ->
        val baseline = strengths[ex.primaryMuscle]?.baselineWeight ?: return@mapNotNull null
        val coef = coefficientSource.get(ex) ?: return@mapNotNull null
        if (coef <= 0f) return@mapNotNull null
        ex.id to baseline * coef
    }.toMap()

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
        pacingEstimator: ExercisePacingEstimator = ExercisePacingEstimator.EMPTY,
        coefficientSource: CoefficientSource = ExerciseCoefficients,
    ) = WorkoutPlanner(
        availableExercises = exercises,
        prescribedE1rm = strengthsToPrescribedE1rm(exercises, strengths, coefficientSource),
        recentHistory = recentHistory,
        weightUnit = WeightUnit.KG,
        locationId = null,
        random = random,
        nowMs = nowMs,
        pacingEstimator = pacingEstimator,
        coefficientSource = coefficientSource,
    )

    private fun lbsPlanner() = WorkoutPlanner(
        availableExercises = emptyList(),
        prescribedE1rm = emptyMap(),
        recentHistory = emptyMap(),
        weightUnit = WeightUnit.LBS,
        locationId = null,
    )

    private fun lbsToKg(lb: Float) = WeightUnit.LBS.toKg(lb)
    private fun io.github.fowles.stochastic_strength.domain.model.WarmupSet.roundedLbs() =
        WeightUnit.LBS.fromKg(weight).roundToInt()

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
    // repriceForReps
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun repriceForReps_preservesExerciseListInOrder() {
        val ex1 = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val ex2 = exercise(2L, "Ex2", MuscleGroup.BACK)
        val strengths = strengthsFor(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 80f)
        val p = planner(listOf(ex1, ex2), strengths)

        val original = p.generateWorkout(sessionReps = 5)
        val repriced = p.repriceForReps(original, repMin = 8, repMax = 8)

        assertEquals(
            original.exercises.map { it.exercise.id },
            repriced.exercises.map { it.exercise.id },
        )
        assertEquals(2, repriced.exercises.size)
    }

    @Test
    fun repriceForReps_singletonRange_setsSessionRepsAndRecomputesWeight() {
        val baseline = 100f
        val ex = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val strengths = strengthsFor(MuscleGroup.CHEST to baseline)
        val p = planner(listOf(ex), strengths)

        val original = p.generateWorkout(sessionReps = 5)
        val repriced = p.repriceForReps(original, repMin = 10, repMax = 10)

        assertEquals(10, repriced.sessionReps)
        val pe = repriced.exercises.single()
        assertEquals(10, pe.sessionReps)
        val expected = WeightFormatter.round(
            DefaultProgressionEngine.fromOneRepMax(baseline * 1.0f, 10),
            WeightUnit.KG,
        )
        assertEquals(expected, pe.sessionWeight, 0.01f)
    }

    @Test
    fun repriceForReps_timedExerciseStaysAtSixtyRepsZeroWeight() {
        val timed = exercise(1L, isTimed = true)
        val p = planner(listOf(timed))

        val original = p.generateWorkout(sessionReps = 5)
        val repriced = p.repriceForReps(original, repMin = 8, repMax = 8)

        val pe = repriced.exercises.single()
        assertEquals(60, pe.sessionReps)
        assertEquals(0f, pe.sessionWeight, 0f)
        assertTrue(pe.warmupSets.isEmpty())
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

    @Test
    fun pickReplacement_tiers_preferSameMuscleAndLoadedness() {
        val removed = exercise(1, "removed", MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        val chestLoaded = exercise(2, "chestLoaded", MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        val chestUnloaded = exercise(3, "chestUnloaded", MuscleGroup.CHEST, equipment = Equipment.BODYWEIGHT)
        val backLoaded = exercise(4, "backLoaded", MuscleGroup.BACK, equipment = Equipment.BARBELL)
        val coeffs = object : CoefficientSource {
            override fun get(exercise: Exercise): Float? = mapOf(
                1L to 1.0f, 2L to 0.8f, 3L to null, 4L to 1.0f,
            )[exercise.id]
        }
        val p = planner(
            exercises = listOf(removed, chestLoaded, chestUnloaded, backLoaded),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 100f),
            coefficientSource = coeffs,
        )
        val plan = WorkoutPlan(exercises = listOf(PlannedExercise(exercise = removed)), locationId = null, sessionReps = 8)

        val tiers = listOf(ReplacementTier.WEIGHTED_MUSCLE, ReplacementTier.MUSCLE, ReplacementTier.ANY)
        // Tier 1 (same muscle + loaded) is non-empty -> must pick chestLoaded.
        assertEquals(chestLoaded.id, p.pickReplacement(plan, 0, tiers)!!.exercise.id)
    }

    @Test
    fun pickReplacement_tiers_fallThroughToMuscleThenAny() {
        val removed = exercise(1, "removed", MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        val chestUnloaded = exercise(3, "chestUnloaded", MuscleGroup.CHEST, equipment = Equipment.BODYWEIGHT)
        val backLoaded = exercise(4, "backLoaded", MuscleGroup.BACK, equipment = Equipment.BARBELL)
        val coeffs = object : CoefficientSource {
            override fun get(exercise: Exercise): Float? = mapOf(1L to 1.0f, 3L to null, 4L to 1.0f)[exercise.id]
        }
        val p = planner(
            exercises = listOf(removed, chestUnloaded, backLoaded),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 100f),
            coefficientSource = coeffs,
        )
        val plan = WorkoutPlan(exercises = listOf(PlannedExercise(exercise = removed)), locationId = null, sessionReps = 8)
        val tiers = listOf(ReplacementTier.WEIGHTED_MUSCLE, ReplacementTier.MUSCLE, ReplacementTier.ANY)
        // Tier 1 empty (no same-muscle loaded), Tier 2 (same muscle) -> chestUnloaded.
        assertEquals(chestUnloaded.id, p.pickReplacement(plan, 0, tiers)!!.exercise.id)
    }

    @Test
    fun pickReplacement_defaultAny_unchangedBehavior() {
        val removed = exercise(1, "removed", MuscleGroup.CHEST)
        val other = exercise(2, "other", MuscleGroup.BACK)
        val p = planner(
            exercises = listOf(removed, other),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 100f),
        )
        val plan = WorkoutPlan(exercises = listOf(PlannedExercise(exercise = removed)), locationId = null, sessionReps = 8)
        // Default tiers = [ANY]; only `other` is a candidate.
        assertEquals(other.id, p.pickReplacement(plan, 0)!!.exercise.id)
    }

    // ──────────────────────────────────────────────────────────────────────
    // e1rmFromSessionWeight round-trip
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun e1rmFromSessionWeight_roundTrip() {
        val ex = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val e1rm = 100f
        val sessionReps = 5
        val pe = PlannedExercise(
            exercise = ex,
            sessionWeight = WeightFormatter.round(
                DefaultProgressionEngine.fromOneRepMax(e1rm, sessionReps), WeightUnit.KG
            ),
            sessionReps = sessionReps,
        )
        val p = planner()

        val derivedE1rm = p.e1rmFromSessionWeight(pe.sessionWeight, pe.sessionReps)
        val recomputed = p.recomputeExercise(pe, derivedE1rm)

        // Round-trip should reproduce the original session weight within one rounding unit
        assertEquals("session weight should survive e1rmFromSessionWeight→recompute round-trip",
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
    fun `computeWarmupSets KG bar-only warmup when feeler would exceed working weight`() {
        // 39.9 kg: feeler rounds to 40 kg which exceeds the working weight, so only the
        // bar (20 kg) is prescribed — no feeler added. Bar sits just above 50% of the
        // working weight, so it gets 3 reps.
        val p = planner()
        val warmups = p.computeWarmupSets(39.9f)
        assertEquals(listOf(20), warmups.map { it.weight.roundToInt() })
        assertEquals(listOf(3), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 100kg KG mode produces bar-based sequence with feeler`() {
        val p = planner()
        val warmups = p.computeWarmupSets(100f)
        assertEquals(listOf(20, 40, 60, 80, 90), warmups.map { it.weight.roundToInt() })
        assertEquals(listOf(5, 5, 3, 2, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 50lb bar is within 10 percent of working weight so no warmup`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(50f))
        assertTrue(warmups.isEmpty())
    }

    @Test
    fun `computeWarmupSets 55lb bar-only warmup is a double not a fatiguing set of five`() {
        // The bar is 82% of the working weight; proximity-based reps prescribe 2.
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(55f))
        assertEquals(listOf(45), warmups.map { it.roundedLbs() })
        assertEquals(listOf(2), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 100lb drops the 95lb stop and re-adds it as a feeler single`() {
        // 95 lb is >= 90% of 100 lb, so it can't be a multi-rep stop; the feeler
        // (round(0.9×100) = 95 on the ends-in-5 warmup grid) takes its place as a single.
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(100f))
        assertEquals(listOf(45, 65, 95), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 3, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 105lb P&Q anchor demoted to feeler single`() {
        // 95 lb is 90.5% of 105 lb — too close for a multi-rep stop — so it survives
        // only as the 90% feeler single.
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(105f))
        assertEquals(listOf(45, 65, 95), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 3, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 200lb LBS follows plates-and-quarters from bar`() {
        // 185 lb is 92.5% of 200 lb, dropped as a stop; the feeler rounds back to 185
        // but as a single instead of a double.
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(200f))
        assertEquals(listOf(45, 95, 135, 185), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 5, 3, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 225lb follows plates-and-quarters sequence with feeler`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(225f))
        assertEquals(listOf(45, 95, 135, 185, 205), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 5, 3, 2, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 405lb thins heavy sequence`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(405f))
        assertEquals(listOf(45, 135, 225, 315, 365), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 5, 3, 2, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 605lb scales to many stops`() {
        // 585 lb is 96.7% of 605 lb — dropped; the feeler single lands at 545.
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(605f))
        assertEquals(listOf(45, 135, 225, 315, 405, 495, 545), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 5, 5, 3, 3, 2, 1), warmups.map { it.reps })
    }

    // ──────────────────────────────────────────────────────────────────────
    // computeWarmupSets — floor deadlifts
    // ──────────────────────────────────────────────────────────────────────

    private fun deadliftExercise(name: String = "Deadlift") =
        exercise(99L, name = name, muscle = MuscleGroup.HAMSTRINGS)

    @Test
    fun `computeWarmupSets deadlift 225lb LBS follows plates-and-quarters from 95 with feeler`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(225f), deadliftExercise())
        assertEquals(listOf(95, 135, 185, 205), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 3, 2, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets deadlift 100lb LBS bar-fill plus feeler single`() {
        // One P&Q anchor at 95 lb, dropped as a multi-rep stop (95% of working weight);
        // the feeler single rounds back to 95. Bar-fill 65 lb remains the only real stop.
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(100f), deadliftExercise())
        assertEquals(listOf(65, 95), warmups.map { it.roundedLbs() })
        assertEquals(listOf(3, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets deadlift 135lb LBS bar-fill anchor and feeler`() {
        // One P&Q anchor at 95 lb. Bar-fill (65 lb) prepended. 29.6% gap to 135 lb triggers
        // feeler at round(0.9×135) = 125 lb.
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(135f), deadliftExercise())
        assertEquals(listOf(65, 95, 125), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 2, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets deadlift 100kg KG mode follows plates-and-quarters from 40kg with feeler`() {
        // 90 kg is exactly 90% of 100 kg — dropped as a stop, re-added as the feeler single.
        val warmups = planner().computeWarmupSets(100f, deadliftExercise())
        assertEquals(listOf(40, 60, 80, 90), warmups.map { it.weight.roundToInt() })
        assertEquals(listOf(5, 3, 2, 1), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets deadlift 215lb LBS follows plates-and-quarters from 95`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(215f), deadliftExercise())
        assertEquals(listOf(95, 135, 185), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 3, 2), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets Romanian Deadlift also uses plates-and-quarters from 95`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(225f), deadliftExercise("Romanian Deadlift"))
        assertEquals(95, warmups.first().roundedLbs())
    }

    @Test
    fun `computeWarmupSets Sumo Deadlift also uses plates-and-quarters from 95`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(225f), deadliftExercise("Sumo Deadlift"))
        assertEquals(95, warmups.first().roundedLbs())
    }

    @Test
    fun `computeWarmupSets Stiff-Leg Deadlift also uses plates-and-quarters from 95`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(225f), deadliftExercise("Stiff-Leg Deadlift"))
        assertEquals(95, warmups.first().roundedLbs())
    }

    @Test
    fun `computeWarmupSets non-deadlift barbell still starts from bar with exercise param`() {
        val bench = exercise(1L, name = "Barbell Bench Press", muscle = MuscleGroup.CHEST)
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(225f), bench)
        assertEquals(45, warmups.first().roundedLbs())
    }

    @Test
    fun `computeWarmupSets 120lb LBS anchors on 95lb quarter-plate stop with feeler`() {
        // 120 lb yields exactly one P&Q intermediate (95 lb). The sequence anchors on it to avoid
        // 85 lb (two 10-lb plates per side), then adds a 105 lb feeler single (90% of working weight).
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(120f))
        assertEquals(listOf(45, 65, 95, 105), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 3, 2, 1), warmups.map { it.reps })
    }

    // ──────────────────────────────────────────────────────────────────────
    // computeWarmupSets — non-barbell equipment (percentage ramp)
    // ──────────────────────────────────────────────────────────────────────

    private fun dumbbell(id: Long = 50L) =
        exercise(id, name = "Dumbbell Row", muscle = MuscleGroup.BACK, equipment = Equipment.DUMBBELL)

    @Test
    fun `computeWarmupSets 50lb dumbbell is a single light stop, not the 45lb bar`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(50f), dumbbell())
        assertEquals(listOf(30), warmups.map { it.roundedLbs() })
        assertEquals(listOf(3), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 100lb dumbbell steps down by 20 with proximity reps`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(100f), dumbbell())
        assertEquals(listOf(40, 60, 80), warmups.map { it.roundedLbs() })
        assertEquals(listOf(5, 3, 2), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 40lb dumbbell yields one stop at the floor`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(40f), dumbbell())
        assertEquals(listOf(20), warmups.map { it.roundedLbs() })
        assertEquals(listOf(3), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets 30lb dumbbell is too light for any warmup`() {
        val warmups = lbsPlanner().computeWarmupSets(lbsToKg(30f), dumbbell())
        assertTrue(warmups.isEmpty())
    }

    @Test
    fun `computeWarmupSets machine uses percentage ramp in KG with 10kg min jump`() {
        val machine = exercise(60L, name = "Pec Deck", muscle = MuscleGroup.CHEST, equipment = Equipment.MACHINE)
        val warmups = planner().computeWarmupSets(40f, machine)
        assertEquals(listOf(20, 30), warmups.map { it.weight.roundToInt() })
        assertEquals(listOf(3, 2), warmups.map { it.reps })
    }

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
    // Baseline strengths drive session weight
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

    @Test
    fun `generated plan stamps estimated seconds using learned secondsPerRep`() {
        val ex = exercise(id = 1L, name = "Barbell Bench Press", muscle = MuscleGroup.CHEST)
        val estimator = ExercisePacingEstimator(mapOf(1L to 5.0f))
        val p = planner(
            exercises = listOf(ex),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
            pacingEstimator = estimator,
        )

        val plan = p.generateWorkout(sessionReps = 8)
        val planned = plan.exercises.single()

        // Expected via DurationCalculator: barbell, 3 sets, 8 reps, perRep = 5.0, warmups
        // depend on weight from baseline 100kg + coeff 1.0 + reps 8.
        val expected = DurationCalculator.estimate(
            exercise = ex,
            sessionReps = 8,
            numSets = PlannedExercise.DEFAULT_SETS,
            warmupSets = planned.warmupSets,
            secondsPerRep = 5.0f,
        )
        assertEquals(expected, planned.estimatedSeconds)
    }

    @Test
    fun `generated plan uses default secondsPerRep when estimator has no value`() {
        val ex = exercise(id = 1L, name = "Barbell Bench Press", muscle = MuscleGroup.CHEST)
        val p = planner(
            exercises = listOf(ex),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
        )

        val plan = p.generateWorkout(sessionReps = 8)
        val planned = plan.exercises.single()

        val expected = DurationCalculator.estimate(
            exercise = ex,
            sessionReps = 8,
            numSets = PlannedExercise.DEFAULT_SETS,
            warmupSets = planned.warmupSets,
            secondsPerRep = null,
        )
        assertEquals(expected, planned.estimatedSeconds)
    }

    @Test
    fun `repriceForReps recomputes estimated seconds at the new rep target`() {
        val ex = exercise(id = 1L, name = "Barbell Bench Press", muscle = MuscleGroup.CHEST)
        val p = planner(
            exercises = listOf(ex),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
        )

        val planA = p.generateWorkout(sessionReps = 5)
        val planB = p.repriceForReps(planA, repMin = 15, repMax = 15)

        val a = planA.exercises.single()
        val b = planB.exercises.single()
        assertEquals(15, b.sessionReps)

        val expectedB = DurationCalculator.estimate(
            exercise = ex,
            sessionReps = 15,
            numSets = PlannedExercise.DEFAULT_SETS,
            warmupSets = b.warmupSets,
            secondsPerRep = null,
        )
        assertEquals(expectedB, b.estimatedSeconds)
        assertTrue(
            "estimates should differ across rep targets",
            a.estimatedSeconds != b.estimatedSeconds,
        )
    }

    @Test
    fun machinePrescriptionIsCappedByDemonstratedCapacity() {
        val now = System.currentTimeMillis()
        val ex = exercise(1L, muscle = MuscleGroup.QUADS, equipment = Equipment.MACHINE)
        // Most recent session on this exercise: failed 35 kg × 10 at 2 reps.
        val failedSet = WorkoutSet(
            sessionId = 1L, exerciseId = 1L, setNumber = 1, targetWeight = 35f, targetReps = 10,
            actualReps = 2, feedback = SetFeedback.TOO_HARD, completedAt = now - 86_400_000L,
        )
        val facts = PolicyFacts.build(listOf(failedSet), mapOf(1L to MuscleGroup.QUADS))
        val p = WorkoutPlanner(
            availableExercises = listOf(ex),
            prescribedE1rm = mapOf(1L to 60f),  // entrenched raw estimate, way above the failure
            recentHistory = emptyMap(),
            weightUnit = WeightUnit.KG,
            locationId = null,
            nowMs = now,
            // ExerciseCoefficients is name-keyed; synthetic "Ex1" needs an explicit coefficient.
            coefficientSource = UserCoefficientSource(mapOf(1L to 1f)),
            policyFacts = facts,
        )
        val w = p.weightForExerciseTest(ex, sessionReps = 10)
        assertTrue("must be strictly below the failed 35 kg, was $w", w < 35f)
        assertTrue(w > 0f)
    }

    @Test
    fun manualOverrideBypassesPolicy() {
        val now = System.currentTimeMillis()
        val ex = exercise(1L, muscle = MuscleGroup.QUADS, equipment = Equipment.MACHINE)
        val failedSet = WorkoutSet(
            sessionId = 1L, exerciseId = 1L, setNumber = 1, targetWeight = 35f, targetReps = 10,
            actualReps = 2, feedback = SetFeedback.TOO_HARD, completedAt = now - 86_400_000L,
        )
        val facts = PolicyFacts.build(listOf(failedSet), mapOf(1L to MuscleGroup.QUADS))
        val p = WorkoutPlanner(
            availableExercises = listOf(ex),
            prescribedE1rm = mapOf(1L to 60f),
            recentHistory = emptyMap(),
            weightUnit = WeightUnit.KG,
            locationId = null,
            nowMs = now,
            coefficientSource = UserCoefficientSource(mapOf(1L to 1f)),
            policyFacts = facts,
            exerciseE1rmOverrides = mapOf(1L to 60f),  // user explicitly chose this
        )
        val w = p.weightForExerciseTest(ex, sessionReps = 10)
        assertTrue("manual override is the user's decision; policy must not cap it", w > 35f)
    }
}
