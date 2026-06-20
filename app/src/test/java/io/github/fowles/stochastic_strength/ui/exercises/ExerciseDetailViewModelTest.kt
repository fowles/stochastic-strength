package io.github.fowles.stochastic_strength.ui.exercises

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
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

    @Test
    fun `uses true unrounded product not a plate-rounded value`() {
        val baselines = listOf(baseline(10, 0f, 101.3f))
        val points = buildPrescribedPoints(baselines, listOf(coeff(10, 0.617f)),
            seedCoefficient = 0.617f, dayKeys = listOf(15L), zone = zone)
        assertEquals(62.5021f, points[0].weightKg, 0.001f)
    }
}
