package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals
import org.junit.Assert.assertEquals
import org.junit.Test

class BeliefHeldOutScorerTest {
    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        phi = 0.05f, qPerDay = 1e-3f,
        sigmaObs = 0.10f,
        tau = 0.10f, sigma2Floor = 4e-4f, sigma2Cap = 0.25f,
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
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
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
        // No override, single lonely exercise: session 1 has no prediction for it.
        val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1)),
        ))
        val result = BeliefHeldOutScorer.score(data, config)
        assertEquals(0, result.report.scoredSets)
        assertEquals(1, result.report.skippedSets)
    }
}
