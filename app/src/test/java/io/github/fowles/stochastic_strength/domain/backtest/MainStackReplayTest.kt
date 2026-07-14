package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimate
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepper
import org.junit.Assert.assertEquals
import org.junit.Test

class MainStackReplayTest {

    private val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
    private val s1 = WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)
    private val s2 = WorkoutSession(id = 2, startTime = 0, endTime = 3 * DAY_MS)
    private val sets1 = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1))
    private val sets2 = listOf(WorkoutSet(id = 2, sessionId = 2, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4))

    @Test
    fun predictionsArePreFoldPostOverride() {
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(s1, s2),
            sets = sets1 + sets2,
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))

        val predictions = mutableListOf<Map<Long, Float>>()
        MainStackReplay.run(data) { _, _, _, preds, _ -> predictions += preds }
        assertEquals(2, predictions.size)

        // Hand-replay with the same prod components (mirrors ReplayEngine.run).
        val snapshot = data.newSnapshot()
        snapshot.currentEstimates[1L] = ExerciseEstimate.seed(110f, at = 0)
        val expected1 = MuscleStrengthProjector()
            .project(snapshot.currentEstimates, snapshot.seedCoefficients, listOf(1L), 1 * DAY_MS)
            .effectiveE1rm[1L]!!
        assertEquals(expected1, predictions[0][1L]!!, 1e-4f)

        SessionProgressionStepper().step(sets1, snapshot, 1 * DAY_MS)
        val expected2 = MuscleStrengthProjector()
            .project(snapshot.currentEstimates, snapshot.seedCoefficients, listOf(1L), 3 * DAY_MS)
            .effectiveE1rm[1L]!!
        assertEquals(expected2, predictions[1][1L]!!, 1e-4f)
    }

    @Test
    fun sessionOverrideIsAppliedBeforeThatSessionsPrediction() {
        // Same as prod ReplayEngine.run: override rows for session k land before session k's step —
        // and therefore before its prediction (user-entered corrections are known pre-workout).
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(s1, s2),
            sets = sets1 + sets2,
            strengthOverrides = listOf(
                ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0),
                ExerciseStrengthOverride(sessionId = 2, exerciseId = 1, e1rm = 200f, asOf = 2 * DAY_MS),
            ),
        ))
        var secondPrediction = 0f
        MainStackReplay.run(data) { sessionId, _, _, preds, _ ->
            if (sessionId == 2L) secondPrediction = preds[1L]!!
        }
        // Override confidence is 1.0 (prod value); the lone exercise dominates its muscle, so the
        // projected effective 1RM sits at the override.
        assertEquals(200f, secondPrediction, 1f)
    }
}
