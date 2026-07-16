package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class PrescriptionPolicyTest {

    private fun set(feedback: SetFeedback?, w: Float = 100f, r: Int = 10, a: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = w, targetReps = r, actualReps = a, feedback = feedback)

    private fun lnRm(w: Float, reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))

    // --- capLnFor ---

    @Test
    fun failedSessionCapsAtTheFailuresImpliedOneRepMax() {
        // Counted failure: implied 1RM = 1RM(w, a + 0.5) (spec phase-0 table midpoint).
        val cap = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 35f, a = 2)))
        assertEquals(lnRm(35f, 2.5f), cap!!, 1e-6f)
    }

    @Test
    fun failedSessionTakesTheMinOverFailedSetsAndIgnoresSuccesses() {
        val cap = PrescriptionPolicy.capLnFor(
            listOf(
                set(SetFeedback.TOO_HARD, w = 24.9f, a = 2),
                set(SetFeedback.TOO_HARD, w = 15.9f, a = 2),
                set(SetFeedback.RIR_0_1, w = 9.1f),  // success does not lift a failed session's cap
            )
        )
        assertEquals(lnRm(15.9f, 2.5f), cap!!, 1e-6f)
    }

    @Test
    fun uncountedFailureCapsAtTargetRepsBound() {
        val cap = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 35f, a = null)))
        assertEquals(lnRm(35f, 10f), cap!!, 1e-6f)
    }

    @Test
    fun cleanSessionCapsAtMaxDemonstratedUpperBound() {
        // RIR_0_1 at (w, r) → upper 1RM(w, r+2); RIR_2_4 → 1RM(w, r+5). Max wins.
        val cap = PrescriptionPolicy.capLnFor(
            listOf(set(SetFeedback.RIR_0_1, w = 20f), set(SetFeedback.RIR_2_4, w = 18f))
        )
        assertEquals(maxOf(lnRm(20f, 12f), lnRm(18f, 15f)), cap!!, 1e-6f)
    }

    @Test
    fun anyRir5PlusSetUncapsACleanSession() {
        assertNull(PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.RIR_5_PLUS), set(SetFeedback.RIR_0_1))))
    }

    @Test
    fun hurtOnlyOrFeedbacklessSessionHasNoCap() {
        assertNull(PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.HURT), set(null))))
        assertNull(PrescriptionPolicy.capLnFor(emptyList()))
    }

    // --- hurtMultiplier ---

    @Test
    fun freshHurtBacksOffByDepthAndFadesWithHalfLife() {
        val now = 0L
        assertEquals(0.85f, PrescriptionPolicy.hurtMultiplier(listOf(now), now), 1e-4f)
        val after14d = 14L * 24 * 60 * 60 * 1000
        assertEquals(0.925f, PrescriptionPolicy.hurtMultiplier(listOf(0L), after14d), 1e-4f)
    }

    @Test
    fun noHurtEventsMeansNoBackoff() {
        assertEquals(1f, PrescriptionPolicy.hurtMultiplier(emptyList(), 0L), 0f)
    }

    @Test
    fun stackedHurtsFloorAtSixtyPercent() {
        val m = PrescriptionPolicy.hurtMultiplier(List(7) { 0L }, 0L)  // 0.85^7 ≈ 0.32 → floored
        assertEquals(0.6f, m, 1e-6f)
        assertTrue(PrescriptionPolicy.hurtMultiplier(List(2) { 0L }, 0L) > 0.6f)
    }

    // --- prescribe ---

    private val DAY = 24L * 60 * 60 * 1000

    private fun prescribe(
        rawE1rm: Float,
        facts: PolicyFacts,
        reps: Int = 10,
        now: Long = 30 * DAY,
        unit: WeightUnit = WeightUnit.KG,
    ) = PrescriptionPolicy.prescribe(
        rawE1rm = rawE1rm, sessionReps = reps, exerciseId = 1L, muscle = MuscleGroup.QUADS,
        facts = facts, now = now, weightUnit = unit, engine = DefaultProgressionEngine,
    )

    private fun capFacts(capLn: Float?, at: Long) =
        PolicyFacts(capByExercise = mapOf(1L to ExerciseCapFact(capLn, at)))

    @Test
    fun noFactsReproducesTheLegacyRoundedPrescription() {
        val raw = DefaultProgressionEngine.rawToOneRepMax(100f, 10f)
        val p = prescribe(raw, PolicyFacts.EMPTY)
        assertEquals(WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(raw, 10), WeightUnit.KG), p.weightKg, 1e-4f)
        assertFalse(p.capBound)
        assertEquals(1f, p.hurtMultiplier, 0f)
    }

    @Test
    fun bindingCapFloorsAtTheGridAndClosesTheFailThenNarrowSuccessHole() {
        // "fail 35 → narrowly succeed at 20 → engine says 35 again": most recent session was the
        // narrow success, so the cap is 1RM(20, 12) and the prescription creeps instead of jumping.
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.RIR_0_1, w = 20f)))
        val raw = DefaultProgressionEngine.rawToOneRepMax(35f, 10f)
        val p = prescribe(raw, capFacts(capLn, at = 29 * DAY))
        assertTrue(p.capBound)
        assertTrue("crept prescription, not a jump back to 35", p.weightKg < 25f)
        assertTrue("cap is above the demonstrated 20", p.weightKg >= 20f)
    }

    @Test
    fun prescriptionAfterAFailureIsStrictlyBelowTheFailedWeight() {
        // Light-weight edge: failed 10 kg × 10 doing 9 — even one rep short must prescribe < 10 kg.
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 10f, a = 9)))
        val p = prescribe(DefaultProgressionEngine.rawToOneRepMax(10f, 10f), capFacts(capLn, at = 29 * DAY))
        assertTrue(p.capBound)
        assertTrue(p.weightKg < 10f)
    }

    @Test
    fun expiredCapDoesNotBind() {
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 20f, a = 2)))
        val raw = DefaultProgressionEngine.rawToOneRepMax(35f, 10f)
        val p = prescribe(raw, capFacts(capLn, at = 1 * DAY), now = 30 * DAY)  // 29 days later
        assertFalse(p.capBound)
    }

    @Test
    fun rawBelowTheCapPassesThroughUnbound() {
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.RIR_0_1, w = 20f)))
        val raw = exp(capLn!!) * 0.9f
        val p = prescribe(raw, capFacts(capLn, at = 29 * DAY))
        assertFalse(p.capBound)
    }

    @Test
    fun hurtBackoffScalesThePrescriptionAndCapAppliesOnTop() {
        val now = 30 * DAY
        val facts = PolicyFacts(hurtEventsByMuscle = mapOf(MuscleGroup.QUADS to listOf(now)))
        val raw = DefaultProgressionEngine.rawToOneRepMax(100f, 10f)
        val backed = prescribe(raw, facts, now = now)
        val unbacked = prescribe(raw, PolicyFacts.EMPTY, now = now)
        assertEquals(0.85f, backed.hurtMultiplier, 1e-4f)
        assertTrue(backed.weightKg < unbacked.weightKg)
    }

    @Test
    fun nearCapEstimateCannotRoundBackUpToTheFailedWeight() {
        // Real-history hole (exercise 30): failed 15 lb x 10 doing 9; a raw estimate just
        // BELOW the cap used nearest-rounding and climbed back to exactly 15 lb.
        val failedKg = 6.803894f  // 15 lb
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = failedKg, a = 9)))
        val raw = exp(capLn!!) * 0.995f  // just under the cap: old code took the uncapped path
        val p = prescribe(raw, capFacts(capLn, at = 29 * DAY), unit = WeightUnit.LBS)
        assertTrue("prescribed ${p.weightKg} must be strictly below the failed $failedKg", p.weightKg < failedKg)
    }

    @Test
    fun capBindsAtExactlyTheExpiryBoundaryAndNotOneMsLater() {
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 20f, a = 2)))
        val raw = DefaultProgressionEngine.rawToOneRepMax(35f, 10f)
        val at = 30 * DAY
        // now - demonstratedAt == CAP_EXPIRY_MS → still binds (<= comparison)…
        assertTrue(prescribe(raw, capFacts(capLn, at), now = at + PrescriptionPolicy.CAP_EXPIRY_MS).capBound)
        // …one ms past the boundary → expired.
        assertFalse(prescribe(raw, capFacts(capLn, at), now = at + PrescriptionPolicy.CAP_EXPIRY_MS + 1).capBound)
    }

    @Test
    fun capAppliesOnTopOfHurtBackoff() {
        // A binding cap must ceiling the BACKED-OFF target: with a fresh HURT and a low cap,
        // the result is the capped weight, and both clamp indicators report.
        val now = 30 * DAY
        val capLn = PrescriptionPolicy.capLnFor(listOf(set(SetFeedback.TOO_HARD, w = 20f, a = 2)))
        val facts = PolicyFacts(
            capByExercise = mapOf(1L to ExerciseCapFact(capLn, now - DAY)),
            hurtEventsByMuscle = mapOf(MuscleGroup.QUADS to listOf(now)),
        )
        val raw = DefaultProgressionEngine.rawToOneRepMax(100f, 10f)  // far above the cap even after ×0.85
        val p = prescribe(raw, facts, now = now)
        assertTrue(p.capBound)
        assertEquals(0.85f, p.hurtMultiplier, 1e-4f)
        // Same result as the cap alone: the cap is the binding constraint after backoff.
        assertEquals(prescribe(raw, capFacts(capLn, now - DAY), now = now).weightKg, p.weightKg, 1e-4f)
    }

    // --- overload nudge ---

    @Test
    fun overloadNudgeAddsOneIncrementWhenLastSessionWasAllEasy() {
        val allEasy = PolicyFacts(capByExercise = mapOf(
            1L to ExerciseCapFact(capLn = null, demonstratedAt = 0L, allEasy = true),
        ))
        val notEasy = PolicyFacts(capByExercise = mapOf(
            1L to ExerciseCapFact(capLn = null, demonstratedAt = 0L, allEasy = false),
        ))
        val raw = DefaultProgressionEngine.rawToOneRepMax(100f, 10f)
        val base = prescribe(raw, notEasy, now = 1_000L)
        val nudged = prescribe(raw, allEasy, now = 1_000L)
        assertEquals(base.weightKg + WeightFormatter.minIncrement(WeightUnit.KG), nudged.weightKg, 1e-4f)
        // The prescription reports the applied nudge instead of consumers re-deriving the rule.
        assertEquals(WeightFormatter.minIncrement(WeightUnit.KG), nudged.nudgeKg, 1e-4f)
        assertEquals(0f, base.nudgeKg, 1e-4f)
    }

    @Test
    fun overloadNudgeExpiresWithTheCapWindowAndNeverPiercesACap() {
        val old = PolicyFacts(capByExercise = mapOf(
            1L to ExerciseCapFact(capLn = null, demonstratedAt = 0L, allEasy = true),
        ))
        val raw = DefaultProgressionEngine.rawToOneRepMax(100f, 10f)
        val expiredNow = PrescriptionPolicy.CAP_EXPIRY_MS + 1
        val expired = prescribe(raw, old, now = expiredNow)
        val noFacts = prescribe(raw, PolicyFacts.EMPTY, now = expiredNow)
        assertEquals(noFacts.weightKg, expired.weightKg, 1e-4f)
        assertEquals(0f, expired.nudgeKg, 1e-4f)

        // A capped exercise: nudge cannot climb past the cap (cap applies on top, spec Phase 2).
        val capLn = ln(DefaultProgressionEngine.rawToOneRepMax(80f, 5.5f))
        val capped = PolicyFacts(capByExercise = mapOf(
            1L to ExerciseCapFact(capLn = capLn, demonstratedAt = 0L, allEasy = true),
        ))
        val notEasyCapped = PolicyFacts(capByExercise = mapOf(
            1L to ExerciseCapFact(capLn = capLn, demonstratedAt = 0L, allEasy = false),
        ))
        val withNudge = prescribe(raw, capped, now = 1_000L)
        val withoutNudge = prescribe(raw, notEasyCapped, now = 1_000L)
        assertEquals(withoutNudge.weightKg, withNudge.weightKg, 1e-4f)
        assertTrue(withNudge.capBound)
        // The cap weight is reported for the trace.
        assertEquals(DefaultProgressionEngine.rawFromOneRepMax(exp(capLn), 10), withNudge.capWeightKg!!, 1e-4f)
    }

    @Test
    fun prescriptionReportsUncappedWeightWhenCapBinds() {
        // Cap demonstrated well below the raw target: raw 100 kg e1rm at 5 reps vs a cap of ln(80).
        val facts = PolicyFacts(
            capByExercise = mapOf(7L to ExerciseCapFact(capLn = ln(80f), demonstratedAt = 1_000L)),
        )
        val p = PrescriptionPolicy.prescribe(
            rawE1rm = 100f, sessionReps = 5, exerciseId = 7L, muscle = MuscleGroup.QUADS,
            facts = facts, now = 2_000L, weightUnit = WeightUnit.KG, engine = DefaultProgressionEngine,
        )
        assertTrue(p.capBound)
        // The uncapped weight is what the engine would have prescribed with no cap.
        val free = PrescriptionPolicy.prescribe(
            rawE1rm = 100f, sessionReps = 5, exerciseId = 99L, muscle = MuscleGroup.QUADS,
            facts = PolicyFacts.EMPTY, now = 2_000L, weightUnit = WeightUnit.KG, engine = DefaultProgressionEngine,
        )
        assertEquals(free.weightKg, p.uncappedWeightKg, 1e-4f)
        assertEquals(free.weightKg, free.uncappedWeightKg, 1e-4f)
    }
}
