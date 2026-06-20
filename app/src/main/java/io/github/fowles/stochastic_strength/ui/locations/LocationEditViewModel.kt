package io.github.fowles.stochastic_strength.ui.locations

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristic
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ExerciseAvailability(val exercise: Exercise, val available: Boolean)

data class LocationEditState(
    val location: KnownLocation? = null,
    val exercisesByEquipment: Map<Equipment, List<ExerciseAvailability>> = emptyMap(),
    val showDeleteConfirm: Boolean = false,
    val navigateBack: Boolean = false,
)

class LocationEditViewModel(
    application: Application,
    private val locationId: Long,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = WorkoutRepository(app.database, heuristics = listOf(EstCoefConsensusHeuristic()))

    private val _state = MutableStateFlow(LocationEditState())
    val state: StateFlow<LocationEditState> = _state.asStateFlow()

    private var saveJob: Job? = null

    private suspend fun performSave() {
        val s = _state.value
        val location = s.location ?: return
        repository.updateLocation(location)
        val excluded = s.exercisesByEquipment.values.flatten()
            .filterNot { it.available }
            .map { it.exercise.id }
            .toSet()
        repository.setExcludedExercises(locationId, excluded)
    }

    private fun autoSave(delayMs: Long = 0) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            performSave()
        }
    }

    override fun onCleared() {
        val hasPendingSave = saveJob?.isActive == true
        super.onCleared() // cancels viewModelScope
        if (hasPendingSave) {
            CoroutineScope(Dispatchers.IO).launch { performSave() }
        }
    }

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val location = repository.getLocations().firstOrNull { it.id == locationId } ?: return
        val excluded = repository.getExcludedExerciseIds(locationId)
        val allExercises = repository.observeAllExercises().first()
        val byEquipment = allExercises
            .groupBy { it.equipment }
            .mapValues { (_, exercises) ->
                exercises
                    .sortedBy { it.name }
                    .map { ExerciseAvailability(it, available = it.id !in excluded) }
            }
        _state.value = LocationEditState(location = location, exercisesByEquipment = byEquipment)
    }

    fun updateName(name: String) {
        val s = _state.value
        val location = s.location ?: return
        _state.value = s.copy(location = location.copy(name = name))
        autoSave(delayMs = 500)
    }

    fun toggleExercise(exerciseId: Long) {
        val s = _state.value
        _state.value = s.copy(
            exercisesByEquipment = s.exercisesByEquipment.mapValues { (_, entries) ->
                entries.map { entry ->
                    if (entry.exercise.id == exerciseId) entry.copy(available = !entry.available) else entry
                }
            }
        )
        autoSave()
    }

    fun toggleEquipmentType(equipment: Equipment) {
        val s = _state.value
        val entries = s.exercisesByEquipment[equipment] ?: return
        val enableAll = entries.none { it.available }
        _state.value = s.copy(
            exercisesByEquipment = s.exercisesByEquipment.mapValues { (eq, list) ->
                if (eq == equipment) list.map { it.copy(available = enableAll) } else list
            }
        )
        autoSave()
    }

    fun requestDelete() {
        _state.value = _state.value.copy(showDeleteConfirm = true)
    }

    fun cancelDelete() {
        _state.value = _state.value.copy(showDeleteConfirm = false)
    }

    fun confirmDelete() {
        viewModelScope.launch {
            repository.deleteLocation(locationId)
            _state.value = _state.value.copy(navigateBack = true)
        }
    }

    companion object {
        fun factory(locationId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[APPLICATION_KEY] ?: error("No application")
                    return LocationEditViewModel(app, locationId) as T
                }
            }
    }
}
