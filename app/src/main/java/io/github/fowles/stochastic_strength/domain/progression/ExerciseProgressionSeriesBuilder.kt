package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.setObservationLn
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

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
)

data class ExerciseProgressionData(
    val series: ExerciseProgressionSeries,
    val frames: List<ProgressionFrame>,
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

/**
 * One exercise's session sets, ranked 1-based by set id (all rows count, matching the fold's rank
 * rule), reduced to its per-set observation dots via [setObservationLn].
 */
private fun perSetDots(sets: List<WorkoutSet>, asOf: Long, config: BeliefConfig): List<ProgressionPoint> =
    sets.sortedBy { it.id }.mapIndexedNotNull { idx, set ->
        setObservationLn(set, rank = idx + 1, config)?.let { ProgressionPoint(asOf, exp(it)) }
    }

/**
 * Computes one session's samples for [targetId], given the post-step [snapshot] (beliefs already
 * folded for [asOf]) and the session's [sets]. Lines are sampled only when the target's muscle was
 * touched; dots are per-set implied observations from [setObservationLn].
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

    // Lines: own post-fold belief, leave-one-out siblings prediction, pooled effective mu/sigma.
    val ownEstimate = snapshot.currentBeliefs[targetId]?.let {
        listOf(ProgressionPoint(asOf, exp(it.mu)))
    } ?: emptyList()

    val fullPooling = pooling.effective(snapshot.currentBeliefs, snapshot.seedCoefficients, muscleIds, asOf)
    val effective = fullPooling.effective[targetId]
    val merged = effective?.let { listOf(ProgressionPoint(asOf, exp(it.mu))) } ?: emptyList()
    val bandUpper = effective?.let { listOf(ProgressionPoint(asOf, exp(it.mu + sqrt(it.sigma2)))) } ?: emptyList()
    val bandLower = effective?.let { listOf(ProgressionPoint(asOf, exp(it.mu - sqrt(it.sigma2)))) } ?: emptyList()

    val looLevelLn = pooling.effective(
        snapshot.currentBeliefs, snapshot.seedCoefficients, muscleIds.filter { it != targetId }, asOf,
    ).levelLn
    val siblingsEstimate = if (targetCoef > 0f && looLevelLn != null) {
        listOf(ProgressionPoint(asOf, exp(ln(targetCoef) + looLevelLn)))
    } else {
        emptyList()
    }

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
 * One session's [ProgressionFrame] at [asOf]: the three line values, the cross-tuning rows as they
 * stood then, and per-exercise displayable set observations (target first, then siblings in
 * [muscleIds] order; exercises with no displayable set are omitted). Pure; no DB.
 */
internal fun buildFrame(
    targetId: Long,
    muscleIds: List<Long>,
    snapshot: ReplaySnapshot,
    sets: List<WorkoutSet>,
    asOf: Long,
    namesById: Map<Long, String>,
    config: BeliefConfig,
): ProgressionFrame {
    val sample = sampleSession(targetId, muscleIds, snapshot, sets, asOf, config)
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
    )
}

/**
 * Recomputes the exercise progression series for one exercise by replaying its muscle through the
 * same engine the production replay uses. On-demand; touches no durable derived state.
 */
class ExerciseProgressionSeriesBuilder(
    private val engine: ReplayEngine = ReplayEngine(),
    private val config: BeliefConfig = BeliefConfig(),
) {
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

        val ownEstimate = mutableListOf<ProgressionPoint>()
        val siblingsEstimate = mutableListOf<ProgressionPoint>()
        val merged = mutableListOf<ProgressionPoint>()
        val bandUpper = mutableListOf<ProgressionPoint>()
        val bandLower = mutableListOf<ProgressionPoint>()
        val ownObservations = mutableListOf<ProgressionPoint>()
        val siblingObservations = mutableListOf<ProgressionPoint>()
        val frames = mutableListOf<ProgressionFrame>()

        engine.run(db, snapshot) { _, asOf, sets, snap, _, beliefResult ->
            if (beliefResult.steps.any { it.muscle == muscle }) {
                val sample = sampleSession(exerciseId, muscleIds, snap, sets, asOf, config)
                ownEstimate += sample.ownEstimate
                siblingsEstimate += sample.siblingsEstimate
                merged += sample.merged
                bandUpper += sample.bandUpper
                bandLower += sample.bandLower
                ownObservations += sample.ownObservations
                siblingObservations += sample.siblingObservations
                frames += buildFrame(exerciseId, muscleIds, snap, sets, asOf, namesById, config)
            }
        }

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
        )
    }
}
