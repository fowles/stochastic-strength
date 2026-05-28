package io.github.fowles.stochastic_strength.ui.workout

import android.app.Application
import android.content.Intent
import android.location.Geocoder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WorkoutGenerator
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import io.github.fowles.stochastic_strength.location.LocationResult
import io.github.fowles.stochastic_strength.location.LocationService
import io.github.fowles.stochastic_strength.notification.WorkoutNotificationService
import io.github.fowles.stochastic_strength.ui.SummaryExercise
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class ExerciseRemovalReason { NO_EQUIPMENT, DISLIKE, SKIP_TODAY }

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = WorkoutRepository(app.database)
    private val locationService = LocationService(app)

    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Loading)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private val _weightUnit = MutableStateFlow(WeightUnit.KG)
    val weightUnit: StateFlow<WeightUnit> = _weightUnit.asStateFlow()

    private val _workoutCompleted = MutableStateFlow(false)
    val workoutCompleted: StateFlow<Boolean> = _workoutCompleted.asStateFlow()

    private val _doneSummary = MutableStateFlow<WorkoutSummaryData?>(null)
    val doneSummary: StateFlow<WorkoutSummaryData?> = _doneSummary.asStateFlow()

    private var restTimerJob: Job? = null
    private var addExerciseJob: Job? = null
    private var sessionStartTime = 0L
    private var preferredExerciseCount: Int? = null

    private var sessionLocationId: Long? = null

    init {
        startWorkout()
        viewModelScope.launch {
            app.workoutCommandFlow.collect { command ->
                when (command) {
                    is WorkoutCommand.RecordFeedback -> recordFeedback(command.feedback)
                    WorkoutCommand.SkipRest -> skipRest()
                    WorkoutCommand.CompleteWarmupSet -> completeWarmupSet()
                }
            }
        }
    }

    private fun startWorkout() {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            _weightUnit.value = profile?.weightUnit ?: WeightUnit.KG
            preferredExerciseCount = profile?.preferredExerciseCount ?: WorkoutGenerator.DEFAULT_EXERCISE_COUNT
            val locationId = when (val loc = locationService.resolveLocation(app.database)) {
                is LocationResult.Known -> loc.locationId
                is LocationResult.Unknown -> createLocation(loc.latitude to loc.longitude)
                LocationResult.Unavailable -> null
            }
            sessionLocationId = locationId
            continueWorkoutGeneration(locationId)
        }
    }

    private suspend fun continueWorkoutGeneration(locationId: Long?) {
        val plan = repository.generateWorkoutForLocation(locationId, _weightUnit.value)
        sessionStartTime = System.currentTimeMillis()
        val sessionId = app.database.workoutSessionDao().insert(
            WorkoutSession(startTime = sessionStartTime, locationId = locationId)
        )
        setState(WorkoutState.PlanPreview(plan = plan, sessionId = sessionId))
        setExerciseCount(preferredExerciseCount ?: WorkoutGenerator.DEFAULT_EXERCISE_COUNT)
    }

    fun replaceExercise(index: Int, reason: ExerciseRemovalReason) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val planned = preview.plan.exercises[index]
        viewModelScope.launch {
            when (reason) {
                ExerciseRemovalReason.DISLIKE ->
                    app.database.exerciseDao().update(planned.exercise.copy(isDisliked = true))
                ExerciseRemovalReason.NO_EQUIPMENT -> {
                    val locationId = sessionLocationId ?: return@launch
                    repository.excludeExercise(locationId, planned.exercise.id)
                }
                ExerciseRemovalReason.SKIP_TODAY -> Unit
            }
            val replacement = repository.pickReplacement(preview.plan, index, _weightUnit.value)
            val newExercises = preview.plan.exercises.toMutableList()
            if (replacement != null) newExercises[index] = replacement else newExercises.removeAt(index)
            setState(WorkoutState.PlanPreview(
                plan = preview.plan.copy(exercises = newExercises),
                sessionId = preview.sessionId,
            ))
        }
    }

    fun setExerciseCount(targetCount: Int) {
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
                addExerciseJob = viewModelScope.launch {
                    repeat(needed) {
                        val p = _state.value as? WorkoutState.PlanPreview ?: return@launch
                        val extra = repository.pickAdditional(p.plan, _weightUnit.value) ?: return@launch
                        setState(p.copy(plan = p.plan.copy(exercises = p.plan.exercises + extra)))
                    }
                }
            }
        }
        if (targetCount != preferredExerciseCount) {
            preferredExerciseCount = targetCount
            viewModelScope.launch {
                val profile = app.database.userProfileDao().getProfile() ?: return@launch
                app.database.userProfileDao().insert(profile.copy(preferredExerciseCount = targetCount))
            }
        }
    }

    fun startFirstExercise() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val firstExercise = preview.plan.exercises[0]
        setState(WorkoutState.ActiveSet(
            plan = preview.plan,
            exerciseIndex = 0,
            setIndex = 0,
            sessionId = preview.sessionId,
            warmupSetIndex = if (firstExercise.warmupSets.isNotEmpty()) 0 else null,
        ))
    }

    fun completeWarmupSet() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        val warmupIdx = current.warmupSetIndex ?: return
        val nextIdx = warmupIdx + 1
        setState(current.copy(
            warmupSetIndex = if (nextIdx < current.plannedExercise.warmupSets.size) nextIdx else null,
        ))
    }

    fun recordFeedback(feedback: SetFeedback) {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        viewModelScope.launch {
            val planned = current.plannedExercise
            app.database.workoutSetDao().insert(
                WorkoutSet(
                    sessionId = current.sessionId,
                    exerciseId = planned.exercise.id,
                    setNumber = current.setIndex + 1,
                    targetWeight = planned.sessionWeight,
                    targetReps = planned.sessionReps,
                    feedback = feedback,
                    completedAt = System.currentTimeMillis(),
                )
            )
        }
        val completedSetIndex = if (feedback == SetFeedback.HURT) current.totalSets - 1 else current.setIndex
        val isLastSet = completedSetIndex + 1 >= current.totalSets &&
            current.exerciseIndex + 1 >= current.plan.exercises.size
        if (isLastSet) {
            finishWorkout(current.plan, current.exerciseIndex, current.setIndex, current.sessionId)
        } else {
            setState(WorkoutState.Resting(
                plan = current.plan,
                exerciseIndex = current.exerciseIndex,
                completedSetIndex = completedSetIndex,
                recordedSetIndex = current.setIndex,
                sessionId = current.sessionId,
                secondsRemaining = REST_SECONDS,
                lastFeedback = feedback,
            ))
            startRestTimer()
        }
    }

    fun undoLastSet() {
        restTimerJob?.cancel()
        val resting = _state.value as? WorkoutState.Resting ?: return
        val exerciseId = resting.plan.exercises[resting.exerciseIndex].exercise.id
        viewModelScope.launch {
            app.database.workoutSetDao().deleteSet(
                sessionId = resting.sessionId,
                exerciseId = exerciseId,
                setNumber = resting.recordedSetIndex + 1,
            )
        }
        setState(WorkoutState.ActiveSet(
            plan = resting.plan,
            exerciseIndex = resting.exerciseIndex,
            setIndex = resting.recordedSetIndex,
            sessionId = resting.sessionId,
        ))
    }

    fun skipRest() {
        restTimerJob?.cancel()
        advanceAfterRest()
    }

    private suspend fun createLocation(coords: Pair<Double, Double>): Long =
        app.database.knownLocationDao().insert(
            KnownLocation(
                name = reverseGeocode(coords.first, coords.second),
                latitude = coords.first,
                longitude = coords.second,
            )
        )

    private suspend fun reverseGeocode(lat: Double, lng: Double): String {
        val fallback = "%.4f, %.4f".format(lat, lng)
        if (!Geocoder.isPresent()) return fallback
        return suspendCancellableCoroutine { cont ->
            try {
                Geocoder(app).getFromLocation(lat, lng, 1) { addresses ->
                    val addr = addresses.firstOrNull()
                    val name = when {
                        addr?.thoroughfare != null ->
                            listOfNotNull(addr.subThoroughfare, addr.thoroughfare, addr.locality)
                                .joinToString(" ")
                        addr?.locality != null ->
                            listOfNotNull(addr.locality, addr.adminArea).joinToString(", ")
                        else -> fallback
                    }
                    cont.resume(name)
                }
            } catch (_: Exception) {
                cont.resume(fallback)
            }
        }
    }

    private fun startRestTimer() {
        restTimerJob?.cancel()
        restTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = _state.value as? WorkoutState.Resting ?: return@launch
                if (current.secondsRemaining <= 1) {
                    advanceAfterRest()
                    return@launch
                }
                setState(current.copy(secondsRemaining = current.secondsRemaining - 1))
            }
        }
    }

    private fun advanceAfterRest() {
        val current = _state.value as? WorkoutState.Resting ?: return
        val plan = current.plan
        val totalSets = PlannedExercise.DEFAULT_SETS
        val nextSet = current.completedSetIndex + 1

        when {
            nextSet < totalSets -> setState(WorkoutState.ActiveSet(
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
            else -> finishWorkout(plan, current.exerciseIndex, current.recordedSetIndex, current.sessionId)
        }
    }

    private fun finishWorkout(
        plan: WorkoutPlan,
        lastExerciseIndex: Int,
        lastRecordedSetIndex: Int,
        sessionId: Long,
    ) {
        val endTime = System.currentTimeMillis()
        viewModelScope.launch {
            app.database.workoutSessionDao().updateEndTime(sessionId, endTime)
            setState(WorkoutState.Done(
                sessionId = sessionId,
                plan = plan,
                startTime = sessionStartTime,
                endTime = endTime,
                lastExerciseIndex = lastExerciseIndex,
                lastRecordedSetIndex = lastRecordedSetIndex,
            ))
            _doneSummary.value = loadDoneSummary(sessionId, endTime)
        }
    }

    private suspend fun loadDoneSummary(sessionId: Long, endTime: Long): WorkoutSummaryData {
        val weightUnit = app.database.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
        val sets = app.database.workoutSetDao().getSetsForSession(sessionId)
        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val exercises = exerciseIds.map { id ->
            val exerciseSets = sets.filter { it.exerciseId == id }
            SummaryExercise(
                name = app.database.exerciseDao().getById(id)?.name ?: "Unknown",
                exerciseId = id,
                weight = exerciseSets.firstOrNull()?.targetWeight ?: 0f,
                feedback = exerciseSets.map { it.feedback },
            )
        }
        return WorkoutSummaryData(
            startTime = sessionStartTime,
            durationSeconds = (endTime - sessionStartTime) / 1000,
            exercises = exercises,
            weightUnit = weightUnit,
        )
    }

    fun undoLastSetFromDone() {
        val done = _state.value as? WorkoutState.Done ?: return
        val exerciseId = done.plan.exercises[done.lastExerciseIndex].exercise.id
        viewModelScope.launch {
            app.database.workoutSetDao().deleteSet(
                sessionId = done.sessionId,
                exerciseId = exerciseId,
                setNumber = done.lastRecordedSetIndex + 1,
            )
        }
        setState(WorkoutState.ActiveSet(
            plan = done.plan,
            exerciseIndex = done.lastExerciseIndex,
            setIndex = done.lastRecordedSetIndex,
            sessionId = done.sessionId,
        ))
    }

    fun completeWorkout() {
        val done = _state.value as? WorkoutState.Done ?: return
        viewModelScope.launch {
            repository.applySessionProgression(done.sessionId)
            _workoutCompleted.value = true
        }
    }

    private fun setState(newState: WorkoutState) {
        val newNotifState = deriveNotificationState(newState)
        val wasNull = app.workoutNotificationState.value == null
        _state.value = newState
        app.workoutNotificationState.value = newNotifState
        if (wasNull && newNotifState != null) {
            app.startForegroundService(Intent(app, WorkoutNotificationService::class.java))
        }
    }

    private fun deriveNotificationState(state: WorkoutState): WorkoutNotificationState? = when (state) {
        is WorkoutState.ActiveSet -> {
            val planned = state.plannedExercise
            if (state.warmupSetIndex != null) {
                val warmupIdx = state.warmupSetIndex + 1
                val totalWarmups = planned.warmupSets.size
                WorkoutNotificationState.WarmupSet(
                    exerciseName = planned.exercise.name,
                    warmupSetLabel = "Warm-up $warmupIdx of $totalWarmups",
                )
            } else {
                WorkoutNotificationState.ActiveSet(
                    exerciseName = planned.exercise.name,
                    weightLabel = if (planned.exercise.equipment == Equipment.BODYWEIGHT)
                        "Bodyweight"
                    else
                        WeightFormatter.format(planned.sessionWeight, _weightUnit.value),
                    reps = planned.sessionReps,
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

    override fun onCleared() {
        super.onCleared()
        restTimerJob?.cancel()
        addExerciseJob?.cancel()
        app.workoutNotificationState.value = null
    }

    companion object {
        const val REST_SECONDS = 90
    }
}
