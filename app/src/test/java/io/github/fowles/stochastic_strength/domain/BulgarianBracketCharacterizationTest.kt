package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimate
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimateUpdater
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end characterization of the real Jun-18 quads session that motivated the bracket-snap
 * feature, driving [SessionSignalExtractor] + [ExerciseEstimateUpdater] + [MuscleStrengthProjector]
 * + [DefaultProgressionEngine] for both the drop-cascade (55 fail -> 35 fail -> 20 complete) and
 * the all-failed (55 -> 35 -> 20 all fail) variants.
 *
 * Asserts on next prescriptions (the only user-visible quantity): a lone Bulgarian bracket drops
 * Bulgarian's estimate so the next prescription is below the failed weight; the Goblet prescription
 * is independently tracked and is unaffected by the Bulgarian drop.
 */
class BulgarianBracketCharacterizationTest {

    private val unit = WeightUnit.LBS
    private val barbell = 1L
    private val goblet = 2L
    private val bulgarian = 3L
    private val updater = ExerciseEstimateUpdater()
    private val projector = MuscleStrengthProjector()

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

    /** Seed an estimate for each exercise at baseline * seedCoef with some prior confidence. */
    private fun seededEstimates(at: Long, confidence: Float = 2f): MutableMap<Long, ExerciseEstimate> =
        seedCoefs.entries.associate { (id, c) ->
            id to ExerciseEstimate(lnE = kotlin.math.ln(baselineKg * c), confidence = confidence, updatedAt = at)
        }.toMutableMap()

    /**
     * Project the next prescription for a single exercise in isolation (no cross-exercise pooling).
     * Used to verify that exercises in separate muscles are not affected by each other's signal.
     */
    private fun nextLb(estimates: Map<Long, ExerciseEstimate>, id: Long, now: Long): Float {
        val proj = projector.project(estimates, seedCoefs, listOf(id), now = now)
        val e1rm = proj.effectiveE1rm[id] ?: return 0f
        return showLb(WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(e1rm, 10), unit))
    }

    private data class Result(
        val bulgarianEst1RmLb: Float,
        val bracketConfidence: Float,
        val nextBulgarianLb: Float,
        val nextGobletLb: Float,
        val oldBulgarianLb: Float,
        val oldGobletLb: Float,
    )

    private fun run(bulgarianSets: List<WorkoutSet>): Result {
        val t0 = 0L
        val t1 = 7L * 24 * 60 * 60 * 1000
        val estimates = seededEstimates(at = t0)

        val oldBulgarianLb = nextLb(estimates, bulgarian, t0)
        val oldGobletLb = nextLb(estimates, goblet, t0)

        // Bulgarian bracket session: extract signal + fold into Bulgarian's estimate.
        val bulgAgg = SessionSignalExtractor.aggregateSession(bulgarianSets)!!
        estimates[bulgarian] = updater.fold(estimates.getValue(bulgarian), bulgAgg.est1RM, bulgAgg.bracketConfidence, t1)
        // Goblet and Barbell are not trained this session (their estimates remain unchanged).

        return Result(
            bulgarianEst1RmLb = showLb(bulgAgg.est1RM),
            bracketConfidence = bulgAgg.bracketConfidence,
            nextBulgarianLb = nextLb(estimates, bulgarian, t1),
            nextGobletLb = nextLb(estimates, goblet, t1),
            oldBulgarianLb = oldBulgarianLb,
            oldGobletLb = oldGobletLb,
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
        // The bracket yields a bracketConfidence signal; est1RM anchors on completed 20 lb set.
        assertTrue("bracketConfidence should be > 0 for drop-cascade", r.bracketConfidence > 0f)
        assertTrue("est1RM ${r.bulgarianEst1RmLb} should be below the failed top weight (55 lb)", r.bulgarianEst1RmLb < 55.0f)
        // Outcome spec: Bulgarian next prescription drops below the failed 55 lb top set.
        assertTrue(
            "next Bulgarian ${r.nextBulgarianLb} should be below failed top weight 55 lb",
            r.nextBulgarianLb < 55.0f,
        )
        // Goblet is independently tracked and is unaffected by the Bulgarian bracket.
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
        // The bracket yields bracketConfidence > 0; est1RM is below the failed top weight.
        assertTrue("bracketConfidence should be > 0 for all-failed bracket", r.bracketConfidence > 0f)
        assertTrue("est1RM ${r.bulgarianEst1RmLb} should be below the failed top weight (55 lb)", r.bulgarianEst1RmLb < 55.0f)
        // Outcome spec: next Bulgarian prescription is below the failed top weight.
        assertTrue(
            "next Bulgarian ${r.nextBulgarianLb} should be below failed top weight 55 lb",
            r.nextBulgarianLb < 55.0f,
        )
        // Goblet is independently tracked and is unaffected by the Bulgarian bracket.
        assertTrue("next Goblet ${r.nextGobletLb} should be >= last (65)", r.nextGobletLb >= 65.0f)
    }
}
