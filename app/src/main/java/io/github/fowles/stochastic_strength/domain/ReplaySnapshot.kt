package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * Holds the inputs and evolving derived state for one full replay of [WorkoutRepository.replayDerivedState].
 *
 * Static fields are loaded once from the DB at the top of replay. Dynamic maps are mutated as each
 * session's step writes its derived rows. The filter methods produce per-session inputs without
 * re-reading the DB.
 */
class ReplaySnapshot(
    val allSets: List<WorkoutSet>,
    val allSessionTimes: Map<Long, Long>,
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val seedCoefficients: Map<Long, Float>,
    val allExercises: List<Exercise> = emptyList(),
) {
    val currentCoefficients: MutableMap<Long, Float> = seedCoefficients.toMutableMap()
    val currentBaselines: MutableMap<MuscleGroup, Float> = mutableMapOf()
    val progressionBaselines: MutableMap<Pair<Long, MuscleGroup>, Float> = mutableMapOf()

    fun filteredCoefficientInput(asOf: Long): CoefficientComputationInput {
        val sessionTimes = allSessionTimes.filterValues { it <= asOf }
        val sets = allSets.filter { set ->
            val ca = set.completedAt
            if (ca != null) ca <= asOf else set.sessionId in sessionTimes
        }
        return CoefficientComputationInput(
            sets = sets,
            sessionTimes = sessionTimes,
            exerciseMuscle = exerciseMuscle,
            baselines = progressionBaselines.toMap(),
            currentCoefficients = currentCoefficients.toMap(),
        )
    }

    fun filteredNormalizationInput(asOf: Long): BaselineNormalizationInput {
        val sessionTimes = allSessionTimes.filterValues { it <= asOf }
        val sets = allSets.filter { set ->
            val ca = set.completedAt
            if (ca != null) ca <= asOf else set.sessionId in sessionTimes
        }
        val snapshots = allExercises.map { ex ->
            val seed = seedCoefficients[ex.id] ?: 0f
            val current = currentCoefficients[ex.id] ?: seed
            ExerciseCoefficientSnapshot(ex, seed, current)
        }
        return BaselineNormalizationInput(
            sets = sets,
            exercises = snapshots,
            baselines = currentBaselines.toMap(),
        )
    }

    companion object {
        /** Reads static (input-only) data from the DB once for a full replay run. */
        suspend fun loadStaticFromDb(db: AppDatabase): ReplaySnapshot {
            val allExercises = db.exerciseDao().getAll()
            val activeExercises = db.exerciseDao().getActive()
            val allSets = db.workoutSetDao().getAll()
            val allSessionTimes = db.workoutSessionDao().getAll().associate { it.id to it.startTime }
            val exerciseMuscle = allExercises.associate { it.id to it.primaryMuscle }
            val seedCoefficients = activeExercises.associate { ex ->
                ex.id to (ExerciseCoefficients.get(ex) ?: 0f)
            }
            return ReplaySnapshot(
                allSets = allSets,
                allSessionTimes = allSessionTimes,
                exerciseMuscle = exerciseMuscle,
                seedCoefficients = seedCoefficients,
                allExercises = allExercises,
            )
        }
    }
}
