# Peer-Consensus Coefficient Estimation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Replace the coefficient engine's stored-baseline reference + fragile cross-exercise "consensus guard" with a peer-consensus reference, so coefficients learn each exercise's strength *relative to its muscle peers* and are structurally immune to systemic baseline drift.

**Architecture:** For each exercise *i*, recency-combine its recent per-session est-1RMs into one strength estimate `E_i` (no evidence gate). Compute a peer-consensus implied baseline `B_others = weightedMedian over peers j≠i of (E_j / c_j)`, then propose `c_i = E_i / B_others` and damp toward it. Systemic drift cancels in the ratio; renormalization (which runs next in `applySessionProgression`) owns the global scale. All changes are contained to one heuristic file, its test file, and one doc.

**Tech Stack:** Kotlin, JUnit4 (JVM unit tests via `./gradlew :app:testDebugUnitTest`), Android/Gradle, jj (Jujutsu) for version control.

## Global Constraints

- Package: `io.github.fowles.stochastic_strength.domain`.
- The heuristic must keep a no-argument default constructor — `StochasticStrengthApp.kt:39` builds it as `EstCoefConsensusHeuristic()`.
- Class name stays `EstCoefConsensusHeuristic`; `name` stays `"est-coef-consensus"`.
- Keep `setSignal`, `aggregateSession`, `damp`, `weightedMedian`, and `SetSignal` / `SessionAggregate` / `EmitProposal` behavior unchanged.
- Run unit tests with: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"`.
- Commit at each task boundary. End every git/jj commit message with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- This repo uses **jj**, not plain git. Commit a task with:
  `jj commit -m "<message>"` (this describes the current change and starts a new one). Do **not** push or reshape history — the user owns that.

---

### Task 1: Rewrite the heuristic core and adapt existing tests

Replace the stored-baseline + `applyH2` pipeline with the peer-consensus pipeline, and update the existing test file so the surviving tests reflect the new internals. New robustness tests come in Task 2.

**Files:**
- Rewrite: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

**Interfaces:**
- Consumes (unchanged): `CoefficientComputationInput(sets, sessionTimes, exerciseMuscle, baselines, currentCoefficients)`, `CoefficientResult(exerciseId, coefficient, metadata)`, `DefaultProgressionEngine.toOneRepMax`.
- Produces (new/changed internal types used by Task 2 tests):
  - `EstCoefConsensusHeuristic.SessionSignal(sessionId: Long, sessionTime: Long, est1RM: Float, sessionConfidence: Float)` — note: `estCoef` and `hasDefinite` fields are **removed**.
  - `EstCoefConsensusHeuristic.ExerciseEstimate(est1RM: Float, weight: Float, confidence: Float)` — replaces `H1Proposal`.
  - `internal fun computeEstimate(signals: List<SessionSignal>): ExerciseEstimate?` — replaces `computeH1`.
  - `internal fun applyPeerConsensus(estimates: Map<Long, ExerciseEstimate>, currentCoefficients: Map<Long, Float>, exerciseMuscle: Map<Long, MuscleGroup>): Map<Long, EmitProposal>` — replaces `applyH2`.
  - Unchanged: `SetSignal`, `SessionAggregate`, `EmitProposal(proposal, confidence, metadata)`, `internal fun setSignal`, `internal fun aggregateSession`, `internal fun damp`.
  - Constructor: removes `minEvidenceWeight`, `minOutlierSessions`, `tauConsensusThreshold`, `tauOutlierThreshold`; adds `minPeers: Int = 2`, `peerWeightEpsilon: Float = 1e-4f`; keeps `tauHalfMs`, `alpha`, `maxLogStep`, `minRelativeChange`.

- [x] **Step 1: Replace the whole heuristic file**

Overwrite `EstCoefConsensusHeuristic.kt` with exactly this content:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.ln

