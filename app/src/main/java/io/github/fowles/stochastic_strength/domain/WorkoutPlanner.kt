package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WarmupSet
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import kotlin.random.Random

private const val TWO_DAYS_MS = 2L * 24 * 60 * 60 * 1000

enum class ReplacementTier { WEIGHTED_MUSCLE, MUSCLE, ANY }

class WorkoutPlanner(
    val availableExercises: List<Exercise>,
    private val prescribedE1rm: Map<Long, Float>,
    val recentHistory: Map<Long, List<WorkoutSet>>,
    val weightUnit: WeightUnit,
    val locationId: Long?,
    private val random: Random = Random.Default,
    private val nowMs: Long = System.currentTimeMillis(),
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val pacingEstimator: ExercisePacingEstimator = ExercisePacingEstimator.EMPTY,
    private val exerciseE1rmOverrides: Map<Long, Float> = emptyMap(),
) {
    // Muscle groups where a weighted exercise hit RIR 0-1 within the past two days.
    private val recentlyFailedMuscles: Set<MuscleGroup> by lazy {
        val cutoff = nowMs - TWO_DAYS_MS
        val muscleById = availableExercises
            .filter { it.equipment != Equipment.BODYWEIGHT }
            .associate { it.id to it.primaryMuscle }
        recentHistory.entries
            .filter { (exerciseId, sets) ->
                if (exerciseId !in muscleById) return@filter false
                val recent = sets.filter { it.completedAt != null && it.completedAt >= cutoff }
                recent.any { it.feedback == SetFeedback.TOO_HARD } ||
                    recent.count { it.feedback == SetFeedback.RIR_0_1 } > 1
            }
            .mapNotNull { (exerciseId, _) -> muscleById[exerciseId] }
            .toSet()
    }

    fun generateWorkout(sessionReps: Int): WorkoutPlan {
        val plannable = availableExercises.filter { muscleGroupRested(it) }
        val exercises = WorkoutGenerator.generate(WorkoutGenerator.Input(plannable, random))
            .map { withWeight(it, sessionReps) }
        return WorkoutPlan(exercises = exercises, locationId = locationId, sessionReps = sessionReps)
    }

    fun generateWorkout(repMin: Int, repMax: Int): WorkoutPlan =
        generateWorkout(sessionReps = RepRangePicker.pick(repMin, repMax, random))

    fun repriceForReps(plan: WorkoutPlan, repMin: Int, repMax: Int): WorkoutPlan {
        val sessionReps = RepRangePicker.pick(repMin, repMax, random)
        val newExercises = plan.exercises.map { withWeight(it, sessionReps) }
        return plan.copy(exercises = newExercises, sessionReps = sessionReps)
    }

    fun pickReplacement(
        plan: WorkoutPlan,
        removedIndex: Int,
        tiers: List<ReplacementTier> = listOf(ReplacementTier.ANY),
    ): PlannedExercise? {
        val removed = plan.exercises[removedIndex].exercise
        val remaining = plan.exercises.filterIndexed { i, _ -> i != removedIndex }
        val all = candidatesFor(plan, remaining).filter { it.id != removed.id }
        for (tier in tiers) {
            val filtered = when (tier) {
                ReplacementTier.WEIGHTED_MUSCLE -> all.filter {
                    it.primaryMuscle == removed.primaryMuscle && isLoaded(it) == isLoaded(removed)
                }
                ReplacementTier.MUSCLE -> all.filter { it.primaryMuscle == removed.primaryMuscle }
                ReplacementTier.ANY -> all
            }
            pickFrom(filtered, remaining, plan.sessionReps)?.let { return it }
        }
        return null
    }

    fun pickAdditional(plan: WorkoutPlan): PlannedExercise? =
        pickFrom(candidatesFor(plan, plan.exercises), plan.exercises, plan.sessionReps)

    private fun isLoaded(exercise: Exercise): Boolean =
        coefficientSource.get(exercise)?.let { it > 0f } ?: false

    private fun pickFrom(
        candidates: List<Exercise>,
        currentExercises: List<PlannedExercise>,
        sessionReps: Int,
    ): PlannedExercise? {
        if (candidates.isEmpty()) return null
        val picked = WorkoutGenerator.pickReplacement(
            input = WorkoutGenerator.Input(candidates, random),
            currentExercises = currentExercises,
        ) ?: return null
        return withWeight(picked, sessionReps)
    }

    fun e1rmFromSessionWeight(sessionWeight: Float, sessionReps: Int): Float =
        progressionEngine.toOneRepMax(sessionWeight, sessionReps)

    fun recomputeExercise(pe: PlannedExercise, newE1rmKg: Float): PlannedExercise {
        // The coefficient is only a loaded/non-zero guard here: recompute maps the new e1rm straight
        // to a session weight (no coefficient multiply), so unloadable exercises pass through unchanged.
        val coefficient = coefficientSource.get(pe.exercise) ?: return pe
        if (coefficient <= 0f) return pe
        val newWeight = WeightFormatter.round(
            progressionEngine.fromOneRepMax(newE1rmKg, pe.sessionReps),
            weightUnit,
        )
        val warmups = if (pe.exercise.isTimed) emptyList() else computeWarmupSets(newWeight, pe.exercise)
        val perRep = pacingEstimator.secondsPerRep(pe.exercise.id)
        return pe.copy(
            sessionWeight = newWeight,
            warmupSets = warmups,
            estimatedSeconds = DurationCalculator.estimate(
                exercise = pe.exercise,
                sessionReps = pe.sessionReps,
                numSets = PlannedExercise.DEFAULT_SETS,
                warmupSets = warmups,
                secondsPerRep = perRep,
            ),
        )
    }

    fun computeWarmupSets(weightKg: Float, exercise: Exercise? = null): List<WarmupSet> {
        val barKg = WeightFormatter.roundForWarmup(20f, weightUnit)
        val topKg = WeightFormatter.roundForWarmup(weightKg * 0.9f, weightUnit)

        val floorKg: Float
        val minIntermediates: Int
        if (exercise != null && exercise.isFloorDeadlift()) {
            floorKg = deadliftFloorKg(topKg) ?: return emptyList()
            minIntermediates = 1
        } else {
            if (topKg <= barKg) return emptyList()
            floorKg = barKg
            minIntermediates = 2
        }
        if (topKg <= floorKg) return emptyList()

        fun sequence(stepKg: Float): List<Float> = generateSequence(1) { it + 1 }
            .map { i -> WeightFormatter.roundForWarmup(floorKg + i * stepKg, weightUnit) }
            .takeWhile { it < topKg }
            .toList()

        // Step = bar weight. Applied to multiples and rounded, this naturally produces
        // the plates-and-quarters sequence (95, 135, 185, 225... in lbs).
        var intermediates = sequence(barKg)

        // Light lifts: primary step overshoots — use half-step (one smaller plate per side).
        // LBS: one 10 lb plate per side = 20 lb increment (not barKg/2 ≈ 22.5 lb, which rounds
        // back to the same lb value as topKg at the critical 105 lb boundary).
        val halfStepKg = if (weightUnit == WeightUnit.LBS) WeightUnit.LBS.toKg(20f) else barKg / 2f
        if (intermediates.size < 2) intermediates = sequence(halfStepKg)

        // Still too few stops → work weight is too close to the floor for a meaningful warmup.
        if (intermediates.size < minIntermediates) return emptyList()

        // Heavy lifts: thin by keeping odd-indexed elements (0-based), which preserves
        // ≤ 90 lb max jumps while halving the stop count.
        val thinned = if (intermediates.size > 5) {
            intermediates.filterIndexed { i, _ -> i % 2 == 1 }
        } else {
            intermediates
        }

        val allStops = listOf(floorKg) + thinned + listOf(topKg)
        val repScheme = listOf(5, 5, 3, 2)
        return allStops.mapIndexed { i, w -> WarmupSet(w, repScheme.getOrElse(i) { 1 }) }
    }

    // Deadlifts cannot start from just the bar — the bar sits too low without plates.
    // Returns the largest viable floor (in kg) strictly below topKg, or null if none fits.
    private fun deadliftFloorKg(topKg: Float): Float? {
        val rawCandidates = if (weightUnit == WeightUnit.LBS) {
            listOf(WeightUnit.LBS.toKg(135f), WeightUnit.LBS.toKg(95f), WeightUnit.LBS.toKg(65f))
        } else {
            listOf(60f, 40f, 30f)
        }
        return rawCandidates
            .map { WeightFormatter.roundForWarmup(it, weightUnit) }
            .firstOrNull { it < topKg }
    }

    private fun Exercise.isFloorDeadlift(): Boolean =
        equipment == Equipment.BARBELL && name.contains("deadlift", ignoreCase = true)

    private fun muscleGroupRested(exercise: Exercise): Boolean =
        exercise.equipment == Equipment.BODYWEIGHT || exercise.primaryMuscle !in recentlyFailedMuscles

    private fun candidatesFor(plan: WorkoutPlan, currentExercises: List<PlannedExercise>): List<Exercise> {
        val inPlan = currentExercises.map { it.exercise.id }.toSet()
        val excluded = inPlan + plan.sessionRejectedIds
        return availableExercises.filter { it.id !in excluded && muscleGroupRested(it) }
    }

    private fun withWeight(pe: PlannedExercise, sessionReps: Int): PlannedExercise {
        val perRep = pacingEstimator.secondsPerRep(pe.exercise.id)
        if (pe.exercise.isTimed) {
            val timedReps = 60
            return pe.copy(
                sessionWeight = 0f,
                sessionReps = timedReps,
                warmupSets = emptyList(),
                estimatedSeconds = DurationCalculator.estimate(
                    exercise = pe.exercise,
                    sessionReps = timedReps,
                    numSets = PlannedExercise.DEFAULT_SETS,
                    warmupSets = emptyList(),
                    secondsPerRep = perRep,
                ),
            )
        }
        val weight = weightForExercise(pe.exercise, sessionReps)
        val warmups = computeWarmupSets(weight, pe.exercise)
        return pe.copy(
            sessionWeight = weight,
            sessionReps = sessionReps,
            warmupSets = warmups,
            estimatedSeconds = DurationCalculator.estimate(
                exercise = pe.exercise,
                sessionReps = sessionReps,
                numSets = PlannedExercise.DEFAULT_SETS,
                warmupSets = warmups,
                secondsPerRep = perRep,
            ),
        )
    }

    private fun weightForExercise(exercise: Exercise, sessionReps: Int): Float {
        val coeff = coefficientSource.get(exercise) ?: return 0f
        if (coeff <= 0f) return 0f // unloadable (bodyweight/banded): no prescription
        val e1rm = exerciseE1rmOverrides[exercise.id] ?: prescribedE1rm[exercise.id] ?: return 0f
        if (e1rm <= 0f) return 0f
        return WeightFormatter.round(progressionEngine.fromOneRepMax(e1rm, sessionReps), weightUnit)
    }

    internal fun weightForExerciseTest(exercise: Exercise, sessionReps: Int) =
        weightForExercise(exercise, sessionReps)
}
