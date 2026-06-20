package io.github.fowles.stochastic_strength.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.ui.debug.components.DebugChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CoefficientEvent(
    val computedAt: Long,
    val previousCoefficient: Float?,
    val coefficient: Float,
    val heuristicName: String,
    val heuristicMetadata: String?,
)

data class ExerciseCoefficientDetailState(
    val loading: Boolean = true,
    val exercise: Exercise? = null,
    val currentCoefficient: Float = 0f,
    val seedCoefficient: Float? = null,
    val events: List<CoefficientEvent> = emptyList(),
    val chartPoints: List<DebugChartPoint> = emptyList(),
)

class ExerciseCoefficientDetailViewModel(
    application: Application,
    private val exerciseId: Long,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(ExerciseCoefficientDetailState())
    val state: StateFlow<ExerciseCoefficientDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val exercise = repository.getExerciseById(exerciseId) ?: run {
                _state.value = ExerciseCoefficientDetailState(loading = false)
                return@launch
            }
            val seed = repository.getSeedCoefficient(exercise)
            val logs = repository.getCoefficientEvents(exerciseId)
            val currentCoefficient = logs.lastOrNull()?.coefficient ?: seed ?: 0f

            val events = logs.asReversed().map { log ->
                CoefficientEvent(
                    computedAt = log.computedAt,
                    previousCoefficient = log.previousCoefficient,
                    coefficient = log.coefficient,
                    heuristicName = log.heuristicName,
                    heuristicMetadata = log.heuristicMetadata,
                )
            }

            val chartPoints: List<DebugChartPoint> = if (logs.isEmpty()) emptyList() else buildList {
                val first = logs.first()
                if (first.previousCoefficient != null) {
                    add(DebugChartPoint(first.computedAt - 86_400_000L, first.previousCoefficient))
                }
                logs.forEach { add(DebugChartPoint(it.computedAt, it.coefficient)) }
            }

            _state.value = ExerciseCoefficientDetailState(
                loading = false,
                exercise = exercise,
                currentCoefficient = currentCoefficient,
                seedCoefficient = seed,
                events = events,
                chartPoints = chartPoints,
            )
        }
    }

    companion object {
        fun factory(exerciseId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[APPLICATION_KEY] ?: error("No application")
                    return ExerciseCoefficientDetailViewModel(app, exerciseId) as T
                }
            }
    }
}
