package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end characterization of the real Jun-18 quads session that motivated the bracket-snap
 * feature, driving the production [RollingConservingProgressionController] + [DefaultProgressionEngine]
 * + [SessionSignalExtractor] for both the drop-cascade (55 fail -> 35 fail -> 20 complete) and the
 * all-failed (55 -> 35 -> 20 all fail) variants.
 *
 * Setup: a 230 lb quad baseline with per-user coefficients back-solved so the 10-rep prescriptions
 * match the real on-device starts (Bulgarian Split Squat 55 lb, Goblet Squat 65 lb; Barbell = 1.0
 * reference). The per-exercise EMA is primed by one prior on-target session (we cannot pull the real
 * EMA history from a non-debuggable release build). Barbell and Goblet are trained on-target that
 * session, so Bulgarian is the lone outlier.
 *
 * These asserts pin the *accepted* behavior, including the deliberately-tolerated quad baseline dip
 * (~7% drop-cascade, ~10% all-failed): the bracket estimator feeds an accurate-and-low Bulgarian est1RM
 * at high confidence into the unchanged common-mode term, so the whole quad baseline backs off for one
 * session before recovering. This was an explicit "leave it" decision (2026-06-21); a future change
 * that shrinks the dip should update these numbers with a documented rationale.
 */
class BulgarianBracketCharacterizationTest {

    private val unit = WeightUnit.LBS
    private val quads = MuscleGroup.QUADS
    private val barbell = 1L
    private val goblet = 2L
    private val bulgarian = 3L

    private fun lb(x: Float) = x / 2.20462f       // displayed lb -> stored kg
    private fun showLb(kg: Float) = kg * 2.20462f // stored kg -> displayed lb

    private val baselineKg = lb(230f)
    // Per-user coefficients back-solved so the 10-rep prescriptions match the real on-device starts:
    // Bulgarian Split Squat -> 55 lb, Goblet Squat -> 65 lb (baseline 230 lb, Barbell = 1.0 reference).
    private val seedCoefs = mapOf(barbell to 1.00f, goblet to 0.4319f, bulgarian to 0.3734f)

    private fun set(weightLb: Float, reps: Int, fb: SetFeedback, actual: Int?, n: Int) =
        WorkoutSet(
            sessionId = 1, exerciseId = bulgarian, setNumber = n,
            targetWeight = lb(weightLb), targetReps = reps, actualReps = actual, feedback = fb,
        )

    private fun input(now: Long, obs: List<ProgressionObservation>) = ProgressionStepInput(
        now = now, observations = obs,
        baselines = mapOf(quads to baselineKg), coefficients = seedCoefs,
        muscleExercises = mapOf(quads to listOf(barbell, goblet, bulgarian)),
        hurtMuscles = emptySet(), weightUnit = unit,
    )

    /** On-target observation: est1RM equals the prescription's implied 1RM, so EMA reads "as expected". */
    private fun onTarget(id: Long) = ProgressionObservation(id, quads, baselineKg * seedCoefs.getValue(id), 0.85f)

