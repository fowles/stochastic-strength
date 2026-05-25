package io.github.fowles.stochastic_strength.ui.workout

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.model.WorkoutPlan
import io.github.fowles.stochastic_strength.location.LocationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = WorkoutRepository(app.database, LocationService(app))

    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Loading)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private var restTimerJob: Job? = null
    private var sessionStartTime = 0L

    init {
        startWorkout()
    }

    private fun startWorkout() {
        viewModelScope.launch {
            val plan = repository.generateWorkout()
            sessionStartTime = System.currentTimeMillis()
            val sessionId = app.database.workoutSessionDao().insert(
                WorkoutSession(startTime = sessionStartTime, locationId = plan.locationId)
            )
            _state.value = WorkoutState.ActiveSet(
                plan = plan,
                exerciseIndex = 0,
                setIndex = 0,
                sessionId = sessionId,
            )
        }
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
        _state.value = WorkoutState.Resting(
            plan = current.plan,
            exerciseIndex = current.exerciseIndex,
            completedSetIndex = current.setIndex,
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
        val locationId = plan.locationId ?: return
        val equipment = plan.exercises[idx].exercise.equipment
        viewModelScope.launch {
            app.database.locationEquipmentDao().deleteEquipment(locationId, equipment)
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
        }
        _state.value = WorkoutState.Done(
            sessionId = current.sessionId,
            plan = current.plan,
            startTime = sessionStartTime,
            endTime = endTime,
        )
    }

    private fun currentPlanAndIndex(): Pair<WorkoutPlan, Int>? = when (val s = _state.value) {
        is WorkoutState.ActiveSet -> s.plan to s.exerciseIndex
        is WorkoutState.Resting -> s.plan to s.exerciseIndex
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
