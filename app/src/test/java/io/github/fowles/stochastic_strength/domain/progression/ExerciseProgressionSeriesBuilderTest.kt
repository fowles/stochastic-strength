package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
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
    fun mergedLineEqualsFullProjectionEffectiveE1rm() {
        // Target (1) at 80 kg; sibling (2) at 60 kg with seed 0.6 implies level ~100.
        // The leave-one-out prediction for the target is ~100 (sibling-only pool), but the full
        // projection shrinks the target's own estimate (80) toward the prediction, so merged != siblings.
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f, 2L to 0.6f),
        )
        snap.currentEstimates[1L] = ExerciseEstimate(lnE = ln(80f), confidence = 6f, updatedAt = 0L)
        snap.currentEstimates[2L] = ExerciseEstimate(lnE = ln(60f), confidence = 6f, updatedAt = 0L)
        val asOf = 1_000L
        val projector = MuscleStrengthProjector()

        // Target performed a set so the muscle is "touched" and lines are sampled.
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = listOf(set(exerciseId = 1L, weight = 80f, reps = 5)),
            asOf = asOf,
            projector = projector,
        )

        // Expected merged value: full projection effectiveE1rm for target 1.
        val expectedMerged = projector
            .project(snap.currentEstimates, snap.seedCoefficients, listOf(1L, 2L), asOf)
            .effectiveE1rm.getValue(1L)

        assertEquals(1, sample.merged.size)
        assertEquals(expectedMerged, sample.merged.single().value, 1e-3f)

        // merged must meaningfully differ from the leave-one-out (sibling-only) prediction.
        // siblingsEstimate ≈ 100 (sibling 2 implies level 100); merged is shrunk toward 100 from 80 < 100.
        assertEquals(1, sample.siblingsEstimate.size)
        assertTrue(abs(sample.merged.single().value - sample.siblingsEstimate.single().value) > 1f)
    }

    @Test
    fun ownEstimateReflectsTheCurrentEstimate() {
        val snap = snapshot()
        val asOf = 1_000L
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = listOf(set(exerciseId = 1L, weight = 100f, reps = 5)),
            asOf = asOf,
            projector = MuscleStrengthProjector(),
        )

        val expectedOwnEstimate = exp(snap.currentEstimates.getValue(1L).lnE)
        assertEquals(1, sample.ownEstimate.size)
        assertEquals(asOf, sample.ownEstimate.single().timestampMs)
        assertEquals(expectedOwnEstimate, sample.ownEstimate.single().value, 1e-2f)
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

    @Test
    fun frameCarriesLineValuesCrossTuningAndObservationsTargetFirst() {
        val snap = snapshot() // ex1 (CHEST, seed 1.0, E=100), ex2 (CHEST, seed 0.6, E=60)
        val names = mapOf(1L to "Bench", 2L to "Incline")
        val asOf = 1_000L
        // Both exercises trained: target ex1 at 100x5 (RIR_2_4), sibling ex2 at 60x5.
        val sets = listOf(set(exerciseId = 1L, weight = 100f, reps = 5), set(exerciseId = 2L, weight = 60f, reps = 5))

        val frame = buildFrame(
            targetId = 1L, muscleIds = listOf(1L, 2L), snapshot = snap,
            sets = sets, asOf = asOf, namesById = names, projector = MuscleStrengthProjector(),
        )

        // Line values match sampleSession.
        val sample = sampleSession(1L, listOf(1L, 2L), snap, sets, asOf, MuscleStrengthProjector())
        assertEquals(sample.ownEstimate.first().value, frame.own!!, 1e-3f)
        assertEquals(sample.merged.first().value, frame.merged!!, 1e-3f)

        // Cross-tuning evaluated at asOf, one row per weighted exercise.
        assertEquals(2, frame.crossTuning.size)

        // Observations: target first, then sibling; each carries an ObservedSet.
        assertEquals(listOf(1L, 2L), frame.observations.map { it.exerciseId })
        assertEquals("Bench", frame.observations.first().name)
        assertEquals(1, frame.observations.first().sets.size)
    }

    @Test
    fun frameObservationsOmitExercisesThatDidNotTrain() {
        val snap = snapshot()
        val names = mapOf(1L to "Bench", 2L to "Incline")
        // Only sibling ex2 trained this session.
        val sets = listOf(set(exerciseId = 2L, weight = 60f, reps = 5))
        val frame = buildFrame(1L, listOf(1L, 2L), snap, sets, 1_000L, names, MuscleStrengthProjector())
        assertEquals(listOf(2L), frame.observations.map { it.exerciseId })
    }
}
