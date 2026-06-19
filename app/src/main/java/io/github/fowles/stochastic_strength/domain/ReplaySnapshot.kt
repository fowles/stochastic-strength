package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup

/**
 * Holds the evolving derived state for one full replay of [WorkoutRepository.replayDerivedState].
 * Static fields are loaded once from the DB; dynamic maps are mutated as each session's step writes
 * its derived rows. The progression controller reads [currentBaselines]/[currentCoefficients] and the
 * static [exerciseMuscle]/[seedCoefficients]; it does not consume per-session set windows, so the
 * former set/session caches and `filtered*Input` projections are gone.
 */
class ReplaySnapshot(
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val seedCoefficients: Map<Long, Float>,
) {
    val currentCoefficients: MutableMap<Long, Float> = seedCoefficients.toMutableMap()
    val currentBaselines: MutableMap<MuscleGroup, Float> = mutableMapOf()
    // progressionBaselines and baselineHistoryByMuscle are written during replay (baseline bookkeeping)
    // but are no longer read by the controller. They are harmless scaffolding; removable in a later cleanup.
    val progressionBaselines: MutableMap<Pair<Long, MuscleGroup>, Float> = mutableMapOf()
    val baselineHistoryByMuscle: MutableMap<MuscleGroup, MutableList<BaselineHistory>> = mutableMapOf()

    companion object {
        /** Reads static (input-only) data from the DB once for a full replay run. */
        suspend fun loadStaticFromDb(db: AppDatabase): ReplaySnapshot {
            val allExercises = db.exerciseDao().getAll()
            val activeExercises = db.exerciseDao().getActive()
            val exerciseMuscle = allExercises.associate { it.id to it.primaryMuscle }
            val seedCoefficients = activeExercises.associate { ex ->
                ex.id to (ExerciseCoefficients.get(ex) ?: 0f)
            }
            return ReplaySnapshot(exerciseMuscle = exerciseMuscle, seedCoefficients = seedCoefficients)
        }
    }
}
