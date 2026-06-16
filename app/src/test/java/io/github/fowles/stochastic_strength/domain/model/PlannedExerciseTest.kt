package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannedExerciseTest {

    private fun exercise(
        isTimed: Boolean = false,
        isUnilateral: Boolean = false,
    ) = Exercise(
        id = 1L,
        name = "Ex",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
        isTimed = isTimed,
        isUnilateral = isUnilateral,
    )

    @Test
    fun `estimatedSeconds uses formula when override is null`() {
        val pe = PlannedExercise(exercise = exercise(), warmupSets = emptyList())
        // 3 sets × 135s + 0 warmups = 405
        assertEquals(405, pe.estimatedSeconds)
    }

    @Test
    fun `estimatedSeconds uses formula plus warmups when override is null`() {
        val pe = PlannedExercise(
            exercise = exercise(),
            warmupSets = listOf(WarmupSet(40f, 8), WarmupSet(60f, 5)),
        )
        // 3 × 135 + 2 × 60 = 525
        assertEquals(525, pe.estimatedSeconds)
    }

    @Test
    fun `estimatedSeconds returns override when present`() {
        val pe = PlannedExercise(
            exercise = exercise(),
            estimatedSecondsOverride = 612,
        )
        assertEquals(612, pe.estimatedSeconds)
    }

    @Test
    fun `estimatedSeconds override wins regardless of warmup count`() {
        val pe = PlannedExercise(
            exercise = exercise(),
            warmupSets = listOf(WarmupSet(40f, 8), WarmupSet(60f, 5)),
            estimatedSecondsOverride = 800,
        )
        assertEquals(800, pe.estimatedSeconds)
    }
}
