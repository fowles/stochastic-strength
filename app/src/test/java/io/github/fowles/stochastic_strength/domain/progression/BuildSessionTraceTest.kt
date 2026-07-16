package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class BuildSessionTraceTest {
    private val config = BeliefConfig()

    private fun completedSet(exerciseId: Long, weight: Float, reps: Int, actual: Int, fb: SetFeedback, at: Long) =
        WorkoutSet(
            sessionId = at, exerciseId = exerciseId, setNumber = 1,
            targetWeight = weight, targetReps = reps, actualReps = actual,
            feedback = fb, completedAt = at,
        )

    @Test
    fun capBindsWhenPriorSessionFailedTheWantedWeight() {
        // Belief wants a weight that exceeds the failed cap; a recent failed (TOO_HARD) session at
        // a lower weight must surface a binding capacity cap in the trace. The belief mu = ln(60f)
        // produces an uncapped prescription > 35 kg, so the TOO_HARD at 35 kg caps it.
        val beliefs = mapOf(1L to Belief(mu = ln(60f), sigma2 = 0.005f, updatedAt = 0L))
        val now = 100_000_000L
        val priorSets = listOf(
            completedSet(1L, weight = 35f, reps = 10, actual = 6, fb = SetFeedback.TOO_HARD, at = now - 1_000L),
        )
        val trace = buildSessionTrace(
            targetId = 1L,
            muscle = MuscleGroup.CHEST,
            beliefs = beliefs,
            seedCoef = mapOf(1L to 0.3f),
            muscleExerciseIds = listOf(1L),
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST),
            priorSets = priorSets,
            sessionReps = 10,
            now = now,
            weightUnit = WeightUnit.KG,
            config = config,
        )
        assertNotNull(trace)
        // The capacity-cap line reports a binding cap (mentions "capped").
        assertTrue(trace!!.lines.any { it.label == "Capacity cap" && it.detail.contains("capped") })
    }

    @Test
    fun setsOutsideTheFactsWindowDoNotFormFacts() {
        val beliefs = mapOf(1L to Belief(mu = ln(60f), sigma2 = 0.005f, updatedAt = 0L))
        val now = 100_000_000L
        // A failed session OLDER than the facts window must be ignored (no binding cap).
        val stale = now - PrescriptionPolicy.FACTS_WINDOW_MS - 1_000L
        val priorSets = listOf(
            completedSet(1L, weight = 35f, reps = 10, actual = 6, fb = SetFeedback.TOO_HARD, at = stale),
        )
        val trace = buildSessionTrace(
            targetId = 1L, muscle = MuscleGroup.CHEST, beliefs = beliefs,
            seedCoef = mapOf(1L to 0.3f), muscleExerciseIds = listOf(1L),
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST), priorSets = priorSets,
            sessionReps = 10, now = now, weightUnit = WeightUnit.KG, config = config,
        )
        assertNotNull(trace)
        assertTrue(trace!!.lines.any { it.label == "Capacity cap" && it.detail == "no cap" })
    }

    @Test
    fun emptyPriorSetsProducesNoCap() {
        val beliefs = mapOf(1L to Belief(mu = ln(60f), sigma2 = 0.005f, updatedAt = 0L))
        val now = 100_000_000L
        // With no prior sets, there are no failed sessions to form a capacity cap.
        val priorSets = emptyList<WorkoutSet>()
        val trace = buildSessionTrace(
            targetId = 1L, muscle = MuscleGroup.CHEST, beliefs = beliefs,
            seedCoef = mapOf(1L to 0.3f), muscleExerciseIds = listOf(1L),
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST), priorSets = priorSets,
            sessionReps = 10, now = now, weightUnit = WeightUnit.KG, config = config,
        )
        assertNotNull(trace)
        assertTrue(trace!!.lines.any { it.label == "Capacity cap" && it.detail == "no cap" })
    }
}
