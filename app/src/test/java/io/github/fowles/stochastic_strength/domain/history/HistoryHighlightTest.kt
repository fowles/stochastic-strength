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
    fun `muscle stat uses lowercase name and plural-aware verb`() {
        fun muscleSeries(muscle: MuscleGroup) = HighlightSeries(
            subject = muscle.displayName(), muscle = muscle, kind = HighlightKind.MUSCLE,
            points = listOf(ProgressionPoint(now - 40 * day, 100f), ProgressionPoint(now, 115f)),
        )
        val glutes = HistoryHighlight.pick(
            series = listOf(muscleSeries(MuscleGroup.GLUTES)),
            weightUnit = WeightUnit.KG, nowMs = now, random = statSeed(),
        )
        assertTrue(glutes, glutes.contains("Your glutes are up"))
        val chest = HistoryHighlight.pick(
            series = listOf(muscleSeries(MuscleGroup.CHEST)),
            weightUnit = WeightUnit.KG, nowMs = now, random = statSeed(),
        )
        assertTrue(chest, chest.contains("Your chest is up"))
    }

    @Test
    fun `a stat pick always carries a quip`() {
        val series = listOf(liftSeries("Bench Press", MuscleGroup.CHEST, 60f, 70f))
        repeat(50) { s ->
            val text = HistoryHighlight.pick(series, WeightUnit.KG, now, Random(s.toLong()))
            assertTrue(text, HistoryHighlight.QUIPS.any { text.endsWith(it.text) })
        }
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
    fun `new pantheon members are present and generic`() {
        val members = listOf(
            "Colossal Katherine Johnson", "Beefy al-Khwarizmi", "Chiseled Chien-Shiung Wu",
            "Titanic Tu Youyou", "Girthy George Washington Carver", "Peak Rosalind Franklin",
            "Unbreakable Emmy Noether", "Astro-Jacked Mae Jemison", "Granite Ibn al-Haytham",
            "Bulletproof Bose", "Thicc Occam", "Buff Bayes",
        )
        for (member in members) {
            val quip = HistoryHighlight.QUIPS.find { it.text.contains(member) }
            assertTrue("missing pantheon member: $member", quip != null)
            assertEquals("pantheon quips must be generic: $member", null, quip!!.muscle)
        }
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

    @Test
    fun scopeToSession_keepsSessionLiftsAndMuscles() {
        val pts = listOf(ProgressionPoint(0L, 100f), ProgressionPoint(1L, 110f))
        val inLift = HighlightSeries("Bench Press", MuscleGroup.CHEST, pts, HighlightKind.LIFT, exerciseId = 1L)
        val outLift = HighlightSeries("Squat", MuscleGroup.QUADS, pts, HighlightKind.LIFT, exerciseId = 2L)
        val inMuscle = HighlightSeries("Chest", MuscleGroup.CHEST, pts, HighlightKind.MUSCLE)
        val outMuscle = HighlightSeries("Quads", MuscleGroup.QUADS, pts, HighlightKind.MUSCLE)

        val scoped = HistoryHighlight.scopeToSession(
            series = listOf(inLift, outLift, inMuscle, outMuscle),
            exerciseIds = setOf(1L),
            muscles = setOf(MuscleGroup.CHEST),
        )

        assertEquals(listOf(inLift, inMuscle), scoped)
    }

    @Test
    fun pick_withNoQuipOnly_returnsFactWhenCandidateExists() {
        // A clear month-over-month lift gain guarantees a candidate.
        val monthMs = 30L * 24 * 3600 * 1000
        val now = 10L * monthMs
        val series = listOf(
            HighlightSeries(
                subject = "Bench Press",
                muscle = MuscleGroup.CHEST,
                points = listOf(
                    ProgressionPoint(now - 2 * monthMs, 100f),
                    ProgressionPoint(now, 120f),
                ),
                kind = HighlightKind.LIFT,
                exerciseId = 1L,
            ),
        )
        // Try several seeds; with quipOnlyProbability = 0f none may be a bare quip.
        repeat(20) { seed ->
            val text = HistoryHighlight.pick(
                series = series,
                weightUnit = WeightUnit.KG,
                nowMs = now,
                random = Random(seed.toLong()),
                config = HighlightConfig(quipOnlyProbability = 0f),
            )
            assertTrue("expected a fact, got: $text", text.contains("Bench Press is up"))
        }
    }
}
