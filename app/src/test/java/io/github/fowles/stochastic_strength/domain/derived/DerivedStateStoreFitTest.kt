package io.github.fowles.stochastic_strength.domain.derived

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DerivedStateStoreFitTest {
    @Test fun defaultsBeforeAnyFit() {
        val store = DerivedStateStore()
        assertEquals(EstimatorConfig(), store.activeConfig())
        assertNull(store.activeFitKey())
        assertNull(store.fitDiagnostics())
    }

    @Test fun setFitRoundTrips() {
        val store = DerivedStateStore()
        val fitted = EstimatorConfig().copy(fatiguePerSet = 0.05f)
        val key = FitKey(sessionCount = 20, latestEndTime = 999L)
        val diag = FitDiagnostics(fitted, EstimatorConfig(), score = -10.0, defaultScore = -12.0, atDefaults = false, sessionCount = 20)
        store.setFit(fitted, key, diag)
        assertEquals(fitted, store.activeConfig())
        assertEquals(key, store.activeFitKey())
        assertEquals(diag, store.fitDiagnostics())
    }
}
