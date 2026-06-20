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
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
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
    val events: List<CoefficientEvent> = emptyList(),
    val chartPoints: List<DebugChartPoint> = emptyList(),
    val coefficientDeviations: List<CoefficientDeviationRow> = emptyList(),
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

            val chartPoints: List<DebugChartPoint> = if (logs.isEmpty()) emptyList() else buildList {
                val first = logs.first()
                if (first.previousCoefficient != null) {
                    add(DebugChartPoint(first.computedAt - 86_400_000L, first.previousCoefficient))
                }
                logs.forEach { add(DebugChartPoint(it.computedAt, it.coefficient)) }
            }

            val muscleExercises = app.database.exerciseDao().getAll()
                .filter { it.primaryMuscle == exercise.primaryMuscle }
            val latestUserCoefficients = app.database.coefficientHistoryDao()
                .getLatestPerExercise()
                .associate { it.exerciseId to it.coefficient }
            val deviations = computeCoefficientDeviations(
                exercises = muscleExercises.map { it.id to it.name },
                seedByName = ExerciseCoefficients.byName,
                currentByExerciseId = latestUserCoefficients,
            )
            val currentRow = deviations.firstOrNull { it.name == exercise.name }
            val coefficientDeviations = if (currentRow == null) {
                deviations
            } else {
                listOf(currentRow) + deviations.filter { it.name != exercise.name }
            }

            _state.value = ExerciseCoefficientDetailState(
                loading = false,
                exercise = exercise,
                events = events,
                chartPoints = chartPoints,
                coefficientDeviations = coefficientDeviations,
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