class EstCoefConsensusHeuristic(
    private val tauHalfMs: Long = 14L * 24 * 60 * 60 * 1000,
    private val minPeers: Int = 2,
    private val peerWeightEpsilon: Float = 1e-4f,
    private val alpha: Float = 0.2f,
    private val maxLogStep: Float = ln(1.05f),
    private val minRelativeChange: Float = 0.005f,
) : CoefficientHeuristic {

    override val name: String = "est-coef-consensus"

    data class SetSignal(
        val est1RM: Float,
        val confidence: Float,
        val isUpperBound: Boolean,
        val isDefinite: Boolean,
    )

    override fun compute(input: CoefficientComputationInput): List<CoefficientResult> {
        val buckets = input.sets.groupBy { it.sessionId to it.exerciseId }
        val perExerciseSignals = mutableMapOf<Long, MutableList<SessionSignal>>()

        for ((key, bucketSets) in buckets) {
            val (sessionId, exerciseId) = key
            val current = input.currentCoefficients[exerciseId] ?: 0f
            if (current <= 0f) continue
            if (input.exerciseMuscle[exerciseId] == null) continue
            val sessionTime = input.sessionTimes[sessionId] ?: continue
            val agg = aggregateSession(bucketSets) ?: continue
            perExerciseSignals.getOrPut(exerciseId) { mutableListOf() }
                .add(
                    SessionSignal(
                        sessionId = sessionId,
                        sessionTime = sessionTime,
                        est1RM = agg.est1RM,
                        sessionConfidence = agg.sessionConfidence,
                    )
                )
        }

        val estimates = perExerciseSignals.mapNotNull { (id, signals) ->
            computeEstimate(signals)?.let { id to it }
        }.toMap()
        if (estimates.isEmpty()) return emptyList()

        val emits = applyPeerConsensus(estimates, input.currentCoefficients, input.exerciseMuscle)

        return emits.mapNotNull { (id, emit) ->
            val cur = input.currentCoefficients[id] ?: return@mapNotNull null
            damp(id, emit, cur)
        }
    }

    data class SessionAggregate(
        val est1RM: Float,
        val sessionConfidence: Float,
        val hasDefinite: Boolean,
    )

    internal fun aggregateSession(sets: List<WorkoutSet>): SessionAggregate? {
        val signals = sets.mapNotNull { setSignal(it) }
        if (signals.isEmpty()) return null

        val nonUpperBound = signals.filter { !it.isUpperBound }
        val included = if (nonUpperBound.isEmpty()) {
            signals
        } else {
            val nonBoundMean = nonUpperBound.sumOf { (it.est1RM * it.confidence).toDouble() }
                .toFloat() / nonUpperBound.sumOf { it.confidence.toDouble() }.toFloat()
            signals.filter { sig ->
                if (!sig.isUpperBound) true
                else nonBoundMean > sig.est1RM
            }
        }
        if (included.isEmpty()) return null

        val totalConf = included.sumOf { it.confidence.toDouble() }.toFloat()
        val weighted1RM = included.sumOf { (it.est1RM * it.confidence).toDouble() }.toFloat() / totalConf
        val avgConf = totalConf / included.size
        return SessionAggregate(
            est1RM = weighted1RM,
            sessionConfidence = avgConf,
            hasDefinite = signals.any { it.isDefinite },
        )
    }

    internal fun setSignal(set: WorkoutSet): SetSignal? {
        val feedback = set.feedback ?: return null
        return when (feedback) {
            SetFeedback.HURT -> null
            SetFeedback.RIR_5_PLUS -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 7),
                confidence = 0.4f, isUpperBound = false, isDefinite = false,
            )
            SetFeedback.RIR_2_4 -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 3),
                confidence = 0.7f, isUpperBound = false, isDefinite = false,
            )
            SetFeedback.RIR_0_1 -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 1),
                confidence = 0.85f, isUpperBound = false, isDefinite = false,
            )
            SetFeedback.TOO_HARD -> {
                val reps = set.actualReps
                if (reps != null) {
                    SetSignal(
                        est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, reps),
                        confidence = 0.95f,
                        isUpperBound = false,
                        isDefinite = true,
                    )
                } else {
                    SetSignal(
                        est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, maxOf(1, set.targetReps - 1)),
                        confidence = 0.5f,
                        isUpperBound = true,
                        isDefinite = false,
                    )
                }
            }
        }
    }

    data class SessionSignal(
        val sessionId: Long,
        val sessionTime: Long,
        val est1RM: Float,
        val sessionConfidence: Float,
    )

    data class ExerciseEstimate(
        val est1RM: Float,
        val weight: Float,
        val confidence: Float,
    )

    internal fun computeEstimate(signals: List<SessionSignal>): ExerciseEstimate? {
        if (signals.isEmpty()) return null
        val nowT = signals.maxOf { it.sessionTime }
        val ln2OverHalf = ln(2.0) / tauHalfMs
        val weighted = signals.map { s ->
            val recency = kotlin.math.exp(-(nowT - s.sessionTime).coerceAtLeast(0L) * ln2OverHalf).toFloat()
            Triple(s, recency, recency * s.sessionConfidence)
        }
        val totalWeight = weighted.sumOf { it.third.toDouble() }.toFloat()
        val median = weightedMedian(weighted.map { it.first.est1RM to it.third })
        val recencySum = weighted.sumOf { it.second.toDouble() }.toFloat()
        val confSum = weighted.sumOf { (it.second * it.first.sessionConfidence).toDouble() }.toFloat()
        val confidence = if (recencySum > 0f) confSum / recencySum else 0f
        return ExerciseEstimate(est1RM = median, weight = totalWeight, confidence = confidence)
    }

    data class EmitProposal(
        val proposal: Float,
        val confidence: Float,
        val metadata: String?,
    )

    private data class Peer(val id: Long, val impliedBaseline: Float, val weight: Float)

    internal fun applyPeerConsensus(
        estimates: Map<Long, ExerciseEstimate>,
        currentCoefficients: Map<Long, Float>,
        exerciseMuscle: Map<Long, MuscleGroup>,
    ): Map<Long, EmitProposal> {
        val out = mutableMapOf<Long, EmitProposal>()
        val groups = estimates.keys.groupBy { exerciseMuscle[it] }
        for ((muscle, idsInMuscle) in groups) {
            if (muscle == null) continue
            val peers = idsInMuscle.mapNotNull { id ->
                val est = estimates.getValue(id)
                val c = currentCoefficients[id] ?: 0f
                if (c <= 0f) return@mapNotNull null
                Peer(id, est.est1RM / c, est.weight)
            }
            for (id in idsInMuscle) {
                val est = estimates.getValue(id)
                val c = currentCoefficients[id] ?: 0f
                if (c <= 0f) continue
                val others = peers.filter { it.id != id && it.weight > peerWeightEpsilon }
                if (others.size < minPeers) continue
                val reference = weightedMedian(others.map { it.impliedBaseline to it.weight })
                if (reference <= 0f) continue
                val proposal = est.est1RM / reference
                out[id] = EmitProposal(proposal, est.confidence, "peer_consensus:peers=${others.size}")
            }
        }
        return out
    }

    internal fun damp(exerciseId: Long, emit: EmitProposal, currentCoef: Float): CoefficientResult? {
        if (currentCoef <= 0f) return null
        val raw = alpha * emit.confidence * ln((emit.proposal / currentCoef).toDouble()).toFloat()
        val step = raw.coerceIn(-maxLogStep, maxLogStep)
        val newCoef = currentCoef * kotlin.math.exp(step.toDouble()).toFloat()
        if (kotlin.math.abs(newCoef - currentCoef) < minRelativeChange * currentCoef) return null
        return CoefficientResult(exerciseId, newCoef, emit.metadata)
    }

    private fun weightedMedian(valueWeights: List<Pair<Float, Float>>): Float {
        val sorted = valueWeights.sortedBy { it.first }
        val total = sorted.sumOf { it.second.toDouble() }.toFloat()
        val half = total / 2f
        var cum = 0f
        for ((v, w) in sorted) {
            cum += w
            if (cum >= half) return v
        }
        return sorted.last().first
    }
}
```

- [x] **Step 2: Update the test file — replace the synthetic helpers**

In `EstCoefConsensusHeuristicTest.kt`, replace the `sessionSignal(...)` helper (currently lines ~170-182) with the new field set (drop `estCoef`/`hasDefinite`, add `est1RM`):

```kotlin
    // Synthetic sessions to drive computeEstimate directly.
    private fun sessionSignal(
        sessionId: Long,
        sessionTime: Long,
        est1RM: Float,
        sessionConfidence: Float,
    ) = EstCoefConsensusHeuristic.SessionSignal(
        sessionId = sessionId,
        sessionTime = sessionTime,
        est1RM = est1RM,
        sessionConfidence = sessionConfidence,
    )
