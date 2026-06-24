package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.SessionSignalExtractor
import kotlin.math.exp

data class ProgressionPoint(val timestampMs: Long, val value: Float)

data class ExerciseProgressionSeries(
    val ownEstimate: List<ProgressionPoint>,
    val siblingsEstimate: List<ProgressionPoint>,
    val merged: List<ProgressionPoint>,
    val ownObservations: List<ProgressionPoint>,
    val siblingObservations: List<ProgressionPoint>,
) {
    companion object {
        fun empty() = ExerciseProgressionSeries(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
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

/** One session's contribution to the five series. Pure; no DB. */
internal data class SessionSample(
    val ownEstimate: List<ProgressionPoint>,
    val siblingsEstimate: List<ProgressionPoint>,
    val merged: List<ProgressionPoint>,
    val ownObservations: List<ProgressionPoint>,
    val siblingObservations: List<ProgressionPoint>,
)

/**
 * Computes one session's samples for [targetId], given the post-step [snapshot] (estimates already
 * folded for [asOf]) and the session's [sets]. Lines are sampled only when the target's muscle was
 * touched; dots come straight from the session's observed aggregates.
 */
internal fun sampleSession(
    targetId: Long,
    muscleIds: List<Long>,
    snapshot: ReplaySnapshot,
    sets: List<WorkoutSet>,
    asOf: Long,
    projector: MuscleStrengthProjector,
): SessionSample {
    val targetSeed = snapshot.seedCoefficients[targetId] ?: 0f

    // Lines: own estimate, leave-one-out siblings prediction, engine merged effectiveE1rm.
    val ownEstimate = snapshot.currentEstimates[targetId]?.let {
        listOf(ProgressionPoint(asOf, exp(it.lnE)))
    } ?: emptyList()

    val fullProjection = projector.project(snapshot.currentEstimates, snapshot.seedCoefficients, muscleIds, asOf)
    val merged = fullProjection.effectiveE1rm[targetId]?.let { listOf(ProgressionPoint(asOf, it)) } ?: emptyList()

    val leaveOneOut = projector.project(
        snapshot.currentEstimates, snapshot.seedCoefficients, muscleIds.filter { it != targetId }, asOf,
    )
    val siblingsEstimate = if (targetSeed > 0f && leaveOneOut.level > 0f) {
        listOf(ProgressionPoint(asOf, leaveOneOut.level * targetSeed))
    } else {
        emptyList()
    }

    // Dots: own + sibling observed aggregates, siblings rescaled into target space.
    val byExercise = sets.groupBy { it.exerciseId }
    val ownObservations = byExercise[targetId]?.let { exSets ->
        SessionSignalExtractor.aggregateSession(exSets)?.let { listOf(ProgressionPoint(asOf, it.est1RM)) }
    }.orEmpty()

    val siblingObservations = byExercise.entries.mapNotNull { (id, exSets) ->
        if (id == targetId) return@mapNotNull null
        val sibSeed = snapshot.seedCoefficients[id] ?: return@mapNotNull null
        if (sibSeed <= 0f || targetSeed <= 0f) return@mapNotNull null
        val agg = SessionSignalExtractor.aggregateSession(exSets) ?: return@mapNotNull null
        ProgressionPoint(asOf, agg.est1RM * (targetSeed / sibSeed))
    }

    return SessionSample(ownEstimate, siblingsEstimate, merged, ownObservations, siblingObservations)
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
    projector: MuscleStrengthProjector,
): ProgressionFrame {
    val sample = sampleSession(targetId, muscleIds, snapshot, sets, asOf, projector)
    val crossTuning = computeCrossTuning(
        estimates = snapshot.currentEstimates,
        seedCoef = snapshot.seedCoefficients,
        namesById = namesById,
        muscleExerciseIds = muscleIds,
        now = asOf,
        projector = projector,
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
 * Recomputes the five progression series for one exercise by replaying its muscle through the same
 * engine the production replay uses. On-demand; touches no durable derived state.
 */
class ExerciseProgressionSeriesBuilder(
    private val engine: ReplayEngine = ReplayEngine(),
    private val projector: MuscleStrengthProjector = MuscleStrengthProjector(),
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
        val ownObservations = mutableListOf<ProgressionPoint>()
        val siblingObservations = mutableListOf<ProgressionPoint>()
        val frames = mutableListOf<ProgressionFrame>()

        engine.run(db, snapshot) { _, asOf, sets, snap, result ->
            if (result.steps.any { it.muscle == muscle }) {
                val sample = sampleSession(exerciseId, muscleIds, snap, sets, asOf, projector)
                ownEstimate += sample.ownEstimate
                siblingsEstimate += sample.siblingsEstimate
                merged += sample.merged
                ownObservations += sample.ownObservations
                siblingObservations += sample.siblingObservations
                frames += buildFrame(exerciseId, muscleIds, snap, sets, asOf, namesById, projector)
            }
        }

        return ExerciseProgressionData(
            series = ExerciseProgressionSeries(
                ownEstimate = ownEstimate,
                siblingsEstimate = siblingsEstimate,
                merged = merged,
                ownObservations = ownObservations,
                siblingObservations = siblingObservations,
            ),
            frames = frames,
        )
    }
}
