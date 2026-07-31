package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.domain.StartingWeights

/**
 * Live cold-start seeding: expands per-muscle baselines into per-exercise [SeedBelief]s via the
 * current coefficients. A muscle with no [BaselineOverride] initial row defaults to the
 * [StartingWeights] reference for the user's (sex, level).
 */
object ExerciseSeedExpansion {

    data class MuscleBaseline(
        val sessionId: Long?,
        val muscleGroup: MuscleGroup,
        val baselineWeight: Float,
        val asOf: Long,
    )

    data class Seeds(
        val initial: List<SeedBelief>,
        val bySession: Map<Long, List<SeedBelief>>,
    )

    /** One seed per loaded (coef > 0) exercise in each baseline's muscle; drops non-positive e1rm. */
    fun expand(
        muscleBaselines: List<MuscleBaseline>,
        exerciseMuscle: Map<Long, MuscleGroup>,
        coefById: Map<Long, Float>,
    ): List<SeedBelief> {
        val loadedByMuscle: Map<MuscleGroup, List<Pair<Long, Float>>> =
            coefById.filterValues { it > 0f }
                .mapNotNull { (id, coef) -> exerciseMuscle[id]?.let { it to (id to coef) } }
                .groupBy({ it.first }, { it.second })
        return muscleBaselines.flatMap { row ->
            loadedByMuscle[row.muscleGroup].orEmpty().mapNotNull { (exerciseId, coef) ->
                (row.baselineWeight * coef).takeIf { it > 0f }
                    ?.let { SeedBelief(row.sessionId, exerciseId, it, row.asOf) }
            }
        }
    }

    /**
     * Build the replay's initial + session seed sets from durable [BaselineOverride] rows. Every
     * muscle without an initial override falls back to the [StartingWeights] default for (sex, level)
     * — identical to what `seedInitialWeights` used to materialize, so existing behavior is preserved.
     */
    fun buildSeeds(
        initialOverrides: List<BaselineOverride>,
        sessionOverrides: List<BaselineOverride>,
        sex: Sex,
        level: StrengthLevel,
        exerciseMuscle: Map<Long, MuscleGroup>,
        coefById: Map<Long, Float>,
    ): Seeds {
        val initialByMuscle = initialOverrides.associateBy { it.muscleGroup }
        val initialBaselines = MuscleGroup.entries.map { muscle ->
            val override = initialByMuscle[muscle]
            MuscleBaseline(
                sessionId = null,
                muscleGroup = muscle,
                baselineWeight = override?.baselineWeight ?: StartingWeights.baseline(sex, level, muscle),
                asOf = override?.asOf ?: 0L,
            )
        }
        val sessionBaselines = sessionOverrides.map {
            MuscleBaseline(it.sessionId, it.muscleGroup, it.baselineWeight, it.asOf)
        }
        val all = expand(initialBaselines + sessionBaselines, exerciseMuscle, coefById)
        return Seeds(
            initial = all.filter { it.sessionId == null },
            bySession = all.filter { it.sessionId != null }.groupBy { it.sessionId!! },
        )
    }
}
