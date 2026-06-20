package io.github.fowles.stochastic_strength.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.CoefficientRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DebugStatsState(
    val loading: Boolean = true,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val muscleStrengths: List<MuscleGroupStrength> = emptyList(),
    val recentCoefficientChanges: List<CoefficientRow> = emptyList(),
    val allCoefficients: List<CoefficientRow> = emptyList(),
)

class DebugStatsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(DebugStatsState())
    val state: StateFlow<DebugStatsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            val weightUnit = profile?.weightUnit ?: WeightUnit.KG
            val muscleStrengths = repository.getMuscleGroupStrengths()
                .sortedBy { it.muscleGroup.ordinal }
            val recent = repository.getRecentCoefficientChanges(limit = 2)
            val all = repository.getAllCoefficientRows().filter { it.currentCoefficient > 0f }
            _state.value = DebugStatsState(
                loading = false,
                weightUnit = weightUnit,
                muscleStrengths = muscleStrengths,
                recentCoefficientChanges = recent,
                allCoefficients = all,
            )
        }
    }
}