    private fun nextBulgarianLb(newBaselineKg: Float, newBulgarianCoef: Float): Float =
        showLb(WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(newBaselineKg * newBulgarianCoef, 10), unit))

    private data class Result(
        val bulgarianEst1RmLb: Float,
        val bracketConfidence: Float,
        val outputBaselineLb: Float,
        val barbellCoef: Float,
        val gobletCoef: Float,
        val bulgarianCoef: Float,
        val nextBulgarianLb: Float,
        val oldBulgarianLb: Float,
        val oldGobletLb: Float,
    )

    private fun run(bulgarianSets: List<WorkoutSet>): Result {
        val c = RollingConservingProgressionController()
        // Prime EMA with one prior on-target session.
        c.step(input(0L, listOf(onTarget(barbell), onTarget(goblet), onTarget(bulgarian))))

        val bulgAgg = SessionSignalExtractor.aggregateSession(bulgarianSets)!!
        val out = c.step(
            input(
                7L * 24 * 60 * 60 * 1000,
                listOf(
                    onTarget(barbell),
                    onTarget(goblet),
                    ProgressionObservation(bulgarian, quads, bulgAgg.est1RM, bulgAgg.sessionConfidence, bulgAgg.bracketConfidence),
                ),
            ),
        )

        val newBaselineKg = out.baselineUpdates.firstOrNull { it.muscleGroup == quads }?.newBaseline ?: baselineKg
        fun coef(id: Long) = out.coefficientUpdates.firstOrNull { it.exerciseId == id }?.coefficient ?: seedCoefs.getValue(id)
        return Result(
            bulgarianEst1RmLb = showLb(bulgAgg.est1RM),
            bracketConfidence = bulgAgg.bracketConfidence,
            outputBaselineLb = showLb(newBaselineKg),
            barbellCoef = coef(barbell),
            gobletCoef = coef(goblet),
            bulgarianCoef = coef(bulgarian),
            nextBulgarianLb = nextBulgarianLb(newBaselineKg, coef(bulgarian)),
            oldBulgarianLb = nextBulgarianLb(baselineKg, seedCoefs.getValue(bulgarian)),
            oldGobletLb = showLb(WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(baselineKg * seedCoefs.getValue(goblet), 10), unit)),
        )
    }

    @Test
    fun drop_cascade_55_35_20_complete() {
        // 55 fail(2) -> 35 fail(2) -> 20 complete (0-1 left)
        val r = run(
            listOf(
                set(55f, 10, SetFeedback.TOO_HARD, actual = 2, n = 1),
                set(35f, 10, SetFeedback.TOO_HARD, actual = 2, n = 2),
                set(20f, 10, SetFeedback.RIR_0_1, actual = null, n = 3),
            ),
        )

        // Calibration: the real on-device starting prescriptions.
        assertEquals(55.0f, r.oldBulgarianLb, 0.5f)
        assertEquals(65.0f, r.oldGobletLb, 0.5f)
        // est1RM anchors on the completed 20 lb set, far below the 55 lb top set.
        assertEquals(38.0f, r.bulgarianEst1RmLb, 0.5f)
        assertEquals(0.95f, r.bracketConfidence, 1e-6f)
        // Bulgarian coefficient snaps down hard; peers tick up to conserve the gauge.
        assertEquals(0.289f, r.bulgarianCoef, 0.005f)
        assertTrue("Barbell peer compensates up", r.barbellCoef > 1.00f)
        assertTrue("Goblet peer compensates up", r.gobletCoef > 0.4319f)
        // Accepted one-session quad baseline dip (~7%), recovers next sessions.
        assertEquals(213.8f, r.outputBaselineLb, 1.0f)
        // Next 10-rep Bulgarian prescription drops a real step (55 -> 40 lb).
        assertEquals(40.0f, r.nextBulgarianLb, 0.5f)
    }

    @Test
    fun all_failed_55_35_20_fail() {
        // 55 fail(2) -> 35 fail(3) -> 20 fail(4)
        val r = run(
            listOf(
                set(55f, 10, SetFeedback.TOO_HARD, actual = 2, n = 1),
                set(35f, 10, SetFeedback.TOO_HARD, actual = 3, n = 2),
                set(20f, 10, SetFeedback.TOO_HARD, actual = 4, n = 3),
            ),
        )

        // Calibration: the real on-device starting prescriptions.
        assertEquals(55.0f, r.oldBulgarianLb, 0.5f)
        assertEquals(65.0f, r.oldGobletLb, 0.5f)
        // est1RM comes from the lightest failed set's achieved reps -> lower than the drop-cascade.
        assertEquals(26.7f, r.bulgarianEst1RmLb, 0.5f)
        assertEquals(0.95f, r.bracketConfidence, 1e-6f)
        // Harder failure -> deeper coefficient cut than the drop-cascade variant.
        assertEquals(0.259f, r.bulgarianCoef, 0.005f)
        assertTrue("all-failed cuts deeper than drop-cascade", r.bulgarianCoef < 0.289f)
        assertTrue("Barbell peer compensates up", r.barbellCoef > 1.00f)
        assertTrue("Goblet peer compensates up", r.gobletCoef > 0.4319f)
        // Accepted one-session quad baseline dip (~10%), larger than the drop-cascade case.
        assertEquals(207.2f, r.outputBaselineLb, 1.0f)
        // Next 10-rep Bulgarian prescription drops further than the drop-cascade case (55 -> 30 lb).
        assertEquals(30.0f, r.nextBulgarianLb, 0.5f)
    }
}
