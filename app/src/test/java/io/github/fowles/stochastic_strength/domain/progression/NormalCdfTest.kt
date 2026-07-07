package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalCdfTest {
    @Test
    fun erfMatchesGoldenValues() {
        // Golden values (Abramowitz–Stegun 7.1.26 max abs error 1.5e-7).
        assertEquals(0.0f, NormalCdf.erf(0f), 1e-6f)
        assertEquals(0.5204999f, NormalCdf.erf(0.5f), 5e-6f)
        assertEquals(0.8427008f, NormalCdf.erf(1f), 5e-6f)
        assertEquals(0.9953223f, NormalCdf.erf(2f), 5e-6f)
        assertEquals(-0.8427008f, NormalCdf.erf(-1f), 5e-6f)
    }

    @Test
    fun cdfAndPdfAreConsistent() {
        assertEquals(0.5f, NormalCdf.cdf(0f), 1e-6f)
        assertEquals(0.8413447f, NormalCdf.cdf(1f), 1e-5f)
        assertEquals(0.1586553f, NormalCdf.cdf(-1f), 1e-5f)
        assertEquals(0.3989423f, NormalCdf.pdf(0f), 1e-6f)
        assertEquals(0.2419707f, NormalCdf.pdf(1f), 1e-6f)
    }
}
