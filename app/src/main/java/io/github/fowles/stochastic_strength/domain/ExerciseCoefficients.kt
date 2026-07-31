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
     * `fitted` 2026-07-31 — global log-coefficient compression exponent (coef' = guess^λ), adopted
     * from the held-out λ sweep (CoefExponentFitTest). Curve (held-out per-set, ln-units):
     * 0.60→0.08959  0.65→0.08463  0.70→0.08175  0.75→0.08075(best)  0.80→0.08132  0.85→0.08469
     * 0.90→0.09105  1.00→0.11015(identity). Clean interior minimum at 0.75, a 27% improvement over
     * identity. Independent cold-start LOO (scratchpad) landed the same 0.75–0.80. Reference (1.0)
     * and bodyweight (0.0) lifts are unchanged (compress is a no-op at those exponent boundaries).
     */
    const val LAMBDA: Float = 0.75f

    val byName: Map<String, Float> = CoefficientCompression.compressAll(CoefficientGuesses.raw, LAMBDA)

    override fun get(exercise: Exercise): Float? = byName[exercise.name]
}
