package io.github.fowles.stochastic_strength.ui.exercises

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExercisesState(
    val exercises: List<Exercise> = emptyList(),
    val selectedFilter: MuscleGroup? = null,
    val selectedEquipmentFilter: Equipment? = null,
)

class ExercisesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(ExercisesState())
    val state: StateFlow<ExercisesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAllExercises().collect { exercises ->
                _state.value = _state.value.copy(exercises = exercises)
            }
        }
    }

    fun setFilter(muscle: MuscleGroup?) {
        _state.value = _state.value.copy(selectedFilter = muscle)
    }

    fun setEquipmentFilter(equipment: Equipment?) {
        _state.value = _state.value.copy(selectedEquipmentFilter = equipment)
    }
}