```

Replace the `proposal(...)` helper (currently lines ~240-250) with an `estimate(...)` helper building `ExerciseEstimate`:

```kotlin
    private fun estimate(
        est1RM: Float,
        weight: Float = 3f,
        confidence: Float = 0.8f,
    ) = EstCoefConsensusHeuristic.ExerciseEstimate(
        est1RM = est1RM,
        weight = weight,
        confidence = confidence,
    )
```

- [x] **Step 3: Delete the obsolete tests**

Delete these test methods entirely (they assert removed behavior):
- `computeH1_belowMinEvidenceAndNoDefinite_returnsNull`
- `computeH1_singleDefinitePointBypassesMinEvidence`
- `computeH1_recencyDecayMakesRecentLowConfWeighComparableToOldHighConf`
- `applyH2_singleExercise_passesThrough`
- `applyH2_uniformDriftAboveThreshold_suppressesAll`
- `applyH2_uniformDriftBelowThreshold_passesThroughAll`
- `applyH2_outlierWithMultipleSessions_emitsBoostedConfidence`
- `applyH2_outlierWithSingleSession_fallsThroughToMixedPath`
- `compute_singleExerciseConsistentRir2_4_nudgesCoefficientUp` (single-exercise muscles now never emit — replaced by a multi-exercise test in Task 2)

- [x] **Step 4: Adapt the surviving `computeH1` tests to `computeEstimate`**

Replace `computeH1_empty_returnsNull` with:

```kotlin
    @Test
    fun computeEstimate_empty_returnsNull() {
        val h = EstCoefConsensusHeuristic()
        assertNull(h.computeEstimate(emptyList()))
    }
