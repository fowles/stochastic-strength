package io.github.fowles.stochastic_strength.ui.exercises

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.progression.impliedSessionE1rm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ExerciseDetailViewModelTest {

    private val zone = ZoneOffset.UTC
    private val dayMs = 86_400_000L

    private fun baseline(day: Long, prev: Float, new: Float) = BaselineHistory(
        id = 0,
        sessionId = null,
        muscleGroup = MuscleGroup.CHEST,
        previousBaseline = prev,
        newBaseline = new,
        changeReason = BaselineChangeReason.PROGRESSION,
        timestamp = day * dayMs,
    )

    private fun coeff(day: Long, value: Float) = CoefficientHistory(
        id = 0,
        exerciseId = 1L,
        coefficient = value,
        heuristicName = "test",
        computedAt = day * dayMs,
    )

    @Test
    fun `samples latest baseline and coefficient at or before each day`() {
        val baselines = listOf(baseline(10, 100f, 110f), baseline(20, 110f, 120f))
        val coeffs = listOf(coeff(10, 0.5f), coeff(20, 0.6f))
        val points = buildPrescribedPoints(baselines, coeffs, seedCoefficient = 0.5f,
            dayKeys = listOf(15L, 25L), zone = zone)
        assertEquals(2, points.size)
        // day 15: baseline 110 (event@10), coeff 0.5 (event@10) -> 55
        assertEquals(15 * dayMs, points[0].dateMs)
        assertEquals(55f, points[0].weightKg, 0.0001f)
        // day 25: baseline 120 (event@20), coeff 0.6 (event@20) -> 72
        assertEquals(72f, points[1].weightKg, 0.0001f)
    }

    @Test
    fun `falls back to seed coefficient before first coefficient event`() {
        val baselines = listOf(baseline(10, 100f, 110f))
        val points = buildPrescribedPoints(baselines, coefficientEvents = emptyList(),
            seedCoefficient = 0.4f, dayKeys = listOf(15L), zone = zone)
        assertEquals(1, points.size)
        assertEquals(110f * 0.4f, points[0].weightKg, 0.0001f)
    }

    @Test
    fun `uses previousBaseline for days before the first baseline event`() {
        val baselines = listOf(baseline(10, 90f, 110f))
        val points = buildPrescribedPoints(baselines, listOf(coeff(0, 0.5f)),
            seedCoefficient = 0.5f, dayKeys = listOf(5L), zone = zone)
        assertEquals(1, points.size)
        assertEquals(90f * 0.5f, points[0].weightKg, 0.0001f)
    }

    @Test
    fun `drops leading days when first event previousBaseline is zero`() {
        val baselines = listOf(baseline(10, 0f, 110f)) // INITIAL assessment
        val points = buildPrescribedPoints(baselines, listOf(coeff(0, 0.5f)),
            seedCoefficient = 0.5f, dayKeys = listOf(5L, 15L), zone = zone)
        // day 5 dropped (no baseline yet), day 15 kept
        assertEquals(1, points.size)
        assertEquals(15 * dayMs, points[0].dateMs)
        assertEquals(110f * 0.5f, points[0].weightKg, 0.0001f)
    }

    @Test
    fun `returns empty for bodyweight exercises`() {
        val baselines = listOf(baseline(10, 100f, 110f))
        val points = buildPrescribedPoints(baselines, listOf(coeff(10, 0.5f)),
            seedCoefficient = 0f, dayKeys = listOf(15L), zone = zone)
        assertTrue(points.isEmpty())
    }

    private fun workSet(
        session: Long,
        setNumber: Int,
        weight: Float,
        targetReps: Int,
        feedback: SetFeedback?,
        actualReps: Int? = null,
    ) = WorkoutSet(
        sessionId = session,
        exerciseId = 1L,
        setNumber = setNumber,
        targetWeight = weight,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
        completedAt = 1L,
    )

    @Test
    fun `observed dot uses the broad-prior implied session e1rm`() {
        // The broad-prior implied e1rm should give a non-zero result for a valid set.
        val sets = listOf(
            workSet(session = 1, setNumber = 1, weight = 100f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS),
            workSet(session = 1, setNumber = 2, weight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
        )
        val expected = impliedSessionE1rm(sets)!!
        val points = observedSessionPoints(listOf(ObservedSession(day = 7L, scale = 1f, sets = sets)))
        assertEquals(1, points.size)
        assertEquals(7 * dayMs, points[0].dateMs)
        assertEquals(expected, points[0].weightKg, 0.0001f)
    }

    @Test
    fun `observed dot omits sessions with no load-bearing signal`() {
        val sets = listOf(workSet(session = 1, setNumber = 1, weight = 100f, targetReps = 5, feedback = SetFeedback.HURT))
        assertTrue(observedSessionPoints(listOf(ObservedSession(day = 7L, scale = 1f, sets = sets))).isEmpty())
    }

    @Test
    fun `observed dot scales the implied e1rm into the target exercise's space`() {
        val sets = listOf(workSet(session = 1, setNumber = 1, weight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1))
        val expected = impliedSessionE1rm(sets)!!
        val points = observedSessionPoints(listOf(ObservedSession(day = 7L, scale = 0.5f, sets = sets)))
        assertEquals(expected * 0.5f, points[0].weightKg, 0.0001f)
    }

    @Test
    fun `emits one dot per session, never averaging sessions that share a day`() {
        // Two siblings trained the same day with very different scale factors: the debug chart shows
        // two dots, so this view must too (the old per-day mean collapsed them into one).
        val s1 = listOf(workSet(session = 1, setNumber = 1, weight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1))
        val s2 = listOf(workSet(session = 2, setNumber = 1, weight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1))
        val implied = impliedSessionE1rm(s1)!!
        val points = observedSessionPoints(
            listOf(
                ObservedSession(day = 7L, scale = 0.77f, sets = s1),
                ObservedSession(day = 7L, scale = 3.33f, sets = s2),
            )
        )
        assertEquals(2, points.size)
        assertEquals(7 * dayMs, points[0].dateMs)
        assertEquals(7 * dayMs, points[1].dateMs)
        val values = points.map { it.weightKg }.sorted()
        assertEquals(implied * 0.77f, values[0], 0.0001f)
        assertEquals(implied * 3.33f, values[1], 0.0001f)
    }

    @Test
    fun `uses true unrounded product not a plate-rounded value`() {
        val baselines = listOf(baseline(10, 0f, 101.3f))
        val points = buildPrescribedPoints(baselines, listOf(coeff(10, 0.617f)),
            seedCoefficient = 0.617f, dayKeys = listOf(15L), zone = zone)
        assertEquals(62.5021f, points[0].weightKg, 0.001f)
    }
}
