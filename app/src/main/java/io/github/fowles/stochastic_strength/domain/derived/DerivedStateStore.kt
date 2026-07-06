package io.github.fowles.stochastic_strength.domain.derived

import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.domain.policy.PolicyState
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory replacement for the muscle_group_strength, baseline_history, and
 * coefficient_history Room tables. Rebuilt from scratch by
 * [io.github.fowles.stochastic_strength.domain.WorkoutRepository.replayDerivedState]
 * on every cold start and every session finish.
 *
 * Single source of truth is an immutable [Snapshot] swapped atomically at the
 * end of [rebuild]. If the rebuild block throws, the live snapshot is preserved.
 */
class DerivedStateStore {
    private val rebuildMutex = Mutex()

    @Volatile
    private var live: Snapshot = Snapshot.empty()

    fun snapshot(): Snapshot = live

    /**
     * Atomically rebuild the store. The block receives a [MutableDerivedState]
     * that supports both reads (reflecting in-progress writes) and writes.
     * On normal return, the mutable view becomes the new live snapshot.
     * On exception, the previous snapshot is retained.
     */
    suspend fun rebuild(block: suspend (MutableDerivedState) -> Unit) {
        rebuildMutex.withLock {
            val scratch = MutableDerivedState()
            block(scratch)
            live = scratch.toSnapshot()
        }
    }

    class Snapshot internal constructor(
        private val muscleStrengths: Map<MuscleGroup, MuscleGroupStrength>,
        private val baselineHistory: List<BaselineHistory>,
        private val coefficientHistory: List<CoefficientHistory>,
        private val exerciseEstimates: Map<Long, ExerciseEstimate>,
        private val policyState: PolicyState,
    ) {
        fun exerciseEstimates(): Map<Long, ExerciseEstimate> = exerciseEstimates
        fun policyState(): PolicyState = policyState
        fun muscleGroupStrength(muscle: MuscleGroup): MuscleGroupStrength? = muscleStrengths[muscle]

        fun allMuscleGroupStrengths(): List<MuscleGroupStrength> = muscleStrengths.values.toList()

        fun allBaselineHistory(): List<BaselineHistory> = baselineHistory

        fun baselineHistoryForMuscle(muscle: MuscleGroup): List<BaselineHistory> =
            baselineHistory.filter { it.muscleGroup == muscle }.sortedBy { it.timestamp }

        fun coefficientHistoryForExercise(exerciseId: Long): List<CoefficientHistory> =
            coefficientHistory.filter { it.exerciseId == exerciseId }.sortedBy { it.computedAt }

        fun coefficientHistoryLatestPerExercise(): List<CoefficientHistory> =
            coefficientHistory
                .groupBy { it.exerciseId }
                .mapNotNull { (_, rows) -> rows.maxByOrNull { it.computedAt } }

        fun allCoefficientHistory(): List<CoefficientHistory> = coefficientHistory

        fun coefficientHistoryMostRecent(limit: Int): List<CoefficientHistory> =
            coefficientHistory.sortedByDescending { it.computedAt }.take(limit)

        companion object {
            fun empty() = Snapshot(emptyMap(), emptyList(), emptyList(), emptyMap(), PolicyState.EMPTY)
        }
    }
}

class MutableDerivedState internal constructor() {
    private val muscleStrengths = mutableMapOf<MuscleGroup, MuscleGroupStrength>()
    private val baselineHistory = mutableListOf<BaselineHistory>()
    private val coefficientHistory = mutableListOf<CoefficientHistory>()
    private var nextBaselineId: Long = 1
    private var nextCoefficientId: Long = 1
    private var exerciseEstimates: Map<Long, ExerciseEstimate> = emptyMap()
    private var policyState: PolicyState = PolicyState.EMPTY

    fun putExerciseEstimates(map: Map<Long, ExerciseEstimate>) {
        exerciseEstimates = map
    }

    fun putPolicyState(state: PolicyState) {
        policyState = state
    }

    fun upsertMuscleGroupStrength(strength: MuscleGroupStrength) {
        muscleStrengths[strength.muscleGroup] = strength
    }

    fun insertBaselineHistory(row: BaselineHistory): Long {
        val id = nextBaselineId++
        baselineHistory.add(row.copy(id = id))
        return id
    }

    fun insertCoefficientHistory(row: CoefficientHistory): Long {
        val id = nextCoefficientId++
        coefficientHistory.add(row.copy(id = id))
        return id
    }

    // Read accessors — symmetric with Snapshot, used during rebuild.
    fun muscleGroupStrength(muscle: MuscleGroup): MuscleGroupStrength? = muscleStrengths[muscle]

    fun allMuscleGroupStrengths(): List<MuscleGroupStrength> = muscleStrengths.values.toList()

    fun allBaselineHistory(): List<BaselineHistory> = baselineHistory.toList()

    fun baselineHistoryForMuscle(muscle: MuscleGroup): List<BaselineHistory> =
        baselineHistory.filter { it.muscleGroup == muscle }.sortedBy { it.timestamp }

    fun coefficientHistoryLatestPerExercise(): List<CoefficientHistory> =
        coefficientHistory
            .groupBy { it.exerciseId }
            .mapNotNull { (_, rows) -> rows.maxByOrNull { it.computedAt } }

    internal fun toSnapshot(): DerivedStateStore.Snapshot = DerivedStateStore.Snapshot(
        muscleStrengths = muscleStrengths.toMap(),
        baselineHistory = baselineHistory.toList(),
        coefficientHistory = coefficientHistory.toList(),
        exerciseEstimates = exerciseEstimates,
        policyState = policyState,
    )
}
