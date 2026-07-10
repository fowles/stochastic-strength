package io.github.fowles.stochastic_strength.domain.progression

/**
 * Pure, dependency-free downhill-simplex (Nelder-Mead) minimizer. Deterministic: the initial simplex
 * is [start] plus one vertex per dimension offset by [step]. Standard reflection/expansion/contraction/
 * shrink coefficients. Used by [HyperparameterFitter]; the objective clamps its own parameter bounds.
 */
object NelderMead {
    fun minimize(start: DoubleArray, step: Double, maxIter: Int, f: (DoubleArray) -> Double): DoubleArray {
        val n = start.size
        val simplex = Array(n + 1) { i -> start.copyOf().also { if (i > 0) it[i - 1] += step } }
        val fv = DoubleArray(n + 1) { f(simplex[it]) }
        repeat(maxIter) {
            val order = (0..n).sortedBy { fv[it] }
            val best = order.first(); val worst = order.last(); val second = order[order.size - 2]
            // Centroid of all but the worst.
            val centroid = DoubleArray(n)
            for (i in 0..n) if (i != worst) for (d in 0 until n) centroid[d] += simplex[i][d] / n
            fun at(coef: Double): DoubleArray = DoubleArray(n) { centroid[it] + coef * (centroid[it] - simplex[worst][it]) }
            val refl = at(1.0); val fRefl = f(refl)
            if (fRefl < fv[best]) {
                val exp = at(2.0); val fExp = f(exp)
                if (fExp < fRefl) { simplex[worst] = exp; fv[worst] = fExp } else { simplex[worst] = refl; fv[worst] = fRefl }
            } else if (fRefl < fv[second]) {
                simplex[worst] = refl; fv[worst] = fRefl
            } else {
                val contract = at(0.5); val fCon = f(contract)
                if (fCon < fv[worst]) { simplex[worst] = contract; fv[worst] = fCon }
                else { // shrink toward best
                    for (i in 0..n) if (i != best) {
                        for (d in 0 until n) simplex[i][d] = simplex[best][d] + 0.5 * (simplex[i][d] - simplex[best][d])
                        fv[i] = f(simplex[i])
                    }
                }
            }
        }
        val bestIdx = (0..n).minByOrNull { fv[it] }!!
        return simplex[bestIdx]
    }
}
