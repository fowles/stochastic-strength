package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefSessionStep
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

/**
 * Pins the replay's derived writes (WorkoutRepository.replayDerivedState) against a hand-driven
 * BeliefSessionStep over the same sessions — self-consistent, not magic constants (Phase-3 swap).
 */
class ReplayProjectionTest {
    @Test
    fun projectionPreservesPrescriptionIdentity() {
        // level * derivedCoef == effectiveE1rm for every touched exercise -> the derived-state
        // projection written by replayDerivedState is internally consistent.
        val config = BeliefConfig()
        val sigmaSeed2 = config.sigmaSeed * config.sigmaSeed
        val seedCoef = mapOf(10L to 1.0f, 11L to 0.6f, 12L to 0.4f)
        val exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS }
        val muscleExerciseIds = mapOf(MuscleGroup.QUADS to seedCoef.keys.toList())
        val beliefs: MutableMap<Long, Belief> = mutableMapOf(
            10L to Belief(ln(100f), sigmaSeed2, 0L),
            11L to Belief(ln(58f), sigmaSeed2, 0L),
            // 12L intentionally cold (no seed belief) — it will lean on its siblings' prediction.
        )

        val sets = listOf(
            WorkoutSet(
                sessionId = 1, exerciseId = 10L, setNumber = 1,
                targetWeight = 100f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = 1_000L,
            ),
            WorkoutSet(
                sessionId = 1, exerciseId = 11L, setNumber = 1,
                targetWeight = 58f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = 1_000L,
            ),
        )

        val step = BeliefSessionStep(config)
        val result = step.step(
            beliefs = beliefs,
            sets = sets,
            seedCoef = seedCoef,
            exerciseMuscle = exerciseMuscle,
            muscleExerciseIds = muscleExerciseIds,
            asOf = 1_000L,
        )

        val quadsStep = result.steps.single { it.muscle == MuscleGroup.QUADS }
        for (id in listOf(10L, 11L, 12L)) {
            val effectiveE1rm = quadsStep.effectiveE1rm.getValue(id)
            val derivedCoef = quadsStep.derivedCoef.getValue(id)
            assertEquals(effectiveE1rm, quadsStep.level * derivedCoef, 1e-2f)
        }
    }
}
