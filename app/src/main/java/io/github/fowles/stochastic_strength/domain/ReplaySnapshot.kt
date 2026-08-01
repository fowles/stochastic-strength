package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.belief.Belief

/**
 * Holds the evolving derived state for one full replay of [WorkoutRepository.replayDerivedState].
 * Static fields are loaded once from the DB; dynamic maps are mutated as each session's step writes
 * its derived rows.
 */
class ReplaySnapshot(
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val seedCoefficients: Map<Long, Float>,
) {
    /** Per-exercise beliefs (the Phase-2 stack), updated as each session is folded in. */
    val currentBeliefs: MutableMap<Long, Belief> = mutableMapOf()

    /**
     * Loaded exercise ids grouped by muscle group (only exercises with seed coefficient > 0).
     * Used for HURT propagation and per-muscle projection.
     */
    val muscleExerciseIds: Map<MuscleGroup, List<Long>> =
        seedCoefficients.filterValues { it > 0f }.keys
            .mapNotNull { id -> exerciseMuscle[id]?.let { it to id } }
            .groupBy({ it.first }, { it.second })

    /** Last derived coefficient written per exercise (epsilon-dedupe for CoefficientHistory rows). */
    val lastWrittenCoef: MutableMap<Long, Float> = mutableMapOf()

    companion object {
        /** Reads static (input-only) data from the DB once for a full replay run. */
        suspend fun loadStaticFromDb(db: AppDatabase): ReplaySnapshot {
            val allExercises = db.exerciseDao().getAll()
            val exerciseMuscle = allExercises.associate { it.id to it.primaryMuscle }
            // Active exercises PLUS any disliked lift the user has actually trained: dropping a lift
            // you have real sets for would silently discard that evidence — no seed, no fold (the
            // fold gates on seedCoef > 0), and no sibling vote for the muscle. A disliked lift is
            // still never *prescribed*; the planner filters on the active list independently.
            val trainedIds = db.workoutSetDao().getFirstCompletedAtByExercise().mapTo(HashSet()) { it.exerciseId }
            val seedExercises = allExercises.filter { !it.isDisliked || it.id in trainedIds }
            val seedCoefficients = seedExercises.associate { ex ->
                ex.id to (ExerciseCoefficients.get(ex) ?: 0f)
            }
            return ReplaySnapshot(exerciseMuscle = exerciseMuscle, seedCoefficients = seedCoefficients)
        }
    }
}
