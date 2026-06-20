package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
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
    private val strengths: Map<MuscleGroup, MuscleGroupStrength>,
    val recentHistory: Map<Long, List<WorkoutSet>>,
    val weightUnit: WeightUnit,
    val locationId: Long?,
    private val random: Random = Random.Default,
    private val nowMs: Long = System.currentTimeMillis(),
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val pacingEstimator: ExercisePacingEstimator = ExercisePacingEstimator.EMPTY,
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

    fun deriveBaselineFromSessionWeight(sessionWeight: Float, pe: PlannedExercise): Float {
        val coeff = coefficientSource.get(pe.exercise) ?: return 0f
        if (coeff <= 0f) return 0f
        return progressionEngine.toOneRepMax(sessionWeight, pe.sessionReps) / coeff
    }

    fun recomputeExercise(pe: PlannedExercise, newBaselineKg: Float): PlannedExercise {
        val coeff = coefficientSource.get(pe.exercise) ?: return pe
        if (coeff <= 0f) return pe
        val newWeight = WeightFormatter.round(
            progressionEngine.fromOneRepMax(newBaselineKg * coeff, pe.sessionReps),
            weightUnit,
        )
        val warmups = if (pe.exercise.isTimed) emptyList() else computeWarmupSets(newWeight)
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

    fun computeWarmupSets(weightKg: Float): List<WarmupSet> {
        if (weightKg < 40f) return emptyList()
        fun w(pct: Float) = WeightFormatter.roundForWarmup(weightKg * pct, weightUnit)
        return if (weightKg < 60f) {
            listOf(WarmupSet(w(0.5f), 8), WarmupSet(w(0.75f), 5))
        } else {
            listOf(WarmupSet(w(0.4f), 8), WarmupSet(w(0.6f), 5), WarmupSet(w(0.8f), 3))
        }
    }

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
        val warmups = computeWarmupSets(weight)
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
        if (coeff <= 0f) return 0f
        val baseline = strengths[exercise.primaryMuscle]?.baselineWeight ?: return 0f
        return WeightFormatter.round(
            progressionEngine.fromOneRepMax(baseline * coeff, sessionReps),
            weightUnit,
        )
    }
}
