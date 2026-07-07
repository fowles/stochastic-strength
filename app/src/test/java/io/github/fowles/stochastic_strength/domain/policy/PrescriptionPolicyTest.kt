package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrescriptionPolicyTest {

    private val DAY = 24L * 60 * 60 * 1000
    private val NOW = 100L * DAY

    private val bench = Exercise(id = 1L, name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)

    private fun policy(
        pooled: Map<Long, Float> = mapOf(1L to 100f),
        state: PolicyState = PolicyState.EMPTY,
        unit: WeightUnit = WeightUnit.KG,
    ) = PrescriptionPolicy(pooled, state, EstimatorConfig(), DefaultProgressionEngine, unit, nowMs = NOW)

    private fun oldFormulaWeight(e1rm: Float, reps: Int, unit: WeightUnit = WeightUnit.KG) =
        WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(e1rm, reps), unit)

    @Test
    fun neutralPolicyMatchesTheOldFormulaExactly() {
        assertEquals(oldFormulaWeight(100f, 10), policy().prescribe(bench, 10)!!, 1e-4f)
        assertNull("no pooled e1rm -> null", policy(pooled = emptyMap()).prescribe(bench, 10))
    }

    // For the ceiling tests the pooled belief must sit ABOVE the cap, or the ceiling never binds:
    // rawToOneRepMax(80 kg, 10 reps) ≈ 109.5 kg 1RM, so pooled = 120 kg forces the bind.
    private val pooledAboveCeiling = mapOf(1L to 120f)

    @Test
    fun clearCeilingPrescribesStrictlyBelowTheFailedWeight() {
        val failedWeight = 80f
        val ceiling = DefaultProgressionEngine.rawToOneRepMax(failedWeight, 10)
        val state = PolicyState(
            ceilings = mapOf(1L to FailureCeiling(1L, ceiling, isClear = true, sessionEndTime = NOW - DAY)),
            hurtEvents = emptyList(),
            muscleStress = emptyMap(),
        )
        val unbound = policy(pooled = pooledAboveCeiling).prescribe(bench, 10)!!
        assertTrue("precondition: without the ceiling the target exceeds the failed weight", unbound > failedWeight)
        val w = policy(pooled = pooledAboveCeiling, state = state).prescribe(bench, 10)!!
        assertTrue("prescribed $w must be strictly below failed $failedWeight", w < failedWeight)
    }

    @Test
    fun marginalCeilingAllowsTheSameGridWeight() {
        val failedWeight = 80f
        val ceiling = DefaultProgressionEngine.rawToOneRepMax(failedWeight, 10)
        val state = PolicyState(
            ceilings = mapOf(1L to FailureCeiling(1L, ceiling, isClear = false, sessionEndTime = NOW - DAY)),
            hurtEvents = emptyList(),
            muscleStress = emptyMap(),
        )
        val w = policy(pooled = pooledAboveCeiling, state = state).prescribe(bench, 10)!!
        assertEquals("marginal miss re-prescribes the failed grid weight", failedWeight, w, 1e-3f)
    }

    @Test
    fun ceilingIsRepAwareAndExpires() {
        val ceiling = DefaultProgressionEngine.rawToOneRepMax(80f, 10)
        val fresh = FailureCeiling(1L, ceiling, isClear = true, sessionEndTime = NOW - DAY)
        val freshState = PolicyState(mapOf(1L to fresh), emptyList(), emptyMap())
        // At 5 reps the same 1RM cap allows a heavier bar than the failed 10-rep weight.
        assertTrue(policy(pooled = pooledAboveCeiling, state = freshState).prescribe(bench, 5)!! > 80f * 0.97f)

        val stale = fresh.copy(sessionEndTime = NOW - 29 * DAY)
        val staleState = PolicyState(mapOf(1L to stale), emptyList(), emptyMap())
        assertEquals(
            "expired ceiling does not bind",
            oldFormulaWeight(120f, 10),
            policy(pooled = pooledAboveCeiling, state = staleState).prescribe(bench, 10)!!,
            1e-4f,
        )
    }

    @Test
    fun hurtMultiplierDecaysAndFloors() {
        val p0 = policy(state = PolicyState(emptyMap(), listOf(HurtEvent(MuscleGroup.CHEST, NOW)), emptyMap()))
        assertEquals(0.85f, p0.hurtMultiplier(MuscleGroup.CHEST), 1e-3f)

        val p14 = policy(state = PolicyState(emptyMap(), listOf(HurtEvent(MuscleGroup.CHEST, NOW - 14 * DAY)), emptyMap()))
        assertEquals(1f - 0.15f / 2f, p14.hurtMultiplier(MuscleGroup.CHEST), 1e-3f)

        val many = List(8) { HurtEvent(MuscleGroup.CHEST, NOW) }
        val pFloor = policy(state = PolicyState(emptyMap(), many, emptyMap()))
        assertEquals(EstimatorConfig().hurtFloor, pFloor.hurtMultiplier(MuscleGroup.CHEST), 1e-3f)

        assertEquals("other muscles unaffected", 1f, p0.hurtMultiplier(MuscleGroup.QUADS), 0f)
    }

    @Test
    fun hurtLowersThePrescribedWeight() {
        val hurt = policy(state = PolicyState(emptyMap(), listOf(HurtEvent(MuscleGroup.CHEST, NOW)), emptyMap()))
        assertTrue(hurt.prescribe(bench, 10)!! < policy().prescribe(bench, 10)!!)
    }

    @Test
    fun muscleRestedMatchesTheOldPlannerRule() {
        val recent = NOW - DAY
        fun stressed(state: MuscleStress) = policy(
            state = PolicyState(emptyMap(), emptyList(), mapOf(MuscleGroup.CHEST to state)),
        )
        // Any TOO_HARD within 2 days blocks.
        assertFalse(stressed(MuscleStress(listOf(recent), emptyMap())).muscleRested(MuscleGroup.CHEST))
        // >1 RIR_0_1 on ONE exercise within 2 days blocks.
        assertFalse(stressed(MuscleStress(emptyList(), mapOf(1L to listOf(recent, recent - 1000)))).muscleRested(MuscleGroup.CHEST))
        // Single RIR_0_1, or split across two exercises, does not block.
        assertTrue(stressed(MuscleStress(emptyList(), mapOf(1L to listOf(recent)))).muscleRested(MuscleGroup.CHEST))
        assertTrue(stressed(MuscleStress(emptyList(), mapOf(1L to listOf(recent), 2L to listOf(recent - 1000)))).muscleRested(MuscleGroup.CHEST))
        // Older than 2 days does not block.
        assertTrue(stressed(MuscleStress(listOf(NOW - 3 * DAY), emptyMap())).muscleRested(MuscleGroup.CHEST))
        // Unknown muscle is rested.
        assertTrue(policy().muscleRested(MuscleGroup.BACK))
    }

    @Test
    fun roundDownSnapsToTheGridBelow() {
        assertEquals(77.5f, WeightFormatter.roundDown(79.9f, WeightUnit.KG), 1e-4f)
        assertEquals(80f, WeightFormatter.roundDown(80f, WeightUnit.KG), 1e-4f)
        assertEquals(WeightUnit.LBS.toKg(75f), WeightFormatter.roundDown(WeightUnit.LBS.toKg(79f), WeightUnit.LBS), 1e-3f)
    }

    @Test
    fun hurtCompoundsUnderTheCeilingAndNeverRoundsBackToTheFailedWeight() {
        // Fail 35 kg x10 clearly; a 28-day-old HURT (multiplier ~0.9625) drags the clamped target
        // just under the cap. With hurt applied BEFORE the ceiling (old order) the cap never bound
        // and nearest-rounding landed back on 35 kg. Spec order + hazard-scoped round-down forbids it.
        val failedWeight = 35f
        val ceiling = DefaultProgressionEngine.rawToOneRepMax(failedWeight, 10)
        val state = PolicyState(
            ceilings = mapOf(1L to FailureCeiling(1L, ceiling, isClear = true, sessionEndTime = NOW - DAY)),
            hurtEvents = listOf(HurtEvent(MuscleGroup.CHEST, NOW - 28 * DAY)),
            muscleStress = emptyMap(),
        )
        val w = policy(pooled = mapOf(1L to 51.9f), state = state).prescribe(bench, 10)!!
        assertTrue("prescribed $w must stay strictly below failed $failedWeight", w < failedWeight)
    }

    @Test
    fun nearCapTargetWithoutHurtAlsoStaysBelowTheFailedWeight() {
        // Even with no HURT at all: a pooled estimate landing just under the clear cap must not
        // nearest-round back up to the failed weight (at 35 kg, half a 2.5 kg grid step exceeds
        // the 3% haircut, so nearest-rounding alone would re-prescribe the failed weight).
        val failedWeight = 35f
        val ceiling = DefaultProgressionEngine.rawToOneRepMax(failedWeight, 10)
        val state = PolicyState(
            ceilings = mapOf(1L to FailureCeiling(1L, ceiling, isClear = true, sessionEndTime = NOW - DAY)),
            hurtEvents = emptyList(),
            muscleStress = emptyMap(),
        )
        val justUnderCap = ceiling * 0.97f * 0.999f
        val w = policy(pooled = mapOf(1L to justUnderCap), state = state).prescribe(bench, 10)!!
        assertTrue("prescribed $w must stay strictly below failed $failedWeight", w < failedWeight)
    }
}
