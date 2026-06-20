package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RepRangePickerTest {

    @Test
    fun candidates_5to10_matchesLegacyDistribution() {
        assertEquals(listOf(5, 8, 10), RepRangePicker.candidates(5, 10))
    }

    @Test
    fun candidates_1to20_returnsAllRoundReps() {
        assertEquals(
            listOf(1, 2, 3, 5, 8, 10, 12, 15, 18, 20),
            RepRangePicker.candidates(1, 20),
        )
    }

    @Test
    fun candidates_singleValueRange_returnsSingleton() {
        assertEquals(listOf(1), RepRangePicker.candidates(1, 1))
        assertEquals(listOf(4), RepRangePicker.candidates(4, 4))
        assertEquals(listOf(20), RepRangePicker.candidates(20, 20))
    }

    @Test
    fun candidates_noRoundInRange_returnsEndpointsOnly() {
        assertEquals(listOf(6, 7), RepRangePicker.candidates(6, 7))
    }

    @Test
    fun candidates_endpointsRound_noOtherRoundsInside_returnsEndpoints() {
        assertEquals(listOf(3, 5), RepRangePicker.candidates(3, 5))
    }

    @Test
    fun candidates_nonRoundEndpoints_includesEndpointsAndInnerRounds() {
        assertEquals(listOf(4, 5, 8, 9), RepRangePicker.candidates(4, 9))
    }

    @Test
    fun candidates_2to18_returnsRoundEndpointsAndInnerRounds() {
        assertEquals(listOf(2, 3, 5, 8, 10, 12, 15, 18), RepRangePicker.candidates(2, 18))
    }

    @Test
    fun pick_onlyReturnsValuesFromCandidates_andCoversAllCandidates() {
        val random = Random(0)
        val expected = RepRangePicker.candidates(4, 9).toSet()
        val seen = mutableSetOf<Int>()
        repeat(2_000) {
            val v = RepRangePicker.pick(4, 9, random)
            assertTrue("pick($v) not in candidates $expected", v in expected)
            seen += v
        }
        assertEquals("pick should cover every candidate at least once", expected, seen)
    }

    @Test
    fun pick_singletonRange_alwaysReturnsThatValue() {
        val random = Random(0)
        repeat(50) { assertEquals(7, RepRangePicker.pick(7, 7, random)) }
    }
}
