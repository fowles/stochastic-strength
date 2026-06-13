package io.github.fowles.stochastic_strength.domain

class SeedNormalizer : BaselineNormalizer {
    override val name: String = "seed-normalizer"

    override fun compute(input: BaselineNormalizationInput): List<BaselineNormalizationProposal> {
        val observed = input.sets.mapTo(mutableSetOf()) { it.exerciseId }
        val byMuscle = input.exercises.groupBy { it.exercise.primaryMuscle }
        return byMuscle.mapNotNull { (muscle, snaps) ->
            val qualifying = snaps.filter {
                it.exercise.id in observed && it.currentCoefficient > 0f
            }
            if (qualifying.size < 2) return@mapNotNull null
            // Real math arrives in Task 5 — for now, emit a placeholder that the existing tests don't require.
            null
        }
    }
}