```

Replace `computeH1_weightedMedianIgnoresSingleOutlier` with the est-1RM-space equivalent:

```kotlin
    @Test
    fun computeEstimate_weightedMedianIgnoresSingleOutlier() {
        // Three near-100 + one freak — median picks the cluster.
        val h = EstCoefConsensusHeuristic()
        val signals = listOf(
            sessionSignal(1L, 1000L, 100.0f, 0.7f),
            sessionSignal(2L, 1000L, 100.0f, 0.7f),
            sessionSignal(3L, 1000L, 105.0f, 0.7f),
            sessionSignal(4L, 1000L, 180.0f, 0.4f), // freak, low confidence
        )
        val est = h.computeEstimate(signals)!!
        assertTrue("median should sit in the 100-105 cluster, got ${est.est1RM}",
            est.est1RM in 100.0f..105.0f)
    }
```

- [x] **Step 5: Adapt the wall-clock test to the peer-consensus compute path**

Replace the body of `` `compute uses max sessionTime from input as now, not wall clock` `` so it has a real peer group (3 exercises in one muscle). With the correct "now = max sessionTime", peer weights exceed `peerWeightEpsilon` and proposals emit; with a wall-clock "now" (years later) all recencies collapse below epsilon, leaving `<2` peers and an empty result.

```kotlin
    @Test
    fun `compute uses max sessionTime from input as now, not wall clock`() {
        val newT = 1_700_000_000_000L
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        // Three exercises, one session each at newT; coefficients disagree so a proposal must emit.
        fun s(sessionId: Long, exerciseId: Long) = WorkoutSet(
            id = sessionId, sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = newT,
        )
        val sets = listOf(s(1, 101), s(2, 102), s(3, 103))
        val input = CoefficientComputationInput(
            sets = sets,
            sessionTimes = mapOf(1L to newT, 2L to newT, 3L to newT),
            exerciseMuscle = mapOf(101L to muscle, 102L to muscle, 103L to muscle),
            baselines = emptyMap(),
            currentCoefficients = mapOf(101L to 1.0f, 102L to 1.0f, 103L to 1.2f),
        )
        val results = EstCoefConsensusHeuristic().compute(input)
        assertTrue("expected at least one result; was empty (heuristic likely using wall clock)",
            results.isNotEmpty())
    }
