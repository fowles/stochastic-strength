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

class WorkoutPlanner(
    val availableExercises: List<Exercise>,
    private val strengths: Map<MuscleGroup, MuscleGroupStrength>,
    val recentHistory: Map<Long, List<WorkoutSet>>,
    val weightUnit: WeightUnit,
    val locationId: Long?,
    private val random: Random = Random.Default,
    private val nowMs: Long = System.currentTimeMillis(),
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
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

    fun generateWorkout(sessionReps: Int = ProgressionEngine.REP_OPTIONS.random(random)): WorkoutPlan {
        val plannable = availableExercises.filter { muscleGroupRested(it) }
        val exercises = WorkoutGenerator.generate(WorkoutGenerator.Input(plannable, random))
            .map { withWeight(it, sessionReps) }
        return WorkoutPlan(exercises = exercises, locationId = locationId, sessionReps = sessionReps)
    }

    fun pickReplacement(plan: WorkoutPlan, removedIndex: Int): PlannedExercise? {
        val remaining = plan.exercises.filterIndexed { i, _ -> i != removedIndex }
        return pickFrom(candidatesFor(plan, remaining), remaining, plan.sessionReps)
    }

    fun pickAdditional(plan: WorkoutPlan): PlannedExercise? =
        pickFrom(candidatesFor(plan, plan.exercises), plan.exercises, plan.sessionReps)

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
        return ProgressionEngine.toOneRepMax(sessionWeight, pe.sessionReps) / coeff
    }

    fun recomputeExercise(pe: PlannedExercise, newBaselineKg: Float): PlannedExercise {
        val coeff = coefficientSource.get(pe.exercise) ?: return pe
        if (coeff <= 0f) return pe
        val newWeight = WeightFormatter.round(
            ProgressionEngine.fromOneRepMax(newBaselineKg * coeff, pe.sessionReps),
            weightUnit,
        )
        return pe.copy(
            sessionWeight = newWeight,
            warmupSets = if (pe.exercise.isTimed) emptyList() else computeWarmupSets(newWeight),
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
        if (pe.exercise.isTimed) return pe.copy(sessionWeight = 0f, sessionReps = 60, warmupSets = emptyList())
        val weight = weightForExercise(pe.exercise, sessionReps)
        return pe.copy(
            sessionWeight = weight,
            sessionReps = sessionReps,
            warmupSets = computeWarmupSets(weight),
        )
    }

    private fun weightForExercise(exercise: Exercise, sessionReps: Int): Float {
        val coeff = coefficientSource.get(exercise) ?: return 0f
        if (coeff <= 0f) return 0f
        val baseline = strengths[exercise.primaryMuscle]?.baselineWeight ?: return 0f
        return WeightFormatter.round(
            ProgressionEngine.fromOneRepMax(baseline * coeff, sessionReps),
            weightUnit,
        )
    }
}
