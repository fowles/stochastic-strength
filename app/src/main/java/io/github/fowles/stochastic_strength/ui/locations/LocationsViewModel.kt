package io.github.fowles.stochastic_strength.ui.locations

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LocationsState {
    data object Loading : LocationsState
    data class Loaded(val locations: List<KnownLocation>) : LocationsState
}

class LocationsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = WorkoutRepository(app.database)

    private val _state = MutableStateFlow<LocationsState>(LocationsState.Loading)
    val state: StateFlow<LocationsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeLocations().collect { locations ->
                _state.value = LocationsState.Loaded(locations)
            }
        }
    }
}