```

Note: `baselines` is now unused by the heuristic, so passing `emptyMap()` is fine.

- [x] **Step 6: Run the heuristic test class and verify green**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"`
Expected: BUILD SUCCESSFUL; all remaining `setSignal_*`, `aggregateSession_*`, `damp_*`, `computeEstimate_*`, `compute_skipsBodyweightExercisesWithZeroCoefficient`, and the wall-clock test pass.

- [x] **Step 7: Commit**

```bash
jj commit -m "refactor: peer-consensus reference for coefficient estimation

Replace stored-baseline division + applyH2 consensus guard with a
per-exercise estimate (E_i) compared against a weighted-median peer
consensus (E_j/c_j). Systemic drift now cancels structurally; renorm
owns global scale. Drops the per-exercise evidence gate.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Add peer-consensus robustness tests

Encode the properties the redesign exists to guarantee. Impl is already complete from Task 1; this task only adds tests.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

**Interfaces:**
- Consumes: `computeEstimate`, `applyPeerConsensus`, `compute`, helpers `sessionSignal`, `estimate`, `set` from Task 1.

- [x] **Step 1: Add — a single recent session yields a usable estimate (no gate)**

```kotlin
    @Test
    fun computeEstimate_singleSession_returnsEstimateWithNoGate() {
        // Under the old design a single 0.7-confidence session (weight 0.7 < 1.5)
        // returned null. There is no gate now — one session is usable.
        val h = EstCoefConsensusHeuristic()
        val est = h.computeEstimate(listOf(sessionSignal(1L, 1000L, 120.0f, 0.7f)))!!
        assertEquals(120.0f, est.est1RM, 0.001f)
        assertEquals(0.7f, est.confidence, 0.001f)
        assertTrue("weight should be positive", est.weight > 0f)
    }
```

- [x] **Step 2: Add — peer-consensus proposal is `E_i / weightedMedian(E_j/c_j)`**

```kotlin
    @Test
    fun applyPeerConsensus_proposalIsEstimateOverPeerMedianImpliedBaseline() {
        val h = EstCoefConsensusHeuristic()
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        // Three exercises, all E=100. Peers 2 and 3 have coef 1.0 -> implied baseline 100 each.
        // Exercise 1 has coef 0.8; its proposal = E_1 / median(100,100) = 100/100 = 1.0.
        val estimates = mapOf(
            1L to estimate(est1RM = 100f),
            2L to estimate(est1RM = 100f),
            3L to estimate(est1RM = 100f),
        )
        val result = h.applyPeerConsensus(
            estimates,
            currentCoefficients = mapOf(1L to 0.8f, 2L to 1.0f, 3L to 1.0f),
            exerciseMuscle = mapOf(1L to muscle, 2L to muscle, 3L to muscle),
        )
        assertEquals(1.0f, result.getValue(1L).proposal, 0.001f)
        assertTrue(result.getValue(1L).metadata?.startsWith("peer_consensus") == true)
    }
```

- [x] **Step 3: Add — fewer than two peers emits nothing (cold-start guard)**

```kotlin
    @Test
    fun applyPeerConsensus_fewerThanTwoPeers_emitsNothing() {
        val h = EstCoefConsensusHeuristic()
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        // Two exercises in the muscle: each has exactly one peer (< minPeers = 2).
        val estimates = mapOf(
            1L to estimate(est1RM = 100f),
            2L to estimate(est1RM = 120f),
        )
        val result = h.applyPeerConsensus(
            estimates,
            currentCoefficients = mapOf(1L to 1.0f, 2L to 1.0f),
            exerciseMuscle = mapOf(1L to muscle, 2L to muscle),
        )
        assertTrue("a 2-exercise muscle has <2 peers per exercise", result.isEmpty())
    }
