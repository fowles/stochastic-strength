package io.github.fowles.stochastic_strength.ui.debug

import io.github.fowles.stochastic_strength.domain.derived.FitDiagnostics
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitPanelRowsTest {
    @Test fun nullDiagnosticsYieldsNoRows() {
        assertEquals(emptyList<FitPanelRow>(), buildFitPanelRows(null))
    }

    @Test fun rowsCoverFourParamsPlusScore() {
        val d = EstimatorConfig()
        val fitted = d.copy(fatiguePerSet = d.fatiguePerSet * 1.5f)
        val diag = FitDiagnostics(fitted, d, score = -100.0, defaultScore = -110.0, atDefaults = false, sessionCount = 30)
        val rows = buildFitPanelRows(diag)
        // four fitted parameters + one score-gain row
        assertEquals(5, rows.size)
        assertTrue(rows.any { it.label.contains("fatigue", ignoreCase = true) })
        assertTrue(rows.any { it.label.contains("score", ignoreCase = true) })
    }
}
