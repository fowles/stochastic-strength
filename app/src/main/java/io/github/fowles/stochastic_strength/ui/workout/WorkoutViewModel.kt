package io.github.fowles.stochastic_strength.ui.workout

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationEquipment
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.location.LocationResult
import io.github.fowles.stochastic_strength.location.LocationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ExerciseRemovalReason { NO_EQUIPMENT, DISLIKE, SKIP_TODAY }

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = WorkoutRepository(app.database)
    private val locationService = LocationService(app)

    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Loading)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private val _weightUnit = MutableStateFlow(WeightUnit.KG)
    val weightUnit: StateFlow<WeightUnit> = _weightUnit.asStateFlow()

    private var restTimerJob: Job? = null
    private var addExerciseJob: Job? = null
    private var sessionStartTime = 0L
    private var preferredExerciseCount: Int? = null

    private var sessionLocationId: Long? = null
    private var pendingLocationCoords: Pair<Double, Double>? = null

    init {
        startWorkout()
    }

    private fun startWorkout() {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            _weightUnit.value = profile?.weightUnit ?: WeightUnit.KG
            preferredExerciseCount = profile?.preferredExerciseCount
            when (val loc = locationService.resolveLocation(app.database)) {
                is LocationResult.Known -> {
                    sessionLocationId = loc.locationId
                    continueWorkoutGeneration(loc.locationId)
                }
                is LocationResult.Unknown -> {
                    pendingLocationCoords = loc.latitude to loc.longitude
                    continueWorkoutGeneration(null)
                }
                LocationResult.Unavailable -> continueWorkoutGeneration(null)
            }
        }
    }

    private suspend fun continueWorkoutGeneration(locationId: Long?) {
        val plan = repository.generateWorkoutForLocation(locationId, _weightUnit.value)
        sessionStartTime = System.currentTimeMillis()
        val sessionId = app.database.workoutSessionDao().insert(
            WorkoutSession(startTime = sessionStartTime, locationId = locationId)
        )
        _state.value = WorkoutState.PlanPreview(plan = plan, sessionId = sessionId)
        preferredExerciseCount?.let { setExerciseCount(it) }
    }

    fun replaceExercise(index: Int, reason: ExerciseRemovalReason) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val planned = preview.plan.exercises[index]
        viewModelScope.launch {
            when (reason) {
                ExerciseRemovalReason.DISLIKE ->
                    app.database.exerciseDao().update(planned.exercise.copy(isDisliked = true))
                ExerciseRemovalReason.NO_EQUIPMENT -> {
                    val locationId = sessionLocationId
                    if (locationId != null) {
                        app.database.locationEquipmentDao().deleteEquipment(locationId, planned.exercise.equipment)
                    } else {
                        val coords = pendingLocationCoords
                        if (coords != null) {
                            val newLocationId = createLocationWithAllEquipmentExcept(coords, planned.exercise.equipment)
                            sessionLocationId = newLocationId
                            app.database.workoutSessionDao().updateLocationId(preview.sessionId, newLocationId)
                        }
                    }
                }
                ExerciseRemovalReason.SKIP_TODAY -> Unit
            }
            val replacement = repository.pickReplacement(preview.plan, index, _weightUnit.value)
            val newExercises = preview.plan.exercises.toMutableList()
            if (replacement != null) newExercises[index] = replacement else newExercises.removeAt(index)
            _state.value = WorkoutState.PlanPreview(
                plan = preview.plan.copy(exercises = newExercises),
                sessionId = preview.sessionId,
            )
        }
    }

    fun setExerciseCount(targetCount: Int) {
        addExerciseJob?.cancel()
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val current = preview.plan.exercises
        when {
            targetCount < current.size -> {
                val trimmed = current.take(targetCount.coerceAtLeast(1))
                _state.value = preview.copy(plan = preview.plan.copy(exercises = trimmed))
            }
            targetCount > current.size -> {
                val needed = targetCount - current.size
                addExerciseJob = viewModelScope.launch {
                    repeat(needed) {
                        val p = _state.value as? WorkoutState.PlanPreview ?: return@launch
                        val extra = repository.pickAdditional(p.plan, _weightUnit.value) ?: return@launch
                        _state.value = p.copy(plan = p.plan.copy(exercises = p.plan.exercises + extra))
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
        _state.value = WorkoutState.ActiveSet(
            plan = preview.plan,
            exerciseIndex = 0,
            setIndex = 0,
            sessionId = preview.sessionId,
            warmupSetIndex = if (firstExercise.warmupSets.isNotEmpty()) 0 else null,
        )
    }

    fun completeWarmupSet() {
        val current = _state.value as? WorkoutState.ActiveSet ?: return
        val warmupIdx = current.warmupSetIndex ?: return
        val nextIdx = warmupIdx + 1
        _state.value = current.copy(
            warmupSetIndex = if (nextIdx < current.plannedExercise.warmupSets.size) nextIdx else null,
        )
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
        _state.value = WorkoutState.Resting(
            plan = current.plan,
            exerciseIndex = current.exerciseIndex,
            completedSetIndex = completedSetIndex,
            recordedSetIndex = current.setIndex,
            sessionId = current.sessionId,
            secondsRemaining = REST_SECONDS,
            lastFeedback = feedback,
        )
        startRestTimer()
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
        _state.value = WorkoutState.ActiveSet(
            plan = resting.plan,
            exerciseIndex = resting.exerciseIndex,
            setIndex = resting.recordedSetIndex,
            sessionId = resting.sessionId,
        )
    }

    fun skipRest() {
        restTimerJob?.cancel()
        advanceAfterRest()
    }

    fun dislikeCurrentExercise() {
        val (plan, idx) = currentPlanAndIndex() ?: return
        val exercise = plan.exercises[idx].exercise
        viewModelScope.launch {
            app.database.exerciseDao().update(exercise.copy(isDisliked = true))
        }
    }

    fun markNoEquipmentHere() {
        val (plan, idx) = currentPlanAndIndex() ?: return
        val equipment = plan.exercises[idx].exercise.equipment
        val sessionId = currentSessionId() ?: return
        val locationId = sessionLocationId

        if (locationId != null) {
            viewModelScope.launch {
                app.database.locationEquipmentDao().deleteEquipment(locationId, equipment)
            }
        } else {
            val coords = pendingLocationCoords ?: return
            viewModelScope.launch {
                val newLocationId = createLocationWithAllEquipmentExcept(coords, equipment)
                sessionLocationId = newLocationId
                app.database.workoutSessionDao().updateLocationId(sessionId, newLocationId)
            }
        }
    }

    private suspend fun createLocationWithAllEquipmentExcept(
        coords: Pair<Double, Double>,
        excluded: Equipment,
    ): Long {
        val locationId = app.database.knownLocationDao().insert(
            KnownLocation(
                name = "%.4f, %.4f".format(coords.first, coords.second),
                latitude = coords.first,
                longitude = coords.second,
            )
        )
        val allEquipment = Equipment.entries.filter { it != excluded }
        app.database.locationEquipmentDao().insertAll(
            allEquipment.map { LocationEquipment(locationId = locationId, equipment = it) }
        )
        return locationId
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
                _state.value = current.copy(secondsRemaining = current.secondsRemaining - 1)
            }
        }
    }

    private fun advanceAfterRest() {
        val current = _state.value as? WorkoutState.Resting ?: return
        val plan = current.plan
        val totalSets = plan.exercises[current.exerciseIndex].state.currentSets
        val nextSet = current.completedSetIndex + 1

        when {
            nextSet < totalSets -> _state.value = WorkoutState.ActiveSet(
                plan = plan,
                exerciseIndex = current.exerciseIndex,
                setIndex = nextSet,
                sessionId = current.sessionId,
            )
            current.exerciseIndex + 1 < plan.exercises.size -> {
                val nextExercise = plan.exercises[current.exerciseIndex + 1]
                _state.value = WorkoutState.ActiveSet(
                    plan = plan,
                    exerciseIndex = current.exerciseIndex + 1,
                    setIndex = 0,
                    sessionId = current.sessionId,
                    warmupSetIndex = if (nextExercise.warmupSets.isNotEmpty()) 0 else null,
                )
            }
            else -> finishWorkout(current)
        }
    }

    private fun finishWorkout(current: WorkoutState.Resting) {
        val endTime = System.currentTimeMillis()
        viewModelScope.launch {
            app.database.workoutSessionDao().updateEndTime(current.sessionId, endTime)
            repository.applySessionProgression(current.sessionId)
            _state.value = WorkoutState.Done(
                sessionId = current.sessionId,
                plan = current.plan,
                startTime = sessionStartTime,
                endTime = endTime,
            )
        }
    }

    private fun currentPlanAndIndex() = when (val s = _state.value) {
        is WorkoutState.ActiveSet -> s.plan to s.exerciseIndex
        is WorkoutState.Resting -> s.plan to s.exerciseIndex
        else -> null
    }

    private fun currentSessionId() = when (val s = _state.value) {
        is WorkoutState.ActiveSet -> s.sessionId
        is WorkoutState.Resting -> s.sessionId
        else -> null
    }

    override fun onCleared() {
        super.onCleared()
        restTimerJob?.cancel()
        addExerciseJob?.cancel()
    }

    companion object {
        const val REST_SECONDS = 90
    }
}