```

- [x] **Step 4: Add — two wrong coefficients both converge toward truth**

```kotlin
    @Test
    fun compute_twoWrongCoefficientsBothMoveTowardTruth() {
        // Five CHEST exercises, identical sessions (same E). True coefficient is 1.0 for all.
        // Exercises 1 and 2 start wrong (0.8 and 1.25); 3,4,5 are correct (1.0).
        // Each exercise's peer median ignores one polluted peer, so both wrong ones
        // are pulled toward 1.0 while the correct ones do not move.
        val nowT = 100_000_000_000L
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        fun s(exerciseId: Long) = WorkoutSet(
            id = exerciseId, sessionId = exerciseId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = nowT,
        )
        val ids = listOf(1L, 2L, 3L, 4L, 5L)
        val input = CoefficientComputationInput(
            sets = ids.map { s(it) },
            sessionTimes = ids.associateWith { nowT },
            exerciseMuscle = ids.associateWith { muscle },
            baselines = emptyMap(),
            currentCoefficients = mapOf(1L to 0.8f, 2L to 1.25f, 3L to 1.0f, 4L to 1.0f, 5L to 1.0f),
        )
        val results = EstCoefConsensusHeuristic().compute(input).associateBy { it.exerciseId }

        // Exercise 1 (too low) moves up toward 1.0; exercise 2 (too high) moves down toward 1.0.
        val one = results.getValue(1L).coefficient
        val two = results.getValue(2L).coefficient
        assertTrue("ex1 should rise from 0.8, got $one", one in 0.80f..1.00f && one > 0.80f)
        assertTrue("ex2 should fall from 1.25, got $two", two in 1.00f..1.25f && two < 1.25f)
        // The three correct exercises sit at peer consensus and do not move.
        assertFalse(results.containsKey(3L))
        assertFalse(results.containsKey(4L))
        assertFalse(results.containsKey(5L))
    }
```

- [x] **Step 5: Add — systemic drift produces zero coefficient movement**

```kotlin
    @Test
    fun compute_systemicDriftProducesNoCoefficientMovement() {
        // Three CHEST exercises, all coef 1.0, all performing identically. Because every
        // implied baseline matches, each proposal equals the current coefficient -> no move.
        // This holds regardless of the absolute weight (i.e. a uniform strength shift is invisible).
        val nowT = 100_000_000_000L
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        fun run(weight: Float): List<CoefficientResult> {
            fun s(exerciseId: Long) = WorkoutSet(
                id = exerciseId, sessionId = exerciseId, exerciseId = exerciseId, setNumber = 1,
                targetWeight = weight, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = nowT,
            )
            val ids = listOf(1L, 2L, 3L)
            return EstCoefConsensusHeuristic().compute(
                CoefficientComputationInput(
                    sets = ids.map { s(it) },
                    sessionTimes = ids.associateWith { nowT },
                    exerciseMuscle = ids.associateWith { muscle },
                    baselines = emptyMap(),
                    currentCoefficients = ids.associateWith { 1.0f },
                )
            )
        }
        assertTrue("no movement at 80kg", run(80f).isEmpty())
        assertTrue("no movement at 120kg (uniform drift invisible)", run(120f).isEmpty())
    }
```

- [x] **Step 6: Add — equilibrium is a fixed point of the coefficient pass**

```kotlin
    @Test
    fun compute_atPeerConsensusEquilibrium_emitsNothing() {
        // Coefficients already reflect each exercise's relative strength: exercise 2 is
        // genuinely twice as strong as 1 and 3, and its session weight reflects that.
        // At equilibrium the pass proposes no change (so it cannot chase renormalization).
        val nowT = 100_000_000_000L
        val muscle = io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST
        fun s(exerciseId: Long, weight: Float) = WorkoutSet(
            id = exerciseId, sessionId = exerciseId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = weight, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = nowT,
        )
        // Same feedback at proportional weights => E_2 = 2 * E_1 = 2 * E_3.
        // Implied baselines: E_1/1.0, E_2/2.0, E_3/1.0 all equal => every proposal == current.
        val sets = listOf(s(1L, 50f), s(2L, 100f), s(3L, 50f))
        val input = CoefficientComputationInput(
            sets = sets,
            sessionTimes = mapOf(1L to nowT, 2L to nowT, 3L to nowT),
            exerciseMuscle = mapOf(1L to muscle, 2L to muscle, 3L to muscle),
            baselines = emptyMap(),
            currentCoefficients = mapOf(1L to 1.0f, 2L to 2.0f, 3L to 1.0f),
        )
        assertTrue(EstCoefConsensusHeuristic().compute(input).isEmpty())
    }
