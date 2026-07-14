package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.SessionSignalExtractor

/**
 * Pure per-session core of progression: per-exercise fold → projection of each affected muscle.
 * Mutates [ReplaySnapshot.currentEstimates] in place and returns the affected muscles' projections.
 * Persistence of the projections is the caller's concern.
 */
class SessionProgressionStepper(
    private val updater: ExerciseEstimateUpdater = ExerciseEstimateUpdater(),
    private val projector: MuscleStrengthProjector = MuscleStrengthProjector(),
) {
    data class MuscleStep(val muscle: MuscleGroup, val projection: MuscleProjection)
    data class StepResult(val steps: List<MuscleStep>)

    fun step(sets: List<WorkoutSet>, snapshot: ReplaySnapshot, asOf: Long): StepResult {
        if (sets.isEmpty()) return StepResult(emptyList())

        // Per-exercise fold from the session aggregate.
        val affectedMuscles = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
            val agg = SessionSignalExtractor.aggregateSession(exSets) ?: return@forEach
            val prior = snapshot.currentEstimates[id] ?: return@forEach
            snapshot.currentEstimates[id] = updater.fold(prior, agg.est1RM, agg.bracketConfidence, asOf)
            snapshot.exerciseMuscle[id]?.let { affectedMuscles.add(it) }
        }

        val steps = affectedMuscles.mapNotNull { m ->
            val exerciseIds = snapshot.muscleExerciseIds[m] ?: return@mapNotNull null
            val projection = projector.project(
                estimates = snapshot.currentEstimates,
                seedCoef = snapshot.seedCoefficients,
                muscleExerciseIds = exerciseIds,
                now = asOf,
            )
            MuscleStep(muscle = m, projection = projection)
        }
        return StepResult(steps)
    }
}
