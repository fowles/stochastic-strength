package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class BeliefSessionStepTest {
    private val config = BeliefConfig()
    private val step = BeliefSessionStep(config)

    private fun set(id: Long, exerciseId: Long, weight: Float, reps: Int, feedback: SetFeedback?) = WorkoutSet(
        id = id, sessionId = 1L, exerciseId = exerciseId, setNumber = id.toInt(),
        targetWeight = weight, targetReps = reps, feedback = feedback, completedAt = 1_000L,
    )

    @Test
    fun foldMatchesBeliefFoldForAnExistingBelief() {
        val beliefs = mutableMapOf(7L to Belief(ln(100f), 0.01f, 0L))
        val expected = BeliefFold(config).foldSession(
            beliefs.getValue(7L), listOf(set(1, 7L, 90f, 5, SetFeedback.RIR_2_4)), asOf = 86_400_000L)
        step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 7L, 90f, 5, SetFeedback.RIR_2_4)),
            seedCoef = mapOf(7L to 1f),
            exerciseMuscle = mapOf(7L to MuscleGroup.QUADS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to listOf(7L)),
            asOf = 86_400_000L,
        )
        assertEquals(expected.bestGuessLn, beliefs.getValue(7L).bestGuessLn, 1e-6f)
        assertEquals(expected.uncertainty, beliefs.getValue(7L).uncertainty, 1e-6f)
    }

    @Test
    fun coldExerciseFoldsAgainstTheSiblingPredictionAsPrior() {
        // Trained sibling 1 (coef 1.0) at ln(100); cold target 2 (coef 0.5) has no belief.
        val beliefs = mutableMapOf(1L to Belief(ln(100f), 0.01f, 1_000L))
        val result = step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 2L, 40f, 5, SetFeedback.RIR_2_4)),
            seedCoef = mapOf(1L to 1f, 2L to 0.5f),
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS, 2L to MuscleGroup.QUADS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to listOf(1L, 2L)),
            asOf = 1_000L,
        )
        // The pre-fold effective for the cold exercise is the sibling prediction…
        val pre = result.preFoldEffective.getValue(2L)
        assertEquals(ln(0.5f) + ln(100f), pre.bestGuessLn, 1e-4f)
        // …and after the step the cold exercise HAS a belief (folded from that prior).
        assertTrue(2L in beliefs)
    }

    @Test
    fun untouchedMusclesGetNeitherAPostFoldStepNorPreFoldEffective() {
        val beliefs = mutableMapOf(
            1L to Belief(ln(100f), 0.01f, 1_000L), // QUADS, trained this session
            9L to Belief(ln(50f), 0.01f, 1_000L),  // BICEPS, not in this session
        )
        val result = step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 1L, 90f, 5, SetFeedback.RIR_0_1)),
            seedCoef = mapOf(1L to 1f, 9L to 0.6f),
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS, 9L to MuscleGroup.BICEPS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to listOf(1L), MuscleGroup.BICEPS to listOf(9L)),
            asOf = 1_000L,
        )
        assertEquals(listOf(MuscleGroup.QUADS), result.steps.map { it.muscle })
        // Pre-fold pooling is scoped to the session's muscles; untouched muscles are not pooled
        // (consumers needing them pool directly — pooling is a pure read).
        assertTrue(1L in result.preFoldEffective)
        assertTrue(9L !in result.preFoldEffective)
    }

    @Test
    fun postFoldStepReportsLevelEffectiveAndDerivedCoef() {
        val beliefs = mutableMapOf(1L to Belief(ln(100f), 0.01f, 1_000L))
        val result = step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 1L, 90f, 5, SetFeedback.RIR_2_4)),
            seedCoef = mapOf(1L to 1f),
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to listOf(1L)),
            asOf = 1_000L,
        )
        val quads = result.steps.single()
        val eff = quads.effectiveE1rm.getValue(1L)
        // Single voter, coef 1: level == effective e1rm, derived coef == 1.
        assertEquals(eff, quads.level, 1e-3f)
        assertEquals(1f, quads.derivedCoef.getValue(1L), 1e-4f)
        // Post-fold: the RIR_2_4 fold ran before this projection.
        assertEquals(exp(beliefs.getValue(1L).bestGuessLn), eff, 1e-3f)
    }

    @Test
    fun zeroCoefExercisesAreSkippedEntirely() {
        val beliefs = mutableMapOf<Long, Belief>()
        val result = step.step(
            beliefs = beliefs,
            sets = listOf(set(1, 3L, 40f, 5, SetFeedback.RIR_2_4)),
            seedCoef = mapOf(3L to 0f),
            exerciseMuscle = mapOf(3L to MuscleGroup.QUADS),
            muscleExerciseIds = mapOf(MuscleGroup.QUADS to emptyList()),
            asOf = 1_000L,
        )
        assertTrue(beliefs.isEmpty())
        assertTrue(result.steps.isEmpty())
    }
}
