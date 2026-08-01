package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefFold
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class ExerciseProgressionSeriesBuilderCoreTest {
    private val config = BeliefConfig()
    private val builder = ExerciseProgressionSeriesBuilder(config)

    private fun setAt(exerciseId: Long, sessionId: Long, weight: Float, reps: Int, at: Long) = WorkoutSet(
        sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
        targetWeight = weight, targetReps = reps, actualReps = reps,
        feedback = SetFeedback.RIR_2_4, completedAt = at,
    )

    private fun snapshotSeeded(): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f),
        )
        // Initial override seeds a belief at 100 kg so the very first pre-fold decision has a belief.
        return snap
    }

    @Test
    fun framesArePreFoldAndTrailingPredictedFrameIsLive() = runBlocking {
        val snap = snapshotSeeded()
        val initial = SeedBelief(sessionId = null, exerciseId = 1L, e1rm = 100f, asOf = 0L)
        val s1 = WorkoutSession(id = 1L, startTime = 0L, endTime = 1_000L)
        val s2 = WorkoutSession(id = 2L, startTime = 2_000L, endTime = 3_000L)
        val setsBySession = mapOf(
            1L to listOf(setAt(1L, 1L, weight = 100f, reps = 5, at = 1_000L)),
            2L to listOf(setAt(1L, 2L, weight = 105f, reps = 5, at = 3_000L)),
        )
        // `now` is several days after the last session (well past last-set + 24h): the predicted
        // point stays at `now`.
        val now = 5L * 24 * 60 * 60 * 1000

        val data = builder.buildCore(
            exerciseId = 1L,
            snapshot = snap,
            muscle = MuscleGroup.CHEST,
            muscleIds = listOf(1L),
            namesById = mapOf(1L to "Bench"),
            weightUnit = WeightUnit.KG,
            initialSeeds = listOf(initial),
            sessionSeeds = emptyMap(),
            sessions = listOf(s1, s2),
            setsForSession = { setsBySession.getValue(it) },
            now = now,
        )

        // Two historical session frames + one synthetic predicted frame.
        assertEquals(2, data.frames.size)
        assertNotNull(data.predictedFrame)

        // Frame entering S1 = pre-fold = the seeded 100 kg belief.
        assertEquals(100f, data.frames[0].own!!, 0.5f)

        // Frame entering S2 = pre-fold = the belief AFTER folding S1. Compute it directly.
        val afterS1 = BeliefFold(config).foldSession(
            Belief(bestGuessLn = ln(100f), uncertainty = config.seedUncertaintySd * config.seedUncertaintySd, updatedAt = 0L),
            setsBySession.getValue(1L),
            1_000L,
        )
        assertEquals(exp(afterS1.bestGuessLn), data.frames[1].own!!, 0.5f)

        // The predicted frame is stamped at `now` and reflects the state AFTER S2 (differs from S2's frame).
        assertEquals(now, data.predictedFrame!!.timestampMs)
        assertTrue(data.predictedFrame!!.own!! != data.frames[1].own!!)

        // Every emitted frame carries a trace.
        assertTrue(data.frames.all { it.trace != null })
        assertNotNull(data.predictedFrame!!.trace)
    }

    @Test
    fun predictedFramePushedTo24hAfterLastSetWhenNowIsTooSoon() = runBlocking {
        val snap = snapshotSeeded()
        val initial = SeedBelief(sessionId = null, exerciseId = 1L, e1rm = 100f, asOf = 0L)
        val day = 24 * 60 * 60 * 1000L
        val s1Time = 2 * day + 1_000L
        val s2Time = 2 * day + 3_000L
        val s1 = WorkoutSession(id = 1L, startTime = 2 * day, endTime = s1Time)
        val s2 = WorkoutSession(id = 2L, startTime = 2 * day + 2_000L, endTime = s2Time)
        val setsBySession = mapOf(
            1L to listOf(setAt(1L, 1L, weight = 100f, reps = 5, at = s1Time)),
            2L to listOf(setAt(1L, 2L, weight = 105f, reps = 5, at = s2Time)),
        )
        // `now` is only seconds after the last session (< last-set + 24h).
        val now = 2 * day + 10_000L

        val data = builder.buildCore(
            exerciseId = 1L,
            snapshot = snap,
            muscle = MuscleGroup.CHEST,
            muscleIds = listOf(1L),
            namesById = mapOf(1L to "Bench"),
            weightUnit = WeightUnit.KG,
            initialSeeds = listOf(initial),
            sessionSeeds = emptyMap(),
            sessions = listOf(s1, s2),
            setsForSession = { setsBySession.getValue(it) },
            now = now,
        )

        // Too soon → the predicted point is stamped 24h after the last session, not at `now`.
        assertEquals(s2Time + day, data.predictedFrame!!.timestampMs)
    }

    @Test
    fun noTouchedSessionsYieldsEmptyFramesAndNullPredicted() = runBlocking {
        val snap = snapshotSeeded()
        val initial = SeedBelief(sessionId = null, exerciseId = 1L, e1rm = 100f, asOf = 0L)
        val now = 9_999_999L

        val data = builder.buildCore(
            exerciseId = 1L,
            snapshot = snap,
            muscle = MuscleGroup.CHEST,
            muscleIds = listOf(1L),
            namesById = mapOf(1L to "Bench"),
            weightUnit = WeightUnit.KG,
            initialSeeds = listOf(initial),
            sessionSeeds = emptyMap(),
            sessions = emptyList(),
            setsForSession = { emptyList() },
            now = now,
        )

        // With no sessions, the muscle is never touched, so no frames are built.
        assertTrue(data.frames.isEmpty())
        // The predicted frame is null when there are no frames to compute it from.
        assertTrue(data.predictedFrame == null)
        // The series estimate is empty when no sessions touched the muscle.
        assertTrue(data.series.ownEstimate.isEmpty())
    }
}
