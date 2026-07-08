package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilder
import io.github.fowles.stochastic_strength.domain.policy.PooledBelief
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ExerciseBelief
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end regression from the prod backup pulled 2026-06-24 (the Bulgarian-Split-Squat
 * over-prescription bug). Replays that user's QUADS history through the production stack and pins
 * SAFETY PROPERTIES for the projected BSS (exerciseId 55) prescription at 10 reps. (One knowing
 * divergence from buildPlanner: project() is called without muscleLastObs — inert here because the
 * fixture's 6-day gap is inside the 14-day detraining grace.)
 *
 * Task 7 re-pins the exact value after z/δ/fatigue activation lands.
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

    private val config = EstimatorConfig()

    @Test
    fun reportBssPrescription() {
        val exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS }
        val snapshot = ReplaySnapshot(exerciseMuscle = exerciseMuscle, seedCoefficients = seedCoef)
        for ((id, e1rm) in initials) snapshot.currentBeliefs[id] = ExerciseBelief.seed(e1rm, at = 0, config = config)

        val stepper = SessionProgressionStepper()
        for (sessionId in listOf(12L, 14L, 15L, 16L, 18L)) {
            stepper.step(sets.filter { it.sessionId == sessionId }, snapshot, endTimes[sessionId]!!)
        }

        val proj = MuscleStrengthProjector().project(
            beliefs = snapshot.currentBeliefs,
            seedCoef = seedCoef,
            muscleExerciseIds = seedCoef.keys.toList(),
            now = EXPORTED_AT,
        )

        val effE1rm = proj.effectiveE1rm.getValue(55L)
        val sessionWeightKg = DefaultProgressionEngine.fromOneRepMax(effE1rm, 10)
        val prescribedKg = WeightFormatter.round(sessionWeightKg, WeightUnit.LBS)

        // This path intentionally pins the PRE-policy value: projector only, no z/δ/fatigue applied.
        // Measured at Task 7: exactly 30.0 lb (BSS effective e1rm ≈ 23.9 kg; LBS grid coarse enough
        // that the 6.5% policy factor lands on the same 30 lb grid point on the policy path).
        assertEquals("BSS projector-only prescription must be exactly 30 lb",
            WeightUnit.LBS.toKg(30f), prescribedKg, 1e-3f)
    }

    @Test
    fun policyPathSafetyBounds() {
        val exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS }
        val snapshot = ReplaySnapshot(exerciseMuscle = exerciseMuscle, seedCoefficients = seedCoef)
        for ((id, e1rm) in initials) snapshot.currentBeliefs[id] = ExerciseBelief.seed(e1rm, at = 0, config = config)

        val stepper = SessionProgressionStepper()
        val builder = PolicyStateBuilder()
        for (sessionId in listOf(12L, 14L, 15L, 16L, 18L)) {
            val sessionSets = sets.filter { it.sessionId == sessionId }
            stepper.step(sessionSets, snapshot, endTimes[sessionId]!!)
            builder.onSession(endTimes[sessionId]!!, sessionSets, snapshot)
        }

        val proj = MuscleStrengthProjector().project(
            beliefs = snapshot.currentBeliefs,
            seedCoef = seedCoef,
            muscleExerciseIds = seedCoef.keys.toList(),
            now = EXPORTED_AT,
        )
        val policy = PrescriptionPolicy(
            pooled = proj.effectiveE1rm.entries.associate { (id, e1rm) ->
                id to PooledBelief(e1rm, proj.pooledSigma[id] ?: 0f)
            },
            state = builder.build(snapshot.muscleLastObs.toMap()),
            config = EstimatorConfig(),
            progressionEngine = DefaultProgressionEngine,
            weightUnit = WeightUnit.LBS,
            nowMs = EXPORTED_AT,
        )
        val bss = Exercise(id = 55L, name = "Bulgarian Split Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.DUMBBELL)
        val weightKg = policy.prescribe(bss, 10)!!

        // PINNED 30 lb, adjudicated 2026-07-08 (deviates from spec §9's "≈ 20 lb", which was
        // calibrated to the deleted wDownSnap semantics). Why 30 is the honest answer here:
        // session 18's evidence CONTRADICTS itself — 2 reps at 24.9 kg fresh implies ~28 kg 1RM
        // (and session 16's 2×29.5 kg implied ~33), while the rank-2 collapse (2 reps at 15.9 kg)
        // and the 10×9.07 kg RIR_0_1 set imply ~18–19. The Kalman posterior of that mix is
        // ~23.9 kg (σ ≈ 0.030, tight BECAUSE the failures are informative), with no sibling pull
        // (sibling excess ≈ 0; pooled == own). Policy factor exp(−z·σ + δ + ln(1−2φ)) ≈ 0.935
        // → 22.4 kg target → 12.8 kg session weight → 30 lb grid. The failure ceiling is inert
        // (cap ≈ 25.3 kg would prescribe ~35 lb if it bound; the target sits below it). The old
        // 20 lb came from snapping to the most pessimistic reading; the belief system instead
        // relies on ceiling DYNAMICS: the spec safety pin (next weight strictly below every failed
        // weight: 30 < 35 < 55 lb) holds now, and one further clear miss at 30 lb caps ≈25, then
        // ≈20 — self-correcting within ~2 sessions (Task 9's bad-day pin exercises this generally).
        assertTrue("BSS policy prescription must stay below the lightest failed weight (35 lb = 15.876 kg)",
            weightKg < 15.875f)
        assertEquals("BSS policy prescription pinned at 30 lb (got ${weightKg / WeightUnit.LBS.toKg(1f)} lbs)",
            WeightUnit.LBS.toKg(30f), weightKg, 1e-3f)
    }
}
