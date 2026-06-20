package io.github.fowles.stochastic_strength.ui.workout

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity
import io.github.fowles.stochastic_strength.domain.WorkoutPlanner
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

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
) {

    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Loading)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private val _navigationEvent = Channel<NavigationEvent>(Channel.BUFFERED)
    val navigationEvent: Flow<NavigationEvent> = _navigationEvent.receiveAsFlow()

    private var weightUnit: WeightUnit = WeightUnit.KG
    private var planner: WorkoutPlanner? = null
    private var sessionStartTime = 0L
    private var sessionLocationId: Long? = null
    private var preferredRepMin: Int = 5
    private var preferredRepMax: Int = 10

    private var restTimerJob: Job? = null
    private var timedSetTimerJob: Job? = null
    private var addExerciseJob: Job? = null
    private var weightAdjustJob: Job? = null

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
        this.preferredRepMin = preferredRepMin
        this.preferredRepMax = preferredRepMax
        val p = repository.buildPlanner(locationId, weightUnit)
        planner = p
        val plan = p.generateWorkout(repMin = preferredRepMin, repMax = preferredRepMax)
        setState(WorkoutState.PlanPreview(
            plan = plan,
            locationName = locationName,
            repMin = preferredRepMin,
            repMax = preferredRepMax,
        ))
        adjustExerciseCount(preferredExerciseCount)
    }

    fun startFirstExercise() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        if (preview.plan.exercises.isEmpty()) return
        val frozenExercises = preview.plan.exercises.map { it.copy(originalSessionWeight = it.sessionWeight) }
        val plan = preview.plan.copy(exercises = frozenExercises)
        val firstExercise = plan.exercises[0]
        scope.launch {
            val now = System.currentTimeMillis()
            sessionStartTime = now
            val sessionId = database.workoutSessionDao().insert(
                WorkoutSession(startTime = now, locationId = sessionLocationId)
            )
            repository.applyManualBaselineOverrides(sessionId, plan.strengthOverrides)
            setState(WorkoutState.ActiveSet(
                plan = plan,
                exerciseIndex = 0,
                setIndex = 0,
                sessionId = sessionId,
                warmupSetIndex = if (firstExercise.warmupSets.isNotEmpty()) 0 else null,
            ))
        }
    }

    fun replaceExercise(index: Int, reason: ExerciseRemovalReason) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val rejectedId = preview.plan.exercises[index].exercise.id
        val planned = preview.plan.exercises[index]
        scope.launch {
            when (reason) {
                ExerciseRemovalReason.DISLIKE ->
                    database.exerciseDao().update(planned.exercise.copy(isDisliked = true))
                ExerciseRemovalReason.NO_EQUIPMENT -> {
                    val locationId = sessionLocationId ?: return@launch
                    repository.excludeExercise(locationId, planned.exercise.id)
                }
                ExerciseRemovalReason.SKIP_TODAY -> Unit
            }
            val current = _state.value as? WorkoutState.PlanPreview ?: return@launch
            val updatedPlan = current.plan.copy(
                sessionRejectedIds = current.plan.sessionRejectedIds + rejectedId
            )
            val p = if (reason != ExerciseRemovalReason.SKIP_TODAY) {
                repository.buildPlanner(sessionLocationId, weightUnit, updatedPlan.strengthOverrides)
                    .also { planner = it }
            } else {
                planner ?: return@launch
            }
            val currentIndex = updatedPlan.exercises.indexOfFirst { it.exercise.id == rejectedId }
            if (currentIndex < 0) return@launch
            val replacement = p.pickReplacement(updatedPlan, currentIndex)
            val newExercises = updatedPlan.exercises.toMutableList()
            if (replacement != null) newExercises[currentIndex] = replacement else newExercises.removeAt(currentIndex)
            setState(current.copy(plan = updatedPlan.copy(exercises = newExercises)))
        }
    }

    fun adjustExerciseCount(targetCount: Int) {
        addExerciseJob?.cancel()
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val current = preview.plan.exercises
        when {
            targetCount < current.size -> {
                val trimmed = current.take(targetCount.coerceAtLeast(1))
                setState(preview.copy(plan = preview.plan.copy(exercises = trimmed)))
            }
            targetCount > current.size -> {
                val needed = targetCount - current.size
                addExerciseJob = scope.launch {
                    repeat(needed) {
                        val p = _state.value as? WorkoutState.PlanPreview ?: return@launch
                        val extra = planner?.pickAdditional(p.plan) ?: return@launch
                        setState(p.copy(plan = p.plan.copy(exercises = p.plan.exercises + extra)))
                    }
                }
            }
        }
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

    fun adjustExerciseWeight(index: Int, delta: Float) {
        val state = _state.value as? WorkoutState.PlanPreview ?: return
        val p = planner ?: return
        val exercises = state.plan.exercises.toMutableList()
        val pe = exercises[index]
        val newWeight = WeightFormatter.round(
            (pe.sessionWeight + delta).coerceAtLeast(2.5f),
            weightUnit,
        )
        if (newWeight == pe.sessionWeight) return
        val newBaseline = p.deriveBaselineFromSessionWeight(newWeight, pe)
        if (newBaseline <= 0f) return
        exercises[index] = pe.copy(
            sessionWeight = newWeight,
            warmupSets = if (pe.exercise.isTimed) emptyList() else p.computeWarmupSets(newWeight),
        )
        val muscle = pe.exercise.primaryMuscle
        for (i in exercises.indices) {
            if (i == index) continue
            if (exercises[i].exercise.primaryMuscle == muscle) {
                exercises[i] = p.recomputeExercise(exercises[i], newBaseline)
            }
        }
        val updatedOverrides = state.plan.strengthOverrides + (muscle to newBaseline)
        setState(state.copy(plan = state.plan.copy(exercises = exercises, strengthOverrides = updatedOverrides)))
        weightAdjustJob?.cancel()
        weightAdjustJob = scope.launch {
            planner = repository.buildPlanner(sessionLocationId, weightUnit, updatedOverrides)
        }
    }

    fun completeWarmupSet() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        val warmupIdx = current.warmupSetIndex ?: return
        val nextIdx = warmupIdx + 1
        setState(current.copy(
            warmupSetIndex = if (nextIdx < current.plannedExercise.warmupSets.size) nextIdx else null,
        ))
    }

    fun startTimedSet() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        if (current.timerSecondsRemaining != null) return
        setState(current.copy(timerSecondsRemaining = TIMED_SET_SECONDS))
        timedSetTimerJob?.cancel()
        timedSetTimerJob = scope.launch {
            while (true) {
                delay(1000)
                val s = _state.value as? WorkoutState.ActiveSet ?: return@launch
                val remaining = s.timerSecondsRemaining ?: return@launch
                if (remaining <= 1) {
                    onVibrate()
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
        scope.launch {
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
                    actualReps = initialActualReps,
                    feedback = feedback,
                    completedAt = System.currentTimeMillis(),
                    durationSeconds = if (planned.exercise.isTimed) TIMED_SET_SECONDS else null,
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
            val completedSetIndex = if (feedback == SetFeedback.HURT) current.totalSets - 1 else current.setIndex
            setState(WorkoutState.Resting(
                plan = current.plan,
                exerciseIndex = current.exerciseIndex,
                completedSetIndex = completedSetIndex,
                sessionId = current.sessionId,
                secondsRemaining = REST_SECONDS,
                lastFeedback = feedback,
                weightAtSetStart = current.plannedExercise.sessionWeight,
                currentSetRowId = rowId,
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
            val setIndex = row?.let { it.setNumber - 1 }
                ?: resting.completedSetIndex.coerceAtMost(PlannedExercise.DEFAULT_SETS - 1)
            database.workoutSetDao().deleteById(resting.currentSetRowId)
            setState(WorkoutState.ActiveSet(
                plan = restoredPlan,
                exerciseIndex = resting.exerciseIndex,
                setIndex = setIndex,
                sessionId = resting.sessionId,
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
        val moreSetsForThisExercise =
            resting.completedSetIndex < PlannedExercise.DEFAULT_SETS - 1
        val exercise = resting.plan.exercises[resting.exerciseIndex]
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

    fun completeWorkout() {
        val done = _state.value as? WorkoutState.Done ?: return
        scope.launch {
            val reductions = done.plan.exercises
                .filter { it.originalSessionWeight > 0f && it.sessionWeight < it.originalSessionWeight }
                .associate { it.exercise.id to (it.originalSessionWeight - it.sessionWeight) / it.originalSessionWeight }
            repository.finishSession(done.sessionId, reductions)
            _navigationEvent.send(NavigationEvent.WorkoutCompleted)
        }
    }

    fun onLocationRefreshed() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val locationId = sessionLocationId ?: return
        scope.launch {
            val locationName = database.knownLocationDao().getById(locationId)?.name
            val freshPlanner = repository.buildPlanner(locationId, weightUnit, preview.plan.strengthOverrides)
            planner = freshPlanner
            val availableIds = freshPlanner.availableExercises.map { it.id }.toSet()
            var plan = preview.plan
            var i = 0
            while (i < plan.exercises.size) {
                if (plan.exercises[i].exercise.id !in availableIds) {
                    val replacement = freshPlanner.pickReplacement(plan, i)
                    val updated = plan.exercises.toMutableList()
                    if (replacement != null) {
                        updated[i] = replacement
                    } else {
                        updated.removeAt(i)
                        i--
                    }
                    plan = plan.copy(exercises = updated)
                }
                i++
            }
            if (plan != preview.plan || locationName != preview.locationName) {
                setState(WorkoutState.PlanPreview(plan = plan, locationName = locationName))
            }
        }
    }

    fun setActiveSetWeight(newWeight: Float) {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        val i = current.exerciseIndex
        val pe = current.plannedExercise
        val w = WeightFormatter.round(newWeight, weightUnit).coerceAtLeast(WeightFormatter.minIncrement(weightUnit))
        if (w == pe.sessionWeight) return
        val exercises = current.plan.exercises.toMutableList()
        exercises[i] = pe.copy(
            sessionWeight = w,
            warmupSets = when {
                pe.exercise.isTimed -> emptyList()
                current.warmupSetIndex != null -> planner?.computeWarmupSets(w) ?: pe.warmupSets
                else -> pe.warmupSets
            },
        )
        val newPlan = current.plan.copy(exercises = exercises)
        val commitTarget = WorkoutState.ActiveSet(
            plan = newPlan,
            exerciseIndex = i,
            setIndex = current.setIndex,
            sessionId = current.sessionId,
            warmupSetIndex = current.warmupSetIndex,
        )
        stageRest(current, StagedAction(
            kind = StagedKind.ADJUST_WEIGHT,
            undoTarget = current,
            commitTarget = commitTarget,
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
        val commitTarget = if (hasLogged) {
            nextExerciseActiveSet(current.plan, i + 1, current.sessionId)
        } else {
            val trimmed = current.plan.exercises.toMutableList().also { it.removeAt(i) }
            nextExerciseActiveSet(current.plan.copy(exercises = trimmed), i, current.sessionId)
        }
        stageRest(current, StagedAction(
            kind = StagedKind.END_EXERCISE,
            undoTarget = current,
            commitTarget = commitTarget,
        ))
    }

    private fun stageRest(current: WorkoutState.ActiveSet, staged: StagedAction) {
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
        ))
        startRestTimer()
    }

    private fun nextExerciseActiveSet(
        plan: WorkoutPlan,
        index: Int,
        sessionId: Long,
    ): WorkoutState.ActiveSet? {
        if (index !in plan.exercises.indices) return null
        val ex = plan.exercises[index]
        return WorkoutState.ActiveSet(
            plan = plan,
            exerciseIndex = index,
            setIndex = 0,
            sessionId = sessionId,
            warmupSetIndex = if (ex.warmupSets.isNotEmpty()) 0 else null,
        )
    }

    private suspend fun persistSwap(swap: PendingSwap, overrides: Map<MuscleGroup, Float>) {
        when (swap.reason) {
            ExerciseRemovalReason.DISLIKE -> {
                val ex = database.exerciseDao().getById(swap.exerciseId) ?: return
                database.exerciseDao().update(ex.copy(isDisliked = true))
            }
            ExerciseRemovalReason.NO_EQUIPMENT -> {
                val locationId = swap.locationId ?: return
                repository.excludeExercise(locationId, swap.exerciseId)
            }
            ExerciseRemovalReason.SKIP_TODAY -> Unit
        }
        planner = repository.buildPlanner(sessionLocationId, weightUnit, overrides)
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

    private fun advanceAfterRest() {
        val current = _state.value as? WorkoutState.Resting ?: return
        val staged = current.staged
        if (staged != null) {
            scope.launch {
                staged.pendingSwap?.let { persistSwap(it, current.plan.strengthOverrides) }
                val target = staged.commitTarget
                if (target != null) setState(target) else finishWorkout(current.plan, current.sessionId)
            }
            return
        }
        val plan = current.plan
        val nextSet = current.completedSetIndex + 1
        when {
            nextSet < PlannedExercise.DEFAULT_SETS -> setState(WorkoutState.ActiveSet(
                plan = plan,
                exerciseIndex = current.exerciseIndex,
                setIndex = nextSet,
                sessionId = current.sessionId,
            ))
            current.exerciseIndex + 1 < plan.exercises.size -> {
                val nextExercise = plan.exercises[current.exerciseIndex + 1]
                setState(WorkoutState.ActiveSet(
                    plan = plan,
                    exerciseIndex = current.exerciseIndex + 1,
                    setIndex = 0,
                    sessionId = current.sessionId,
                    warmupSetIndex = if (nextExercise.warmupSets.isNotEmpty()) 0 else null,
                ))
            }
            else -> finishWorkout(plan, current.sessionId)
        }
    }

    private fun finishWorkout(
        plan: WorkoutPlan,
        sessionId: Long,
    ) {
        val endTime = System.currentTimeMillis()
        scope.launch {
            database.workoutSessionDao().updateEndTime(sessionId, endTime)
            setState(WorkoutState.Done(
                sessionId = sessionId,
                plan = plan,
                startTime = sessionStartTime,
                endTime = endTime,
            ))
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
                    setLabel = "Set ${state.setIndex + 1} of ${state.totalSets}",
                    secondsRemaining = state.timerSecondsRemaining,
                    progressMax = TIMED_SET_SECONDS,
                )
            } else {
                WorkoutNotificationState.ActiveSet(
                    exerciseName = planned.exercise.name,
                    weightLabel = if (planned.exercise.equipment == Equipment.BODYWEIGHT)
                        "Bodyweight"
                    else
                        WeightFormatter.format(planned.sessionWeight, weightUnit),
                    repsLabel = formatQuantity(planned.sessionReps, planned.exercise.isTimed),
                    setLabel = "Set ${state.setIndex + 1} of ${state.totalSets}",
                )
            }
        }
        is WorkoutState.Resting -> {
            val plan = state.plan
            val nextSet = state.completedSetIndex + 1
            val upNextLabel = when {
                nextSet < PlannedExercise.DEFAULT_SETS ->
                    "Next: Set ${nextSet + 1} · ${plan.exercises[state.exerciseIndex].exercise.name}"
                state.exerciseIndex + 1 < plan.exercises.size ->
                    "Next: ${plan.exercises[state.exerciseIndex + 1].exercise.name}"
                else -> "Last set — almost done!"
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
        const val REST_SECONDS = 90
        const val TIMED_SET_SECONDS = 60
        const val NO_ROW = -1L
    }
}
