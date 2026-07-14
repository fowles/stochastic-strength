package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import io.github.fowles.stochastic_strength.domain.policy.LnInterval
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class HeldOutScorerTest {

    private val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)

    @Test
    fun coldSingleExerciseScoreIsHandComputable() {
        // One exercise seeded at 110 kg with zero confidence: the projector returns the seed
        // itself (lone voter, level == its own seed-relative level), so the session-1 prediction
        // is exactly 110. One RIR_0_1 set at 100x5 -> interval [ln 1RM(100,5), ln 1RM(100,7)].
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1)),
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        val report = HeldOutScorer.score(data)
        val interval = LnInterval(
            lowerLn = ln(DefaultProgressionEngine.rawToOneRepMax(100f, 5f)),
            upperLn = ln(DefaultProgressionEngine.rawToOneRepMax(100f, 7f)),
        )
        assertEquals(interval.distanceTo(ln(110f)).toDouble(), report.totalDistance, 1e-6)
        assertEquals(1, report.scoredSets)
        assertEquals(0, report.skippedSets)
        assertEquals(1, report.perSession.size)
    }

    @Test
    fun hurtSetsAreNotScoredAndColdExercisesAreSkipped() {
        val lunge = Exercise(id = 2, name = "Dumbbell Lunge", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.DUMBBELL)
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat, lunge),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = listOf(
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.HURT),
                // Exercise 2 has no initial override -> no estimate -> no prediction -> skipped.
                WorkoutSet(id = 2, sessionId = 1, exerciseId = 2, setNumber = 1, targetWeight = 20f, targetReps = 10, feedback = SetFeedback.RIR_2_4),
            ),
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        val report = HeldOutScorer.score(data)
        assertEquals(0, report.scoredSets)   // HURT: no interval at all
        assertEquals(1, report.skippedSets)  // lunge: interval but no prediction
        assertEquals(0.0, report.totalDistance, 0.0)
    }

    @Test
    fun multiSessionTotalsAreSummed() {
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(
                WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS),
                WorkoutSession(id = 2, startTime = 0, endTime = 3 * DAY_MS),
            ),
            sets = listOf(
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1),
                WorkoutSet(id = 2, sessionId = 2, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4),
            ),
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        val report = HeldOutScorer.score(data)
        assertEquals(2, report.scoredSets)
        assertEquals(report.perSession.sumOf { it.distance }, report.totalDistance, 1e-9)
        assertEquals(report.perSession.sumOf { it.scoredSets }, report.scoredSets)
    }
}
