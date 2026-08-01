package io.github.fowles.stochastic_strength.domain.progression

/**
 * A cold-start seed belief for one exercise, synthesized live during replay from the per-muscle
 * baseline times the current coefficient. Not persisted — the coefficient half is never stored.
 *
 * - `sessionId == null` seeds the belief at replay start (seedUncertaintySd).
 * - `sessionId == N` reseeds it at session N's boundary (overrideUncertaintySd, a deliberate per-muscle edit).
 */
data class SeedBelief(
    val sessionId: Long?,
    val exerciseId: Long,
    val e1rm: Float,
    val asOf: Long,
)
