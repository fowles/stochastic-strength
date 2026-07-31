package io.github.fowles.stochastic_strength.domain

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefPooling
import io.github.fowles.stochastic_strength.domain.belief.BeliefPrescriber
import io.github.fowles.stochastic_strength.domain.derived.DerivedStateStore
import io.github.fowles.stochastic_strength.domain.derived.MutableDerivedState
import io.github.fowles.stochastic_strength.domain.history.HighlightConfig
import io.github.fowles.stochastic_strength.domain.history.HighlightKind
import io.github.fowles.stochastic_strength.domain.history.HighlightSeries
import io.github.fowles.stochastic_strength.domain.history.HistoryHighlight
import io.github.fowles.stochastic_strength.domain.progression.CrossTuningRow
import io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionData
import io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeriesBuilder
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSparklines
import io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint
import io.github.fowles.stochastic_strength.domain.progression.ReplayEngine
import io.github.fowles.stochastic_strength.domain.progression.computeCrossTuning
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class WorkoutRepository(
    private val db: AppDatabase,
    val derivedState: DerivedStateStore = DerivedStateStore(),
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
) {
    private val replayMutex = Mutex()
    private val replayEngine = ReplayEngine()
    private val beliefConfig = BeliefConfig()
    private val beliefPooling = BeliefPooling(beliefConfig)

    private suspend fun excludedExerciseIds(locationId: Long?): Set<Long> =
        if (locationId != null) db.locationExcludedExerciseDao().getExcludedIds(locationId).toSet()
        else emptySet()

    private fun effectiveCoefficientSource(): UserCoefficientSource {
        val latest = derivedState.snapshot().coefficientHistoryLatestPerExercise()
            .associate { it.exerciseId to it.coefficient }
        return UserCoefficientSource(latest)
    }

    /**
     * The shared prescription inputs, derived one way for every entry point (the live planner and
     * the "why this weight" trace must describe the same pipeline). [PolicyFacts] read the set log
     * over a TIME window ([PrescriptionPolicy.FACTS_WINDOW_MS]) — a row-count limit can silently
     * drop a demonstrated-capacity cap that is still inside its expiry.
     */
    private class PrescriptionContext(
        val available: List<Exercise>,
        val seedCoef: Map<Long, Float>,
        val muscleExerciseIds: Map<MuscleGroup, List<Long>>,
        val policyFacts: PolicyFacts,
    )

    private suspend fun prescriptionContext(locationId: Long?, now: Long): PrescriptionContext {
        val excluded = excludedExerciseIds(locationId)
        val available = db.exerciseDao().getActive().filter { it.id !in excluded }
        val seedCoef = available.associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) }
        val muscleIds = available.filter { (seedCoef[it.id] ?: 0f) > 0f }
            .groupBy { it.primaryMuscle }.mapValues { e -> e.value.map { it.id } }
        val factsSets = if (available.isNotEmpty())
            db.workoutSetDao().getCompletedSetsForExercisesSince(
                available.map { it.id }, now - PrescriptionPolicy.FACTS_WINDOW_MS)
        else emptyList()
        val policyFacts = PolicyFacts.build(
            sets = factsSets,
            exerciseMuscle = available.associate { it.id to it.primaryMuscle },
        )
        return PrescriptionContext(available, seedCoef, muscleIds, policyFacts)
    }

    suspend fun buildPlanner(
        locationId: Long?,
        weightUnit: WeightUnit,
        exerciseOverrides: Map<Long, Float> = emptyMap(),
    ): WorkoutPlanner {
        val now = System.currentTimeMillis()
        val ctx = prescriptionContext(locationId, now)
        val available = ctx.available
        val beliefs = derivedState.snapshot().exerciseBeliefs()
        val recentSessions = db.workoutSessionDao().getRecentCompletedSessions(limit = 50)
        // Inferred detraining: a gap since the last completed session eases the comeback
        // prescription down (DetrainingModel curve). The set log self-corrects the belief after.
        val lastCompletedEnd = recentSessions.mapNotNull { it.endTime }.maxOrNull()
        val retention = lastCompletedEnd?.let { DetrainingModel.retention(now - it) } ?: 1f
        val prescribedE1rm = ctx.muscleExerciseIds.flatMap { (_, ids) ->
            beliefPooling.effective(beliefs, ctx.seedCoef, ids, now).effective.entries
                .map { it.key to BeliefPrescriber.targetE1rm(it.value) * retention }
        }.toMap()
        val history = if (available.isNotEmpty())
            db.workoutSetDao().getRecentSetsForExercises(available.map { it.id }, limit = 200)
                .groupBy { it.exerciseId }
        else emptyMap()
        val recentSets = if (recentSessions.isNotEmpty())
            db.workoutSetDao().getSetsForSessions(recentSessions.map { it.id })
                .groupBy { it.sessionId }
        else emptyMap()
        val effectiveCoefficients = effectiveCoefficientSource()
        val exercisesById = available.associateBy { it.id }
        val pacingEstimator = ExercisePacingEstimator.build(recentSessions, recentSets, exercisesById)
        return WorkoutPlanner(
            availableExercises = available,
            prescribedE1rm = prescribedE1rm,
            recentHistory = history,
            weightUnit = weightUnit,
            locationId = locationId,
            coefficientSource = effectiveCoefficients,
            progressionEngine = progressionEngine,
            pacingEstimator = pacingEstimator,
            exerciseE1rmOverrides = exerciseOverrides,
            policyFacts = ctx.policyFacts,
        )
    }

    private fun writeLevelUpdate(
        muscle: MuscleGroup,
        level: Float,
        sessionId: Long,
        asOf: Long,
        scratch: MutableDerivedState,
    ) {
        if (level <= 0f) return
        val current = scratch.muscleGroupStrength(muscle)?.baselineWeight
        // Epsilon-dedupe (parity with writeDerivedCoefficients): suppress sub-epsilon float-noise
        // updates entirely so the baseline_history chart isn't littered with no-op level rows.
        if (current != null && abs(level - current) / current.coerceAtLeast(1e-6f) < 1e-4f) return
        scratch.upsertMuscleGroupStrength(MuscleGroupStrength(muscleGroup = muscle, baselineWeight = level))
        scratch.insertBaselineHistory(
            BaselineHistory(
                sessionId = sessionId,
                muscleGroup = muscle,
                previousBaseline = current ?: 0f,
                newBaseline = level,
                changeReason = BaselineChangeReason.PROGRESSION,
                feedbacks = null,
                sessionReps = null,
                minReductionFraction = null,
                timestamp = asOf,
                heuristicName = "per-exercise-estimate",
                heuristicMetadata = null,
            )
        )
    }

    private fun writeDerivedCoefficients(
        muscleExerciseIds: List<Long>,
        derivedCoef: Map<Long, Float>,
        snapshot: ReplaySnapshot,
        asOf: Long,
        scratch: MutableDerivedState,
    ) {
        val latestByExercise = scratch.coefficientHistoryLatestPerExercise().associateBy { it.exerciseId }
        for (id in muscleExerciseIds) {
            val coef = derivedCoef[id] ?: continue
            val last = snapshot.lastWrittenCoef[id]
            // Epsilon-dedupe: only write when the coefficient changed materially.
            if (last != null && abs(coef - last) / last.coerceAtLeast(1e-6f) < 1e-4f) continue
            val row = CoefficientHistory(
                exerciseId = id,
                previousCoefficient = latestByExercise[id]?.coefficient
                    ?: snapshot.seedCoefficients[id],
                coefficient = coef,
                heuristicName = "per-exercise-estimate",
                heuristicMetadata = null,
                computedAt = asOf,
            )
            scratch.insertCoefficientHistory(row)
            snapshot.lastWrittenCoef[id] = coef
        }
    }

    suspend fun applyManualExerciseOverrides(sessionId: Long, overrides: Map<Long, Float>) {
        if (overrides.isEmpty()) return
        val session = db.workoutSessionDao().getById(sessionId)
        val asOf = session?.startTime ?: System.currentTimeMillis()
        for ((exerciseId, e1rm) in overrides) {
            db.exerciseStrengthOverrideDao().insert(
                ExerciseStrengthOverride(
                    sessionId = sessionId,
                    exerciseId = exerciseId,
                    e1rm = e1rm,
                    asOf = asOf,
                    reason = BaselineChangeReason.OVERRIDE,
                )
            )
        }
        replayDerivedState()
    }

    suspend fun applyDetrainingReduction(sessionId: Long, overrides: Map<Long, Float>) {
        if (overrides.isEmpty()) return
        val session = db.workoutSessionDao().getById(sessionId)
        val asOf = session?.startTime ?: System.currentTimeMillis()
        for ((exerciseId, e1rm) in overrides) {
            db.exerciseStrengthOverrideDao().insert(
                ExerciseStrengthOverride(
                    sessionId = sessionId,
                    exerciseId = exerciseId,
                    e1rm = e1rm,
                    asOf = asOf,
                    reason = BaselineChangeReason.DETRAIN,
                )
            )
        }
        replayDerivedState()
    }

    /**
     * Replays all sessions to fold the just-finished session into derived state. Mid-set weight
     * drops flow through the set log as negative innovations, so no reduction data is threaded here.
     */
    suspend fun finishSession() {
        replayDerivedState()
    }

    suspend fun replayDerivedState() = replayMutex.withLock {
        derivedState.rebuild { scratch ->
            val snapshot = ReplaySnapshot.loadStaticFromDb(db)

            replayEngine.run(db, snapshot) { sessionId, asOf, _, _, beliefResult ->
                for (stepResult in beliefResult.steps) {
                    writeLevelUpdate(stepResult.muscle, stepResult.level, sessionId, asOf, scratch)
                    val exerciseIds = snapshot.muscleExerciseIds[stepResult.muscle] ?: continue
                    writeDerivedCoefficients(
                        muscleExerciseIds = exerciseIds,
                        derivedCoef = stepResult.derivedCoef,
                        snapshot = snapshot,
                        asOf = asOf,
                        scratch = scratch,
                    )
                }
            }

            // Store the final belief map — the live planner reads this (buildPlanner).
            scratch.putExerciseBeliefs(snapshot.currentBeliefs.toMap())

            // Cold-start / untrained-muscle display fill: any muscle never touched by a replayed
            // session still gets a representative muscle_group_strength row (pooled from its
            // seeded/overridden beliefs) so the History strength grid matches the old per-muscle
            // onboarding behavior instead of showing an empty grid. Session-filled muscles are
            // guarded out, so this changes nothing for trained muscles and keeps replay idempotent.
            // No baseline_history row is written (there is no session boundary here).
            val displayNow = snapshot.currentBeliefs.values.maxOfOrNull { it.updatedAt } ?: 0L
            for ((muscle, exerciseIds) in snapshot.muscleExerciseIds) {
                if (scratch.muscleGroupStrength(muscle) != null) continue
                val levelLn = beliefPooling.effective(
                    snapshot.currentBeliefs, snapshot.seedCoefficients, exerciseIds, displayNow,
                ).levelLn ?: continue
                val level = exp(levelLn)
                if (level > 0f) {
                    scratch.upsertMuscleGroupStrength(
                        MuscleGroupStrength(muscleGroup = muscle, baselineWeight = level)
                    )
                }
            }
        }
    }

    suspend fun seedInitialWeights(sex: Sex, strengthLevel: StrengthLevel, weightUnit: WeightUnit) {
        db.userProfileDao().insert(UserProfile(sex = sex, strengthLevel = strengthLevel, weightUnit = weightUnit, perExerciseSeedsBackfilled = true))
        val exercises = db.exerciseDao().getAll()
        // Transactional for parity with ExerciseStrengthOverrideBackfill: the per-exercise
        // delete+insert seeds are still self-healing (idempotent re-run), but an all-or-nothing
        // write avoids leaving a half-seeded initial set if this is interrupted.
        db.withTransaction {
            for (ex in exercises) {
                val e1rm = StartingWeights.seedInitialE1rm(sex, strengthLevel, ex)
                if (e1rm > 0f) {
                    db.exerciseStrengthOverrideDao().deleteInitialFor(ex.id)
                    db.exerciseStrengthOverrideDao().insert(
                        ExerciseStrengthOverride(sessionId = null, exerciseId = ex.id, e1rm = e1rm, asOf = 0L)
                    )
                }
            }
        }
        replayDerivedState()
    }

    // Locations
    suspend fun getLocations(): List<KnownLocation> = db.knownLocationDao().getAll()

    fun observeLocations(): Flow<List<KnownLocation>> = db.knownLocationDao().observeAll()

    suspend fun updateLocation(location: KnownLocation) = db.knownLocationDao().update(location)

    suspend fun deleteLocation(locationId: Long) = db.withTransaction {
        db.locationExcludedExerciseDao().deleteAllForLocation(locationId)
        db.knownLocationDao().deleteById(locationId)
    }

    suspend fun getExcludedExerciseIds(locationId: Long): Set<Long> =
        db.locationExcludedExerciseDao().getExcludedIds(locationId).toSet()

    suspend fun excludeExercise(locationId: Long, exerciseId: Long) =
        db.locationExcludedExerciseDao().insert(LocationExcludedExercise(locationId, exerciseId))

    suspend fun setExcludedExercises(locationId: Long, exerciseIds: Set<Long>) = db.withTransaction {
        db.locationExcludedExerciseDao().deleteAllForLocation(locationId)
        db.locationExcludedExerciseDao().insertAll(exerciseIds.map { LocationExcludedExercise(locationId, it) })
    }

    // Exercise library
    fun observeAllExercises(): Flow<List<Exercise>> = db.exerciseDao().observeAll()

    suspend fun getExerciseById(exerciseId: Long): Exercise? = db.exerciseDao().getById(exerciseId)

    suspend fun updateExercise(exercise: Exercise) = db.exerciseDao().update(exercise)

    suspend fun getAllSetsForExercise(exerciseId: Long): List<WorkoutSet> =
        db.workoutSetDao().getAllForExercise(exerciseId)

    // History
    suspend fun getAllSessions(): List<WorkoutSession> = db.workoutSessionDao().getAll()

    suspend fun deleteSession(sessionId: Long) = db.withTransaction {
        db.workoutSetDao().deleteAllForSession(sessionId)
        db.workoutSessionDao().deleteById(sessionId)
    }

    suspend fun getSessionExerciseNames(sessionId: Long): List<String> {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        val orderedIds = sets.map { it.exerciseId }.distinct()
        val nameById = db.exerciseDao().getByIds(orderedIds).associate { it.id to it.name }
        return orderedIds.mapNotNull { nameById[it] }
    }

    suspend fun getMuscleGroupStrengths(): List<MuscleGroupStrength> =
        derivedState.snapshot().allMuscleGroupStrengths()

    suspend fun getRecentCoefficientChanges(limit: Int = 2): List<CoefficientRow> {
        val rows = derivedState.snapshot().coefficientHistoryMostRecent(limit)
        if (rows.isEmpty()) return emptyList()
        val exerciseIds = rows.map { it.exerciseId }.distinct()
        val exercisesById = db.exerciseDao().getByIds(exerciseIds).associateBy { it.id }
        return rows.mapNotNull { log ->
            val exercise = exercisesById[log.exerciseId] ?: return@mapNotNull null
            CoefficientRow(
                exerciseId = exercise.id,
                exerciseName = exercise.name,
                currentCoefficient = log.coefficient,
                previousCoefficient = log.previousCoefficient,
                computedAt = log.computedAt,
                heuristicName = log.heuristicName,
                heuristicMetadataPreview = log.heuristicMetadata
                    ?.replace('\n', ' ')
                    ?.take(80),
            )
        }
    }

    suspend fun getAllCoefficientRows(): List<CoefficientRow> {
        val allExercises = db.exerciseDao().getAll()
        val latestByExercise = derivedState.snapshot().coefficientHistoryLatestPerExercise()
            .associateBy { it.exerciseId }
        return allExercises
            .map { exercise ->
                val log = latestByExercise[exercise.id]
                val seed = ExerciseCoefficients.get(exercise) ?: 0f
                CoefficientRow(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    currentCoefficient = log?.coefficient ?: seed,
                    previousCoefficient = null,
                    computedAt = log?.computedAt,
                    heuristicName = log?.heuristicName,
                    heuristicMetadataPreview = null,
                )
            }
            .sortedBy { it.exerciseName }
    }

    suspend fun getBaselineEvents(muscleGroup: MuscleGroup): List<BaselineHistory> =
        derivedState.snapshot().baselineHistoryForMuscle(muscleGroup)

    /**
     * Input series for the History highlight card. Per-muscle series come from baseline_history;
     * per-lift series are each exercise's merged (belief) 1RM trend, all produced in ONE replay via
     * [ExerciseProgressionSeriesBuilder.buildAllMergedSeries] (not a replay per lift — that was ~1s
     * on a cold open). The highlight's own window filter drops exercises with no recent point.
     */
    suspend fun buildHighlightSeries(): List<HighlightSeries> {
        val muscleSeries = MuscleGroup.entries.mapNotNull { muscle ->
            val points = getBaselineEvents(muscle)
                .map { ProgressionPoint(it.timestamp, it.newBaseline) }
            if (points.isEmpty()) null
            else HighlightSeries(muscle.displayName(), muscle, points, HighlightKind.MUSCLE)
        }

        val exercisesById = observeAllExercises().first().associateBy { it.id }
        val liftSeries = progressionSeriesBuilder.buildAllMergedSeries(db).mapNotNull { (id, points) ->
            val exercise = exercisesById[id] ?: return@mapNotNull null
            if (points.isEmpty()) null
            else HighlightSeries(exercise.name, exercise.primaryMuscle, points, HighlightKind.LIFT, exerciseId = id)
        }

        return muscleSeries + liftSeries
    }

    /**
     * Highlight string for the finished-workout card: a fact about a lift or muscle
     * performed in [sessionId], paired with a quip. Always tries a session fact
     * (quipOnlyProbability = 0), falling back to a bare quip only when nothing
     * qualifies. Seed [random] with the session id for a stable-per-session pick.
     */
    suspend fun buildSessionHighlight(
        sessionId: Long,
        weightUnit: WeightUnit,
        nowMs: Long,
        random: Random,
    ): String {
        val series = buildHighlightSeries()
        val exerciseIds = db.workoutSetDao().getSetsForSession(sessionId)
            .map { it.exerciseId }.toSet()
        val muscles = db.exerciseDao().getByIds(exerciseIds.toList())
            .map { it.primaryMuscle }.toSet()
        val scoped = HistoryHighlight.scopeToSession(series, exerciseIds, muscles)
        return HistoryHighlight.pick(
            series = scoped,
            weightUnit = weightUnit,
            nowMs = nowMs,
            random = random,
            config = HighlightConfig(quipOnlyProbability = 0f),
        )
    }

    suspend fun getCoefficientEvents(exerciseId: Long): List<CoefficientHistory> =
        derivedState.snapshot().coefficientHistoryForExercise(exerciseId)

    fun getSeedCoefficient(exercise: Exercise): Float? =
        ExerciseCoefficients.get(exercise)

    private val progressionSeriesBuilder = ExerciseProgressionSeriesBuilder(config = beliefConfig)

    suspend fun getExerciseProgressionData(exerciseId: Long): ExerciseProgressionData =
        progressionSeriesBuilder.build(db, exerciseId)

    /**
     * Per-exercise merged-1RM sparkline values for the exercises list: every exercise's merged
     * (belief) 1RM trend from [ExerciseProgressionSeriesBuilder.buildAllMergedSeries] (ONE replay),
     * windowed to the last [windowMs] and reduced to bare values via [ExerciseSparklines.windowValues].
     * The per-exercise first-performed time trims leading sibling-driven points from before the lift's
     * own debut; exercises with fewer than 2 surviving points are omitted (their row shows nothing).
     */
    suspend fun buildExerciseSparklines(
        windowMs: Long = ExerciseSparklines.DEFAULT_WINDOW_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<Long, List<Float>> {
        val firstPerformed = db.workoutSetDao().getFirstCompletedAtByExercise()
            .associate { it.exerciseId to it.firstCompletedAt }
        return ExerciseSparklines.windowValues(
            progressionSeriesBuilder.buildAllMergedSeries(db), firstPerformed, nowMs, windowMs,
        )
    }

    /** Most-recent completed-set time per exercise, for ordering the exercises-list "Recent" section. */
    suspend fun getLastPerformedByExercise(): Map<Long, Long> =
        db.workoutSetDao().getLastCompletedAtByExercise()
            .associate { it.exerciseId to it.lastCompletedAt }

    suspend fun getCrossTuning(
        muscle: MuscleGroup,
        now: Long = System.currentTimeMillis(),
    ): List<CrossTuningRow> {
        val snapshot = ReplaySnapshot.loadStaticFromDb(db)
        val muscleIds = snapshot.muscleExerciseIds[muscle] ?: return emptyList()
        val beliefs = derivedState.snapshot().exerciseBeliefs()
        val namesById = db.exerciseDao().getAll().associate { it.id to it.name }
        return computeCrossTuning(
            beliefs = beliefs,
            seedCoef = snapshot.seedCoefficients,
            namesById = namesById,
            muscleExerciseIds = muscleIds,
            now = now,
            config = beliefConfig,
        )
    }

}
