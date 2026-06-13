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
            val num = qualifying.sumOf { (it.currentCoefficient * it.seedCoefficient).toDouble() }
            val den = qualifying.sumOf { (it.currentCoefficient * it.currentCoefficient).toDouble() }
            if (den <= 0.0) return@mapNotNull null
            val m = (num / den).toFloat()
            BaselineNormalizationProposal(
                muscleGroup = muscle,
                scale = m,
                metadata = null,
            )
        }
    }
}
