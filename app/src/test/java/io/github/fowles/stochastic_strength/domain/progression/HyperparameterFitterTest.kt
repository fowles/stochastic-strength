package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperparameterFitterTest {
    private val defaults = EstimatorConfig()
    private val fitter = HyperparameterFitter(defaults)

    @Test fun applyThetaZeroIsDefaults() {
        val c = fitter.applyTheta(DoubleArray(5) { 0.0 })
        assertEquals(defaults.fatiguePerSet, c.fatiguePerSet, 1e-7f)
        assertEquals(defaults.processNoisePerDay, c.processNoisePerDay, 1e-9f)
        assertEquals(defaults.tauBarbell, c.tauBarbell, 1e-7f)
        assertEquals(defaults.detrainRatePerWeek, c.detrainRatePerWeek, 1e-7f)
        assertEquals(defaults.repNoiseBucket, c.repNoiseBucket, 1e-7f)
    }

    @Test fun applyThetaClampsToBounds() {
        // A huge positive log-multiplier saturates at ×4; huge negative at ÷4.
        val hi = fitter.applyTheta(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 0.0))
        assertEquals(defaults.fatiguePerSet * 4f, hi.fatiguePerSet, 1e-6f)
        val lo = fitter.applyTheta(doubleArrayOf(0.0, 0.0, -10.0, 0.0, 0.0))
        assertEquals(defaults.fatiguePerSet * 0.25f, lo.fatiguePerSet, 1e-6f)
    }

    @Test fun belowFloorReturnsDefaults() {
        val history = fatiguePlantedHistory(nSessions = 5, trueFatigue = 0.09f)
        val r = HyperparameterFitter(defaults).fit(history) { syntheticSnapshot() }
        assertTrue(r.atDefaults)
        assertEquals(defaults.fatiguePerSet, r.config.fatiguePerSet, 0f)
    }

    @Test fun recoversHigherFatigueFromSyntheticHistory() {
        // A lifter whose TRUE per-set fatigue (0.09) is well above default (0.03). Each session is three
        // counted TOO_HARD sets at a fixed weight whose achieved reps are generated from the true fatigue
        // via the SAME 1RM formula the estimator uses — so the data is exactly what such a lifter produces
        // and recovering high fatigue is a genuine round-trip. The fitter should move fatiguePerSet up
        // toward truth (bounded at ×4 = 0.12).
        val history = fatiguePlantedHistory(nSessions = 30, trueFatigue = 0.09f)
        val r = HyperparameterFitter(defaults).fit(history) { syntheticSnapshot() }
        assertTrue("expected a real fit", !r.atDefaults)
        assertTrue("fatigue should rise toward truth, got ${r.config.fatiguePerSet}",
            r.config.fatiguePerSet > defaults.fatiguePerSet)
        assertTrue(r.score >= r.defaultScore)
    }

    private fun syntheticSnapshot() = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to MuscleGroup.QUADS),
        seedCoefficients = mapOf(1L to 1.0f),
        exerciseEquipment = mapOf(1L to Equipment.BARBELL),
    ).also { it.currentBeliefs[1L] = ExerciseBelief.seed(100f, at = 0L) }

    private fun fatiguePlantedHistory(
        nSessions: Int, trueFatigue: Float, trueFresh1RM: Float = 100f, weight: Float = 80f,
    ): ReplayHistory {
        val dayMs = 86_400_000L
        fun repsFor(capacity: Float): Int = (1..15).minByOrNull { rep ->
            kotlin.math.abs(DefaultProgressionEngine.rawToOneRepMax(weight, rep.toFloat()) - capacity)
        }!!
        val sessions = (1..nSessions).map { WorkoutSession(id = it.toLong(), startTime = it * dayMs - 3600_000L, endTime = it * dayMs) }
        val sets = sessions.associate { s ->
            val rows = (1..3).map { k ->
                val capacity = trueFresh1RM * (1f - trueFatigue * (k - 1))
                WorkoutSet(sessionId = s.id, exerciseId = 1L, setNumber = k, targetWeight = weight,
                    targetReps = 8, actualReps = repsFor(capacity), feedback = SetFeedback.TOO_HARD)
            }
            s.id to rows
        }
        return ReplayHistory(sessions, sets, initialOverrides = emptyList(), sessionOverrides = emptyMap())
    }
}
