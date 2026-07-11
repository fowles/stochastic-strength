package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.progression.SetObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VarianceStudyDiagnosticsTest {

    private fun pointSet(session: Long, ex: Long, muscle: MuscleGroup, residual: Float) =
        ScoredSet(session, ex, muscle, session, 0, 1,
            SetObservation(null, null, gaussianLn = 1.0f + residual, noiseSd = 0.1f),
            predMeanLn = 1.0f, cleanVar = 0.04f)

    @Test fun betweenSessionVarianceCapturesAWholeSessionShift() {
        // Session 1 residuals all +0.2, session 2 all -0.2 → pure between-session, ~0 within.
        val stream = listOf(
            pointSet(1L, 1L, MuscleGroup.QUADS, 0.2f), pointSet(1L, 2L, MuscleGroup.QUADS, 0.2f),
            pointSet(2L, 1L, MuscleGroup.QUADS, -0.2f), pointSet(2L, 2L, MuscleGroup.QUADS, -0.2f),
        )
        val d = decomposeResiduals(stream)
        assertTrue(d.betweenSessionVar > 0.03)      // ~0.04
        assertEquals(0.0, d.withinSessionVar, 1e-6) // identical within each session
    }

    @Test fun perfectlyCorrelatedSiblingsReportCorrelationNearOne() {
        val stream = listOf(
            pointSet(1L, 1L, MuscleGroup.QUADS, 0.2f), pointSet(1L, 2L, MuscleGroup.QUADS, 0.2f),
            pointSet(2L, 1L, MuscleGroup.QUADS, -0.1f), pointSet(2L, 2L, MuscleGroup.QUADS, -0.1f),
            pointSet(3L, 1L, MuscleGroup.QUADS, 0.05f), pointSet(3L, 2L, MuscleGroup.QUADS, 0.05f),
        )
        val corrs = sameMusclePairCorrelations(stream)
        assertEquals(1, corrs.size)
        assertEquals(1.0, corrs[0].correlation, 1e-6)
        assertEquals(3, corrs[0].nSessions)
    }

    @Test fun lightestLiftSwingPicksSmallestMedianAndMaxStep() {
        val rows = listOf(
            BacktestHarness.Row(1L, 10L, 100f), BacktestHarness.Row(2L, 10L, 100f), // heavy exercise, stable
            BacktestHarness.Row(1L, 20L, 5f), BacktestHarness.Row(2L, 20L, 15f), BacktestHarness.Row(3L, 20L, 10f),
        )
        val swing = lightestLiftSwing(rows)!!
        assertEquals(20L, swing.exerciseId)
        assertEquals(5f, swing.minKg, 1e-6f)
        assertEquals(15f, swing.maxKg, 1e-6f)
        assertEquals(10f, swing.maxStepKg, 1e-6f) // |15-5| across sessions 1->2
        assertEquals(3, swing.sessions)
    }
}
