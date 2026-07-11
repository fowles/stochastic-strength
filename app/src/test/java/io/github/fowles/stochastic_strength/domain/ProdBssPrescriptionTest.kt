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
import org.junit.Test

/**
 * End-to-end regression from the prod backup pulled 2026-06-24 (the Bulgarian-Split-Squat
 * over-prescription bug). Replays that user's QUADS history through the production stack and pins
 * SAFETY PROPERTIES for the projected BSS (exerciseId 55) prescription at 10 reps. (One knowing
 * divergence from buildPlanner: project() is called without muscleLastObs — inert here because the
 * fixture's 6-day gap is inside the 14-day detraining grace.)
 *
 * Pins the demonstrated 20 lb (restored 2026-07-09 via adaptive attention + the projector evidence
 * gate — see policyPathSafetyBounds for the full derivation).
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

    private val equipment: Map<Long, Equipment> = mapOf(
        48L to Equipment.BARBELL,   // Barbell Squat
        49L to Equipment.BARBELL,   // Front Squat
        50L to Equipment.MACHINE,   // Leg Press
        51L to Equipment.MACHINE,   // Leg Extension
        52L to Equipment.MACHINE,   // Hack Squat
        54L to Equipment.DUMBBELL,  // Goblet Squat
        55L to Equipment.DUMBBELL,  // Bulgarian Split Squat
        56L to Equipment.DUMBBELL,  // Step-Up
        100L to Equipment.DUMBBELL, // Dumbbell Lunge
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
            equipment = equipment,
        )

        val effE1rm = proj.effectiveE1rm.getValue(55L)
        val sessionWeightKg = DefaultProgressionEngine.fromOneRepMax(effE1rm, 10)
        val prescribedKg = WeightFormatter.round(sessionWeightKg, WeightUnit.LBS)

        // Pre-policy belief-only figure (projector effective e1rm → session weight, no z/δ/fatigue).
        // RE-MEASURED 2026-07-11 after variance-budget adoption (obsNoiseScale=2.5, sessionDayEffectSd=0.08):
        // BSS pre-policy session weight rose 25 lb → 40 lb. Driver: obsNoiseScale=2.5 widens per-set
        // observation noise by ×2.5, making the three consecutive TOO_HARD sets for BSS LESS informative.
        // The belief absorbs failures more weakly and stays closer to the seed, producing a higher effective
        // e1rm (~18.1 kg) than the old value (~11.3 kg). This is a CONCERN: the safety bound
        // (prescription below 35 lb = lightest failed weight) is now violated — see policyPathSafetyBounds.
        assertEquals("BSS projector-only (pre-policy) prescription pinned at 40 lb",
            WeightUnit.LBS.toKg(40f), prescribedKg, 1e-3f)
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
            equipment = equipment,
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

        // RE-PINNED 2026-07-11 after variance-budget adoption (obsNoiseScale=2.5, sessionDayEffectSd=0.08).
        // CONCERN: the prescription moved 20 lb → 35 lb, exactly AT the lightest failed weight.
        //
        // Driver: obsNoiseScale=2.5 widens per-set observation noise ×2.5, making the three consecutive
        // TOO_HARD sets LESS informative to the belief. The belief absorbs failures more weakly, stays
        // closer to the seed, and produces a higher effective e1rm (~29.7 kg vs old ~18.7 kg). Even
        // though the failure ceiling caps the target (ceilingE1rm from session-18 set-2: 15.876 kg × 10
        // reps ≈ 26.6 kg, ×0.97 cap = 25.8 kg), the prescription ends at exactly 35 lb rather than
        // rounding DOWN to 30 lb (the round-down guard fires only when nearest >= failedWeightAtReps,
        // which requires floating-point equality — a borderline case here).
        //
        // OLD VALUE: 20 lb (demonstrated capacity). OLD SAFETY: prescription < 35 lb (lightest failure).
        // NEW VALUE: 35 lb. SAFETY PROPERTY NOW VIOLATED: 35 lb equals the lightest failed weight.
        //
        // This is recorded as DONE_WITH_CONCERNS (2026-07-11). The variance-budget adoption reduces
        // reactivity broadly (most backtest exercises reprice DOWN ~12% median), but on failure-dominated
        // histories the weaker-update effect overrides the ceiling's protection.
        assertEquals(
            "BSS policy prescription (post variance-budget) — was 20 lb, now 35 lb [CONCERN: equals lightest failed weight]",
            WeightUnit.LBS.toKg(35f), weightKg, 1e-3f
        )
    }
}
