package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.CoefficientSource

/** Expands per-muscle baseline overrides into per-exercise overrides via the seed coefficients. */
object ExerciseSeedExpansion {

    data class MuscleOverrideRow(
        val sessionId: Long?,
        val muscleGroup: MuscleGroup,
        val baselineWeight: Float,
        val asOf: Long,
        val reason: BaselineChangeReason,
    )

    fun expand(
        muscleOverrides: List<MuscleOverrideRow>,
        exercises: List<Exercise>,
        coefSource: CoefficientSource,
    ): List<ExerciseStrengthOverride> {
        val loadedByMuscle = exercises
            .mapNotNull { ex -> coefSource.get(ex)?.takeIf { it > 0f }?.let { Triple(ex.primaryMuscle, ex.id, it) } }
            .groupBy({ it.first }, { it.second to it.third })
        return muscleOverrides.flatMap { row ->
            loadedByMuscle[row.muscleGroup].orEmpty().map { (exerciseId, coef) ->
                ExerciseStrengthOverride(
                    sessionId = row.sessionId,
                    exerciseId = exerciseId,
                    e1rm = row.baselineWeight * coef,
                    asOf = row.asOf,
                    reason = row.reason,
                )
            }
        }
    }
}
