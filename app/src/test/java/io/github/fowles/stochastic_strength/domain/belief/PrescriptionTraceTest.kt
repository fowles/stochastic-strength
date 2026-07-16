package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.policy.ExerciseCapFact
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class PrescriptionTraceTest {
    private val config = BeliefConfig()
    private val targetId = 1L
    private val siblingId = 2L
    private val muscle = MuscleGroup.QUADS

    private val seedCoef = mapOf(targetId to 0.3f, siblingId to 1.0f)
    private val muscleExerciseIds = listOf(targetId, siblingId)
    private val now = 1_000_000_000L

    // Own belief high and tight enough that the uncapped target actually exceeds the failed
    // session's demonstrated-capacity cap below, so the cap binds and cites the failed set.
    private val beliefs = mapOf(
        targetId to Belief(mu = ln(60f), sigma2 = 0.005f, updatedAt = now),
        siblingId to Belief(mu = ln(100f), sigma2 = 0.01f, updatedAt = now),
    )

    private val capSessionSets = listOf(
        WorkoutSet(
            id = 100L, sessionId = 50L, exerciseId = targetId, setNumber = 1,
            targetWeight = 35f, targetReps = 10, actualReps = 6,
            feedback = SetFeedback.TOO_HARD, completedAt = now - 1000L,
        ),
    )

    private val facts = PolicyFacts(
        capByExercise = mapOf(
            targetId to ExerciseCapFact(
                capLn = PrescriptionPolicy.capLnFor(capSessionSets),
                demonstratedAt = now - 1000L,
                allEasy = false,
            ),
        ),
        hurtEventsByMuscle = emptyMap(),
    )

    @Test
    fun traceListsEveryStageWithCitations() {
        val trace = PrescriptionTraceBuilder.build(
            exerciseId = targetId,
            muscle = muscle,
            beliefs = beliefs,
            seedCoef = seedCoef,
            muscleExerciseIds = muscleExerciseIds,
            facts = facts,
            capSessionSets = capSessionSets,
            sessionReps = 10,
            now = now,
            weightUnit = WeightUnit.KG,
            config = config,
            engine = DefaultProgressionEngine,
        )!!

        val labels = trace.lines.map { it.label }
        assertEquals(
            listOf("Own belief", "Sibling pull", "Effective belief", "Risk percentile", "HURT backoff", "Overload nudge", "Capacity cap", "Rounding"),
            labels,
        )

        val cap = trace.lines.first { it.label == "Capacity cap" }
        assertTrue(cap.detail.contains("35"))

        val pooling = BeliefPooling(config)
        val effective = pooling.effective(beliefs, seedCoef, muscleExerciseIds, now).effective.getValue(targetId)
        val rawE1rm = BeliefPrescriber.targetE1rm(effective)
        val expected = PrescriptionPolicy.prescribe(
            rawE1rm = rawE1rm, sessionReps = 10, exerciseId = targetId,
            muscle = MuscleGroup.QUADS, facts = facts, now = now, weightUnit = WeightUnit.KG,
            engine = DefaultProgressionEngine,
        )
        assertEquals(expected.weightKg, trace.finalWeightKg, 1e-4f)
    }

    @Test
    fun traceIsNullForAnExerciseWithNoEffectiveBelief() {
        val trace = PrescriptionTraceBuilder.build(
            exerciseId = targetId,
            muscle = muscle,
            beliefs = emptyMap(),
            seedCoef = mapOf(targetId to 0f, siblingId to 1.0f),
            muscleExerciseIds = muscleExerciseIds,
            facts = PolicyFacts.EMPTY,
            capSessionSets = emptyList(),
            sessionReps = 10,
            now = now,
            weightUnit = WeightUnit.KG,
            config = config,
            engine = DefaultProgressionEngine,
        )
        assertNull(trace)
    }

    @Test
    fun nonBindingCapLineShowsTheCapWeightNotTheWantedWeight() {
        // Own belief low enough that the target sits far below the demonstrated cap.
        val lowBeliefs = mapOf(
            targetId to Belief(mu = ln(20f), sigma2 = 0.005f, updatedAt = now),
        )
        val trace = PrescriptionTraceBuilder.build(
            exerciseId = targetId,
            muscle = muscle,
            beliefs = lowBeliefs,
            seedCoef = mapOf(targetId to 0.3f),
            muscleExerciseIds = listOf(targetId),
            facts = facts,
            capSessionSets = capSessionSets,
            sessionReps = 10,
            now = now,
            weightUnit = WeightUnit.KG,
            config = config,
            engine = DefaultProgressionEngine,
        )!!
        val capLine = trace.lines.first { it.label == "Capacity cap" }
        assertTrue(capLine.detail.contains("not binding"))
        // The line must show the CAP weight (raw rep-max inverse of the demonstrated cap), not the
        // uncapped wanted weight the old code printed.
        val capWeight = DefaultProgressionEngine.rawFromOneRepMax(
            kotlin.math.exp(PrescriptionPolicy.capLnFor(capSessionSets)!!), 10,
        )
        assertTrue(
            "expected cap ~$capWeight in: ${capLine.detail}",
            capLine.detail.contains(io.github.fowles.stochastic_strength.domain.WeightFormatter.format(capWeight, WeightUnit.KG)),
        )
    }

    @Test
    fun ownBeliefLineShowsTheStoredFoldDateNotToday() {
        val foldedAt = now - 40L * 24 * 60 * 60 * 1000  // 40 days before `now`
        val staleBeliefs = mapOf(
            targetId to Belief(mu = ln(60f), sigma2 = 0.005f, updatedAt = foldedAt),
        )
        val trace = PrescriptionTraceBuilder.build(
            exerciseId = targetId,
            muscle = muscle,
            beliefs = staleBeliefs,
            seedCoef = mapOf(targetId to 0.3f),
            muscleExerciseIds = listOf(targetId),
            facts = PolicyFacts.EMPTY,
            capSessionSets = emptyList(),
            sessionReps = 10,
            now = now,
            weightUnit = WeightUnit.KG,
            config = config,
            engine = DefaultProgressionEngine,
        )!!
        val ownLine = trace.lines.first { it.label == "Own belief" }
        val expected = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
            .format(java.util.Date(foldedAt))
        val today = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
            .format(java.util.Date(now))
        assertTrue("expected '$expected' in: ${ownLine.detail}", ownLine.detail.contains(expected))
        assertTrue("must not show the aged-to date '$today'", !ownLine.detail.contains(today))
    }

    @Test
    fun uncappedTraceSaysNoCap() {
        val trace = PrescriptionTraceBuilder.build(
            exerciseId = targetId,
            muscle = muscle,
            beliefs = beliefs,
            seedCoef = seedCoef,
            muscleExerciseIds = muscleExerciseIds,
            facts = PolicyFacts.EMPTY,
            capSessionSets = emptyList(),
            sessionReps = 10,
            now = now,
            weightUnit = WeightUnit.KG,
            config = config,
            engine = DefaultProgressionEngine,
        )!!
        val cap = trace.lines.first { it.label == "Capacity cap" }
        assertTrue(cap.detail.contains("no cap"))
    }
}
