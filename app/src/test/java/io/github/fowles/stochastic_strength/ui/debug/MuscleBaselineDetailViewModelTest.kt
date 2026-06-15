package io.github.fowles.stochastic_strength.ui.debug

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import org.junit.Assert.assertEquals
import org.junit.Test

class MuscleBaselineDetailViewModelTest {

    // ---- parseFeedbacks ---------------------------------------------------

    @Test
    fun `parseFeedbacks returns empty list for null input`() {
        assertEquals(emptyList<SetFeedback>(), parseFeedbacks(null))
    }

    @Test
    fun `parseFeedbacks returns empty list for empty string`() {
        assertEquals(emptyList<SetFeedback>(), parseFeedbacks(""))
    }

    @Test
    fun `parseFeedbacks parses a single valid token`() {
        assertEquals(listOf(SetFeedback.RIR_2_4), parseFeedbacks("RIR_2_4"))
    }

    @Test
    fun `parseFeedbacks parses two valid tokens in order`() {
        assertEquals(
            listOf(SetFeedback.RIR_2_4, SetFeedback.HURT),
            parseFeedbacks("RIR_2_4,HURT"),
        )
    }

    @Test
    fun `parseFeedbacks drops unknown tokens`() {
        assertEquals(
            listOf(SetFeedback.RIR_2_4, SetFeedback.HURT),
            parseFeedbacks("RIR_2_4,BOGUS,HURT"),
        )
    }

    // ---- computeCoefficientDeviations ------------------------------------

    @Test
    fun `computeCoefficientDeviations returns empty list for empty exercise input`() {
        val rows = computeCoefficientDeviations(
            exercises = emptyList(),
            seedByName = mapOf("Bench Press" to 1.0f),
            currentByExerciseId = mapOf(1L to 1.1f),
        )

        assertEquals(emptyList<CoefficientDeviationRow>(), rows)
    }

    @Test
    fun `computeCoefficientDeviations omits exercise whose name is not in seed map`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(1L to "Unknown Exercise"),
            seedByName = emptyMap(),
            currentByExerciseId = mapOf(1L to 1.0f),
        )

        assertEquals(emptyList<CoefficientDeviationRow>(), rows)
    }

    @Test
    fun `computeCoefficientDeviations omits exercise whose seed is zero`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(
                1L to "Push-Up",
                2L to "Bench Press",
            ),
            seedByName = mapOf(
                "Push-Up" to 0f,
                "Bench Press" to 1.0f,
            ),
            currentByExerciseId = mapOf(
                1L to 0.5f, // would otherwise divide by zero
                2L to 1.1f,
            ),
        )

        assertEquals(listOf("Bench Press"), rows.map { it.name })
    }

    @Test
    fun `computeCoefficientDeviations falls back to seed when current entry is missing`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(1L to "Bench Press"),
            seedByName = mapOf("Bench Press" to 1.0f),
            currentByExerciseId = emptyMap(),
        )

        assertEquals(1, rows.size)
        assertEquals("Bench Press", rows[0].name)
        assertEquals(0f, rows[0].deviation, 1e-6f)
    }

    @Test
    fun `computeCoefficientDeviations sorts results descending by deviation`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(
                1L to "AboveSeed",
                2L to "AtSeed",
                3L to "BelowSeed",
            ),
            seedByName = mapOf(
                "AboveSeed" to 1.0f,
                "AtSeed" to 1.0f,
                "BelowSeed" to 1.0f,
            ),
            currentByExerciseId = mapOf(
                1L to 1.25f,  // +25%
                2L to 1.0f,   //  0%
                3L to 0.80f,  // -20%
            ),
        )

        assertEquals(listOf("AboveSeed", "AtSeed", "BelowSeed"), rows.map { it.name })
        assertEquals(0.25f, rows[0].deviation, 1e-4f)
        assertEquals(0f, rows[1].deviation, 1e-6f)
        assertEquals(-0.20f, rows[2].deviation, 1e-4f)
    }
}
