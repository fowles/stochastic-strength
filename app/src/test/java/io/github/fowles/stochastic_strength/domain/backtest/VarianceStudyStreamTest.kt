package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ExerciseBelief
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import io.github.fowles.stochastic_strength.domain.progression.SetObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VarianceStudyStreamTest {

    private val DAY = 24L * 60 * 60 * 1000

    private fun set(session: Long, setNo: Int, reps: Int, fb: SetFeedback) = WorkoutSet(
        sessionId = session, exerciseId = 1L, setNumber = setNo, targetWeight = 80f,
        targetReps = reps, actualReps = null, feedback = fb,
    )

    private fun history(): ReplayHistory {
        val s1 = WorkoutSession(id = 1L, startTime = 0L, endTime = 10L * DAY)
        val s2 = WorkoutSession(id = 2L, startTime = 0L, endTime = 20L * DAY)
        return ReplayHistory(
            sessions = listOf(s2, s1), // deliberately unsorted; capture must sort by endTime
            setsBySession = mapOf(
                1L to listOf(set(1L, 1, 8, SetFeedback.RIR_2_4), set(1L, 2, 8, SetFeedback.RIR_2_4)),
                2L to listOf(set(2L, 1, 8, SetFeedback.RIR_0_1)),
            ),
            initialOverrides = emptyList(),
            sessionOverrides = emptyMap(),
        )
    }

    private fun newSnapshot(): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS),
            seedCoefficients = mapOf(1L to 1.0f),
            exerciseEquipment = mapOf(1L to Equipment.BARBELL),
        )
        snap.currentBeliefs[1L] = ExerciseBelief.seed(100f, at = 0L)
        return snap
    }

    @Test fun captureEmitsOneScoredSetPerLoadObservationInEndTimeOrder() {
        val stream = captureStream(history(), EstimatorConfig(), ::newSnapshot)
        // 3 load-bearing sets total, session 1 before session 2 by endTime.
        assertEquals(3, stream.size)
        assertEquals(listOf(1L, 1L, 2L), stream.map { it.sessionId })
        assertEquals(listOf(0, 0, 1), stream.map { it.sessionRank })
        stream.forEach { assertTrue(it.predMeanLn.isFinite() && it.cleanVar > 0f) }
    }

    @Test fun obsLocationMidpointForTwoSidedInterval() {
        val obs = SetObservation(lowerLn = 1.0f, upperLn = 3.0f, gaussianLn = null, noiseSd = 0.1f)
        assertEquals(2.0f, obsLocation(obs), 1e-6f)
    }

    @Test fun hurtSetsAreNotEmitted() {
        val h = ReplayHistory(
            sessions = listOf(WorkoutSession(id = 1L, startTime = 0L, endTime = 10L * DAY)),
            setsBySession = mapOf(1L to listOf(set(1L, 1, 8, SetFeedback.HURT))),
            initialOverrides = emptyList(), sessionOverrides = emptyMap(),
        )
        assertTrue(captureStream(h, EstimatorConfig(), ::newSnapshot).isEmpty())
    }
}
