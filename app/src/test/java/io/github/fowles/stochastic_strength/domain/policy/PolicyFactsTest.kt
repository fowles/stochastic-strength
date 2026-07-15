package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyFactsTest {

    private val DAY = 24L * 60 * 60 * 1000

    private fun set(
        sessionId: Long,
        exerciseId: Long = 1L,
        feedback: SetFeedback? = SetFeedback.RIR_0_1,
        w: Float = 100f,
        r: Int = 10,
        a: Int? = null,
        at: Long? = sessionId * DAY,
    ) = WorkoutSet(sessionId = sessionId, exerciseId = exerciseId, setNumber = 1, targetWeight = w, targetReps = r, actualReps = a, feedback = feedback, completedAt = at)

    private val muscles = mapOf(1L to MuscleGroup.QUADS, 2L to MuscleGroup.QUADS, 3L to MuscleGroup.CHEST)

    @Test
    fun capComesFromTheMostRecentFeedbackSessionOnly() {
        val facts = PolicyFacts.build(
            listOf(
                set(sessionId = 1, feedback = SetFeedback.TOO_HARD, w = 35f, a = 2),
                set(sessionId = 2, feedback = SetFeedback.RIR_0_1, w = 20f),
            ),
            muscles,
        )
        val fact = facts.capByExercise.getValue(1L)
        // Newer clean session supersedes the older failure entirely.
        assertEquals(PrescriptionPolicy.capLnFor(listOf(set(sessionId = 2, feedback = SetFeedback.RIR_0_1, w = 20f)))!!, fact.capLn!!, 1e-6f)
        assertEquals(2 * DAY, fact.demonstratedAt)
    }

    @Test
    fun allEasySessionYieldsAnUncappedFactThatSupersedesOlderCaps() {
        val facts = PolicyFacts.build(
            listOf(
                set(sessionId = 1, feedback = SetFeedback.TOO_HARD, w = 35f, a = 2),
                set(sessionId = 2, feedback = SetFeedback.RIR_5_PLUS, w = 20f),
            ),
            muscles,
        )
        val fact = facts.capByExercise.getValue(1L)
        assertNull(fact.capLn)  // present but uncapped: the clean easy session cleared it
    }

    @Test
    fun hurtOnlySessionDoesNotSupersedeACapAndFeedbacklessSetsAreIgnored() {
        val facts = PolicyFacts.build(
            listOf(
                set(sessionId = 1, feedback = SetFeedback.TOO_HARD, w = 35f, a = 2),
                set(sessionId = 2, feedback = SetFeedback.HURT),
                set(sessionId = 3, feedback = null),
                set(sessionId = 4, exerciseId = 3L, feedback = SetFeedback.RIR_0_1),  // other exercise
            ),
            muscles,
        )
        val fact = facts.capByExercise.getValue(1L)
        assertEquals(1 * DAY, fact.demonstratedAt)  // still the failure session
        assertEquals(PrescriptionPolicy.capLnFor(listOf(set(sessionId = 1, feedback = SetFeedback.TOO_HARD, w = 35f, a = 2)))!!, fact.capLn!!, 1e-6f)
    }

    @Test
    fun hurtEventsGroupByMuscleOnePerSession() {
        val facts = PolicyFacts.build(
            listOf(
                set(sessionId = 1, exerciseId = 1L, feedback = SetFeedback.HURT, at = 100L),
                set(sessionId = 1, exerciseId = 2L, feedback = SetFeedback.HURT, at = 200L),  // same muscle+session → one event
                set(sessionId = 2, exerciseId = 1L, feedback = SetFeedback.HURT, at = 300L),
                set(sessionId = 2, exerciseId = 3L, feedback = SetFeedback.HURT, at = 400L),  // CHEST
            ),
            muscles,
        )
        assertEquals(listOf(200L, 300L), facts.hurtEventsByMuscle.getValue(MuscleGroup.QUADS).sorted())
        assertEquals(listOf(400L), facts.hurtEventsByMuscle.getValue(MuscleGroup.CHEST))
    }

    @Test
    fun setsWithoutCompletedAtAreIgnored() {
        val facts = PolicyFacts.build(listOf(set(sessionId = 1, at = null)), muscles)
        assertTrue(facts.capByExercise.isEmpty())
        assertTrue(facts.hurtEventsByMuscle.isEmpty())
    }

    @Test
    fun timestampTieBreaksByHigherSessionId() {
        // Two sessions share the same completedAt; the higher sessionId is the newer session.
        // Session 1 (TOO_HARD) is listed first, so a naive first-max-wins tie-break would fail this test.
        val facts = PolicyFacts.build(
            listOf(
                set(sessionId = 1, feedback = SetFeedback.TOO_HARD, w = 35f, a = 2, at = 100L),
                set(sessionId = 2, feedback = SetFeedback.RIR_5_PLUS, at = 100L),   // uncapped, session 2 wins the tie
            ),
            muscles,
        )
        assertNull(facts.capByExercise.getValue(1L).capLn)  // session 2 wins the tie
    }

    @Test
    fun allEasyIsTrueOnlyWhenEveryFeedbackSetOfTheLatestSessionIsRir2Plus() {
        // Session 1 (older): all easy. Session 2 (newer): contains an RIR_0_1 → allEasy = false.
        val sets = listOf(
            set(sessionId = 1, exerciseId = 7L, feedback = SetFeedback.RIR_2_4, at = 1_000L),
            set(sessionId = 1, exerciseId = 7L, feedback = SetFeedback.RIR_5_PLUS, at = 2_000L),
            set(sessionId = 2, exerciseId = 7L, feedback = SetFeedback.RIR_0_1, at = 9_000L),
            set(sessionId = 2, exerciseId = 7L, feedback = SetFeedback.RIR_2_4, at = 9_500L),
        )
        val facts = PolicyFacts.build(sets, muscles + (7L to MuscleGroup.QUADS))
        assertFalse(facts.capByExercise.getValue(7L).allEasy)

        // Only the older session → allEasy = true.
        val factsEasy = PolicyFacts.build(sets.filter { it.sessionId == 1L }, muscles + (7L to MuscleGroup.QUADS))
        assertTrue(factsEasy.capByExercise.getValue(7L).allEasy)
    }

    @Test
    fun aHurtSetVetoesAllEasy() {
        val sets = listOf(
            set(sessionId = 1, exerciseId = 7L, feedback = SetFeedback.RIR_2_4, at = 1_000L),
            set(sessionId = 1, exerciseId = 7L, feedback = SetFeedback.HURT, at = 2_000L),
        )
        val facts = PolicyFacts.build(sets, muscles + (7L to MuscleGroup.QUADS))
        assertFalse(facts.capByExercise.getValue(7L).allEasy)
    }
}
