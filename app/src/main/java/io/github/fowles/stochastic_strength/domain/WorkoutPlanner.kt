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
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import kotlin.random.Random

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
    private val policyFacts: PolicyFacts = PolicyFacts.EMPTY,
) {
    // Muscle groups where a weighted exercise hit RIR 0-1 within the past two days.
    private val recentlyFailedMuscles: Set<MuscleGroup> by lazy {
        val cutoff = nowMs - PrescriptionPolicy.COOLDOWN_MS
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
        // Non-barbell lifts have no bar and no plate math — ramp as a percentage
        // of the working weight instead of the barbell plates-and-quarters model.
        if (exercise != null && exercise.equipment != Equipment.BARBELL) {
            return percentageRampWarmups(weightKg)
        }

        val barKg = WeightFormatter.roundForWarmup(20f, weightUnit)

        // Step = bar weight. Applied to multiples and rounded, this naturally produces
        // the plates-and-quarters sequence (95, 135, 185, 225… lb or 40, 60, 80… kg).
        val pqIntermediates: List<Float> = generateSequence(1) { it + 1 }
            .map { i -> WeightFormatter.roundForWarmup(barKg + i * barKg, weightUnit) }
            .takeWhile { it < weightKg }
            .toList()

        // Thin for very heavy lifts (> 5 P&Q stops) to keep warmup count manageable.
        val thinned = if (pqIntermediates.size > 5) {
            val last = pqIntermediates.last()
            pqIntermediates.dropLast(1).filterIndexed { i, _ -> i % 2 == 1 } + last
        } else {
            pqIntermediates
        }

        // LBS: one 10 lb plate per side = 20 lb increment (not barKg/2 ≈ 22.5 lb, which rounds
        // back to the same lb value as the feeler at the critical 105 lb boundary).
        val halfStepKg = if (weightUnit == WeightUnit.LBS) WeightUnit.LBS.toKg(20f) else barKg / 2f

        // Base sequence: bar + thinned P&Q intermediates, all below working weight.
        var stops = (listOf(barKg) + thinned).filter { it < weightKg }
        if (stops.isEmpty()) return emptyList()

        // For light lifts (< 2 P&Q intermediates), the natural jumps are too large;
        // intersperse +20 lb fills between adjacent stops.
        if (pqIntermediates.size < 2) {
            stops = buildList {
                for (i in stops.indices) {
                    add(stops[i])
                    if (i + 1 < stops.size) {
                        val fill = WeightFormatter.roundForWarmup(stops[i] + halfStepKg, weightUnit)
                        if (fill < stops[i + 1]) add(fill)
                    }
                }
            }
        }

        // Deadlifts cannot use the bare bar — strip it.
        if (exercise != null && exercise.isFloorDeadlift()) stops = stops.drop(1)

        // Multi-rep stops must sit strictly below 90% of the working weight — anything
        // closer is wasted fatigue. The 90% zone belongs to the feeler single alone.
        // (Epsilon guards the exact-90% boundary, e.g. bar vs a 50 lb working weight.)
        stops = stops.filter { it < weightKg * 0.9f - 0.001f }
        if (stops.isEmpty()) return emptyList()

        // Add feeler only if the last stop is more than 15% below the working weight.
        val feelerKg = WeightFormatter.roundForWarmup(weightKg * 0.9f, weightUnit)
        val feelerAdded = (weightKg - stops.last()) / weightKg >= 0.15f && feelerKg > stops.last() && feelerKg < weightKg
        if (feelerAdded) stops = stops + feelerKg

        // Reps scale with proximity to the working weight: light stops groove the
        // movement, near-work stops just prime without accumulating fatigue.
        return stops.mapIndexed { i, w ->
            val reps = when {
                feelerAdded && i == stops.lastIndex -> 1
                w < weightKg * 0.5f -> 5
                w < weightKg * 0.7f -> 3
                else -> 2
            }
            WarmupSet(w, reps)
        }
    }

    // Percentage ramp for non-barbell lifts: step DOWN from the working weight by
    // max(minJump, 20% of W), collecting stops down to a 40% floor. No feeler —
    // the down-built ramp already ends close to the working weight.
    private fun percentageRampWarmups(weightKg: Float): List<WarmupSet> {
        if (weightKg <= 0f) return emptyList()

        val minJump = if (weightUnit == WeightUnit.LBS) WeightUnit.LBS.toKg(20f) else 10f
        val step = maxOf(minJump, weightKg * 0.20f)
        val floor = weightKg * 0.40f

        val stops = generateSequence(weightKg - step) { it - step }
            .takeWhile { it >= floor - 0.001f }
            .toList()
            .asReversed()
            .map { WeightFormatter.round(it, weightUnit) }
            .filter { it > 0f && it < weightKg }
            .distinct()

        return stops.map { w ->
            val reps = when {
                w < weightKg * 0.5f -> 5
                w < weightKg * 0.7f -> 3
                else -> 2
            }
            WarmupSet(w, reps)
        }
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
        // A manual e1rm override is the user's explicit decision — policy clamps machine
        // prescriptions only, so overrides take the plain legacy path.
        val manual = exerciseE1rmOverrides[exercise.id]
        if (manual != null) {
            if (manual <= 0f) return 0f
            return WeightFormatter.round(progressionEngine.fromOneRepMax(manual, sessionReps), weightUnit)
        }
        val e1rm = prescribedE1rm[exercise.id] ?: return 0f
        if (e1rm <= 0f) return 0f
        return PrescriptionPolicy.prescribe(
            rawE1rm = e1rm,
            sessionReps = sessionReps,
            exerciseId = exercise.id,
            muscle = exercise.primaryMuscle,
            facts = policyFacts,
            now = nowMs,
            weightUnit = weightUnit,
            engine = progressionEngine,
        ).weightKg
    }

    internal fun weightForExerciseTest(exercise: Exercise, sessionReps: Int) =
        weightForExercise(exercise, sessionReps)
}
