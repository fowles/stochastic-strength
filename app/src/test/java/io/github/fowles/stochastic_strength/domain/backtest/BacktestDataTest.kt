package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonBuilder
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestDataTest {

    private val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
    private val goblet = Exercise(id = 2, name = "Goblet Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.DUMBBELL, isDisliked = true)

    @Test
    fun sessionsAreSortedAndUnfinishedDropped() {
        val backup = BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(
                WorkoutSession(id = 3, startTime = 0, endTime = 2000),
                WorkoutSession(id = 1, startTime = 0, endTime = 1000),
                WorkoutSession(id = 2, startTime = 0, endTime = null), // in-flight: dropped
            ),
            sets = listOf(
                WorkoutSet(id = 2, sessionId = 1, exerciseId = 1, setNumber = 2, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            ),
        )
        val data = BacktestData.from(backup)
        assertEquals(listOf(1L, 3L), data.sessions.map { it.id })
        assertEquals(listOf(1L, 2L), data.setsBySession[1L]!!.map { it.id })
    }

    @Test
    fun overridesAreSplitByInitialVsSession() {
        val backup = BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = emptyList(),
            sets = emptyList(),
            strengthOverrides = listOf(
                ExerciseStrengthOverride(id = 1, sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0),
                ExerciseStrengthOverride(id = 2, sessionId = 7, exerciseId = 1, e1rm = 120f, asOf = 5),
            ),
        )
        val data = BacktestData.from(backup)
        assertEquals(1, data.initialOverrides.size)
        assertEquals(110f, data.initialOverrides[0].e1rm, 0f)
        assertEquals(120f, data.sessionOverrides[7L]!![0].e1rm, 0f)
    }

    @Test
    fun snapshotMirrorsProductionSeeding() {
        // Production seeds coefficients from active (non-disliked) exercises only; muscle map covers all.
        val data = BacktestData.from(BacktestFixtures.backup(listOf(squat, goblet), emptyList(), emptyList()))
        val snapshot = data.newSnapshot()
        assertEquals(1.00f, snapshot.seedCoefficients[1L]!!, 0f)
        assertNull(snapshot.seedCoefficients[2L]) // disliked: excluded like DAO getActive()
        assertEquals(MuscleGroup.QUADS, snapshot.exerciseMuscle[2L])
    }

    @Test
    fun jsonRoundTripSurvivesTheProdBuilder() {
        val backup = BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1000)),
            sets = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1)),
        )
        val data = BacktestData.from(BackupJsonParser.parse(BackupJsonBuilder.build(backup)))
        assertEquals(1, data.sessions.size)
        assertTrue(data.setsBySession[1L]!!.single().feedback == SetFeedback.RIR_0_1)
    }
}
