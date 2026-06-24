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
    fun `formatBaselineSetLine renders RIR_0_1 reserve rounded to target plus one with tilde`() {
        val out = formatBaselineSetLine(
            set(feedback = SetFeedback.RIR_0_1, targetReps = 10, targetWeight = 24.9477f),
            WeightUnit.LBS,
        )
        // RESERVE_RIR_0_1 is +0.5; (10 + 0.5) rounds half-up to 11.
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

class FormatBaselineSetLineTest {
    private fun set(feedback: SetFeedback?, actualReps: Int? = null) = WorkoutSet(
        sessionId = 1L, exerciseId = 1L, setNumber = 1,
        targetWeight = 50f, targetReps = 8, actualReps = actualReps, feedback = feedback,
    )

    @Test fun rirIsTildeEstimate() {
        assertEquals("~11@110lbs", formatBaselineSetLine(set(SetFeedback.RIR_2_4), WeightUnit.LBS))
    }
    @Test fun tooHardShowsActualReps() {
        assertEquals("6@50.0kg", formatBaselineSetLine(set(SetFeedback.TOO_HARD, actualReps = 6), WeightUnit.KG))
    }
    @Test fun tooHardWithoutRepsShowsQuestionMark() {
        assertEquals("?@50.0kg", formatBaselineSetLine(set(SetFeedback.TOO_HARD), WeightUnit.KG))
    }
    @Test fun hurtRendersHurt() {
        assertEquals("hurt@50.0kg", formatBaselineSetLine(set(SetFeedback.HURT), WeightUnit.KG))
    }
    @Test fun noFeedbackIsNull() {
        assertNull(formatBaselineSetLine(set(feedback = null), WeightUnit.KG))
    }
}
