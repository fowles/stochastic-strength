package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import org.junit.Assert.assertEquals
import org.junit.Test

class RecalibrationHarnessTest {

    private fun session(id: Long, end: Long) =
        WorkoutSession(id = id, startTime = end - 1000, endTime = end)

    private fun set(id: Long, sessionId: Long) =
        WorkoutSet(id = id, sessionId = sessionId, exerciseId = 1L, setNumber = 1, targetWeight = 20f, targetReps = 10)

    private fun history(n: Int): ReplayHistory {
        val sessions = (1..n).map { session(it.toLong(), end = it * 1000L) }
        val sets = (1..n).associate { it.toLong() to listOf(set(it.toLong(), it.toLong())) }
        return ReplayHistory(
            sessions = sessions,
            setsBySession = sets,
            initialOverrides = emptyList(),
            sessionOverrides = emptyMap(),
        )
    }

    private fun emptySnapshot() = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to MuscleGroup.CHEST),
        seedCoefficients = mapOf(1L to 1.0f),
        exerciseEquipment = emptyMap(),
    )

    @Test
    fun truncateTo_keepsFirstKSessionsAndTheirSets() {
        val h = history(5)
        val t = RecalibrationHarness.truncateTo(h, 3)
        assertEquals(listOf(1L, 2L, 3L), t.sessions.map { it.id })
        assertEquals(setOf(1L, 2L, 3L), t.setsBySession.keys)
    }

    @Test
    fun foldScores_enumeratesFoldsAndDefaultMatchesProposedForIdentityFit() {
        val user = RecalibrationHarness.UserHistory(history(6)) { emptySnapshot() }
        // Identity fit: always return defaults -> proposed == default per fold.
        val rows = RecalibrationHarness.foldScores(user, minFoldSessions = 3) { EstimatorConfig() }
        // Folds k = 3,4,5 (k .. N-1, N=6)
        assertEquals(listOf(3, 4, 5), rows.map { it.k })
        rows.forEach { assertEquals(it.heldOutDefault, it.heldOutProposed, 1e-9) }
    }
}
