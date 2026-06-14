package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySnapshotTest {

    @Test
    fun `filteredCoefficientInput drops sessions and sets newer than asOf`() {
        val snapshot = ReplaySnapshot(
            allSets = listOf(
                set(id = 1, sessionId = 1, completedAt = 100),
                set(id = 2, sessionId = 2, completedAt = 200),
                set(id = 3, sessionId = 3, completedAt = 300),
            ),
            allSessionTimes = mapOf(1L to 100L, 2L to 200L, 3L to 300L),
            exerciseMuscle = mapOf(100L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(100L to 1.0f),
        )
        snapshot.currentCoefficients[100L] = 1.05f
        snapshot.progressionBaselines[1L to MuscleGroup.CHEST] = 100f
        snapshot.progressionBaselines[2L to MuscleGroup.CHEST] = 102f

        val filtered = snapshot.filteredCoefficientInput(asOf = 200L)

        assertEquals(setOf(1L, 2L), filtered.sessionTimes.keys)
        assertEquals(listOf(1L, 2L), filtered.sets.map { it.id })
        assertEquals(1.05f, filtered.currentCoefficients[100L])
        assertEquals(100f, filtered.baselines[1L to MuscleGroup.CHEST])
    }

    @Test
    fun `filteredCoefficientInput includes set with null completedAt when its session is included`() {
        val snapshot = ReplaySnapshot(
            allSets = listOf(set(id = 1, sessionId = 1, completedAt = null)),
            allSessionTimes = mapOf(1L to 100L),
            exerciseMuscle = mapOf(100L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(100L to 1.0f),
        )

        val filtered = snapshot.filteredCoefficientInput(asOf = 200L)

        assertEquals(1, filtered.sets.size)
    }

    @Test
    fun `filteredCoefficientInput excludes set with null completedAt when its session is excluded`() {
        val snapshot = ReplaySnapshot(
            allSets = listOf(set(id = 1, sessionId = 1, completedAt = null)),
            allSessionTimes = mapOf(1L to 500L),
            exerciseMuscle = mapOf(100L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(100L to 1.0f),
        )

        val filtered = snapshot.filteredCoefficientInput(asOf = 200L)

        assertTrue(filtered.sets.isEmpty())
    }

    private fun set(id: Long, sessionId: Long, completedAt: Long?): WorkoutSet =
        WorkoutSet(
            id = id,
            sessionId = sessionId,
            exerciseId = 100,
            setNumber = 1,
            targetWeight = 100f,
            targetReps = 5,
            actualReps = 5,
            feedback = SetFeedback.RIR_2_4,
            completedAt = completedAt,
            durationSeconds = null,
        )
}
