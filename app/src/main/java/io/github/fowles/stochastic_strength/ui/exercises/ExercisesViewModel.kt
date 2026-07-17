package io.github.fowles.stochastic_strength.ui.exercises

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    val hurtMap: StateFlow<Map<Long, Boolean>> = app.database.exerciseHurtStateDao()
        .observeAll()
        .map { rows -> rows.associate { it.exerciseId to it.isHurt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _sparklines = MutableStateFlow<Map<Long, List<Float>>>(emptyMap())
    val sparklines: StateFlow<Map<Long, List<Float>>> = _sparklines.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAllExercises().collect { exercises ->
                _state.value = _state.value.copy(exercises = exercises)
            }
        }
        // Computed once (beliefs only change after a workout finishes, and this screen is entered
        // fresh from home) — mirrors the History highlight's one-shot series build.
        viewModelScope.launch {
            _sparklines.value = repository.buildExerciseSparklines()
        }
    }

    fun setFilter(muscle: MuscleGroup?) {
        _state.value = _state.value.copy(selectedFilter = muscle)
    }

    fun setEquipmentFilter(equipment: Equipment?) {
        _state.value = _state.value.copy(selectedEquipmentFilter = equipment)
    }
}
