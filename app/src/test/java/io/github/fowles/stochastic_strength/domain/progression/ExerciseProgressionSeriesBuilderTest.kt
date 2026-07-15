package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.setObservationLn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class ExerciseProgressionSeriesBuilderTest {

    private val config = BeliefConfig()

    private fun snapshot(): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f, 2L to 0.6f),
        )
        snap.currentBeliefs[1L] = Belief(mu = ln(100f), sigma2 = 0.01f, updatedAt = 0L)
        snap.currentBeliefs[2L] = Belief(mu = ln(60f), sigma2 = 0.01f, updatedAt = 0L)
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
        val expectedLn = setObservationLn(siblingSets.first(), rank = 1, config)!!
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = siblingSets,
            asOf = 1_000L,
            config = config,
        )
        assertEquals(1, sample.siblingObservations.size)
        val expected = exp(expectedLn) * (1.0f / 0.6f)
        assertEquals(expected, sample.siblingObservations.first().value, 1e-2f)
        // No own observation this session (target had no sets).
        assertEquals(0, sample.ownObservations.size)
    }

    @Test
    fun siblingObservationsExcludeExercisesOutsideTheTargetsMuscle() {
        // A loadable exercise from a DIFFERENT muscle is also in the session (e.g. a leg lift on a
        // biceps day). It must NOT be rescaled into the target's space and plotted as a "sibling" —
        // only same-muscle siblings (muscleIds) count.
        val snap = snapshot() // 1L, 2L are CHEST
        val crossMuscleId = 3L
        val snapWithCross = ReplaySnapshot(
            exerciseMuscle = snap.exerciseMuscle + (crossMuscleId to MuscleGroup.QUADS),
            seedCoefficients = snap.seedCoefficients + (crossMuscleId to 0.5f),
        ).also {
            it.currentBeliefs.putAll(snap.currentBeliefs)
            it.currentBeliefs[crossMuscleId] = Belief(mu = ln(200f), sigma2 = 0.01f, updatedAt = 0L)
        }
        val sets = listOf(
            set(exerciseId = 2L, weight = 60f, reps = 5),           // same-muscle sibling
            set(exerciseId = crossMuscleId, weight = 120f, reps = 5), // cross-muscle, must be ignored
        )
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snapWithCross,
            sets = sets,
            asOf = 1_000L,
            config = config,
        )
        assertEquals(1, sample.siblingObservations.size)
    }

    @Test
    fun mergedLineEqualsPoolingEffectiveMu() {
        // Target (1) at 80 kg; sibling (2) at 60 kg with seed 0.6 implies level ~100.
        // The leave-one-out prediction for the target is ~100 (sibling-only pool), but the pooled
        // effective belief shrinks the target's own belief (80) toward the prediction, so merged != siblings.
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f, 2L to 0.6f),
        )
        snap.currentBeliefs[1L] = Belief(mu = ln(80f), sigma2 = 0.01f, updatedAt = 0L)
        snap.currentBeliefs[2L] = Belief(mu = ln(60f), sigma2 = 0.01f, updatedAt = 0L)
        val asOf = 1_000L
        val pooling = BeliefPooling(config)

        // Target performed a set so the muscle is "touched" and lines are sampled.
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = listOf(set(exerciseId = 1L, weight = 80f, reps = 5)),
            asOf = asOf,
            config = config,
        )

        // Expected merged value: full pooling effective mu for target 1.
        val expectedEffective = pooling
            .effective(snap.currentBeliefs, snap.seedCoefficients, listOf(1L, 2L), asOf)
            .effective.getValue(1L)
        val expectedMerged = exp(expectedEffective.mu)

        assertEquals(1, sample.merged.size)
        assertEquals(expectedMerged, sample.merged.single().value, 1e-3f)

        val expectedUpper = exp(expectedEffective.mu + sqrt(expectedEffective.sigma2))
        val expectedLower = exp(expectedEffective.mu - sqrt(expectedEffective.sigma2))
        assertEquals(1, sample.bandUpper.size)
        assertEquals(expectedUpper, sample.bandUpper.single().value, 1e-3f)
        assertEquals(expectedLower, sample.bandLower.single().value, 1e-3f)

        // merged must meaningfully differ from the leave-one-out (sibling-only) prediction.
        // siblingsEstimate ≈ 100 (sibling 2 implies level 100); merged is shrunk toward 100 from 80 < 100.
        assertEquals(1, sample.siblingsEstimate.size)
        assertTrue(abs(sample.merged.single().value - sample.siblingsEstimate.single().value) > 1f)
    }

    @Test
    fun ownEstimateReflectsTheCurrentPostFoldBelief() {
        // sampleSession reads currentBeliefs as-is (post-fold is the caller/ReplayEngine's job) —
        // here we simulate the post-fold state directly by folding the prior ourselves.
        val snap = snapshot()
        val asOf = 1_000L
        val sets = listOf(set(exerciseId = 1L, weight = 100f, reps = 5))
        val folded = io.github.fowles.stochastic_strength.domain.belief.BeliefFold(config)
            .foldSession(snap.currentBeliefs.getValue(1L), sets, asOf)
        snap.currentBeliefs[1L] = folded

        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = sets,
            asOf = asOf,
            config = config,
        )

        assertEquals(1, sample.ownEstimate.size)
        assertEquals(asOf, sample.ownEstimate.single().timestampMs)
        assertEquals(exp(folded.mu), sample.ownEstimate.single().value, 1e-2f)
    }

    @Test
    fun leaveOneOutLineExcludesTargetVote() {
        val snap = snapshot()
        // Make target (1) artificially huge; leave-one-out must ignore it and reflect sibling 2.
        snap.currentBeliefs[1L] = Belief(mu = ln(1000f), sigma2 = 0.01f, updatedAt = 0L)
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = listOf(set(exerciseId = 1L, weight = 100f, reps = 5)),
            asOf = 1_000L,
            config = config,
        )
        // Sibling 2 at 60 with seed 0.6 implies level ~100, so target prediction ~100*1.0 = 100,
        // NOT ~1000. Far below the inflated own estimate.
        assertEquals(1, sample.siblingsEstimate.size)
        assertTrue(sample.siblingsEstimate.first().value < 200f)
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
            sets = sets, asOf = asOf, namesById = names, config = config,
        )

        // Line values match sampleSession.
        val sample = sampleSession(1L, listOf(1L, 2L), snap, sets, asOf, config)
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
        val frame = buildFrame(1L, listOf(1L, 2L), snap, sets, 1_000L, names, config)
        assertEquals(listOf(2L), frame.observations.map { it.exerciseId })
    }

    @Test
    fun perSetDotsEmitOneDotPerSetWithFatigueCorrectedRank() {
        val snap = snapshot()
        val asOf = 1_000L
        // Two sets for the target in one session: rank 1 and rank 2 dots.
        val sets = listOf(
            set(exerciseId = 1L, weight = 100f, reps = 5).copy(id = 1L),
            set(exerciseId = 1L, weight = 100f, reps = 5).copy(id = 2L),
        )
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = sets,
            asOf = asOf,
            config = config,
        )
        assertEquals(2, sample.ownObservations.size)
        val expectedRank1 = exp(setObservationLn(sets[0], rank = 1, config)!!)
        val expectedRank2 = exp(setObservationLn(sets[1], rank = 2, config)!!)
        assertEquals(expectedRank1, sample.ownObservations[0].value, 1e-2f)
        assertEquals(expectedRank2, sample.ownObservations[1].value, 1e-2f)
    }
}
