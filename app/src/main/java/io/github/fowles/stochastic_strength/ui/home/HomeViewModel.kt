package io.github.fowles.stochastic_strength.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeState {
    data object Loading : HomeState
    data object ProfileSetup : HomeState
    data object Ready : HomeState
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = WorkoutRepository(app.database)

    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            _state.value = if (profile == null) HomeState.ProfileSetup else HomeState.Ready
        }
    }

    fun submitProfile(sex: Sex, strengthLevel: StrengthLevel) {
        viewModelScope.launch {
            repository.seedInitialWeights(sex, strengthLevel)
            _state.value = HomeState.Ready
        }
    }
}
