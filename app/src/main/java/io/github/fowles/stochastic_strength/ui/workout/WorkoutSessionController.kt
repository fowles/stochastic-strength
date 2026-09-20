package io.github.fowles.stochastic_strength.ui.workout

import androidx.annotation.VisibleForTesting
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.CircuitEdits
import io.github.fowles.stochastic_strength.domain.CircuitStructure
import io.github.fowles.stochastic_strength.domain.DetrainingModel
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.DurationCalculator
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity
import io.github.fowles.stochastic_strength.domain.ReplacementTier
import io.github.fowles.stochastic_strength.domain.TimedSet
import io.github.fowles.stochastic_strength.domain.WorkoutGenerator
import io.github.fowles.stochastic_strength.domain.WorkoutPlanner
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.WorkoutSequence
import io.github.fowles.stochastic_strength.domain.history.RestQuips
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutDetail
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExerciseRemovalReason { NO_EQUIPMENT, DISLIKE, SKIP_TODAY }

sealed interface NavigationEvent {
    data object WorkoutCompleted : NavigationEvent
}

class WorkoutSessionController(
    private val database: AppDatabase,
    private val repository: WorkoutRepository,
    private val bus: WorkoutSessionBus,
    private val scope: CoroutineScope,
    private val onVibrate: () -> Unit = {},
    private val timedSetSeconds: Int = TimedSet.DURATION_SECONDS,
) {

    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Loading)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private val _navigationEvent = Channel<NavigationEvent>(Channel.BUFFERED)
    val navigationEvent: Flow<NavigationEvent> = _navigationEvent.receiveAsFlow()

    private var weightUnit: WeightUnit = WeightUnit.KG
    private var planner: WorkoutPlanner? = null

    /**
     * The single place [planner] is replaced. Every caller goes through here rather than assigning
     * the field, because the field is read across suspends: `addExercise`, `applySavedWorkout` and
     * `replaceExercise` all reach for it *after* their database reads, so when it is swapped
     * matters as much as what it is swapped to.
     *
     * A test needs the swap to land at an exact moment — while another method's suspend is
     * blocked — and cannot get there through [onLocationRefreshed], whose own database calls
     * queue behind that block on a single-threaded test executor. Calling this directly is that
     * seam: it touches no database, so it can run from the test thread mid-block.
     */
    @VisibleForTesting
    internal fun adoptPlanner(p: WorkoutPlanner) {
        planner = p
    }
    private var sessionLocationId: Long? = null
    private var preferredRepMin: Int = 5
    private var preferredRepMax: Int = 10
    private var targetCount: Int = WorkoutGenerator.DEFAULT_EXERCISE_COUNT

    /**
     * Exercises the user put in the plan on purpose (added by hand, or loaded from a saved
     * workout). They survive a location refresh even when this location excludes them — the rule
     * is that an explicit pick is flagged, never dropped.
     */
    private val explicitIds = mutableSetOf<Long>()

    private var restTimerJob: Job? = null
    private var timedSetTimerJob: Job? = null
    private var addExerciseJob: Job? = null

    init {
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                bus.notificationState.value = null
            }
        }
    }

    suspend fun initializeSession(
        locationId: Long?,
        locationName: String?,
        preferredExerciseCount: Int,
        preferredRepMin: Int,
        preferredRepMax: Int,
        weightUnit: WeightUnit,
    ) {
        this.weightUnit = weightUnit
        this.sessionLocationId = locationId
        explicitIds.clear()
        this.preferredRepMin = preferredRepMin
        this.preferredRepMax = preferredRepMax
        val p = repository.buildPlanner(locationId, weightUnit)
        adoptPlanner(p)
        val plan = p.generateWorkout(repMin = preferredRepMin, repMax = preferredRepMax)
        targetCount = preferredExerciseCount
        setState(WorkoutState.PlanPreview(
            plan = plan,
            locationName = locationName,
            repMin = preferredRepMin,
            repMax = preferredRepMax,
            targetCount = preferredExerciseCount,
        ))
        adjustExerciseCount(preferredExerciseCount)
        maybeNoteDetraining()
    }

    private suspend fun maybeNoteDetraining() {
        if (_state.value !is WorkoutState.PlanPreview) return
        val lastCompleted = database.workoutSessionDao().getRecentCompletedSessions(limit = 1)
            .firstOrNull()?.endTime ?: return
        val weeks = DetrainingModel.weeksOff(lastCompleted, System.currentTimeMillis())
        if (!DetrainingModel.qualifies(weeks)) return
        // The delta this method owns is the notice alone. initializeSession kicks off the count
        // slider's async grow loop just before calling this, so a plan snapshot taken before the
        // query above would revert the plan to its pre-grow state on the way back.
        applyPreviewDelta { it.copy(detraining = DetrainingNotice(weeksOff = weeks)) }
    }

    fun dismissDetrainingNotice() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        setState(preview.copy(detraining = null))
    }

    /**
     * True while a tap's database write is in flight. The state only moves on after the write, so
     * a second tap in that window would pass the same state check and write the same thing again.
     */
    private var writeInFlight = false

    private fun launchOnce(block: suspend () -> Unit) {
        if (writeInFlight) return
        writeInFlight = true
        scope.launch {
            try { block() } finally { writeInFlight = false }
        }
    }

    fun startFirstExercise() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        if (preview.plan.exercises.isEmpty()) return
        launchOnce {
            val now = System.currentTimeMillis()
            val sessionId = database.workoutSessionDao().insert(
                WorkoutSession(startTime = now, locationId = sessionLocationId)
            )
            // Re-read the preview after the insert rather than using the snapshot taken before
            // it. The preview is still on screen for the duration of that suspend, so an edit
            // can land in the window — and the plan chosen here is the one the whole session
            // runs on, so a stale snapshot would lose that edit for the rest of the workout.
            // (applyPreviewDelta doesn't fit: this is a deliberate exit *from* PlanPreview.)
            val plan = (_state.value as? WorkoutState.PlanPreview)?.plan ?: preview.plan
            activeSetFor(plan, emptyMap(), sessionId)?.let(::setState)
                ?: finishWorkout(sessionId)
        }
    }

    /** The set [WorkoutSequence] says is next, warmups first when it is that exercise's first. Null = finished. */
    private fun activeSetFor(plan: WorkoutPlan, done: Map<Long, Int>, sessionId: Long): WorkoutState.ActiveSet? {
        val step = WorkoutSequence.next(plan.exercises, done) ?: return null
        val ex = plan.exercises[step.exerciseIndex]
        return WorkoutState.ActiveSet(
            plan = plan,
            exerciseIndex = step.exerciseIndex,
            setIndex = step.setIndex,
            sessionId = sessionId,
            warmupSetIndex = if (step.setIndex == 0 && ex.warmupSets.isNotEmpty()) 0 else null,
            done = done,
        )
    }

    fun replaceExercise(exerciseId: Long, reason: ExerciseRemovalReason) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val planned = preview.plan.exercises.find { it.exercise.id == exerciseId } ?: return
        val rejectedId = exerciseId
        scope.launch {
            when (reason) {
                ExerciseRemovalReason.DISLIKE ->
                    database.exerciseDao().update(planned.exercise.copy(isDisliked = true))
                // With no location there is nowhere to remember it, but the row still goes.
                ExerciseRemovalReason.NO_EQUIPMENT ->
                    sessionLocationId?.let { repository.excludeExercise(it, planned.exercise.id) }
                ExerciseRemovalReason.SKIP_TODAY -> Unit
            }
            val p = if (reason != ExerciseRemovalReason.SKIP_TODAY) {
                repository.buildPlanner(sessionLocationId, weightUnit)
                    .also(::adoptPlanner)
            } else {
                planner ?: return@launch
            }
            // The delta this method owns is the one rejected row (replaced or removed) plus the
            // rejected-ids bookkeeping: apply it against the live preview, not a plan snapshot
            // taken before buildPlanner's suspend.
            applyPreviewDelta { live ->
                val updatedPlan = live.plan.copy(
                    sessionRejectedIds = live.plan.sessionRejectedIds + rejectedId
                )
                val currentIndex = updatedPlan.exercises.indexOfFirst { it.exercise.id == rejectedId }
                if (currentIndex < 0) return@applyPreviewDelta null
                // The slider is a floor, not the plan's size: only restock when removing would drop below it.
                val replacement = if (updatedPlan.exercises.size - 1 < targetCount)
                    p.pickReplacement(updatedPlan, currentIndex) else null
                val old = updatedPlan.exercises[currentIndex]
                val newExercises = if (replacement != null)
                    updatedPlan.exercises.toMutableList().also { it[currentIndex] = replacement.inSlotOf(old, p) }
                else CircuitEdits.remove(updatedPlan.exercises, currentIndex)
                prunedToPlanRows(live.copy(plan = updatedPlan.copy(exercises = newExercises)))
            }
        }
    }

    fun adjustExerciseCount(newTarget: Int) {
        addExerciseJob?.cancel()
        targetCount = newTarget.coerceAtLeast(1)
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val current = preview.plan.exercises
        when {
            targetCount < current.size -> {
                setState(prunedToPlanRows(
                    preview.copy(plan = preview.plan.copy(exercises = trimToTarget(current)), targetCount = targetCount)
                ))
            }
            targetCount > current.size -> {
                val needed = targetCount - current.size
                setState(preview.copy(targetCount = targetCount))
                addExerciseJob = scope.launch {
                    repeat(needed) {
                        val p = _state.value as? WorkoutState.PlanPreview ?: return@launch
                        val extra = planner?.pickAdditional(p.plan) ?: return@launch
                        setState(p.copy(plan = p.plan.copy(exercises = p.plan.exercises + extra)))
                    }
                }
            }
            else -> setState(preview.copy(targetCount = targetCount))
        }
    }

    /**
     * The count slider is a minimum, never a cut: it removes only *plain* rows — not explicit, not
     * pinned, not a circuit member — latest first, until [targetCount] is reached or no plain row
     * is left. The plan may therefore stay longer than the target.
     */
    private fun trimToTarget(exercises: List<PlannedExercise>): List<PlannedExercise> {
        var rows = CircuitStructure.normalize(exercises)
        while (rows.size > targetCount) {
            val blocks = CircuitStructure.blocks(rows)
            val i = rows.indices.lastOrNull { idx ->
                val block = blocks.first { idx in it.indices }
                !block.isCircuit &&
                    rows[idx].exercise.id !in explicitIds &&
                    !rows[idx].repsPinned &&
                    !rows[idx].weightPinned
            } ?: break
            rows = CircuitEdits.remove(rows, i)
        }
        return rows
    }

    fun setRepRange(repMin: Int, repMax: Int) {
        addExerciseJob?.cancel()
        preferredRepMin = repMin
        preferredRepMax = repMax
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val p = planner ?: return
        val newPlan = p.repriceForReps(preview.plan, repMin, repMax)
        setState(preview.copy(plan = newPlan, repMin = repMin, repMax = repMax))
    }

    /** Applies [edit] to one preview row and re-prices it through the planner, honouring its pins. */
    private fun editRow(exerciseId: Long, edit: (PlannedExercise) -> PlannedExercise) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val p = planner ?: return
        val rows = preview.plan.exercises.map {
            if (it.exercise.id == exerciseId) p.reprice(edit(it), preview.plan.sessionReps) else it
        }
        if (rows == preview.plan.exercises) return
        setState(preview.copy(plan = preview.plan.copy(exercises = rows), edited = true))
    }

    /** What the planner would price [pe] at right now, for the "suggests …" line beside a pinned weight. */
    fun suggestedWeight(pe: PlannedExercise): Float = planner?.suggestedWeight(pe.exercise, pe.sessionReps) ?: pe.sessionWeight

    /** [steps] grid increments (±1 per stepper tap); a row with no weight to move is left alone. */
    fun adjustExerciseWeight(exerciseId: Long, steps: Int) = editRow(exerciseId) { pe ->
        if (pe.sessionWeight <= 0f) pe else pe.copy(
            sessionWeight = WeightFormatter.step(pe.sessionWeight, steps, weightUnit),
            weightPinned = true,
        )
    }

    fun setExerciseReps(exerciseId: Long, reps: Int) = editRow(exerciseId) { pe ->
        if (pe.exercise.isTimed) pe else pe.copy(sessionReps = reps.coerceIn(PlannedExercise.PINNED_REPS), repsPinned = true)
    }

    fun resetExerciseReps(exerciseId: Long) = editRow(exerciseId) { it.copy(repsPinned = false) }

    fun resetExerciseWeight(exerciseId: Long) = editRow(exerciseId) { it.copy(weightPinned = false) }

    /** Applies a structure edit to the preview rows and re-prices durations (rounds may have changed). */
    private fun editStructure(edit: (List<PlannedExercise>) -> List<PlannedExercise>) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val p = planner ?: return
        val edited = edit(preview.plan.exercises)
        if (edited == preview.plan.exercises) return
        setState(preview.copy(plan = preview.plan.copy(exercises = edited.map(p::restampDuration)), edited = true))
    }

    /** A replacement row takes over the structural slot of the row it replaces. */
    private fun PlannedExercise.inSlotOf(old: PlannedExercise, p: WorkoutPlanner): PlannedExercise =
        p.restampDuration(withStructure(old.sets, old.circuitId))

    /** Indices are block indices: a circuit moves as a unit, and a drag never changes membership. */
    fun moveExercise(from: Int, to: Int) = editStructure { CircuitEdits.moveBlock(it, from, to) }

    fun linkExercises(rowIndex: Int) = editStructure { CircuitEdits.link(it, rowIndex) }

    fun unlinkExercises(rowIndex: Int) = editStructure { CircuitEdits.unlink(it, rowIndex) }

    fun setExerciseSets(exerciseId: Long, sets: Int) = editStructure { rows ->
        CircuitEdits.setRounds(rows, rows.indexOfFirst { it.exercise.id == exerciseId }, sets)
    }

    fun addExercise(exerciseId: Long) {
        // A running count-slider grow loop would overwrite the added row with pre-add state.
        addExerciseJob?.cancel()
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        if (preview.plan.exercises.any { it.exercise.id == exerciseId }) return
        scope.launch {
            if (planner == null) return@launch
            val exercise = repository.getExerciseById(exerciseId) ?: return@launch
            val excluded = sessionLocationId?.let { repository.getExcludedExerciseIds(it) } ?: emptySet()
            // The delta this method owns is only the appended row: price and flag it against
            // whatever the live preview looks like now, and fold it into that live state — never
            // a plan snapshot taken before these suspends.
            applyPreviewDelta { live ->
                // Read the planner here, not before the suspends: onLocationRefreshed can swap it
                // for a fresher one in that window, and the row should be priced against the
                // latest. `excluded` stays valid across that swap — onLocationRefreshed rebuilds
                // the planner for the same sessionLocationId, so the exclusion set is unchanged.
                val p = planner ?: return@applyPreviewDelta null
                if (live.plan.exercises.any { it.exercise.id == exerciseId }) return@applyPreviewDelta null
                val planned = p.planExplicit(exercise, reps = null, plan = live.plan)
                explicitIds += exerciseId
                val newPlan = live.plan.copy(
                    exercises = live.plan.exercises + planned,
                    sessionRejectedIds = live.plan.sessionRejectedIds - exerciseId,
                )
                val flag = rowFlagFor(exercise, excluded, p)
                // Unconditionally correct even if a stale flag were ever left under this id.
                val clearedFlags = live.rowFlags - exerciseId
                live.copy(
                    plan = newPlan,
                    rowFlags = if (flag != null) clearedFlags + (exerciseId to flag) else clearedFlags,
                    edited = true,
                )
            }
        }
    }

    fun loadSavedWorkout(id: Long) = applySavedWorkout(id, append = false)

    fun appendSavedWorkout(id: Long) = applySavedWorkout(id, append = true)

    private fun applySavedWorkout(id: Long, append: Boolean) {
        addExerciseJob?.cancel()
        scope.launch {
            val saved = repository.getSavedWorkout(id) ?: return@launch
            val entries = saved.entries.distinctBy { it.exercise.id }
            val loadedIds = entries.map { it.exercise.id }.toSet()
            if (planner == null) return@launch
            val excluded = sessionLocationId?.let { repository.getExcludedExerciseIds(it) } ?: emptySet()
            // This method's delta IS the whole exercise list — a load/append replaces the rows
            // wholesale. What must survive is everything outside the exercise list (location name,
            // slider target, detraining notice, …), so fold the new list into the live preview
            // rather than writing back a plan snapshot taken before these suspends.
            applyPreviewDelta { live ->
                // Read the planner here, not before the suspends: onLocationRefreshed can swap it
                // for a fresher one in that window, and the loaded rows should be priced against
                // the latest. `excluded` stays valid across that swap — onLocationRefreshed
                // rebuilds the planner for the same sessionLocationId.
                val p = planner ?: return@applyPreviewDelta null
                val basePlan = live.plan
                // A loaded row wins over an existing row for the same exercise.
                val kept = if (append) basePlan.exercises.filter { it.exercise.id !in loadedIds } else emptyList()
                val loaded = entries.map { p.planExplicit(it.exercise, it.reps, basePlan, it.sets, it.circuitId, it.weight) }
                explicitIds += loadedIds
                val newPlan = basePlan.copy(
                    // concat keeps a kept circuit and a loaded one distinct even when their ids collide.
                    exercises = CircuitStructure.concat(CircuitStructure.normalize(kept), loaded),
                    sessionRejectedIds = basePlan.sessionRejectedIds - loadedIds,
                )
                // A load replaces the plan wholesale, so rows that were explicit before can depart here.
                val pruned = prunedToPlanRows(live.copy(plan = newPlan, edited = true))
                pruned.copy(rowFlags = computeRowFlags(pruned.plan, excluded, p))
            }
        }
    }

    /** Saves the current preview rows — order, sets and circuits — carrying only their pinned reps/weight. Null if not on the preview. */
    suspend fun saveCurrentPlan(name: String): SavedWorkoutDetail? {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return null
        val id = repository.saveWorkout(
            id = null,
            name = name,
            entries = preview.plan.exercises.map {
                SavedWorkoutEntry(
                    it.exercise,
                    it.sessionReps.takeIf { _ -> it.repsPinned },
                    it.sets,
                    it.circuitId,
                    it.sessionWeight.takeIf { _ -> it.weightPinned },
                )
            },
        )
        return repository.getSavedWorkout(id)
    }

    /**
     * Drops the bookkeeping for rows that have left the plan, so [explicitIds] and
     * [WorkoutState.PlanPreview.rowFlags] stay a faithful projection of the rows on screen.
     */
    private fun prunedToPlanRows(preview: WorkoutState.PlanPreview): WorkoutState.PlanPreview {
        val presentIds = preview.plan.exercises.mapTo(mutableSetOf()) { it.exercise.id }
        explicitIds.retainAll(presentIds)
        if (preview.rowFlags.keys.all { it in presentIds }) return preview
        return preview.copy(rowFlags = preview.rowFlags.filterKeys { it in presentIds })
    }

    /** The flag the generator would have filtered [exercise] on: location-excluded first, then unrested muscle. */
    private fun rowFlagFor(exercise: Exercise, excluded: Set<Long>, p: WorkoutPlanner?): RowFlag? = when {
        exercise.id in excluded -> RowFlag.NOT_AT_LOCATION
        p != null && !p.isMuscleRested(exercise) -> RowFlag.TRAINED_RECENTLY
        else -> null
    }

    /** [rowFlagFor] applied to every row currently in [plan]. */
    private fun computeRowFlags(plan: WorkoutPlan, excluded: Set<Long>, p: WorkoutPlanner?): Map<Long, RowFlag> =
        plan.exercises.mapNotNull { pe -> rowFlagFor(pe.exercise, excluded, p)?.let { pe.exercise.id to it } }.toMap()

    /**
     * Re-reads the live [WorkoutState.PlanPreview] after a suspend and folds in only the delta
     * [transform] computes from it, instead of writing back a plan snapshot taken before that
     * suspend (which would silently discard any edit the user made in the meantime). Drops the
     * update — leaving the live state exactly as it is — when the user has since left the preview,
     * or when [transform] finds nothing to apply (it returns null).
     */
    private fun applyPreviewDelta(transform: (WorkoutState.PlanPreview) -> WorkoutState.PlanPreview?) {
        val live = _state.value as? WorkoutState.PlanPreview ?: return
        val updated = transform(live) ?: return
        setState(updated)
    }

    fun completeWarmupSet() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        val warmupIdx = current.warmupSetIndex ?: return
        val nextIdx = warmupIdx + 1
        if (nextIdx < current.plannedExercise.warmupSets.size) {
            setState(current.copy(warmupSetIndex = nextIdx))
        } else {
            val commitTarget = WorkoutState.ActiveSet(
                plan = current.plan,
                exerciseIndex = current.exerciseIndex,
                setIndex = 0,
                sessionId = current.sessionId,
                warmupSetIndex = null,
                done = current.done,
            )
            stageRest(current, StagedAction(
                kind = StagedKind.WARMUP_DONE,
                undoTarget = current,
                commitTarget = commitTarget,
            ))
        }
    }

    fun startTimedSet() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        if (current.timerSecondsRemaining != null) return
        setState(current.copy(timerSecondsRemaining = timedSetSeconds))
        timedSetTimerJob?.cancel()
        timedSetTimerJob = scope.launch {
            while (true) {
                delay(1000)
                val s = _state.value as? WorkoutState.ActiveSet ?: return@launch
                val remaining = s.timerSecondsRemaining ?: return@launch
                if (remaining <= 1) {
                    onVibrate()
                    setState(s.copy(timerSecondsRemaining = 0))
                    recordFeedback(SetFeedback.RIR_0_1)
                    return@launch
                }
                setState(s.copy(timerSecondsRemaining = remaining - 1))
            }
        }
    }

    fun recordFeedback(feedback: SetFeedback) {
        timedSetTimerJob?.cancel()
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        launchOnce {
            val planned = current.plannedExercise
            val initialActualReps: Int? = when (feedback) {
                SetFeedback.RIR_0_1, SetFeedback.RIR_2_4, SetFeedback.RIR_5_PLUS -> planned.sessionReps
                SetFeedback.TOO_HARD, SetFeedback.HURT -> null
            }
            val rowId = database.workoutSetDao().insert(
                WorkoutSet(
                    sessionId = current.sessionId,
                    exerciseId = planned.exercise.id,
                    setNumber = current.setIndex + 1,
                    targetWeight = planned.sessionWeight,
                    targetReps = planned.sessionReps,
                    circuitId = planned.circuitId,
                    actualReps = initialActualReps,
                    feedback = feedback,
                    completedAt = System.currentTimeMillis(),
                    durationSeconds = TimedSet.elapsedSeconds(
                        isTimed = planned.exercise.isTimed,
                        secondsRemaining = current.timerSecondsRemaining,
                        fullSeconds = timedSetSeconds,
                    ),
                )
            )
            if (feedback == SetFeedback.HURT) {
                database.exerciseHurtStateDao().upsert(
                    ExerciseHurtState(
                        exerciseId = planned.exercise.id,
                        isHurt = true,
                        asOf = System.currentTimeMillis(),
                    )
                )
            }
            val isHurt = feedback == SetFeedback.HURT
            val completedSetIndex = if (isHurt) current.totalSets - 1 else current.setIndex
            // HURT ends the exercise: it drops out of any remaining rounds.
            val done = current.done + (planned.exercise.id to if (isHurt) planned.sets else current.setIndex + 1)
            setState(WorkoutState.Resting(
                plan = current.plan,
                exerciseIndex = current.exerciseIndex,
                completedSetIndex = completedSetIndex,
                sessionId = current.sessionId,
                secondsRemaining = REST_SECONDS,
                lastFeedback = feedback,
                weightAtSetStart = current.plannedExercise.sessionWeight,
                currentSetRowId = rowId,
                restQuip = RestQuips.pick(upcomingMusclesAfterRest(current.plan, done), Random.Default),
                done = done,
            ))
            startRestTimer()
        }
    }

    fun undoLastSet() {
        restTimerJob?.cancel()
        val resting = _state.value as? WorkoutState.Resting ?: return
        resting.staged?.let {
            setState(it.undoTarget)
            return
        }
        val restoredExercises = resting.plan.exercises.toMutableList()
        restoredExercises[resting.exerciseIndex] =
            restoredExercises[resting.exerciseIndex].copy(sessionWeight = resting.weightAtSetStart)
        val restoredPlan = resting.plan.copy(exercises = restoredExercises)
        scope.launch {
            val row = database.workoutSetDao().getById(resting.currentSetRowId)
            val exerciseId = restoredPlan.exercises[resting.exerciseIndex].exercise.id
            val setIndex = row?.let { it.setNumber - 1 } ?: resting.completedSetIndex
            database.workoutSetDao().deleteById(resting.currentSetRowId)
            setState(WorkoutState.ActiveSet(
                plan = restoredPlan,
                exerciseIndex = resting.exerciseIndex,
                setIndex = setIndex,
                sessionId = resting.sessionId,
                done = resting.done + (exerciseId to setIndex),
            ))
        }
    }

    fun skipRest() {
        restTimerJob?.cancel()
        advanceAfterRest()
    }

    fun reduceExerciseWeight(completedReps: Int) {
        val resting = _state.value as? WorkoutState.Resting ?: return
        scope.launch {
            database.workoutSetDao().updateActualReps(resting.currentSetRowId, completedReps)
        }
        val exercise = resting.plan.exercises[resting.exerciseIndex]
        val moreSetsForThisExercise = (resting.done[exercise.exercise.id] ?: 0) < exercise.sets
        if (!moreSetsForThisExercise || exercise.sessionWeight <= 0f) {
            setState(resting.copy(weightReductionApplied = true))
            return
        }
        val newWeight = maxOf(0.5f, WeightFormatter.round(
            DefaultProgressionEngine.scaleReps(exercise.sessionWeight, from = maxOf(1, completedReps), to = exercise.sessionReps),
            weightUnit,
        ))
        val updatedExercises = resting.plan.exercises.toMutableList()
        updatedExercises[resting.exerciseIndex] = exercise.copy(sessionWeight = newWeight)
        setState(resting.copy(plan = resting.plan.copy(exercises = updatedExercises), weightReductionApplied = true))
    }

    /** Completes once the finished session has been replayed into derived state. */
    private val sessionReplayed = CompletableDeferred<Unit>()

    fun completeWorkout() {
        if (_state.value !is WorkoutState.Done) return
        scope.launch {
            sessionReplayed.await()
            _navigationEvent.send(NavigationEvent.WorkoutCompleted)
        }
    }

    /**
     * Fill in a location's human-readable name once background reverse-geocoding returns. No-op
     * unless we're still on the plan preview (the only place the label shows); the label is purely
     * cosmetic, so a late answer just updates it in place.
     */
    fun updateLocationName(name: String) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        if (preview.locationName == name) return
        setState(preview.copy(locationName = name))
    }

    fun onLocationRefreshed() {
        if (_state.value !is WorkoutState.PlanPreview) return
        val locationId = sessionLocationId ?: return
        scope.launch {
            val locationName = database.knownLocationDao().getById(locationId)?.name
            val freshPlanner = repository.buildPlanner(locationId, weightUnit)
            val excluded = repository.getExcludedExerciseIds(locationId)
            // Deliberately unconditional even if the preview has moved on (or away) by now: this
            // is a plain field, not the observable WorkoutState, and it's still the freshest
            // planner for sessionLocationId either way — nothing to drop here.
            adoptPlanner(freshPlanner)
            val availableIds = freshPlanner.availableExercises.map { it.id }.toSet()
            // This method's delta is only the location-derived row flags (and the availability
            // swaps/removals that follow from them) — apply them to the live preview's rows, not
            // to a plan snapshot taken before these suspends.
            applyPreviewDelta { live ->
                var plan = live.plan
                var i = 0
                while (i < plan.exercises.size) {
                    val id = plan.exercises[i].exercise.id
                    // An explicitly chosen row is flagged, not dropped, even where it's unavailable.
                    if (id !in availableIds && id !in explicitIds) {
                        val replacement = freshPlanner.pickReplacement(plan, i)
                        val updated = if (replacement != null)
                            plan.exercises.toMutableList().also { it[i] = replacement.inSlotOf(plan.exercises[i], freshPlanner) }
                        else CircuitEdits.remove(plan.exercises, i).also { i-- }
                        plan = plan.copy(exercises = updated)
                    }
                    i++
                }
                // Flags can change even when the rows don't (this location now excludes an explicit
                // row, or no longer does), so compare the fully rebuilt preview.
                val pruned = prunedToPlanRows(live.copy(plan = plan, locationName = locationName))
                val refreshed = pruned.copy(rowFlags = computeRowFlags(pruned.plan, excluded, freshPlanner))
                if (refreshed == live) null else refreshed
            }
        }
    }

    fun setActiveSetWeight(newWeight: Float) {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        val i = current.exerciseIndex
        val pe = current.plannedExercise
        val w = WeightFormatter.clampToGrid(newWeight, weightUnit)
        if (w == pe.sessionWeight) return
        val exercises = current.plan.exercises.toMutableList()
        val warmupSets = when {
            pe.exercise.isTimed -> emptyList()
            current.warmupSetIndex != null -> planner?.computeWarmupSets(w, pe.exercise) ?: pe.warmupSets
            else -> pe.warmupSets
        }
        exercises[i] = pe.copy(sessionWeight = w, warmupSets = warmupSets)
        val newPlan = current.plan.copy(exercises = exercises)
        val commitTarget = WorkoutState.ActiveSet(
            plan = newPlan,
            exerciseIndex = i,
            setIndex = current.setIndex,
            sessionId = current.sessionId,
            // A lighter weight has fewer warmups (none at all near the bar): an index past the new
            // list means the warmups are over, not that there is a set to show there.
            warmupSetIndex = current.warmupSetIndex?.takeIf { it < warmupSets.size },
            done = current.done,
        )
        stageRest(current, StagedAction(
            kind = StagedKind.ADJUST_WEIGHT,
            undoTarget = current,
            commitTarget = commitTarget,
        ))
    }

    /**
     * Drops row [i] without normalizing: mid-session, logged rows already carry their circuit id,
     * and renumbering the survivors would write later circuits under an id the session has used.
     * A lone tagged row is a block of one, so the sequence and its labels are unaffected.
     */
    private fun List<PlannedExercise>.withoutRow(i: Int): List<PlannedExercise> =
        if (i !in indices) this else filterIndexed { idx, _ -> idx != i }

    fun swapCurrentExercise(reason: ExerciseRemovalReason) {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        val i = current.exerciseIndex
        val original = current.plannedExercise.exercise
        val hasLogged = current.warmupSetIndex == null && current.setIndex > 0
        val p = planner ?: return

        val rejectedPlan = current.plan.copy(
            sessionRejectedIds = current.plan.sessionRejectedIds + original.id,
        )
        val replacement = p.pickReplacement(
            rejectedPlan, i,
            listOf(ReplacementTier.WEIGHTED_MUSCLE, ReplacementTier.MUSCLE, ReplacementTier.ANY),
        )

        val old = current.plannedExercise
        val loggedSets = current.done[original.id] ?: 0
        var done = current.done
        val exercises: List<PlannedExercise> = when {
            replacement == null && hasLogged -> {
                done = done + (original.id to old.sets) // keep original, advance past it
                rejectedPlan.exercises
            }
            replacement == null -> rejectedPlan.exercises.withoutRow(i)
            hasLogged -> {
                // The replacement owes only what the original had left, in the same block.
                done = done + (original.id to old.sets)
                rejectedPlan.exercises.toMutableList().also {
                    it.add(i + 1, replacement.withStructure(old.sets - loggedSets, old.circuitId))
                }
            }
            else -> rejectedPlan.exercises.toMutableList().also {
                it[i] = replacement.withStructure(old.sets, old.circuitId)
            }
        }
        val newPlan = rejectedPlan.copy(exercises = exercises)
        val commitTarget = activeSetFor(newPlan, done, current.sessionId)

        stageRest(current, StagedAction(
            kind = StagedKind.SWAP,
            undoTarget = current,
            commitTarget = commitTarget,
            pendingSwap = PendingSwap(reason, original.id, sessionLocationId),
        ))
    }

    fun stopWorkout() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        stageRest(current, StagedAction(
            kind = StagedKind.STOP_WORKOUT,
            undoTarget = current,
            commitTarget = null,
        ))
    }

    fun endCurrentExercise() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        val i = current.exerciseIndex
        val hasLogged = current.warmupSetIndex == null && current.setIndex > 0
        val id = current.plannedExercise.exercise.id
        val commitTarget = if (hasLogged) {
            activeSetFor(current.plan, current.done + (id to current.plannedExercise.sets), current.sessionId)
        } else {
            val trimmed = current.plan.exercises.withoutRow(i)
            activeSetFor(current.plan.copy(exercises = trimmed), current.done, current.sessionId)
        }
        stageRest(current, StagedAction(
            kind = StagedKind.END_EXERCISE,
            undoTarget = current,
            commitTarget = commitTarget,
        ))
    }

    private fun stageRest(current: WorkoutState.ActiveSet, action: StagedAction) {
        // A timed set that was running stops here. Undo hands back one that hasn't started: a
        // frozen countdown with no timer behind it could be neither resumed nor restarted.
        timedSetTimerJob?.cancel()
        val staged = action.copy(undoTarget = action.undoTarget.copy(timerSecondsRemaining = null))
        val target = staged.commitTarget
        setState(WorkoutState.Resting(
            plan = target?.plan ?: current.plan,
            exerciseIndex = target?.exerciseIndex ?: current.exerciseIndex,
            completedSetIndex = current.setIndex,
            sessionId = current.sessionId,
            secondsRemaining = REST_SECONDS,
            lastFeedback = null,
            weightAtSetStart = current.plannedExercise.sessionWeight,
            currentSetRowId = NO_ROW,
            staged = staged,
            done = target?.done ?: current.done,
        ))
        startRestTimer()
    }

    private suspend fun persistSwap(swap: PendingSwap) {
        when (swap.reason) {
            ExerciseRemovalReason.DISLIKE -> {
                val ex = database.exerciseDao().getById(swap.exerciseId) ?: return
                database.exerciseDao().update(ex.copy(isDisliked = true))
            }
            ExerciseRemovalReason.NO_EQUIPMENT -> {
                val locationId = swap.locationId ?: return
                repository.excludeExercise(locationId, swap.exerciseId)
            }
            // Skipping for today changes nothing the planner reads, so it needs no rebuild.
            ExerciseRemovalReason.SKIP_TODAY -> return
        }
        adoptPlanner(repository.buildPlanner(sessionLocationId, weightUnit))
    }

    private fun startRestTimer() {
        restTimerJob?.cancel()
        restTimerJob = scope.launch {
            while (true) {
                delay(1000)
                val current = _state.value as? WorkoutState.Resting ?: return@launch
                if (current.secondsRemaining <= 1) {
                    onVibrate()
                    advanceAfterRest()
                    return@launch
                }
                setState(current.copy(secondsRemaining = current.secondsRemaining - 1))
            }
        }
    }

    /** Muscles of the exercise the upcoming rest precedes; null when the rest is the workout's last. */
    private fun upcomingMusclesAfterRest(plan: WorkoutPlan, done: Map<Long, Int>): Set<MuscleGroup>? {
        val step = WorkoutSequence.next(plan.exercises, done) ?: return null
        val exercise = plan.exercises[step.exerciseIndex].exercise
        return setOf(exercise.primaryMuscle) + exercise.secondaryMuscles
    }

    private fun advanceAfterRest() {
        val current = _state.value as? WorkoutState.Resting ?: return
        val staged = current.staged
        if (staged != null) {
            // Commit first: persisting a swap suspends through a planner rebuild, and an Undo
            // tapped in that window would be overwritten by the commit it was meant to cancel.
            val target = staged.commitTarget
            if (target != null) setState(target)
            scope.launch {
                staged.pendingSwap?.let { persistSwap(it) }
                if (target == null) finishWorkout(current.sessionId)
            }
            return
        }
        activeSetFor(current.plan, current.done, current.sessionId)?.let(::setState)
            ?: finishWorkout(current.sessionId)
    }

    private fun finishWorkout(sessionId: Long) {
        val endTime = System.currentTimeMillis()
        scope.launch {
            database.workoutSessionDao().updateEndTime(sessionId, endTime)
            setState(WorkoutState.Done(sessionId))
            // Here rather than on the Done button: system back leaves the Done screen without
            // tapping it, and the next plan would be priced from beliefs that predate today's sets.
            withContext(NonCancellable) { repository.finishSession() }
            sessionReplayed.complete(Unit)
        }
    }

    private fun setState(newState: WorkoutState) {
        _state.value = newState
        bus.notificationState.value = deriveNotificationState(newState)
    }

    private fun deriveNotificationState(state: WorkoutState): WorkoutNotificationState? = when (state) {
        is WorkoutState.ActiveSet -> {
            val planned = state.plannedExercise
            if (state.warmupSetIndex != null) {
                WorkoutNotificationState.WarmupSet(
                    exerciseName = planned.exercise.name,
                    warmupSetLabel = "Warm-up ${state.warmupSetIndex + 1} of ${planned.warmupSets.size}",
                )
            } else if (planned.exercise.isTimed) {
                WorkoutNotificationState.TimedActiveSet(
                    exerciseName = planned.exercise.name,
                    setLabel = state.positionLabel,
                    secondsRemaining = state.timerSecondsRemaining,
                    progressMax = timedSetSeconds,
                )
            } else {
                WorkoutNotificationState.ActiveSet(
                    exerciseName = planned.exercise.name,
                    weightLabel = if (planned.exercise.equipment == Equipment.BODYWEIGHT)
                        "Bodyweight"
                    else
                        WeightFormatter.format(planned.sessionWeight, weightUnit),
                    repsLabel = formatQuantity(planned.sessionReps, planned.exercise.isTimed),
                    setLabel = state.positionLabel,
                )
            }
        }
        is WorkoutState.Resting -> {
            val plan = state.plan
            val staged = state.staged
            val upNextLabel = when {
                // Staged-action rests (stop-workout / end-exercise / swap / adjust-weight) aren't a
                // normal between-sets rest, so the set/exercise counters don't describe what's next.
                // Derive the label from the staged action's commit target instead.
                staged != null -> when (staged.kind) {
                    StagedKind.STOP_WORKOUT -> "Finishing workout…"
                    else -> staged.commitTarget
                        ?.let { "Next: ${it.plannedExercise.exercise.name}" }
                        ?: "Last set — almost done!"
                }
                else -> when (val step = WorkoutSequence.next(plan.exercises, state.done)) {
                    null -> "Last set — almost done!"
                    else -> {
                        val name = plan.exercises[step.exerciseIndex].exercise.name
                        when {
                            step.exerciseIndex == state.exerciseIndex -> {
                                val position = WorkoutSequence
                                    .positionLabel(plan.exercises, step.exerciseIndex, step.setIndex)
                                    .substringBefore(" of")
                                "Next: $position · $name"
                            }
                            else -> WorkoutSequence.circuitRoundLabel(plan.exercises, step)
                                ?.substringBefore(" of")
                                ?.let { round -> "Next: $name · $round" }
                                ?: "Next: $name"
                        }
                    }
                }
            }
            WorkoutNotificationState.Resting(
                secondsRemaining = state.secondsRemaining,
                progressMax = REST_SECONDS,
                upNextLabel = upNextLabel,
            )
        }
        is WorkoutState.Done, is WorkoutState.PlanPreview, WorkoutState.Loading -> null
    }

    companion object {
        const val REST_SECONDS = DurationCalculator.REST_SECONDS
        const val NO_ROW = -1L
    }
}
