package io.github.fowles.stochastic_strength.domain.history

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RestQuipsTest {

    @Test
    fun `final rest never quips regardless of seed`() {
        repeat(1000) { s ->
            assertNull(RestQuips.pick(upcomingMuscles = null, random = Random(s.toLong())))
        }
    }

    @Test
    fun `fires at roughly the configured probability`() {
        val trials = 10_000
        val hits = (0 until trials).count { s ->
            RestQuips.pick(setOf(MuscleGroup.CHEST), Random(s.toLong())) != null
        }
        // 4% of 10k = 400; wide band to avoid flakiness while catching 7% (700) or 0%.
        assertTrue("hit rate $hits/10000", hits in 250..550)
    }

    @Test
    fun `picked quips are always eligible for the upcoming muscles`() {
        val upcoming = setOf(MuscleGroup.QUADS)
        (0 until 10_000).forEach { s ->
            val text = RestQuips.pick(upcoming, Random(s.toLong())) ?: return@forEach
            val quip = HistoryHighlight.QUIPS.first { it.text == text }
            assertTrue(text, quip.muscle == null || quip.muscle == MuscleGroup.QUADS)
        }
    }

    @Test
    fun `muscle-keyed quips are reachable when their muscle is upcoming`() {
        val sawMuscleKeyed = (0 until 20_000).any { s ->
            val text = RestQuips.pick(setOf(MuscleGroup.BICEPS), Random(s.toLong()))
            text != null && HistoryHighlight.QUIPS.first { it.text == text }.muscle == MuscleGroup.BICEPS
        }
        assertTrue("expected at least one biceps quip across seeds", sawMuscleKeyed)
    }

    @Test
    fun `same seed gives same result`() {
        val a = RestQuips.pick(setOf(MuscleGroup.BACK), Random(42))
        val b = RestQuips.pick(setOf(MuscleGroup.BACK), Random(42))
        assertEquals(a, b)
    }
}
