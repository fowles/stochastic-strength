package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.BeliefPrescriber
import io.github.fowles.stochastic_strength.domain.belief.BeliefSessionStep
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * Clamp-behavior invariant from the prod backup pulled 2026-06-24 (the Bulgarian-Split-Squat
 * over-prescription bug). The spec retires the magic-number 20 lb pin; what must hold is the
 * policy invariant: never prescribe at-or-above a weight failed in the exercise's most recent
 * session (session 18 failed 24.95 kg and 15.88 kg at 10 reps) — regardless of what the raw
 * estimator says. The estimator's raw quality is scored by the backtest harness, not here.
 */
class ProdBssPrescriptionTest {

    private val EXPORTED_AT = 1782335930209L

    // Loaded QUADS seed coefficients (from ExerciseCoefficients).
    private val seedCoef: Map<Long, Float> = mapOf(
        48L to 1.00f,  // Barbell Squat
        49L to 0.80f,  // Front Squat
        50L to 2.50f,  // Leg Press
        51L to 0.45f,  // Leg Extension
        52L to 1.80f,  // Hack Squat
        54L to 0.35f,  // Goblet Squat
        55L to 0.30f,  // Bulgarian Split Squat
        56L to 0.20f,  // Step-Up
        100L to 0.25f, // Dumbbell Lunge
    )

    // Initial per-exercise strength overrides (sessionId == null, asOf == 0).
    private val initials: Map<Long, Float> = mapOf(
        48L to 127.00601959228516f,
        49L to 101.60482025146484f,
        50L to 317.5150451660156f,
        51L to 57.152706146240234f,
        52L to 228.61082458496094f,
        54L to 44.45210647583008f,
        55L to 38.101806640625f,
        56L to 25.40120506286621f,
        100L to 31.75150489807129f,
    )

    private val endTimes = mapOf(12L to 1781042671267L, 14L to 1781295722585L, 15L to 1781377407383L, 16L to 1781557799201L, 18L to 1781812212443L)

    private val sets = listOf(
        WorkoutSet(sessionId = 12, exerciseId = 54, setNumber = 1, targetWeight = 15.875752449035645f, targetReps = 10, actualReps = 10, feedback = SetFeedback.RIR_5_PLUS),
        WorkoutSet(sessionId = 12, exerciseId = 54, setNumber = 2, targetWeight = 15.875752449035645f, targetReps = 10, actualReps = 10, feedback = SetFeedback.RIR_5_PLUS),
        WorkoutSet(sessionId = 12, exerciseId = 54, setNumber = 3, targetWeight = 15.875752449035645f, targetReps = 10, actualReps = 10, feedback = SetFeedback.RIR_5_PLUS),
        WorkoutSet(sessionId = 14, exerciseId = 54, setNumber = 1, targetWeight = 22.67964744567871f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_5_PLUS),
        WorkoutSet(sessionId = 14, exerciseId = 54, setNumber = 2, targetWeight = 22.67964744567871f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_5_PLUS),
        WorkoutSet(sessionId = 14, exerciseId = 54, setNumber = 3, targetWeight = 22.67964744567871f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_5_PLUS),
        WorkoutSet(sessionId = 15, exerciseId = 100, setNumber = 1, targetWeight = 24.94761085510254f, targetReps = 5, actualReps = 2, feedback = SetFeedback.TOO_HARD),
        WorkoutSet(sessionId = 15, exerciseId = 100, setNumber = 2, targetWeight = 20.41168212890625f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1),
        WorkoutSet(sessionId = 15, exerciseId = 100, setNumber = 3, targetWeight = 20.41168212890625f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1),
        WorkoutSet(sessionId = 16, exerciseId = 55, setNumber = 1, targetWeight = 29.48354148864746f, targetReps = 10, actualReps = 2, feedback = SetFeedback.TOO_HARD),
        WorkoutSet(sessionId = 16, exerciseId = 55, setNumber = 2, targetWeight = 20.41168212890625f, targetReps = 10, actualReps = 7, feedback = SetFeedback.TOO_HARD),
        WorkoutSet(sessionId = 16, exerciseId = 55, setNumber = 3, targetWeight = 18.14371681213379f, targetReps = 10, actualReps = 6, feedback = SetFeedback.TOO_HARD),
        WorkoutSet(sessionId = 18, exerciseId = 54, setNumber = 1, targetWeight = 29.48354148864746f, targetReps = 10, actualReps = 10, feedback = SetFeedback.RIR_5_PLUS),
        WorkoutSet(sessionId = 18, exerciseId = 54, setNumber = 2, targetWeight = 29.48354148864746f, targetReps = 10, actualReps = 10, feedback = SetFeedback.RIR_2_4),
        WorkoutSet(sessionId = 18, exerciseId = 54, setNumber = 3, targetWeight = 29.48354148864746f, targetReps = 10, actualReps = 10, feedback = SetFeedback.RIR_0_1),
        WorkoutSet(sessionId = 18, exerciseId = 55, setNumber = 1, targetWeight = 24.94761085510254f, targetReps = 10, actualReps = 2, feedback = SetFeedback.TOO_HARD),
        WorkoutSet(sessionId = 18, exerciseId = 55, setNumber = 2, targetWeight = 15.875752449035645f, targetReps = 10, actualReps = 2, feedback = SetFeedback.TOO_HARD),
        WorkoutSet(sessionId = 18, exerciseId = 55, setNumber = 3, targetWeight = 9.071858406066895f, targetReps = 10, actualReps = 10, feedback = SetFeedback.RIR_0_1),
    )

