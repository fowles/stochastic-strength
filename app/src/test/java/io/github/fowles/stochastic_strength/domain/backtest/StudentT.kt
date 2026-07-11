package io.github.fowles.stochastic_strength.domain.backtest

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Student-t distribution: CDF via the regularized incomplete beta function, and standardized log-pdf. */
object StudentT {

    fun logPdf(t: Double, nu: Double): Double {
        val c = lgamma((nu + 1) / 2) - lgamma(nu / 2) - 0.5 * ln(nu * PI)
        return c - (nu + 1) / 2 * ln(1 + t * t / nu)
    }

    /** P(T <= t) for T ~ t_nu. Uses x = nu/(nu+t²) and the identity with I_x(nu/2, 1/2). */
    fun cdf(t: Double, nu: Double): Double {
        if (t == 0.0) return 0.5
        val x = nu / (nu + t * t)
        val ib = 0.5 * regularizedIncompleteBeta(x, nu / 2.0, 0.5)
        return if (t > 0) 1.0 - ib else ib
    }

    // Lanczos log-gamma.
    private fun lgamma(z: Double): Double {
        val g = doubleArrayOf(
            676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7,
        )
        if (z < 0.5) return ln(PI / kotlin.math.sin(PI * z)) - lgamma(1 - z)
        val zz = z - 1
        var a = 0.99999999999980993
        val tt = zz + 7.5
        for (i in g.indices) a += g[i] / (zz + i + 1)
        return 0.5 * ln(2 * PI) + (zz + 0.5) * ln(tt) - tt + ln(a)
    }

    /** Regularized incomplete beta I_x(a,b) via Lentz's continued fraction (Numerical Recipes). */
    private fun regularizedIncompleteBeta(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val lbeta = lgamma(a) + lgamma(b) - lgamma(a + b)
        val front = exp(a * ln(x) + b * ln(1 - x) - lbeta) / a
        // Continued fraction (converges fast for x < (a+1)/(a+b+2); else use symmetry).
        if (x < (a + 1) / (a + b + 2)) return front * betacf(x, a, b)
        return 1.0 - exp(b * ln(1 - x) + a * ln(x) - lbeta) / b * betacf(1 - x, b, a)
    }

    private fun betacf(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-30
        var c = 1.0
        var d = 1.0 - (a + b) * x / (a + 1)
        if (kotlin.math.abs(d) < tiny) d = tiny
        d = 1.0 / d
        var h = d
        for (m in 1..200) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2))
            d = 1.0 + aa * d; if (kotlin.math.abs(d) < tiny) d = tiny
            c = 1.0 + aa / c; if (kotlin.math.abs(c) < tiny) c = tiny
            d = 1.0 / d; h *= d * c
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1))
            d = 1.0 + aa * d; if (kotlin.math.abs(d) < tiny) d = tiny
            c = 1.0 + aa / c; if (kotlin.math.abs(c) < tiny) c = tiny
            d = 1.0 / d; val del = d * c; h *= del
            if (kotlin.math.abs(del - 1.0) < 1e-12) break
        }
        return h
    }
}
