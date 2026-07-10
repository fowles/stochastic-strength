package io.github.fowles.stochastic_strength.domain.derived

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

/** Identity of a fit: the history it was computed over. θ re-fits only when this changes. */
data class FitKey(val sessionCount: Int, val latestEndTime: Long)

/** Read-only fitted-vs-default readout for the debug panel (spec §7). */
data class FitDiagnostics(
    val fitted: EstimatorConfig,
    val defaults: EstimatorConfig,
    val score: Double,
    val defaultScore: Double,
    val atDefaults: Boolean,
    val sessionCount: Int,
)
