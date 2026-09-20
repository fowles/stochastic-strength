package io.github.fowles.stochastic_strength.domain

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SavedWorkout
import io.github.fowles.stochastic_strength.data.model.SavedWorkoutExercise
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
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutDetail
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
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
import kotlinx.coroutines.flow.combine
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
        val allActive = db.exerciseDao().getActive()
        val available = allActive.filter { it.id !in excluded }
        // Estimates and policy facts cover every active exercise so an explicit pick of a
        // location-excluded lift still gets a real weight; only generation is location-filtered.
        // Location-excluded siblings therefore also vote in per-muscle pooling, so a lift's
        // prescription no longer depends on which location the user is standing at.
        val seedCoef = allActive.associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) }
        val muscleIds = allActive.filter { (seedCoef[it.id] ?: 0f) > 0f }
            .groupBy { it.primaryMuscle }.mapValues { e -> e.value.map { it.id } }
        val factsSets = if (allActive.isNotEmpty())
            db.workoutSetDao().getCompletedSetsForExercisesSince(
                allActive.map { it.id }, now - PrescriptionPolicy.FACTS_WINDOW_MS)
        else emptyList()
        val policyFacts = PolicyFacts.build(
            sets = factsSets,
            exerciseMuscle = allActive.associate { it.id to it.primaryMuscle },
        )
        return PrescriptionContext(available, seedCoef, muscleIds, policyFacts)
    }

    suspend fun buildPlanner(
        locationId: Long?,
        weightUnit: WeightUnit,
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
            policyFacts = ctx.policyFacts,
        )
    }

    /** The profile's display unit on its own — readable long before a planner can be built. */
    suspend fun weightUnit(): WeightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG

    /** A planner-backed pricer for off-session editing (the saved-workout editor), at the profile's rep range + unit. */
    suspend fun rowSuggester(): RowSuggester {
        val profile = db.userProfileDao().getProfile()
        val repMin = profile?.preferredRepMin ?: RepRangePicker.DEFAULT_MIN
        val repMax = profile?.preferredRepMax ?: RepRangePicker.DEFAULT_MAX
        val unit = profile?.weightUnit ?: WeightUnit.KG
        return RowSuggester(buildPlanner(locationId = null, weightUnit = unit), repMin, repMax, unit)
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

    /**
     * Replays all sessions to fold the just-finished session into derived state. Mid-set weight
     * drops flow through the set log as negative innovations, so no reduction data is threaded here.
     */
    suspend fun finishSession() {
        replayDerivedState()
    }

    /**
     * Closes every session process death left open (`endTime IS NULL`). Call this at process
     * start, before [replayDerivedState] — a fresh process has no live controller, so no
     * in-progress session can be hit. Per CLAUDE.md ("no restore after process death") this never
     * resumes a session, only closes it: one with no logged sets is deleted, otherwise `endTime`
     * becomes its latest set's `completedAt` (falling back to `startTime` if none have one) so its
     * sets flow into the next replay.
     */
    suspend fun closeOrphanedSessions() = db.withTransaction {
        val orphans = db.workoutSessionDao().getOpenSessions()
        if (orphans.isEmpty()) return@withTransaction
        // Unfiltered: a session whose only set row has no completedAt (e.g. round-tripped
        // verbatim by backup import) still has a set row and must not be deleted as empty.
        val setsBySession = db.workoutSetDao().getAllSetsForSessions(orphans.map { it.id }).groupBy { it.sessionId }
        for (session in orphans) {
            val sets = setsBySession[session.id]
            if (sets.isNullOrEmpty()) {
                db.workoutSessionDao().deleteById(session.id)
            } else {
                val latest = sets.mapNotNull { it.completedAt }.maxOrNull() ?: session.startTime
                db.workoutSessionDao().updateEndTime(session.id, latest)
            }
        }
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
        db.userProfileDao().insert(UserProfile(sex = sex, strengthLevel = strengthLevel, weightUnit = weightUnit))
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

    // Saved workouts

    /** Resolves every saved workout against the live exercise table; rows whose exercise is gone are dropped. */
    fun observeSavedWorkouts(): Flow<List<SavedWorkoutDetail>> = combine(
        db.savedWorkoutDao().observeAll(),
        db.savedWorkoutDao().observeAllExerciseRows(),
        db.exerciseDao().observeAll(),
    ) { workouts, rows, exercises ->
        val byId = exercises.associateBy { it.id }
        val rowsByWorkout = rows.groupBy { it.workoutId }
        workouts.map { w -> w.toDetail(rowsByWorkout[w.id].orEmpty(), byId) }
    }

    /**
     * The saved workout the user's recent history says is due, or null to plan a random workout.
     * See [WorkoutRoutine] for the rule; this supplies it the history and the candidates.
     */
    suspend fun suggestRoutineWorkout(): SavedWorkoutDetail? {
        val saved = db.savedWorkoutDao().getAll()
        if (saved.isEmpty()) return null
        val rowsByWorkout = db.savedWorkoutDao().getAllExerciseRows().groupBy { it.workoutId }
        val candidates = saved.map { w ->
            WorkoutRoutine.Candidate(w.id, rowsByWorkout[w.id].orEmpty().mapTo(mutableSetOf()) { it.exerciseId })
        }
        // Two cycles of the longest routine recognized is all the rule ever looks at.
        val sessions = db.workoutSessionDao().getRecentCompletedSessions(limit = WorkoutRoutine.HISTORY_LIMIT)
        val setsBySession = db.workoutSetDao().getAllSetsForSessions(sessions.map { it.id })
            .groupBy { it.sessionId }
        // getRecentCompletedSessions is already newest-first, which is the order the rule reads.
        val history = sessions.map { s -> setsBySession[s.id].orEmpty().mapTo(mutableSetOf()) { it.exerciseId } }
        return WorkoutRoutine.nextWorkoutId(history, candidates)?.let { getSavedWorkout(it) }
    }

    suspend fun getSavedWorkout(id: Long): SavedWorkoutDetail? {
        val workout = db.savedWorkoutDao().getById(id) ?: return null
        val rows = db.savedWorkoutDao().getExerciseRows(id)
        val byId = db.exerciseDao().getByIds(rows.map { it.exerciseId }).associateBy { it.id }
        return workout.toDetail(rows, byId)
    }

    private fun SavedWorkout.toDetail(rows: List<SavedWorkoutExercise>, byId: Map<Long, Exercise>) =
        SavedWorkoutDetail(
            id = id,
            name = name,
            // Normalized after the drop, so a circuit that lost a member is still a valid structure.
            entries = CircuitStructure.normalize(
                rows.sortedBy { it.position }.mapNotNull { r ->
                    byId[r.exerciseId]?.let { SavedWorkoutEntry(it, r.reps, r.sets, r.circuitId, r.weight) }
                }
            ),
        )

    /** Inserts (id == null) or fully replaces (id != null) a saved workout in one transaction. */
    suspend fun saveWorkout(id: Long?, name: String, entries: List<SavedWorkoutEntry>): Long = db.withTransaction {
        val dao = db.savedWorkoutDao()
        val workoutId = if (id == null) {
            dao.insert(SavedWorkout(name = name, createdAt = System.currentTimeMillis()))
        } else {
            val existing = dao.getById(id) ?: error("Saved workout $id not found")
            dao.update(existing.copy(name = name))
            dao.deleteExerciseRows(id)
            id
        }
        dao.insertExerciseRows(CircuitStructure.normalize(entries).mapIndexed { i, e ->
            SavedWorkoutExercise(
                workoutId = workoutId, exerciseId = e.exercise.id, position = i, reps = e.reps,
                sets = e.sets.coerceIn(CircuitStructure.MIN_SETS, CircuitStructure.MAX_SETS),
                circuitId = e.circuitId, weight = e.weight?.takeIf { it > 0f },
            )
        })
        workoutId
    }

    suspend fun deleteSavedWorkout(id: Long) = db.withTransaction {
        db.savedWorkoutDao().deleteExerciseRows(id)
        db.savedWorkoutDao().deleteById(id)
    }

    /** One exercise's logged sets, reduced to what a saved-workout row needs. */
    private data class LoggedMember(
        val exercise: Exercise,
        val circuitId: Int?,
        val reps: Int?,
        val sets: Int,
        val firstAt: Long,
        val firstId: Long,
        val lastAt: Long,
        val lastId: Long,
    )

    /**
     * Captures a completed session as a saved workout: exercises in order of first set, each with
     * the first set's target reps, its logged set count, and its circuit. Each circuit member keeps
     * the rounds it actually got, so a saved circuit can be uneven.
     *
     * Blocks (circuits and solo rows) keep first-logged-set order. Within a circuit block, members
     * order by their *last* logged set instead: rounds align from the end (the late-joiner rule —
     * see [Block.roundsDone]), so the final round is the one every surviving member shares, and it
     * runs in slot order. Ordering by first set instead would put a late joiner — who logs its
     * first set only once the others are already a round in — ahead of members it actually follows.
     */
    suspend fun saveSessionAsWorkout(sessionId: Long, name: String): Long {
        val byExercise = db.workoutSetDao().getSetsForSession(sessionId).groupBy { it.exerciseId }
        val byId = db.exerciseDao().getByIds(byExercise.keys.toList()).associateBy { it.id }
        val members = byExercise.mapNotNull { (exerciseId, rows) ->
            byId[exerciseId]?.let { exercise ->
                val sorted = rows.sortedWith(compareBy({ it.completedAt ?: Long.MAX_VALUE }, { it.id }))
                LoggedMember(
                    exercise = exercise, circuitId = sorted.first().circuitId,
                    reps = sorted.first().targetReps,
                    sets = sorted.size.coerceIn(CircuitStructure.MIN_SETS, CircuitStructure.MAX_SETS),
                    firstAt = sorted.first().completedAt ?: Long.MAX_VALUE, firstId = sorted.first().id,
                    lastAt = sorted.last().completedAt ?: Long.MAX_VALUE, lastId = sorted.last().id,
                )
            }
        }
        // A circuit runs to completion before the next block starts, so once members are in the
        // order they were first logged the members of one circuit are contiguous — which is the
        // adjacency rule CircuitStructure already knows. Within a block, the order the members
        // last came around is their row order.
        val byFirstSet = members.sortedWith(compareBy({ it.firstAt }, { it.firstId }))
        val ordered = CircuitStructure.blocksBy(byFirstSet, { it.circuitId }).flatMap { block ->
            byFirstSet.slice(block.indices).sortedWith(compareBy({ it.lastAt }, { it.lastId }))
        }
        val entries = ordered.map {
            SavedWorkoutEntry(exercise = it.exercise, reps = it.reps, sets = it.sets, circuitId = it.circuitId)
        }
        // No equalizeRounds here, deliberately: each member keeps the rounds it actually got. A
        // mid-circuit swap leaves both the abandoned exercise and its replacement in the session,
        // and equalizing gave both of them the block's full rounds — saving a template that
        // proposes more of an exercise the user walked away from. The same shape covers a member
        // cut short for any other reason, which the session log does not distinguish from a swap.
        // Circuits in a saved workout can therefore be uneven; a per-row `sets` is what the
        // planner and WorkoutSequence read, and the editor's round chip shows the block maximum.
        return saveWorkout(null, name, CircuitStructure.normalize(entries))
    }

    // History
    suspend fun getAllSessions(): List<WorkoutSession> = db.workoutSessionDao().getAll()

    /** Removes the session and its sets, then replays so derived state forgets them too. */
    suspend fun deleteSession(sessionId: Long) {
        db.withTransaction {
            db.workoutSetDao().deleteAllForSession(sessionId)
            db.workoutSessionDao().deleteById(sessionId)
        }
        replayDerivedState()
    }

    /**
     * Each session's exercise names (first-appearance order, matching [getSetsForSession]'s id-ASC
     * order), for every session in one pass: one sets query grouped in memory + one exercises
     * query, rather than a name lookup per session.
     */
    suspend fun getSessionExerciseNames(sessionIds: List<Long>): Map<Long, List<String>> {
        val setsBySession = db.workoutSetDao().getAllOrderedById().groupBy { it.sessionId }
        val allExerciseIds = setsBySession.values.flatten().map { it.exerciseId }.distinct()
        val nameById = db.exerciseDao().getByIds(allExerciseIds).associate { it.id to it.name }
        return sessionIds.associateWith { sessionId ->
            setsBySession[sessionId].orEmpty().map { it.exerciseId }.distinct().mapNotNull { nameById[it] }
        }
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
