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
 * Asserts on next prescriptions (the only user-visible quantity): a lone Bulgarian bracket drops
 * Bulgarian to its demonstrated capacity while Goblet's prescription holds or rises; the baseline
 * is not dragged by the outlier.
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
        seedCoefficients = seedCoefs,
        hurtMuscles = emptySet(), weightUnit = unit,
    )

    /** On-target observation: est1RM equals the prescription's implied 1RM, so EMA reads "as expected". */
    private fun onTarget(id: Long) = ProgressionObservation(id, quads, baselineKg * seedCoefs.getValue(id), 0.85f)

    private fun nextLb(id: Long, newBaselineKg: Float, newCoef: Float): Float =
        showLb(WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(newBaselineKg * newCoef, 10), unit))

    private data class Result(
        val bulgarianEst1RmLb: Float,
        val bracketConfidence: Float,
        val nextBulgarianLb: Float,
        val nextGobletLb: Float,
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
            nextBulgarianLb = nextLb(bulgarian, newBaselineKg, coef(bulgarian)),
            nextGobletLb = nextLb(goblet, newBaselineKg, coef(goblet)),
            oldBulgarianLb = nextLb(bulgarian, baselineKg, seedCoefs.getValue(bulgarian)),
            oldGobletLb = nextLb(goblet, baselineKg, seedCoefs.getValue(goblet)),
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
        // Outcome spec: Bulgarian lands near the demonstrated ~20 lb; Goblet keeps moving up.
        assertTrue("next Bulgarian ${r.nextBulgarianLb} should be near 20 lb", r.nextBulgarianLb <= 25.0f)
        assertTrue("next Goblet ${r.nextGobletLb} should be >= last (65)", r.nextGobletLb >= 65.0f)
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
        // Outcome spec: Bulgarian lands "near 20"; Goblet holds/up. One bracket session reaches 25 lb
        // (per-session differential clamp + 5-lb grid collapse it to the drop-cascade step); the deeper
        // all-failed est1RM converges the rest of the way to 20 on a second bracket session.
        assertTrue("next Bulgarian ${r.nextBulgarianLb} should be near 20 lb", r.nextBulgarianLb <= 25.0f)
        assertTrue("next Goblet ${r.nextGobletLb} should be >= last (65)", r.nextGobletLb >= 65.0f)
    }

    @Test
    fun unanimous_drop_moves_baseline_not_just_coefficients() {
        // All three quad lifts bracket ~30% low together in one session: shared signal -> baseline drops.
        val c = RollingConservingProgressionController()
        c.step(input(0L, listOf(onTarget(barbell), onTarget(goblet), onTarget(bulgarian))))
        fun lowObs(id: Long) = ProgressionObservation(id, quads, baselineKg * seedCoefs.getValue(id) * 0.70f, 0.95f, 0.95f)
        val out = c.step(input(7L * 24 * 60 * 60 * 1000, listOf(lowObs(barbell), lowObs(goblet), lowObs(bulgarian))))
        val nb = out.baselineUpdates.first { it.muscleGroup == quads }.newBaseline
        assertTrue("unanimous drop should pull the baseline down: ${showLb(nb)}", nb < baselineKg * 0.95f)
    }

    @Test
    fun drift_in_turn_converges_baseline_over_sessions() {
        // A too-high baseline reveals itself one lift per session via brackets; the reclaimer should
        // pull the collective coefficient drift back into the baseline rather than leaving it stuck.
        val c = RollingConservingProgressionController()
        c.step(input(0L, listOf(onTarget(barbell), onTarget(goblet), onTarget(bulgarian))))
        val order = listOf(bulgarian, goblet, barbell)
        var lastBaseline = baselineKg
        order.forEachIndexed { i, id ->
            val now = (7L + i) * 24 * 60 * 60 * 1000
            // The one lift trained this session brackets ~30% low; the others are not retrained.
            val bracket = ProgressionObservation(id, quads, baselineKg * seedCoefs.getValue(id) * 0.70f, 0.95f, 0.95f)
            val out = c.step(input(now, listOf(bracket)))
            out.baselineUpdates.firstOrNull { it.muscleGroup == quads }?.let { lastBaseline = it.newBaseline }
            // Note: input(...) always passes the same seedCoefs as current coefficients, so this models
            // the SIGN of the reclaim, not a full closed loop; convergence direction is what we assert.
        }
        assertTrue("baseline should trend down as drift is reclaimed: ${showLb(lastBaseline)}", lastBaseline < baselineKg)
    }
}
