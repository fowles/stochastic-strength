package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise

/**
 * The shipped runtime coefficient table: [CoefficientGuesses] compressed by the global structural
 * exponent [LAMBDA] (`guess^λ`). A fitted artifact — [LAMBDA] is pinned by the held-out belief
 * backtest and CI-guarded by ExerciseCoefficientsTest. Shipping a new table (new guesses or a
 * re-fit λ) is a pure code change: nothing coefficient-derived is stored per user, so no migration.
 */
object ExerciseCoefficients : CoefficientSource {
    /**
     * `fitted` — global log-coefficient compression exponent. Adopted from the held-out λ sweep
     * (CoefExponentFitTest). Provenance curve recorded in Task 5.
     * TODO(Task 5): move off 1.0 to the fitted optimum after the sweep is reviewed.
     */
    const val LAMBDA: Float = 1.0f

    val byName: Map<String, Float> = CoefficientCompression.compressAll(CoefficientGuesses.raw, LAMBDA)

    override fun get(exercise: Exercise): Float? = byName[exercise.name]
}
