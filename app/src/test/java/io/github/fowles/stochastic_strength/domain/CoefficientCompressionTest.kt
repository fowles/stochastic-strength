package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class CoefficientCompressionTest {
    private val eps = 1e-6f

    @Test fun bodyweightStaysZero() {
        assertEquals(0f, CoefficientCompression.compress(0f, 0.75f), 0f)
    }

    @Test fun referenceStaysOne() {
        assertEquals(1f, CoefficientCompression.compress(1f, 0.75f), 0f)
    }

    @Test fun identityAtLambdaOne() {
        assertEquals(0.85f, CoefficientCompression.compress(0.85f, 1.0f), eps)
        assertEquals(2.50f, CoefficientCompression.compress(2.50f, 1.0f), eps)
    }

    @Test fun belowOneCompressesUpward() {
        // guess^λ moves fractional coefficients toward the reference (1.0) as λ<1.
        val c = CoefficientCompression.compress(0.5f, 0.75f)
        assertEquals(0.5f.pow(0.75f), c, eps)
        assertTrue("0.5^0.75 must sit between 0.5 and 1", c > 0.5f && c < 1f)
    }

    @Test fun aboveOneCompressesDownward() {
        // guess>1 (e.g. Leg Press 2.5) compresses down toward the reference as λ<1.
        val c = CoefficientCompression.compress(2.5f, 0.75f)
        assertEquals(2.5f.pow(0.75f), c, eps)
        assertTrue("2.5^0.75 must sit between 1 and 2.5", c > 1f && c < 2.5f)
    }

    @Test fun compressAllPreservesKeysAndAnchors() {
        val raw = mapOf("ref" to 1f, "bw" to 0f, "half" to 0.5f)
        val out = CoefficientCompression.compressAll(raw, 0.75f)
        assertEquals(raw.keys, out.keys)
        assertEquals(1f, out.getValue("ref"), 0f)
        assertEquals(0f, out.getValue("bw"), 0f)
        assertEquals(0.5f.pow(0.75f), out.getValue("half"), eps)
    }
}
