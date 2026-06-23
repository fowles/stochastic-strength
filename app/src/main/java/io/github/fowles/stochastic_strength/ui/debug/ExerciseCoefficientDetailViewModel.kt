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
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.progression.CrossTuningRow
import io.github.fowles.stochastic_strength.ui.debug.components.DebugChartPoint
import io.github.fowles.stochastic_strength.ui.debug.components.ProgressionChartSeries
import io.github.fowles.stochastic_strength.ui.debug.components.ProgressionColorRole
import io.github.fowles.stochastic_strength.ui.debug.components.ProgressionSeriesStyle
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
    val events: List<CoefficientEvent> = emptyList(),
    val progressionSeries: List<ProgressionChartSeries> = emptyList(),
    val crossTuning: List<CrossTuningRow> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
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
            val profile = app.database.userProfileDao().getProfile()
            val weightUnit = profile?.weightUnit ?: WeightUnit.KG
            val logs = repository.getCoefficientEvents(exerciseId)

            val events = logs.asReversed().map { log ->
                CoefficientEvent(
                    computedAt = log.computedAt,
                    previousCoefficient = log.previousCoefficient,
                    coefficient = log.coefficient,
                    heuristicName = log.heuristicName,
                    heuristicMetadata = log.heuristicMetadata,
                )
            }

            val series = repository.getExerciseProgressionSeries(exerciseId)
            fun pts(list: List<io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint>) =
                list.map { DebugChartPoint(it.timestampMs, it.value) }
            val progressionSeries = listOf(
                ProgressionChartSeries("Own estimate", pts(series.ownEstimate), ProgressionSeriesStyle.LINE, ProgressionColorRole.OWN),
                ProgressionChartSeries("Siblings", pts(series.siblingsEstimate), ProgressionSeriesStyle.LINE, ProgressionColorRole.SIBLINGS),
                ProgressionChartSeries("Merged", pts(series.merged), ProgressionSeriesStyle.LINE, ProgressionColorRole.MERGED),
                ProgressionChartSeries("Sessions", pts(series.ownObservations), ProgressionSeriesStyle.FILLED_DOTS, ProgressionColorRole.OWN_OBS),
                ProgressionChartSeries("Siblings (scaled)", pts(series.siblingObservations), ProgressionSeriesStyle.HOLLOW_DOTS, ProgressionColorRole.SIBLING_OBS),
            )
            val crossTuning = repository.getCrossTuning(exercise.primaryMuscle)

            _state.value = ExerciseCoefficientDetailState(
                loading = false,
                exercise = exercise,
                events = events,
                progressionSeries = progressionSeries,
                crossTuning = crossTuning,
                weightUnit = weightUnit,
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
