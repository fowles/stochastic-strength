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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class CapViolationDiagnosticTest {

    private fun set(feedback: SetFeedback, w: Float, r: Int = 5, a: Int? = null, id: Long = 1) =
        WorkoutSet(id = id, sessionId = 1, exerciseId = 1, setNumber = id.toInt(), targetWeight = w, targetReps = r, actualReps = a, feedback = feedback)

    private fun cap(w: Float, reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))

    @Test
    fun failureSessionCapIsMinOverFailedSets() {
        val ln = CapViolationDiagnostic.capLnFor(listOf(
            set(SetFeedback.RIR_2_4, 90f, id = 1),
            set(SetFeedback.TOO_HARD, 100f, a = 3, id = 2),  // upper = 1RM(100, 4)
            set(SetFeedback.TOO_HARD, 100f, id = 3),          // upper = 1RM(100, 5)
        ))!!
        assertEquals(cap(100f, 4f), ln, 1e-6f)
    }

    @Test
    fun cleanSessionCapIsBestDemonstratedUpperBound() {
        val ln = CapViolationDiagnostic.capLnFor(listOf(
            set(SetFeedback.RIR_0_1, 100f, id = 1),  // upper = 1RM(100, 7)
            set(SetFeedback.RIR_2_4, 95f, id = 2),   // upper = 1RM(95, 10)
        ))!!
        assertEquals(maxOf(cap(100f, 7f), cap(95f, 10f)), ln, 1e-6f)
    }

    @Test
    fun anyRir5PlusMeansNoCap() {
        assertNull(CapViolationDiagnostic.capLnFor(listOf(
            set(SetFeedback.RIR_2_4, 100f, id = 1),
            set(SetFeedback.RIR_5_PLUS, 100f, id = 2),
        )))
    }

    @Test
    fun hurtOnlySessionLeavesNoCap() {
        assertNull(CapViolationDiagnostic.capLnFor(listOf(set(SetFeedback.HURT, 100f))))
    }

    @Test
    fun overridePastAFailureCapIsFlagged() {
        val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(
                WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS),
                WorkoutSession(id = 2, startTime = 0, endTime = 3 * DAY_MS),
            ),
            sets = listOf(
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 2, feedback = SetFeedback.TOO_HARD),
                WorkoutSet(id = 2, sessionId = 2, exerciseId = 1, setNumber = 1, targetWeight = 80f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4),
            ),
            strengthOverrides = listOf(
                ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0),
                // User override right before session 2 jumps the estimate far above the failed cap.
                ExerciseStrengthOverride(sessionId = 2, exerciseId = 1, e1rm = 300f, asOf = 2 * DAY_MS),
            ),
        ))
        val violations = CapViolationDiagnostic.violations(data)
        assertEquals(1, violations.size)
        assertEquals(2L, violations[0].sessionId)
        assertTrue(violations[0].predictedE1rm > violations[0].capE1rm)
    }

    @Test
    fun capExpiresAfterTwentyEightDays() {
        val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(
                WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS),
                WorkoutSession(id = 2, startTime = 0, endTime = 40 * DAY_MS),
            ),
            sets = listOf(
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 2, feedback = SetFeedback.TOO_HARD),
                WorkoutSet(id = 2, sessionId = 2, exerciseId = 1, setNumber = 1, targetWeight = 80f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4),
            ),
            strengthOverrides = listOf(
                ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0),
                ExerciseStrengthOverride(sessionId = 2, exerciseId = 1, e1rm = 300f, asOf = 39 * DAY_MS),
            ),
        ))
        assertTrue(CapViolationDiagnostic.violations(data).isEmpty())
    }
}
