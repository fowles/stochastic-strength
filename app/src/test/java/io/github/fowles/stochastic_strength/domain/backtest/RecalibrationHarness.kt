package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory

/**
 * Phase-5 offline recalibration: forward-chaining cross-validation over real histories to
 * propose new global defaults for the four fitted estimator hyperparameters. Analysis-only,
 * test-tree; changes no production constant (adoption is a separate human-gated step).
 */
object RecalibrationHarness {

    /** First [k] completed sessions (ordered by endTime), with only their sets/overrides. */
    fun truncateTo(history: ReplayHistory, k: Int): ReplayHistory {
        val kept = history.sessions
            .sortedBy { it.endTime ?: Long.MAX_VALUE }
            .take(k)
        val keptIds = kept.map { it.id }.toSet()
        return history.copy(
            sessions = kept,
            setsBySession = history.setsBySession.filterKeys { it in keptIds },
            sessionOverrides = history.sessionOverrides.filterKeys { it in keptIds },
        )
    }
}
