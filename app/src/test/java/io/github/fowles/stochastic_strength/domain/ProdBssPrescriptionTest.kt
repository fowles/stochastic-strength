package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilder
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimate
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepper
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end regression from the prod backup pulled 2026-06-24 (the Bulgarian-Split-Squat
 * over-prescription bug). Replays that user's QUADS history exactly as production does and pins the
 * projected BSS (exerciseId 55) prescription at 10 reps.
 *
 * The user's last BSS session demonstrated a 10-rep capacity of 9.07 kg (20 lb) at RIR_0_1 after
 * failing heavier weights. Before the fixes the app re-prescribed 25 lb — above demonstrated
 * capacity — because (a) the entrenched estimate failed to track down to the demonstrated ceiling
 * and (b) read-time pooling lifted it toward stronger same-session/older siblings. With both fixes
 * the prescription settles at the demonstrated 20 lb.
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

    @Test
    fun reportBssPrescription() {
        val exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS }
        val snapshot = ReplaySnapshot(exerciseMuscle = exerciseMuscle, seedCoefficients = seedCoef)
        for ((id, e1rm) in initials) snapshot.currentEstimates[id] = ExerciseEstimate.seed(e1rm, at = 0)

        val stepper = SessionProgressionStepper()
        for (sessionId in listOf(12L, 14L, 15L, 16L, 18L)) {
            stepper.step(sets.filter { it.sessionId == sessionId }, snapshot, endTimes[sessionId]!!)
        }

        val proj = MuscleStrengthProjector().project(
            estimates = snapshot.currentEstimates,
            seedCoef = seedCoef,
            muscleExerciseIds = seedCoef.keys.toList(),
            now = EXPORTED_AT,
        )

        val effE1rm = proj.effectiveE1rm.getValue(55L)
        val sessionWeightKg = DefaultProgressionEngine.fromOneRepMax(effE1rm, 10)
        val prescribedLbs = WeightUnit.LBS.fromKg(WeightFormatter.round(sessionWeightKg, WeightUnit.LBS))
            .toInt()

        // Demonstrated 10-rep capacity was 9.07 kg (20 lb) at RIR_0_1; the prescription must not exceed
        // it. Pre-fix this was 25 lb.
        assertEquals("BSS @10 reps should be prescribed at the demonstrated 20 lb", 20, prescribedLbs)
    }

    @Test
    fun policyPathAlsoPrescribesTheDemonstrated20lb() {
        val exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS }
        val snapshot = ReplaySnapshot(exerciseMuscle = exerciseMuscle, seedCoefficients = seedCoef)
        for ((id, e1rm) in initials) snapshot.currentEstimates[id] = ExerciseEstimate.seed(e1rm, at = 0)

        val stepper = SessionProgressionStepper()
        val builder = PolicyStateBuilder()
        for (sessionId in listOf(12L, 14L, 15L, 16L, 18L)) {
            val sessionSets = sets.filter { it.sessionId == sessionId }
            stepper.step(sessionSets, snapshot, endTimes[sessionId]!!)
            builder.onSession(endTimes[sessionId]!!, sessionSets, snapshot)
        }

        val proj = MuscleStrengthProjector().project(
            estimates = snapshot.currentEstimates,
            seedCoef = seedCoef,
            muscleExerciseIds = seedCoef.keys.toList(),
            now = EXPORTED_AT,
        )
        val policy = PrescriptionPolicy(
            pooledE1rm = proj.effectiveE1rm,
            state = builder.build(),
            config = EstimatorConfig(),
            progressionEngine = DefaultProgressionEngine,
            weightUnit = WeightUnit.LBS,
            nowMs = EXPORTED_AT,
        )
        val bss = Exercise(id = 55L, name = "Bulgarian Split Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.DUMBBELL)
        val weightKg = policy.prescribe(bss, 10)!!
        // The session-18 clear ceiling (~25.3 kg 1RM) sits ABOVE the demonstrated-capacity target
        // (~16.9 kg 1RM), so it must not bind: the estimator's 20 lb answer passes through.
        assertEquals(20, WeightUnit.LBS.fromKg(weightKg).toInt())
    }
}