```

Note on Step 6: `toOneRepMax` is not exactly linear in weight, so `E_2` may differ from `2*E_1` by a fraction of a percent. If this test emits a tiny sub-`minRelativeChange` move and stays empty, it passes as written. If rounding pushes a proposal just over the `0.5%` floor, relax the assertion to: each emitted coefficient is within `1%` of its current value (`assertTrue(results.all { kotlin.math.abs(it.coefficient - current.getValue(it.exerciseId)) < 0.01f * current.getValue(it.exerciseId) })`). Verify which case holds when running, and keep the stricter empty-assertion if it passes.

- [x] **Step 7: Run the heuristic test class and verify green**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"`
Expected: BUILD SUCCESSFUL; all new tests pass.

- [x] **Step 8: Commit**

```bash
jj commit -m "test: peer-consensus robustness properties

Single-session usability, proposal math, <2-peer cold-start guard,
two-wrong-coefficients convergence, systemic-drift invariance, and
equilibrium fixed point.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Rewrite the coefficient-estimation doc

**Files:**
- Rewrite: `docs/adaptation/03-coefficient-estimation.md`

**Interfaces:** none (documentation only).

- [x] **Step 1: Overwrite the doc**

Replace the entire contents of `docs/adaptation/03-coefficient-estimation.md` with:

```markdown
# Per-exercise coefficient estimation

Source: `domain/EstCoefConsensusHeuristic.kt`
Runs via: `WorkoutRepository` `recomputeCoefficients` (a `CoefficientHeuristic`)

A coefficient is the per-exercise multiplier that bridges the muscle baseline to a
specific lift. The baseline says "this muscle is worth roughly *this* much"; the
coefficient says "but for *this particular exercise* you're stronger or weaker than
that by some factor" — a barbell press and a dumbbell fly both draw on the same
chest baseline, but at very different absolute weights. This engine watches your
logged sets and slowly learns each exercise's true coefficient, rather than
trusting the seeded starting guess forever.

It learns each exercise's strength **relative to its peers** in the same muscle,
not against the stored baseline. That makes it immune to a wrong baseline: if the
whole muscle's baseline is off, every exercise is off by the same factor, and a
relative comparison cancels it out. Correcting that shared, systemic error is the
job of baseline renormalization (see
[04-coefficient-renormalization](04-coefficient-renormalization.md)), which runs on
the very next step and re-attributes any common scale factor back into the baseline.
The two engines are partners: this one sets the *relative shape* of a muscle's
coefficients, renormalization sets their *global scale*.

## Layer 1 — turn each set into a strength estimate (`setSignal`)

Every completed set, based on how it felt, becomes a guess at your one-rep max for
that exercise, tagged with a confidence and a couple of flags:

- **"Lots left in the tank"** is a weak, low-confidence estimate (the engine has to
  extrapolate far).
- **"A couple reps left"** is moderate confidence.
- **"Almost nothing left"** is fairly high confidence.
- **"Too hard, and here's how many reps I actually got"** is the strongest, most
  trustworthy signal — it's a *measured* near-failure.
- **"Too hard but no rep count recorded"** is treated only as an *upper bound* — it
  tells us you're no stronger than this, not exactly how strong.
- **Pain** is discarded entirely.

## Layer 2 — collapse a session into one estimate per exercise (`aggregateSession`)

Within a session, it confidence-weights those per-set estimates into a single
estimated one-rep max. The upper-bound (no-rep-count) signals are special: they're
thrown out if the real, measured signals already point higher, since a fuzzy ceiling
shouldn't drag down a concrete reading.

## Layer 3 — combine an exercise's sessions, favoring recent ones (`computeEstimate`)

Each exercise's recent sessions are blended with a recency weighting (older sessions
fade with a roughly two-week half-life) and combined via a weighted *median* — a
median, not an average, so one freak session can't dominate. The result is a single
recency-biased strength estimate for the exercise, `E`, plus an evidence weight and
an overall confidence.

There is deliberately **no per-exercise evidence threshold**: a single recent
session yields a usable (if low-weight) estimate. This is on purpose. The workout
planner maximizes variety — it samples a handful of exercises from a large library
each session — so any one exercise is rarely repeated enough to accumulate strong
solo evidence. Robustness therefore comes not from one exercise repeating, but from
comparing many exercises across the muscle (Layer 4) and from heavy damping
(Layer 5).

