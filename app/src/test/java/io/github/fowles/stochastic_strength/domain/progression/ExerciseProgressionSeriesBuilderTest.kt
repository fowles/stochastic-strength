package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class ExerciseProgressionSeriesBuilderTest {

    private fun snapshot(): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f, 2L to 0.6f),
        )
        snap.currentEstimates[1L] = ExerciseEstimate(lnE = ln(100f), confidence = 6f, updatedAt = 0L)
        snap.currentEstimates[2L] = ExerciseEstimate(lnE = ln(60f), confidence = 6f, updatedAt = 0L)
        return snap
    }

    private fun set(exerciseId: Long, weight: Float, reps: Int) = WorkoutSet(
        sessionId = 10L,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = weight,
        targetReps = reps,
        actualReps = reps,
        feedback = SetFeedback.RIR_2_4,
    )

    @Test
    fun siblingObservationsAreRescaledIntoTargetSpace() {
        val snap = snapshot()
        // Sibling 2 performed at an observed 1RM; target is 1. Rescale factor = seed[1]/seed[2].
        val siblingSets = listOf(set(exerciseId = 2L, weight = 60f, reps = 5))
        val agg = io.github.fowles.stochastic_strength.domain.SessionSignalExtractor.aggregateSession(siblingSets)!!
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = siblingSets,
            asOf = 1_000L,
            projector = MuscleStrengthProjector(),
        )
        assertEquals(1, sample.siblingObservations.size)
        val expected = agg.est1RM * (1.0f / 0.6f)
        assertEquals(expected, sample.siblingObservations.first().value, 1e-2f)
        // No own observation this session (target had no sets).
        assertEquals(0, sample.ownObservations.size)
    }

    @Test
    fun leaveOneOutLineExcludesTargetVote() {
        val snap = snapshot()
        // Make target (1) artificially huge; leave-one-out must ignore it and reflect sibling 2.
        snap.currentEstimates[1L] = ExerciseEstimate(lnE = ln(1000f), confidence = 6f, updatedAt = 0L)
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = listOf(set(exerciseId = 1L, weight = 100f, reps = 5)),
            asOf = 1_000L,
            projector = MuscleStrengthProjector(),
        )
        // Sibling 2 at 60 with seed 0.6 implies level ~100, so target prediction ~100*1.0 = 100,
        // NOT ~1000. Far below the inflated own estimate.
        assertEquals(1, sample.siblingsEstimate.size)
        org.junit.Assert.assertTrue(sample.siblingsEstimate.first().value < 200f)
    }
}
