package io.github.fowles.stochastic_strength.domain.belief

/** Pure belief updates: aging (this task), fatigue shift + boundary-pull fold (Task 3). */
class BeliefFold(private val config: BeliefConfig) {
    private val dayMs = 24L * 60 * 60 * 1000

    /** sigma² grows by q per idle day (mu untouched); clamped by the flat guards. */
    fun aged(b: Belief, now: Long): Belief {
        val idleDays = (now - b.updatedAt).coerceAtLeast(0L).toFloat() / dayMs
        val s2 = (b.sigma2 + config.qPerDay * idleDays).coerceIn(config.sigma2Floor, config.sigma2Cap)
        return Belief(b.mu, s2, now)
    }
}
