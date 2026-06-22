package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class RobustCenterTest {

    private val delta = ln(1.10f) // ~0.0953

    @Test fun empty_returnsZero() {
        assertEquals(0f, RobustCenter.of(emptyList(), emptyList(), delta), 1e-6f)
    }

    @Test fun inBandValues_equalWeightedMean() {
        // All within delta of each other -> Huber weights all 1 -> plain weighted mean.
        val v = listOf(0.00f, 0.02f, -0.03f)
        val w = listOf(1f, 1f, 1f)
        assertEquals((0.00f + 0.02f - 0.03f) / 3f, RobustCenter.of(v, w, delta), 1e-4f)
    }

    @Test fun loneOutlier_isRejected_centerStaysNearCluster() {
        // Two calm points near 0, one violent -0.6 -> robust center near the cluster, not -0.2.
        val v = listOf(-0.60f, 0.03f, 0.00f)
        val w = listOf(1f, 1f, 1f)
        val c = RobustCenter.of(v, w, delta, iterations = 5)
        assertEquals(0.0f, c, 0.05f) // far from the -0.20 plain mean
    }

    @Test fun unanimousShift_isFollowed() {
        // All agree on ~-0.55 -> that IS the consensus -> center tracks it.
        val v = listOf(-0.55f, -0.60f, -0.50f)
        val w = listOf(1f, 1f, 1f)
        assertEquals(-0.55f, RobustCenter.of(v, w, delta, iterations = 5), 0.03f)
    }

    @Test fun weightsBias_towardHeavierPoints() {
        // In-band spread (< delta from the mean) so the default robust pass reduces to the
        // plain weighted mean and the test exercises weighting, not outlier rejection.
        val v = listOf(0.03f, -0.03f)
        val w = listOf(3f, 1f)
        assertEquals((3f * 0.03f - 1f * 0.03f) / 4f, RobustCenter.of(v, w, delta), 1e-4f)
    }
}
