package io.github.fowles.stochastic_strength.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class CoefficientDeviationTest {

    @Test
    fun `positive and negative drift are computed and sorted descending`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(
                1L to "Bench Press",     // 1.25 / 1.0  = +25%
                2L to "Incline Bench",   // 0.918 / 0.85 = +8%
                3L to "Decline Bench",   // 0.9215 / 0.95 = -3%
                4L to "Dumbbell Press",  // 0.328 / 0.40 = -18%
            ),
            seedByName = mapOf(
                "Bench Press" to 1.00f,
                "Incline Bench" to 0.85f,
                "Decline Bench" to 0.95f,
                "Dumbbell Press" to 0.40f,
            ),
            currentByExerciseId = mapOf(
                1L to 1.25f,
                2L to 0.918f,
                3L to 0.9215f,
                4L to 0.328f,
            ),
        )

        assertEquals(listOf("Bench Press", "Incline Bench", "Decline Bench", "Dumbbell Press"),
            rows.map { it.name })
        assertEquals(0.25f, rows[0].deviation, 1e-4f)
        assertEquals(0.08f, rows[1].deviation, 1e-4f)
        assertEquals(-0.03f, rows[2].deviation, 1e-4f)
        assertEquals(-0.18f, rows[3].deviation, 1e-4f)
    }

    @Test
    fun `seed of zero is omitted`() {
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
                1L to 0.5f, // would otherwise produce divide-by-zero
                2L to 1.1f,
            ),
        )

        assertEquals(listOf("Bench Press"), rows.map { it.name })
    }

    @Test
    fun `unknown seed is omitted`() {
        val rows = computeCoefficientDeviations(
            exercises = listOf(1L to "Unknown Exercise"),
            seedByName = emptyMap(),
            currentByExerciseId = mapOf(1L to 1.0f),
        )

        assertEquals(emptyList<CoefficientDeviationRow>(), rows)
    }

    @Test
    fun `missing current falls back to seed yielding zero deviation`() {
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
    fun `empty input returns empty list`() {
        val rows = computeCoefficientDeviations(
            exercises = emptyList(),
            seedByName = emptyMap(),
            currentByExerciseId = emptyMap(),
        )

        assertEquals(emptyList<CoefficientDeviationRow>(), rows)
    }
}
