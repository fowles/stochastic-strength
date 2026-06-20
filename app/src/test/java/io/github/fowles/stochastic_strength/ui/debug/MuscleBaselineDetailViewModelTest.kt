package io.github.fowles.stochastic_strength.ui.debug

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.ui.debug.components.DebugChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuscleBaselineDetailViewModelTest {

    // ---- formatBaselineSetLine -------------------------------------------

    private fun set(
        feedback: SetFeedback? = null,
        targetReps: Int = 10,
        actualReps: Int? = null,
        targetWeight: Float = 25f,
        exerciseId: Long = 1L,
        setNumber: Int = 1,
    ) = WorkoutSet(
        id = 0,
        sessionId = 1L,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = targetWeight,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
    )

    @Test
    fun `formatBaselineSetLine returns null when set has no feedback`() {
        assertNull(formatBaselineSetLine(set(feedback = null), WeightUnit.KG))
    }

    @Test
    fun `formatBaselineSetLine renders RIR_0_1 as target plus one with tilde`() {
        val out = formatBaselineSetLine(
            set(feedback = SetFeedback.RIR_0_1, targetReps = 10, targetWeight = 24.9477f),
            WeightUnit.LBS,
        )
        assertEquals("~11@55lbs", out)
    }

    @Test
    fun `formatBaselineSetLine renders RIR_2_4 as target plus three`() {
        val out = formatBaselineSetLine(
            set(feedback = SetFeedback.RIR_2_4, targetReps = 8, targetWeight = 30f),
            WeightUnit.KG,
        )
        assertEquals("~11@30.0kg", out)
    }

    @Test
    fun `formatBaselineSetLine renders RIR_5_PLUS as target plus six`() {
        val out = formatBaselineSetLine(
            set(feedback = SetFeedback.RIR_5_PLUS, targetReps = 5, targetWeight = 20f),
            WeightUnit.KG,
        )
        assertEquals("~11@20.0kg", out)
    }

    @Test
    fun `formatBaselineSetLine renders TOO_HARD as actual reps without tilde`() {
        val out = formatBaselineSetLine(
            set(
                feedback = SetFeedback.TOO_HARD,
                targetReps = 10,
                actualReps = 8,
                targetWeight = 24.9477f,
            ),
            WeightUnit.LBS,
        )
        assertEquals("8@55lbs", out)
    }

    @Test
    fun `formatBaselineSetLine renders TOO_HARD without actual reps as question mark`() {
        val out = formatBaselineSetLine(
            set(feedback = SetFeedback.TOO_HARD, targetWeight = 20f),
            WeightUnit.KG,
        )
        assertEquals("?@20.0kg", out)
    }

    @Test
    fun `formatBaselineSetLine renders HURT with hurt literal`() {
        val out = formatBaselineSetLine(
            set(feedback = SetFeedback.HURT, targetWeight = 20f),
            WeightUnit.KG,
        )
        assertEquals("hurt@20.0kg", out)
    }

    // ---- buildExerciseBlocks --------------------------------------------

    @Test
    fun `buildExerciseBlocks returns empty list when sets list is empty`() {
        val out = buildExerciseBlocks(
            sets = emptyList(),
            nameByExerciseId = mapOf(1L to "A"),
            weightUnit = WeightUnit.KG,
        )
        assertEquals(emptyList<BaselineEventExercise>(), out)
    }

    @Test
    fun `buildExerciseBlocks groups sets by exercise and orders by setNumber`() {
        val s1 = set(
            feedback = SetFeedback.RIR_0_1,
            targetReps = 10,
            targetWeight = 24.9477f,
            exerciseId = 7L,
            setNumber = 1,
        )
        val s2 = s1.copy(setNumber = 2)
        val s3 = s1.copy(
            feedback = SetFeedback.TOO_HARD,
            actualReps = 8,
            setNumber = 3,
        )

        val out = buildExerciseBlocks(
            sets = listOf(s3, s1, s2),
            nameByExerciseId = mapOf(7L to "Cable Chest Fly"),
            weightUnit = WeightUnit.LBS,
        )

        assertEquals(1, out.size)
        assertEquals("Cable Chest Fly", out[0].name)
        assertEquals(listOf("~11@55lbs", "~11@55lbs", "8@55lbs"), out[0].setLines)
    }

    @Test
    fun `buildExerciseBlocks emits one block per exercise in first-seen order`() {
        val a1 = set(feedback = SetFeedback.RIR_0_1, targetReps = 10, targetWeight = 20f, exerciseId = 1L, setNumber = 1)
        val b1 = set(feedback = SetFeedback.RIR_2_4, targetReps = 8, targetWeight = 30f, exerciseId = 2L, setNumber = 1)
        val a2 = a1.copy(setNumber = 2)

        val out = buildExerciseBlocks(
            sets = listOf(a1, b1, a2),
            nameByExerciseId = mapOf(1L to "Pec Deck", 2L to "Cable Fly"),
            weightUnit = WeightUnit.KG,
        )

        assertEquals(listOf("Pec Deck", "Cable Fly"), out.map { it.name })
        assertEquals(listOf("~11@20.0kg", "~11@20.0kg"), out[0].setLines)
        assertEquals(listOf("~11@30.0kg"), out[1].setLines)
    }

    @Test
    fun `buildExerciseBlocks drops exercises with no displayable sets`() {
        val warmup = set(feedback = null, exerciseId = 1L, setNumber = 1)
        val working = set(
            feedback = SetFeedback.RIR_0_1,
            targetReps = 10,
            targetWeight = 20f,
            exerciseId = 2L,
            setNumber = 1,
        )

        val out = buildExerciseBlocks(
            sets = listOf(warmup, working),
            nameByExerciseId = mapOf(1L to "Warmup-Only", 2L to "Real"),
            weightUnit = WeightUnit.KG,
        )

        assertEquals(listOf("Real"), out.map { it.name })
    }

    @Test
    fun `buildExerciseBlocks drops sets whose exerciseId is not in name map`() {
        val s = set(
            feedback = SetFeedback.RIR_0_1,
            targetReps = 10,
            targetWeight = 20f,
            exerciseId = 99L,
        )

        val out = buildExerciseBlocks(
            sets = listOf(s),
            nameByExerciseId = emptyMap(),
            weightUnit = WeightUnit.KG,
        )

        assertEquals(emptyList<BaselineEventExercise>(), out)
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

    // ---- buildBaselineChartPoints ---------------------------------------

    private val day = 86_400_000L

    private fun log(
        timestamp: Long,
        previousBaseline: Float,
        newBaseline: Float,
    ) = BaselineHistory(
        sessionId = null,
        muscleGroup = MuscleGroup.CHEST,
        previousBaseline = previousBaseline,
        newBaseline = newBaseline,
        changeReason = BaselineChangeReason.PROGRESSION,
        timestamp = timestamp,
    )

    @Test
    fun `buildBaselineChartPoints returns empty list when there are no logs`() {
        assertEquals(emptyList<DebugChartPoint>(), buildBaselineChartPoints(emptyList()))
    }

    @Test
    fun `buildBaselineChartPoints drops the synthetic start point when first previousBaseline is zero`() {
        // The INITIAL assessment has no prior baseline (previousBaseline == 0),
        // which would otherwise force the chart down to zero.
        val logs = listOf(
            log(timestamp = 10 * day, previousBaseline = 0f, newBaseline = 40f),
            log(timestamp = 11 * day, previousBaseline = 40f, newBaseline = 42f),
        )

        val points = buildBaselineChartPoints(logs)

        assertEquals(
            listOf(
                DebugChartPoint(10 * day, 40f),
                DebugChartPoint(11 * day, 42f),
            ),
            points,
        )
    }

    @Test
    fun `buildBaselineChartPoints keeps the synthetic start point when first previousBaseline is positive`() {
        val logs = listOf(
            log(timestamp = 10 * day, previousBaseline = 38f, newBaseline = 40f),
            log(timestamp = 11 * day, previousBaseline = 40f, newBaseline = 42f),
        )

        val points = buildBaselineChartPoints(logs)

        assertEquals(
            listOf(
                DebugChartPoint(10 * day - day, 38f),
                DebugChartPoint(10 * day, 40f),
                DebugChartPoint(11 * day, 42f),
            ),
            points,
        )
    }
}
