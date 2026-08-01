package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeliefHeldOutScorerTest {
    private val config = BeliefConfig(
        seedUncertaintySd = 0.15f, overrideUncertaintySd = 0.10f,
        fatiguePerSetEstimate = 0.05f, confidenceDecayEstimate = 1e-3f,
        perSetDoubtEstimate = 0.10f,
        crossLiftIndependenceEstimate = 0.10f, uncertaintyFloor = 4e-4f, uncertaintyCap = 0.25f,
    )

    @Test
    fun scoreSumsPerSetDistancesAgainstUnshiftedIntervals() {
        val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
        val sets = listOf(
            WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            WorkoutSet(id = 2, sessionId = 1, exerciseId = 1, setNumber = 2, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
            WorkoutSet(id = 3, sessionId = 1, exerciseId = 1, setNumber = 3, targetWeight = 100f, targetReps = 5),  // no feedback: not scored
        )
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = sets,
            baselineOverrides = listOf(BaselineOverride(sessionId = null, muscleGroup = MuscleGroup.QUADS, baselineWeight = 110f, asOf = 0)),
        ))
        // Expected: replay once, sum interval.distanceTo(predictedLn) over the two feedback sets.
        var expected = 0.0
        BeliefStackReplay.run(data, config) { _, _, predictions, _, _ ->
            for (p in predictions) {
                val interval = SetIntervals.impliedLn1RmInterval(p.set) ?: continue
                expected += interval.distanceTo(p.predictedLn!!).toDouble()
            }
        }
        val result = BeliefHeldOutScorer.score(data, config)
        assertEquals(expected, result.report.totalDistance, 1e-9)
        assertEquals(2, result.report.scoredSets)
        assertEquals(0, result.report.skippedSets)
    }

    @Test
    fun setsWithoutAPredictionAreSkippedNotScored() {
        // Live seed expansion seeds every LOADED (coef > 0) exercise from its muscle baseline —
        // so the only way a set has no prediction now is a zero-coefficient (unloadable) exercise:
        // it's excluded from `seedCoefficients.filterValues { it > 0f }` and therefore from
        // `muscleExerciseIds`, so pooling never produces an effective belief for it (the fold skips
        // it too, per CLAUDE.md: "zero-coefficient (unloadable) exercises are skipped").
        val pullUp = Exercise(id = 1, name = "Pull-Up", primaryMuscle = MuscleGroup.BACK, equipment = Equipment.BODYWEIGHT)
        assertTrue("fixture assumption: Pull-Up must be a real zero-coef entry", (ExerciseCoefficients.get(pullUp) ?: 0f) == 0f)
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(pullUp),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            // targetWeight must be > 0 for SetIntervals to imply an interval at all (bodyweight sets
            // are still logged with a nominal weight); the point under test is the missing
            // PREDICTION, not a missing interval.
            sets = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 70f, targetReps = 8, feedback = SetFeedback.RIR_0_1)),
        ))
        val result = BeliefHeldOutScorer.score(data, config)
        assertEquals(0, result.report.scoredSets)
        assertEquals(1, result.report.skippedSets)
    }
}
