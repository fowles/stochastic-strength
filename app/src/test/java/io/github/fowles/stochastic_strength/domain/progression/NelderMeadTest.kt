package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test

class NelderMeadTest {
    @Test fun findsMinimumOfShiftedQuadratic() {
        // f(x) = (x0-1.5)² + (x1+2)² + (x2-0.3)², min at (1.5, -2, 0.3).
        val target = doubleArrayOf(1.5, -2.0, 0.3)
        val best = NelderMead.minimize(doubleArrayOf(0.0, 0.0, 0.0), step = 0.5, maxIter = 500) { x ->
            var s = 0.0; for (i in x.indices) { val d = x[i] - target[i]; s += d * d }; s
        }
        for (i in target.indices) assertEquals(target[i], best[i], 1e-3)
    }

    @Test fun isDeterministic() {
        val f = { x: DoubleArray -> x.sumOf { (it - 1.0) * (it - 1.0) } }
        val a = NelderMead.minimize(doubleArrayOf(0.0, 0.0), 0.4, 300, f)
        val b = NelderMead.minimize(doubleArrayOf(0.0, 0.0), 0.4, 300, f)
        assertEquals(a[0], b[0], 0.0); assertEquals(a[1], b[1], 0.0)
    }
}
