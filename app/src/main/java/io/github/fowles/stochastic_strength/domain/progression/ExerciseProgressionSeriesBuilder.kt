package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.DetrainingModel
import io.github.fowles.stochastic_strength.domain.ProgressionEngine
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.PrescriptionTrace
import io.github.fowles.stochastic_strength.domain.belief.PrescriptionTraceBuilder
import io.github.fowles.stochastic_strength.domain.belief.setObservationsE1rm
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.max

data class ProgressionPoint(val timestampMs: Long, val value: Float)

data class ExerciseProgressionSeries(
    val ownEstimate: List<ProgressionPoint>,
    val siblingsEstimate: List<ProgressionPoint>,
    val merged: List<ProgressionPoint>,
    val bandUpper: List<ProgressionPoint>,
    val bandLower: List<ProgressionPoint>,
    val ownObservations: List<ProgressionPoint>,
    val siblingObservations: List<ProgressionPoint>,
) {
    companion object {
        fun empty() = ExerciseProgressionSeries(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
    }
}

data class SessionExerciseObservation(
    val exerciseId: Long,
    val name: String,
    val sets: List<ObservedSet>,
)

data class ProgressionFrame(
    val timestampMs: Long,
    val own: Float?,
    val siblings: Float?,
    val merged: Float?,
    val crossTuning: List<CrossTuningRow>,
    val observations: List<SessionExerciseObservation>,
    val trace: PrescriptionTrace? = null,
)

data class ExerciseProgressionData(
    val series: ExerciseProgressionSeries,
    val frames: List<ProgressionFrame>,
    val predictedFrame: ProgressionFrame? = null,
)

/** One session's contribution to the series. Pure; no DB. */
internal data class SessionSample(
    val ownEstimate: List<ProgressionPoint>,
    val siblingsEstimate: List<ProgressionPoint>,
    val merged: List<ProgressionPoint>,
    val bandUpper: List<ProgressionPoint>,
    val bandLower: List<ProgressionPoint>,
    val ownObservations: List<ProgressionPoint>,
    val siblingObservations: List<ProgressionPoint>,
)

/** One exercise's session sets reduced to dots via the shared [setObservationsE1rm] rule. */
private fun perSetDots(sets: List<WorkoutSet>, asOf: Long, config: BeliefConfig): List<ProgressionPoint> =
    setObservationsE1rm(sets, config).map { ProgressionPoint(asOf, it) }

/** Stamps the "next prescription" point at least 24h after the last session that trained the muscle. */
internal fun predictedNow(now: Long, lastMuscleSessionAsOf: Long?): Long {
    val day = 24L * 60 * 60 * 1000
    return max(now, (lastMuscleSessionAsOf ?: 0L) + day)
}

/**
 * Computes one session's samples for [targetId], given the post-step [snapshot] (beliefs already
 * folded for [asOf]) and the session's [sets]. Lines are sampled only when the target's muscle was
 * touched; dots are per-set implied observations from [setObservationsE1rm].
 */
internal fun sampleSession(
    targetId: Long,
    muscleIds: List<Long>,
    snapshot: ReplaySnapshot,
    sets: List<WorkoutSet>,
    asOf: Long,
    config: BeliefConfig,
): SessionSample {
    val pooling = BeliefPooling(config)
    val targetCoef = snapshot.seedCoefficients[targetId] ?: 0f

    // Lines: own post-fold belief, leave-one-out siblings prediction, pooled effective bestGuessLn/uncertainty.
    val ownEstimate = snapshot.currentBeliefs[targetId]?.let {
        listOf(ProgressionPoint(asOf, exp(it.bestGuessLn)))
    } ?: emptyList()

    val fullPooling = pooling.effective(snapshot.currentBeliefs, snapshot.seedCoefficients, muscleIds, asOf)
    val effective = fullPooling.effective[targetId]
    val merged = effective?.let { listOf(ProgressionPoint(asOf, exp(it.bestGuessLn))) } ?: emptyList()
    val bandUpper = effective?.let { listOf(ProgressionPoint(asOf, exp(it.bestGuessLn + sqrt(it.uncertainty)))) } ?: emptyList()
    val bandLower = effective?.let { listOf(ProgressionPoint(asOf, exp(it.bestGuessLn - sqrt(it.uncertainty)))) } ?: emptyList()

    // The leave-one-out sibling prediction comes straight off the pooling breakdown — the same
    // number the blend used, no second pool.
    val siblingsEstimate = effective?.sibling?.let {
        listOf(ProgressionPoint(asOf, exp(it.bestGuessLn)))
    } ?: emptyList()

    // Dots: own + sibling per-set observations, siblings rescaled into target space.
    val byExercise = sets.groupBy { it.exerciseId }
    val ownObservations = byExercise[targetId]?.let { perSetDots(it, asOf, config) }.orEmpty()

    val muscleIdSet = muscleIds.toHashSet()
    val siblingObservations = byExercise.entries.flatMap { (id, exSets) ->
        if (id == targetId) return@flatMap emptyList()
        // Only same-muscle siblings inform the target; a leg lift on a biceps day is not a sibling.
        if (id !in muscleIdSet) return@flatMap emptyList()
        val sibCoef = snapshot.seedCoefficients[id] ?: return@flatMap emptyList()
        if (sibCoef <= 0f || targetCoef <= 0f) return@flatMap emptyList()
        val scale = targetCoef / sibCoef
        perSetDots(exSets, asOf, config).map { it.copy(value = it.value * scale) }
    }

    return SessionSample(ownEstimate, siblingsEstimate, merged, bandUpper, bandLower, ownObservations, siblingObservations)
}

/**
 * One session's [ProgressionFrame] at [asOf]: the three line values (from the [sample] already
 * computed for this session), the cross-tuning rows as they stood then, and per-exercise
 * displayable set observations (target first, then siblings in [muscleIds] order; exercises with
 * no displayable set are omitted). Pure; no DB.
 */
internal fun buildFrame(
    targetId: Long,
    muscleIds: List<Long>,
    snapshot: ReplaySnapshot,
    sets: List<WorkoutSet>,
    asOf: Long,
    namesById: Map<Long, String>,
    config: BeliefConfig,
    sample: SessionSample,
    trace: PrescriptionTrace? = null,
): ProgressionFrame {
    val crossTuning = computeCrossTuning(
        beliefs = snapshot.currentBeliefs,
        seedCoef = snapshot.seedCoefficients,
        namesById = namesById,
        muscleExerciseIds = muscleIds,
        now = asOf,
        config = config,
    )
    val setsByExercise = sets.groupBy { it.exerciseId }
    val orderedIds = listOf(targetId) + muscleIds.filter { it != targetId }
    val observations = orderedIds.mapNotNull { id ->
        val name = namesById[id] ?: return@mapNotNull null
        val observed = setsByExercise[id].orEmpty()
            .sortedBy { it.setNumber }
            .mapNotNull { impliedObservedSet(it) }
        if (observed.isEmpty()) null
        else SessionExerciseObservation(exerciseId = id, name = name, sets = observed)
    }
    return ProgressionFrame(
        timestampMs = asOf,
        own = sample.ownEstimate.firstOrNull()?.value,
        siblings = sample.siblingsEstimate.firstOrNull()?.value,
        merged = sample.merged.firstOrNull()?.value,
        crossTuning = crossTuning,
        observations = observations,
        trace = trace,
    )
}

/**
 * The "why this weight" trace for one exercise's decision made at [now], given the belief snapshot
 * that produced it ([beliefs]) and the completed sets known at that moment ([priorSets] — the sets
 * from sessions BEFORE this decision). Facts are rebuilt over [PrescriptionPolicy.FACTS_WINDOW_MS]
 * exactly like the live planner's context, so the trace matches the production pipeline. Pure; no DB.
 */
internal fun buildSessionTrace(
    targetId: Long,
    muscle: MuscleGroup,
    beliefs: Map<Long, Belief>,
    seedCoef: Map<Long, Float>,
    muscleExerciseIds: List<Long>,
    exerciseMuscle: Map<Long, MuscleGroup>,
    priorSets: List<WorkoutSet>,
    sessionReps: Int,
    now: Long,
    weightUnit: WeightUnit,
    retention: Float,
    config: BeliefConfig,
    engine: ProgressionEngine = DefaultProgressionEngine,
): PrescriptionTrace? {
    val windowStart = now - PrescriptionPolicy.FACTS_WINDOW_MS
    val factsSets = priorSets.filter { it.completedAt != null && it.completedAt >= windowStart }
    val facts = PolicyFacts.build(sets = factsSets, exerciseMuscle = exerciseMuscle)
    val capFact = facts.capByExercise[targetId]
    val capSessionSets = capFact?.let { f ->
        factsSets.groupBy { it.sessionId }
            .values
            .firstOrNull { s -> s.maxOf { it.completedAt!! } == f.demonstratedAt }
    }.orEmpty()
    return PrescriptionTraceBuilder.build(
        exerciseId = targetId,
        muscle = muscle,
        beliefs = beliefs,
        seedCoef = seedCoef,
        muscleExerciseIds = muscleExerciseIds,
        facts = facts,
        capSessionSets = capSessionSets,
        sessionReps = sessionReps,
        now = now,
        weightUnit = weightUnit,
        retention = retention,
        config = config,
        engine = engine,
    )
}

/**
 * Recomputes the exercise progression series for one exercise by replaying its muscle through the
 * same engine the production replay uses. On-demand; touches no durable derived state.
 */
class ExerciseProgressionSeriesBuilder(
    private val config: BeliefConfig = BeliefConfig(),
    // Built from the SAME config: the engine's folds and this builder's dots/lines must never
    // read different constants (the passed-config-ignored seam bit phase 4 once already).
    private val engine: ReplayEngine = ReplayEngine(config),
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
) {
    /**
     * Every exercise's merged (pooled effective) 1RM trend in a SINGLE replay: at each session,
     * pool each touched muscle once and record `exp(bestGuessLn)` for every exercise in it. This is
     * O(sessions × muscles-per-session) instead of [build]'s O(exercises × full-replay-with-traces),
     * for consumers that need only the merged trend — e.g. the History highlight, which reads no
     * frames or "why this weight" traces. Points are post-fold (strength as of each session's end)
     * in id/time order; exercises never trained produce no entry.
     */
    suspend fun buildAllMergedSeries(db: AppDatabase): Map<Long, List<ProgressionPoint>> {
        val snapshot = ReplaySnapshot.loadStaticFromDb(db)
        val pooling = BeliefPooling(config)
        val out = HashMap<Long, MutableList<ProgressionPoint>>()
        engine.run(db = db, snapshot = snapshot) { _, asOf, _, snap, beliefResult ->
            for (muscle in beliefResult.steps.map { it.muscle }.toHashSet()) {
                val muscleIds = snap.muscleExerciseIds[muscle] ?: continue
                val effective = pooling
                    .effective(snap.currentBeliefs, snap.seedCoefficients, muscleIds, asOf)
                    .effective
                for ((exId, belief) in effective) {
                    out.getOrPut(exId) { mutableListOf() }.add(ProgressionPoint(asOf, exp(belief.bestGuessLn)))
                }
            }
        }
        return out
    }

    /** DB adapter: loads the static inputs, then delegates to the DB-free [buildCore]. */
    suspend fun build(db: AppDatabase, exerciseId: Long): ExerciseProgressionData {
        val snapshot = ReplaySnapshot.loadStaticFromDb(db)
        val muscle = snapshot.exerciseMuscle[exerciseId]
            ?: return ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        val muscleIds = snapshot.muscleExerciseIds[muscle]
            ?: return ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        if (exerciseId !in muscleIds) {
            return ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        }
        val namesById = db.exerciseDao().getAll().associate { it.id to it.name }
        val profile = db.userProfileDao().getProfile()
        val weightUnit = profile?.weightUnit ?: WeightUnit.KG
        val seeds = if (profile == null) {
            ExerciseSeedExpansion.Seeds(emptyList(), emptyMap())
        } else {
            ExerciseSeedExpansion.buildSeeds(
                initialOverrides = db.baselineOverrideDao().getInitials(),
                sessionOverrides = db.baselineOverrideDao().getNonInitials(),
                sex = profile.sex, level = profile.strengthLevel,
                exerciseMuscle = snapshot.exerciseMuscle, coefById = snapshot.seedCoefficients,
            )
        }
        return buildCore(
            exerciseId = exerciseId,
            snapshot = snapshot,
            muscle = muscle,
            muscleIds = muscleIds,
            namesById = namesById,
            weightUnit = weightUnit,
            initialSeeds = seeds.initial,
            sessionSeeds = seeds.bySession,
            sessions = db.workoutSessionDao().getAll(),
            setsForSession = { db.workoutSetDao().getSetsForSession(it) },
            now = System.currentTimeMillis(),
        )
    }

    /**
     * DB-free core: replays the muscle, sampling each frame from the PRE-FOLD state (the decision
     * entering that session) with its per-decision trace, then appends one synthetic PREDICTED frame
     * at [now] from the live post-final-fold state. Mirrors the run/runCore split in [ReplayEngine].
     */
    internal suspend fun buildCore(
        exerciseId: Long,
        snapshot: ReplaySnapshot,
        muscle: MuscleGroup,
        muscleIds: List<Long>,
        namesById: Map<Long, String>,
        weightUnit: WeightUnit,
        initialSeeds: List<SeedBelief>,
        sessionSeeds: Map<Long, List<SeedBelief>>,
        sessions: List<WorkoutSession>,
        setsForSession: suspend (Long) -> List<WorkoutSet>,
        now: Long,
    ): ExerciseProgressionData {
        val ownEstimate = mutableListOf<ProgressionPoint>()
        val siblingsEstimate = mutableListOf<ProgressionPoint>()
        val merged = mutableListOf<ProgressionPoint>()
        val bandUpper = mutableListOf<ProgressionPoint>()
        val bandLower = mutableListOf<ProgressionPoint>()
        val ownObservations = mutableListOf<ProgressionPoint>()
        val siblingObservations = mutableListOf<ProgressionPoint>()
        val frames = mutableListOf<ProgressionFrame>()

        // Pre-fold beliefs for the CURRENT session, captured by the beforeSession hook right before
        // the fold mutates them. Copied because the fold mutates snapshot.currentBeliefs in place.
        var preFold: Map<Long, Belief> = emptyMap()
        // Completed sets from sessions strictly before the current one (the facts a decision saw).
        val priorSets = mutableListOf<WorkoutSet>()
        // Time of the most recent session that touched this muscle.
        var lastMuscleSessionAsOf: Long? = null
        // End of the most recent COMPLETED session of any muscle before the current decision — the
        // gap the live planner measures for its inferred detraining backoff (DetrainingModel).
        var prevCompletedEnd: Long? = null

        fun preFoldSnapshot(beliefs: Map<Long, Belief>): ReplaySnapshot =
            ReplaySnapshot(snapshot.exerciseMuscle, snapshot.seedCoefficients)
                .also { it.currentBeliefs.putAll(beliefs) }

        fun targetReps(sets: List<WorkoutSet>): Int =
            sets.filter { it.exerciseId == exerciseId }.minByOrNull { it.setNumber }?.targetReps ?: 10

        engine.runCore(
            snapshot = snapshot,
            initialSeeds = initialSeeds,
            sessionSeeds = sessionSeeds,
            sessions = sessions,
            setsForSession = setsForSession,
            beforeSession = { beliefs, _ -> preFold = HashMap(beliefs) },
            observer = { _, asOf, sets, snap, beliefResult ->
                if (beliefResult.steps.any { it.muscle == muscle }) {
                    lastMuscleSessionAsOf = asOf
                    val preSnap = preFoldSnapshot(preFold)
                    val sample = sampleSession(exerciseId, muscleIds, preSnap, sets, asOf, config)
                    ownEstimate += sample.ownEstimate
                    siblingsEstimate += sample.siblingsEstimate
                    merged += sample.merged
                    bandUpper += sample.bandUpper
                    bandLower += sample.bandLower
                    ownObservations += sample.ownObservations
                    siblingObservations += sample.siblingObservations
                    val retention = DetrainingModel.retention(asOf - (prevCompletedEnd ?: asOf))
                    val trace = buildSessionTrace(
                        targetId = exerciseId, muscle = muscle, beliefs = preFold,
                        seedCoef = snap.seedCoefficients, muscleExerciseIds = muscleIds,
                        exerciseMuscle = snap.exerciseMuscle, priorSets = priorSets,
                        sessionReps = targetReps(sets), now = asOf, weightUnit = weightUnit,
                        retention = retention, config = config, engine = progressionEngine,
                    )
                    frames += buildFrame(exerciseId, muscleIds, preSnap, sets, asOf, namesById, config, sample, trace)
                }
                priorSets += sets.filter { it.completedAt != null }
                prevCompletedEnd = asOf
            },
        )

        // Synthetic PREDICTED frame: the live forward-looking decision, from the post-final-fold
        // state (snapshot.currentBeliefs after the last fold), stamped at `predictedNow`.
        val predictedNow = predictedNow(now, lastMuscleSessionAsOf)
        val predictedFrame: ProgressionFrame? = if (frames.isNotEmpty()) {
            val liveSample = sampleSession(exerciseId, muscleIds, snapshot, emptyList(), predictedNow, config)
            ownEstimate += liveSample.ownEstimate
            siblingsEstimate += liveSample.siblingsEstimate
            merged += liveSample.merged
            bandUpper += liveSample.bandUpper
            bandLower += liveSample.bandLower
            val liveReps = priorSets.filter { it.exerciseId == exerciseId }
                .maxByOrNull { it.completedAt ?: 0L }?.targetReps ?: 10
            val liveRetention = DetrainingModel.retention(predictedNow - (prevCompletedEnd ?: predictedNow))
            val liveTrace = buildSessionTrace(
                targetId = exerciseId, muscle = muscle, beliefs = snapshot.currentBeliefs,
                seedCoef = snapshot.seedCoefficients, muscleExerciseIds = muscleIds,
                exerciseMuscle = snapshot.exerciseMuscle, priorSets = priorSets,
                sessionReps = liveReps, now = predictedNow, weightUnit = weightUnit,
                retention = liveRetention, config = config, engine = progressionEngine,
            )
            buildFrame(exerciseId, muscleIds, snapshot, emptyList(), predictedNow, namesById, config, liveSample, liveTrace)
        } else null

        return ExerciseProgressionData(
            series = ExerciseProgressionSeries(
                ownEstimate = ownEstimate,
                siblingsEstimate = siblingsEstimate,
                merged = merged,
                bandUpper = bandUpper,
                bandLower = bandLower,
                ownObservations = ownObservations,
                siblingObservations = siblingObservations,
            ),
            frames = frames,
            predictedFrame = predictedFrame,
        )
    }
}
