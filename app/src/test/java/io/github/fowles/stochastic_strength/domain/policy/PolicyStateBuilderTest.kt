package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyStateBuilderTest {

    private val DAY = 24L * 60 * 60 * 1000

    private fun snapshot() = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST, 3L to MuscleGroup.CHEST),
        seedCoefficients = mapOf(1L to 1.0f, 2L to 0.6f, 3L to 0f), // 3 is unloadable
        exerciseEquipment = mapOf(1L to Equipment.BARBELL, 2L to Equipment.DUMBBELL, 3L to Equipment.BODYWEIGHT),
    )

    private fun set(
        exerciseId: Long, weight: Float, reps: Int, feedback: SetFeedback,
        actualReps: Int? = null, setNumber: Int = 1, completedAt: Long? = null,
    ) = WorkoutSet(
        sessionId = 1L, exerciseId = exerciseId, setNumber = setNumber, targetWeight = weight,
        targetReps = reps, actualReps = actualReps, feedback = feedback, completedAt = completedAt,
    )

    @Test
    fun failureCreatesClearCeilingAtRawTargetRep1rm() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 6)), snapshot())
        val c = b.build().ceilings.getValue(1L)
        assertEquals(DefaultProgressionEngine.rawToOneRepMax(80f, 10), c.ceilingE1rm, 1e-3f)
        assertTrue("shortfall of 4 reps is a clear miss", c.isClear)
        assertEquals(1_000L, c.sessionEndTime)
    }

    @Test
    fun oneRepShortfallIsMarginalAndUncountedIsClear() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 9)), snapshot())
        assertFalse("1-rep miss is marginal", b.build().ceilings.getValue(1L).isClear)

        val b2 = PolicyStateBuilder()
        b2.onSession(1_000L, listOf(set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = null)), snapshot())
        assertTrue("uncounted miss is clear", b2.build().ceilings.getValue(1L).isClear)
    }

    @Test
    fun ceilingIsMinOverFailedSetsAndSupersededByCleanSession() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(
            set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 6, setNumber = 1),
            set(1L, 70f, 10, SetFeedback.TOO_HARD, actualReps = 8, setNumber = 2),
        ), snapshot())
        assertEquals(DefaultProgressionEngine.rawToOneRepMax(70f, 10), b.build().ceilings.getValue(1L).ceilingE1rm, 1e-3f)

        // A newer session on the same exercise without failures clears the ceiling.
        b.onSession(2_000L, listOf(set(1L, 70f, 10, SetFeedback.RIR_0_1, actualReps = 10)), snapshot())
        assertNull(b.build().ceilings[1L])
    }

    @Test
    fun unloadableExercisesGetNoCeiling() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(set(3L, 0f, 10, SetFeedback.TOO_HARD)), snapshot())
        assertTrue(b.build().ceilings.isEmpty())
    }

    @Test
    fun hurtEventsAreDedupedPerMusclePerSession() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(
            set(1L, 80f, 10, SetFeedback.HURT, setNumber = 1),
            set(2L, 30f, 10, SetFeedback.HURT, setNumber = 1),
        ), snapshot())
        assertEquals(listOf(HurtEvent(MuscleGroup.CHEST, 1_000L)), b.build().hurtEvents)
    }

    @Test
    fun muscleStressTracksTooHardAndPerExerciseRir01ButNotBodyweight() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(
            set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 6, completedAt = 900L),
            set(2L, 30f, 10, SetFeedback.RIR_0_1, setNumber = 1, completedAt = 910L),
            set(2L, 30f, 10, SetFeedback.RIR_0_1, setNumber = 2, completedAt = 920L),
            set(3L, 0f, 10, SetFeedback.TOO_HARD, completedAt = 930L), // bodyweight: exempt
        ), snapshot())
        val s = b.build().muscleStress.getValue(MuscleGroup.CHEST)
        assertEquals(listOf(900L), s.tooHardTimes)
        assertEquals(listOf(910L, 920L), s.rir01TimesByExercise.getValue(2L))
    }

    @Test
    fun stressOlderThanSevenDaysIsPruned() {
        val b = PolicyStateBuilder()
        b.onSession(0L, listOf(set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 6, completedAt = 0L)), snapshot())
        b.onSession(8 * DAY, listOf(set(2L, 30f, 10, SetFeedback.RIR_0_1, completedAt = 8 * DAY)), snapshot())
        val s = b.build().muscleStress.getValue(MuscleGroup.CHEST)
        assertTrue("old TOO_HARD pruned", s.tooHardTimes.isEmpty())
    }
}
