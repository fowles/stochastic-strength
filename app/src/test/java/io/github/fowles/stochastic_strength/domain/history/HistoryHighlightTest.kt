package io.github.fowles.stochastic_strength.domain.history

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random
import org.junit.Test

class HistoryHighlightTest {

    private val now = 1_000_000_000_000L
    private val day = 24L * 3600 * 1000
    // Seed chosen so the first nextFloat() > quipOnlyProbability (shows a stat, not a quip-only).
    // Verified against this Kotlin stdlib's Random(0): first nextFloat() = 0.5496 > 0.25.
    private fun statSeed() = Random(0)

    private fun liftSeries(subject: String, muscle: MuscleGroup, startKg: Float, endKg: Float) =
        HighlightSeries(
            subject = subject, muscle = muscle, kind = HighlightKind.LIFT,
            points = listOf(
                ProgressionPoint(now - 40 * day, startKg),
                ProgressionPoint(now, endKg),
            ),
        )

    @Test
    fun `lift gain over month is reported in absolute weight`() {
        val text = HistoryHighlight.pick(
            series = listOf(liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 70f)),
            weightUnit = WeightUnit.KG, nowMs = now, random = statSeed(),
        )
        assertTrue(text, text.contains("Bench Press"))
        assertTrue(text, text.contains("10.0 kg"))
    }

    @Test
    fun `muscle gain is reported as a percent`() {
        val series = HighlightSeries(
            subject = MuscleGroup.CHEST.displayName(), muscle = MuscleGroup.CHEST,
            kind = HighlightKind.MUSCLE,
            points = listOf(ProgressionPoint(now - 40 * day, 100f), ProgressionPoint(now, 115f)),
        )
        val text = HistoryHighlight.pick(
            series = listOf(series), weightUnit = WeightUnit.KG, nowMs = now, random = statSeed(),
        )
        assertTrue(text, text.contains("15%"))
    }

    @Test
    fun `flat and negative series never produce a stat, only a quip`() {
        val flat = liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 60f)
        val down = liftSeries("Squat", MuscleGroup.QUADS, 100f, 90f)
        // Any seed: with no qualifying candidate the result must be a bare quip from the pool.
        repeat(20) { s ->
            val text = HistoryHighlight.pick(
                series = listOf(flat, down), weightUnit = WeightUnit.KG,
                nowMs = now, random = Random(s.toLong()),
            )
            assertTrue(text, HistoryHighlight.QUIPS.any { it.text == text })
        }
    }

    @Test
    fun `sub-threshold gain does not qualify`() {
        val tiny = liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 61f) // 1kg < 2kg floor
        repeat(20) { s ->
            val text = HistoryHighlight.pick(
                series = listOf(tiny), weightUnit = WeightUnit.KG, nowMs = now, random = Random(s.toLong()),
            )
            assertTrue(text, HistoryHighlight.QUIPS.any { it.text == text })
        }
    }

    @Test
    fun `same date and data produces the same pick`() {
        val series = listOf(liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 70f))
        val a = HistoryHighlight.pick(series, WeightUnit.KG, now, Random(42))
        val b = HistoryHighlight.pick(series, WeightUnit.KG, now, Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `empty series returns a generic quip`() {
        val text = HistoryHighlight.pick(emptyList(), WeightUnit.KG, now, Random(7))
        val quip = HistoryHighlight.QUIPS.first { it.text == text }
        assertEquals(null, quip.muscle) // standalone quips are always generic
    }

    @Test
    fun `committed quip is present in the pool`() {
        assertTrue(HistoryHighlight.QUIPS.any {
            it.text == "The bar does not care about your feelings. Add weight to it anyway."
        })
    }

    @Test
    fun `quip-only outcome is reachable even when a stat qualifies`() {
        val series = listOf(liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 70f))
        val sawQuipOnly = (0 until 200).any { s ->
            val text = HistoryHighlight.pick(series, WeightUnit.KG, now, Random(s.toLong()))
            HistoryHighlight.QUIPS.any { it.text == text }
        }
        assertTrue("expected at least one quip-only pick across seeds", sawQuipOnly)
    }
}
