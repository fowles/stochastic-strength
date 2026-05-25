package io.github.fowles.stochastic_strength.ui.workout

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationEquipment
import io.github.fowles.stochastic_strength.data.model.SetFeedback
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

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = WorkoutRepository(app.database)
    private val locationService = LocationService(app)

    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Loading)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private var restTimerJob: Job? = null
    private var sessionStartTime = 0L

    // Tracks the locationId for this session; may be set lazily on first equipment removal.
    private var sessionLocationId: Long? = null
    // GPS coords stored when we're at an unknown location, for lazy location creation.
    private var pendingLocationCoords: Pair<Double, Double>? = null

    init {
        startWorkout()
    }

    private fun startWorkout() {
        viewModelScope.launch {
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
        val plan = repository.generateWorkoutForLocation(locationId)
        sessionStartTime = System.currentTimeMillis()
        val sessionId = app.database.workoutSessionDao().insert(
            WorkoutSession(startTime = sessionStartTime, locationId = locationId)
        )
        _state.value = WorkoutState.ActiveSet(
            plan = plan,
            exerciseIndex = 0,
            setIndex = 0,
            sessionId = sessionId,
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
                    targetWeight = planned.state.currentWeight,
                    targetReps = planned.state.currentReps,
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
            sessionId = current.sessionId,
            secondsRemaining = REST_SECONDS,
        )
        startRestTimer()
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
            current.exerciseIndex + 1 < plan.exercises.size -> _state.value = WorkoutState.ActiveSet(
                plan = plan,
                exerciseIndex = current.exerciseIndex + 1,
                setIndex = 0,
                sessionId = current.sessionId,
            )
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
    }

    companion object {
        const val REST_SECONDS = 90
    }
}