    private val LIGHTEST_FAILED_KG = 15.875752449035645f  // session 18, set 2

    private fun bssFacts(): PolicyFacts =
        PolicyFacts.build(
            sets = sets.map { it.copy(completedAt = endTimes[it.sessionId]) },
            exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS },
        )

    private fun policyWeightKg(rawE1rm: Float): Float =
        PrescriptionPolicy.prescribe(
            rawE1rm = rawE1rm, sessionReps = 10, exerciseId = 55L, muscle = MuscleGroup.QUADS,
            facts = bssFacts(), now = EXPORTED_AT, weightUnit = WeightUnit.LBS,
            engine = DefaultProgressionEngine, overloadNudge = true,
        ).weightKg

    @Test
    fun bssPrescriptionStaysStrictlyBelowTheMostRecentFailedWeight() {
        // Full replay of the prod history through the belief stack (Phase-3 swap):
        val exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS }
        val muscleExerciseIds = mapOf(MuscleGroup.QUADS to seedCoef.keys.toList())
        val beliefConfig = BeliefConfig()
        val sigmaSeed2 = beliefConfig.sigmaSeed * beliefConfig.sigmaSeed
        val beliefs: MutableMap<Long, Belief> = initials.mapValuesTo(mutableMapOf()) { (_, e1rm) ->
            Belief(ln(e1rm), sigmaSeed2, 0L)
        }
        val step = BeliefSessionStep(beliefConfig)
        for (sessionId in listOf(12L, 14L, 15L, 16L, 18L)) {
            step.step(
                beliefs = beliefs,
                sets = sets.filter { it.sessionId == sessionId },
                seedCoef = seedCoef,
                exerciseMuscle = exerciseMuscle,
                muscleExerciseIds = muscleExerciseIds,
                asOf = endTimes[sessionId]!!,
            )
        }

        val pooling = BeliefPooling(beliefConfig)
        val effective = pooling.effective(
            beliefs = beliefs, seedCoef = seedCoef, muscleExerciseIds = seedCoef.keys.toList(), now = EXPORTED_AT,
        ).effective
        val rawE1rm = BeliefPrescriber.targetE1rm(effective.getValue(55L))

        val prescribed = policyWeightKg(rawE1rm)
        assertTrue(
            "policy prescription $prescribed kg must be strictly below the failed $LIGHTEST_FAILED_KG kg",
            prescribed < LIGHTEST_FAILED_KG,
        )
        assertTrue("must still prescribe something", prescribed > 0f)
    }

    @Test
    fun seatbeltHoldsEvenIfTheEstimatorRegressesToItsEntrenchedSeed() {
        // The scenario that produced three estimator mechanisms on the abandoned branch: an
        // entrenched high estimate (the 38.1 kg seed). Policy alone must contain it.
        val prescribed = policyWeightKg(38.101806640625f)
        assertTrue(
            "capped prescription $prescribed kg must be strictly below the failed $LIGHTEST_FAILED_KG kg",
            prescribed < LIGHTEST_FAILED_KG,
        )
    }
}
