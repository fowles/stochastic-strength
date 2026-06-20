package io.github.fowles.stochastic_strength.domain

/**
 * Deterministic test double: every trained muscle's baseline moves by [upFactor]; coefficients are
 * left untouched. Used for repo-mechanics androidTests, which
 * assert that replay writes the expected derived rows — not the controller's math.
 */
class FakeProgressionController(private val upFactor: Float = 1.05f) : ProgressionController {
    override val name: String = "fake-progression"
    override fun step(input: ProgressionStepInput): ProgressionStepOutput {
        val muscles = input.observations.map { it.muscle }.toSet()
        val baselineUpdates = muscles.mapNotNull { m ->
            val b = input.baselines[m] ?: return@mapNotNull null
            BaselineUpdate(m, WeightFormatter.round(b * upFactor, input.weightUnit), "fake")
        }
        return ProgressionStepOutput(baselineUpdates, emptyList())
    }
}
