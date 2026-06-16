package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExercisePacingEstimatorTest {

    private fun exercise(id: Long, isUnilateral: Boolean = false) = Exercise(
        id = id,
        name = "Ex$id",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
        isUnilateral = isUnilateral,
    )

    private fun session(id: Long, startTime: Long) =
        WorkoutSession(id = id, startTime = startTime, endTime = startTime + 60_000L)

    private fun set(
        sessionId: Long,
        exerciseId: Long,
        setNumber: Int,
        completedAt: Long?,
        targetReps: Int = 10,
        actualReps: Int? = null,
        feedback: SetFeedback? = SetFeedback.RIR_2_4,
    ) = WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = 80f,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
        completedAt = completedAt,
    )

    private fun assertNear(expected: Float, actual: Float?, tol: Float = 0.001f) {
        assertNotNull(actual)
        assertTrue("expected ~$expected, got $actual", abs(expected - actual!!) < tol)
    }

    @Test
    fun emptyInput_yieldsNoLearnedValues() {
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = emptyList(),
            setsBySessionId = emptyMap(),
            exercisesById = emptyMap(),
        )
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun singlePair_returnsExpectedSecondsPerRep() {
        // Set 1 at t=60s, set 2 at t=240s. delta = 180s. workTime = 180 - 90 = 90s.
        // reps = 8 (targetReps). secondsPerRep = 90 / 8 = 11.25.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 8),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L, targetReps = 8),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNear(11.25f, estimator.secondsPerRep(1L))
    }

    @Test
    fun unilateralExercise_dividesPerRepBySides() {
        // Same numbers as above, but unilateral. workTime/reps/sides = 90 / (8*2) = 5.625.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 8),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L, targetReps = 8),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L, isUnilateral = true)),
        )
        assertNear(5.625f, estimator.secondsPerRep(1L))
    }

    @Test
    fun multiplePairsInAppearance_averagedWithinAppearance() {
        // Set 1 at 60s, set 2 at 240s (delta 180, workTime 90, perRep 90/10 = 9.0).
        // Set 2 at 240s, set 3 at 390s (delta 150, workTime 60, perRep 60/10 = 6.0).
        // Average = 7.5.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 390_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNear(7.5f, estimator.secondsPerRep(1L))
    }

    @Test
    fun pairWithHurtFeedbackOnEitherSide_isSkipped() {
        // First pair has HURT on set 2 → skipped. Second pair valid → 6.0.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L, targetReps = 10, feedback = SetFeedback.HURT),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 390_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        // Only the (2,3) pair survives but set 2 has HURT → that pair also skipped.
        // No valid pairs → null.
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun pairWithNullCompletedAt_isSkipped() {
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = null, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 390_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        // Both candidate pairs touch the null-completedAt set → no valid samples → null.
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun sampleBelowMinSecondsPerRep_isSkipped() {
        // Set 1 at 60s, set 2 at 152s. delta = 92s. workTime = 2s. reps = 10 → 0.2 s/rep. Below 1.0 → skip.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 152_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun sampleAboveMaxSecondsPerRep_isSkipped() {
        // workTime = 600s, reps = 10 → 60 s/rep, above 30 → skip.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 750_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun actualReps_takesPrecedenceOverTargetReps() {
        // delta = 240s → workTime = 150s. reps = actualReps (5) → 30 s/rep — at the ceiling.
        // (At-or-below 30.0 passes by the !in 1f..30f bound, so this should be valid.)
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10, actualReps = 5),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 300_000L, targetReps = 10, actualReps = 5),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNear(30.0f, estimator.secondsPerRep(1L))
    }

    @Test
    fun multipleSessions_averagedAcrossAppearances() {
        // Session 20: one pair → 9.0 s/rep. Session 10: one pair → 6.0 s/rep. Mean = 7.5.
        val sessions = listOf(
            session(id = 20L, startTime = 1_000_000L),
            session(id = 10L, startTime = 0L),
        )
        val sets = mapOf(
            20L to listOf(
                set(20L, exerciseId = 1L, setNumber = 1, completedAt = 1_000_000L + 60_000L, targetReps = 10),
                set(20L, exerciseId = 1L, setNumber = 2, completedAt = 1_000_000L + 240_000L, targetReps = 10),
            ),
            10L to listOf(
                set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
                set(10L, exerciseId = 1L, setNumber = 2, completedAt = 210_000L, targetReps = 10),
            ),
        )
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNear(7.5f, estimator.secondsPerRep(1L))
    }

    @Test
    fun maxAppearancesCap_keepsOnlyTheNewest() {
        // 11 sessions; session N produces a single pair with perRep = N seconds.
        // Newest-first session IDs are 11..1. Keeping the 10 newest yields N=2..11, mean = 6.5.
        val sessions = (1..11).map { n ->
            session(id = n.toLong(), startTime = (n * 10_000_000L))
        }.sortedByDescending { it.startTime }

        val sets = (1..11).associate { n ->
            val start = n * 10_000_000L
            val workTimeSec = n * 10L                // → perRep = n s (workTime / 10 reps)
            val deltaMs = (workTimeSec + 90L) * 1000L // delta = workTime + REST_SECONDS
            n.toLong() to listOf(
                set(n.toLong(), exerciseId = 1L, setNumber = 1, completedAt = start, targetReps = 10),
                set(n.toLong(), exerciseId = 1L, setNumber = 2, completedAt = start + deltaMs, targetReps = 10),
            )
        }

        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        // 10 newest: N = 11, 10, 9, 8, 7, 6, 5, 4, 3, 2. Mean = (2+11)*10/2/10 = 6.5.
        assertNear(6.5f, estimator.secondsPerRep(1L))
    }
}
