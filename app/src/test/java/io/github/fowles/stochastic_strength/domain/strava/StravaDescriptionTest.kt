package io.github.fowles.stochastic_strength.domain.strava

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StravaDescriptionTest {

    private fun exercise(id: Long, name: String) = Exercise(
        id = id,
        name = name,
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
    )

    private fun set(exerciseId: Long, setNumber: Int) = WorkoutSet(
        sessionId = 1L,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = 80f,
        targetReps = 5,
        actualReps = 5,
        feedback = SetFeedback.RIR_2_4,
        completedAt = setNumber * 1000L,
    )

    private val exercises = mapOf(
        1L to exercise(1L, "Bench Press"),
        2L to exercise(2L, "Squat"),
        3L to exercise(3L, "Deadlift"),
    )

    /** Sets in the order they were performed: Deadlift, then Bench, then Squat. */
    private val sets = listOf(
        set(exerciseId = 3L, setNumber = 1),
        set(exerciseId = 1L, setNumber = 2),
        set(exerciseId = 2L, setNumber = 3),
    )

    @Test
    fun exercisesListInWorkoutOrderNotMapOrder() {
        val desc = StravaExporter.buildDescription(
            highlight = "",
            sets = sets,
            exerciseById = exercises,
            durationMs = 0L,
            weightUnit = WeightUnit.KG,
        )
        // exerciseById iterates 1,2,3; workout order is 3,1,2. Assert the latter.
        val order = listOf("Deadlift", "Bench Press", "Squat").map { desc.indexOf(it) }
        assertTrue("all exercises present: $order", order.all { it >= 0 })
        assertEquals("exercises in workout order", order.sorted(), order)
    }

    @Test
    fun highlightAppearsAtTop() {
        val desc = StravaExporter.buildDescription(
            highlight = "You crushed a new Deadlift PR! 🎉",
            sets = sets,
            exerciseById = exercises,
            durationMs = 0L,
            weightUnit = WeightUnit.KG,
        )
        assertTrue("highlight is first line", desc.startsWith("You crushed a new Deadlift PR! 🎉\n\n"))
        assertTrue("highlight precedes exercises", desc.indexOf("🎉") < desc.indexOf("Deadlift\n"))
    }

    @Test
    fun blankHighlightAddsNoLeadingText() {
        val desc = StravaExporter.buildDescription(
            highlight = "",
            sets = sets,
            exerciseById = exercises,
            durationMs = 0L,
            weightUnit = WeightUnit.KG,
        )
        assertTrue("starts with first exercise", desc.startsWith("Deadlift\n"))
        assertFalse("no leading blank line", desc.startsWith("\n"))
    }
}
