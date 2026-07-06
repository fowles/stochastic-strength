package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot

/**
 * Prescription-policy inputs derived from replayed history. Pure projections of the set log —
 * rebuilt on every replay, never persisted (spec §4/§7: zero schema changes).
 */
data class FailureCeiling(
    val exerciseId: Long,
    /** min over the session's failed sets of rawToOneRepMax(weight, targetReps). */
    val ceilingE1rm: Float,
    /** true when any failed set missed by ≥2 reps or had no rep count. */
    val isClear: Boolean,
    val sessionEndTime: Long,
)

data class HurtEvent(val muscle: MuscleGroup, val at: Long)

data class MuscleStress(
    val tooHardTimes: List<Long>,
    /** RIR_0_1 timestamps per exercise — the cooldown rule triggers on >1 within the window on ONE exercise. */
    val rir01TimesByExercise: Map<Long, List<Long>>,
)

data class PolicyState(
    val ceilings: Map<Long, FailureCeiling>,
    val hurtEvents: List<HurtEvent>,
    val muscleStress: Map<MuscleGroup, MuscleStress>,
) {
    companion object {
        val EMPTY = PolicyState(emptyMap(), emptyList(), emptyMap())
    }
}

/** Accumulates PolicyState across replayed sessions, in session order. */
class PolicyStateBuilder {
    private companion object {
        const val STRESS_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val HURT_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }

    private val ceilings = mutableMapOf<Long, FailureCeiling>()
    private val hurtEvents = mutableListOf<HurtEvent>()
    private val tooHard = mutableMapOf<MuscleGroup, MutableList<Long>>()
    private val rir01 = mutableMapOf<MuscleGroup, MutableMap<Long, MutableList<Long>>>()

    fun onSession(asOf: Long, sets: List<WorkoutSet>, snapshot: ReplaySnapshot) {
        // Failure ceilings: the most recent session containing an exercise defines (or clears) its ceiling.
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
            val failures = exSets.filter { it.feedback == SetFeedback.TOO_HARD }
            if (failures.isEmpty()) {
                ceilings.remove(id)
            } else {
                val ceiling = failures.minOf { DefaultProgressionEngine.rawToOneRepMax(it.targetWeight, it.targetReps) }
                val clear = failures.any { it.actualReps == null || it.targetReps - it.actualReps >= 2 }
                ceilings[id] = FailureCeiling(id, ceiling, clear, asOf)
            }
        }

        // Hurt events: one per muscle per session.
        sets.filter { it.feedback == SetFeedback.HURT }
            .mapNotNull { snapshot.exerciseMuscle[it.exerciseId] }
            .distinct()
            .forEach { hurtEvents += HurtEvent(it, asOf) }
        hurtEvents.removeAll { asOf - it.at > HURT_RETENTION_MS }

        // Sore-muscle stress (bodyweight exempt, matching the old planner rule).
        for (s in sets) {
            val muscle = snapshot.exerciseMuscle[s.exerciseId] ?: continue
            if (snapshot.exerciseEquipment[s.exerciseId] == Equipment.BODYWEIGHT) continue
            val at = s.completedAt ?: asOf
            when (s.feedback) {
                SetFeedback.TOO_HARD -> tooHard.getOrPut(muscle) { mutableListOf() }.add(at)
                SetFeedback.RIR_0_1 ->
                    rir01.getOrPut(muscle) { mutableMapOf() }.getOrPut(s.exerciseId) { mutableListOf() }.add(at)
                else -> {}
            }
        }
        val cutoff = asOf - STRESS_WINDOW_MS
        tooHard.values.forEach { it.removeAll { t -> t < cutoff } }
        rir01.values.forEach { m -> m.values.forEach { it.removeAll { t -> t < cutoff } } }
    }

    fun build(): PolicyState {
        val stress = (tooHard.keys + rir01.keys).associateWith { m ->
            MuscleStress(
                tooHardTimes = tooHard[m].orEmpty().toList(),
                rir01TimesByExercise = rir01[m].orEmpty().mapValues { it.value.toList() },
            )
        }
        return PolicyState(ceilings.toMap(), hurtEvents.toList(), stress)
    }
}