## Layer 4 — compare against the muscle's peers (`applyPeerConsensus`)

For each exercise *i* the engine asks: "given everyone else's recent lifting, what
should *i*'s coefficient be?" It computes, for every other exercise *j* in the
muscle, the muscle baseline that *j*'s performance implies (`E_j / c_j`), and takes
a weight-aware **median** of those — a peer-consensus baseline that one or two
mis-set exercises can't skew. The proposed coefficient is then simply
`E_i / peer_consensus_baseline`.

Because the comparison is relative:

- **A wrong baseline is invisible.** If the muscle baseline shifts, every `E_j`
  shifts with it, the consensus shifts too, and the ratio cancels — coefficients
  don't move. (The baseline's own level is handled by the baseline machinery and
  renormalization.)
- **A couple of mis-set coefficients self-heal.** Each exercise's reference excludes
  itself and medians over its peers, so one or two wrong neighbors barely move the
  consensus, and they converge over subsequent sessions rather than dragging
  everyone into a stuck state.

If an exercise has **fewer than two peers** with recent evidence — the cold-start
case for a brand-new user, or a muscle with very few logged exercises — there is no
trustworthy reference, so the engine proposes nothing and the coefficient stays put
at its seed. As soon as a couple of peers accumulate evidence, learning begins. Zero
-coefficient (unloadable: bodyweight/banded/wall-sit) exercises are excluded both as
peers and as candidates — they have no load relationship to the baseline.

## Layer 5 — dampen the actual move (`damp`)

Even a proposed coefficient isn't applied wholesale. The coefficient is nudged only
a fraction of the way toward the proposal, scaled by confidence, and the size of any
single update is capped. Tiny moves below a threshold are ignored entirely to avoid
churn. So coefficients ease toward their learned values over many sessions rather
than snapping.

## Output

A set of updated coefficients (with notes on why), which feed back into how every
future session's weights are computed for those exercises. Any systemic, whole-muscle
component is then reconciled into the baseline by renormalization.
```

- [x] **Step 2: Commit**

```bash
jj commit -m "docs: rewrite coefficient-estimation around peer consensus

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Full regression

**Files:** none (verification only).

- [x] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. If any unrelated test referenced the old coefficient
behavior, investigate before proceeding — the refactor is contained to the
heuristic, so failures elsewhere are unexpected.

- [x] **Step 2: Build the debug APK to catch any compile regressions**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit any final state (only if anything changed)**

If Steps 1–2 required fixes, commit them:

```bash
jj commit -m "fix: address regression surfaced by full suite

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

Otherwise nothing to commit — the work is already captured by Tasks 1–3.

---

## Self-Review Notes

- **Spec coverage:** Layer rewrite (Tasks 1, 3), drop 1.5 gate (Task 1 Step 1 + Task 2 Step 1), `<2` peers cold-start (Task 2 Step 3), two-wrong convergence (Task 2 Step 4), systemic-drift invariance (Task 2 Step 5), fixed-point/equilibrium (Task 2 Step 6), zero-coefficient exclusion (kept in `compute` + `applyPeerConsensus`; covered by existing `compute_skipsBodyweightExercisesWithZeroCoefficient`), metadata `peer_consensus:peers=<n>` (Task 1 + asserted Task 2 Step 2), confidence = `E_i` confidence with peer-attenuation deferred (Task 1; deferred per spec "out of scope"). `baselines` input field intentionally left in place but unused by the heuristic (noted in Task 1 Step 5) to avoid rippling into `ReplaySnapshot`/`WorkoutRepository`.
- **Renorm fixed-point:** implemented as an equilibrium unit test (Task 2 Step 6) rather than a heavy cross-engine integration test; the systemic-drift invariance test (Step 5) is the structural guarantee that the coefficient pass cannot chase renorm's uniform rescaling.
- **Type consistency:** `SessionSignal(est1RM)`, `ExerciseEstimate(est1RM, weight, confidence)`, `computeEstimate`, `applyPeerConsensus`, `EmitProposal(proposal, confidence, metadata)` are used consistently across impl and tests.
```
