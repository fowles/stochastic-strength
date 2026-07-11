package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.MuscleGroup

data class ResidualDecomposition(
    val totalVar: Double, val betweenSessionVar: Double, val withinSessionVar: Double, val n: Int,
)

private fun variance(xs: List<Double>): Double {
    if (xs.size < 2) return 0.0
    val m = xs.average()
    return xs.sumOf { (it - m) * (it - m) } / xs.size
}

private fun ScoredSet.residual(): Double = (obsLocation(obs) - predMeanLn).toDouble()

/** Partition residual variance into a between-session (whole-session shift) and within-session component. */
fun decomposeResiduals(stream: List<ScoredSet>): ResidualDecomposition {
    val all = stream.map { it.residual() }
    val bySession = stream.groupBy { it.sessionId }
    val sessionMeans = bySession.values.map { rows -> rows.map { it.residual() }.average() }
    val withinVars = bySession.values.filter { it.size >= 2 }.map { rows -> variance(rows.map { it.residual() }) }
    return ResidualDecomposition(
        totalVar = variance(all),
        betweenSessionVar = variance(sessionMeans),
        withinSessionVar = if (withinVars.isEmpty()) 0.0 else withinVars.average(),
        n = all.size,
    )
}

data class PairCorrelation(
    val muscle: MuscleGroup, val exerciseA: Long, val exerciseB: Long,
    val correlation: Double, val nSessions: Int,
)

/** Pearson correlation of two same-muscle exercises' per-session mean residuals, over co-occurring sessions. */
fun sameMusclePairCorrelations(stream: List<ScoredSet>): List<PairCorrelation> {
    val out = mutableListOf<PairCorrelation>()
    val byMuscle = stream.filter { it.muscle != null }.groupBy { it.muscle!! }
    for ((muscle, rows) in byMuscle) {
        // per (exercise, session) mean residual
        val perExSession: Map<Long, Map<Long, Double>> = rows.groupBy { it.exerciseId }
            .mapValues { (_, exRows) -> exRows.groupBy { it.sessionId }.mapValues { (_, r) -> r.map { it.residual() }.average() } }
        val exercises = perExSession.keys.sorted()
        for (i in exercises.indices) for (j in i + 1 until exercises.size) {
            val a = perExSession[exercises[i]]!!
            val b = perExSession[exercises[j]]!!
            val shared = a.keys.intersect(b.keys).sorted()
            if (shared.size < 3) continue
            val xs = shared.map { a[it]!! }
            val ys = shared.map { b[it]!! }
            val mx = xs.average(); val my = ys.average()
            var cov = 0.0; var vx = 0.0; var vy = 0.0
            for (k in shared.indices) {
                cov += (xs[k] - mx) * (ys[k] - my); vx += (xs[k] - mx) * (xs[k] - mx); vy += (ys[k] - my) * (ys[k] - my)
            }
            val denom = kotlin.math.sqrt(vx * vy)
            val corr = if (denom == 0.0) 0.0 else cov / denom
            out += PairCorrelation(muscle, exercises[i], exercises[j], corr, shared.size)
        }
    }
    return out
}

data class LightLiftSwing(
    val exerciseId: Long, val minKg: Float, val maxKg: Float, val maxStepKg: Float, val sessions: Int,
)

/** The lightest accessory's prescription volatility: smallest-median exercise, its range and max step. */
fun lightestLiftSwing(rows: List<BacktestHarness.Row>): LightLiftSwing? {
    if (rows.isEmpty()) return null
    val byExercise = rows.groupBy { it.exerciseId }
    fun median(xs: List<Float>): Float { val s = xs.sorted(); return s[s.size / 2] }
    val lightest = byExercise.minByOrNull { median(it.value.map { r -> r.weightKg }) } ?: return null
    val ordered = lightest.value.sortedBy { it.sessionId }.map { it.weightKg }
    val maxStep = ordered.zipWithNext { a, b -> kotlin.math.abs(b - a) }.maxOrNull() ?: 0f
    return LightLiftSwing(lightest.key, ordered.min(), ordered.max(), maxStep, ordered.size)
}
