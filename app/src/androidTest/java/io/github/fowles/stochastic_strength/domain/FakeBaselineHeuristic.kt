package io.github.fowles.stochastic_strength.domain

/**
 * Deterministic test helper for repository-level wiring tests.
 * - For each muscle with at least one set, proposes new baseline = current × 1.05.
 * - Honors the minReductionFractions cap.
 * - Used by tests that exercise DB writes / replay order rather than heuristic tuning.
 */
class FakeBaselineHeuristic(private val factor: Float = 1.05f) : BaselineHeuristic {
    override val name: String = "fake-baseline"
    override fun compute(input: BaselineComputationInput): List<BaselineProposal> {
        val byMuscle = input.sets.mapNotNull { input.exerciseMuscle[it.exerciseId] }.toSet()
        return byMuscle.mapNotNull { muscle ->
            val cur = input.currentBaselines[muscle] ?: return@mapNotNull null
            var proposed = cur * factor
            val red = input.minReductionFractions[muscle] ?: 0f
            if (red > 0f) proposed = minOf(proposed, cur * (1f - red))
            BaselineProposal(muscle, proposed, "fake")
        }
    }
}
