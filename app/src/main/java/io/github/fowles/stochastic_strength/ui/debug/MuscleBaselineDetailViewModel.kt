package io.github.fowles.stochastic_strength.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.ui.debug.components.DebugChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BaselineEvent(
    val sessionId: Long,
    val timestamp: Long,
    val previousBaseline: Float,
    val newBaseline: Float,
    val reason: BaselineChangeReason,
    val feedbacks: List<SetFeedback>,
    val sessionReps: Int?,
    val minReductionFraction: Float?,
)

data class CoefficientDeviationRow(
    val name: String,
    val deviation: Float,
)

/**
 * Returns the per-exercise drift of `current` coefficient vs `seed`,
 * expressed as `current / seed - 1` and sorted descending. Exercises
 * whose seed is `0f` are omitted (bodyweight — ratio undefined).
 *
 * If an exercise has no entry in [currentByExerciseId] the current value
 * falls back to its seed, yielding a deviation of `0f`.
 */
internal fun computeCoefficientDeviations(
    exercises: List<Pair<Long, String>>,
    seedByName: Map<String, Float>,
    currentByExerciseId: Map<Long, Float>,
): List<CoefficientDeviationRow> {
    val rows = exercises.mapNotNull { (id, name) ->
        val seed = seedByName[name] ?: return@mapNotNull null
        if (seed == 0f) return@mapNotNull null
        val current = currentByExerciseId[id] ?: seed
        CoefficientDeviationRow(name = name, deviation = current / seed - 1f)
    }
    return rows.sortedByDescending { it.deviation }
}

data class MuscleBaselineDetailState(
    val loading: Boolean = true,
    val muscleGroup: MuscleGroup,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val events: List<BaselineEvent> = emptyList(),
    val chartPoints: List<DebugChartPoint> = emptyList(),
    val coefficientDeviations: List<CoefficientDeviationRow> = emptyList(),
)

class MuscleBaselineDetailViewModel(
    application: Application,
    private val muscleGroup: MuscleGroup,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(MuscleBaselineDetailState(muscleGroup = muscleGroup))
    val state: StateFlow<MuscleBaselineDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            val weightUnit = profile?.weightUnit ?: WeightUnit.KG
            val logs = repository.getBaselineEvents(muscleGroup)

            val allExercises = app.database.exerciseDao().getAll()
                .filter { it.primaryMuscle == muscleGroup }
            val latestUserCoefficients = app.database.coefficientChangeLogDao()
                .getLatestPerExercise()
                .associate { it.exerciseId to it.coefficient }
            val coefficientDeviations = computeCoefficientDeviations(
                exercises = allExercises.map { it.id to it.name },
                seedByName = ExerciseCoefficients.byName,
                currentByExerciseId = latestUserCoefficients,
            )

            val events = logs.asReversed().map { log ->
                BaselineEvent(
                    sessionId = log.sessionId,
                    timestamp = log.timestamp,
                    previousBaseline = log.previousBaseline,
                    newBaseline = log.newBaseline,
                    reason = log.changeReason,
                    feedbacks = parseFeedbacks(log.feedbacks),
                    sessionReps = log.sessionReps,
                    minReductionFraction = log.minReductionFraction,
                )
            }

            val chartPoints: List<DebugChartPoint> = if (logs.isEmpty()) emptyList() else buildList {
                val first = logs.first()
                add(DebugChartPoint(first.timestamp - 86_400_000L, first.previousBaseline))
                logs.forEach { add(DebugChartPoint(it.timestamp, it.newBaseline)) }
            }

            _state.value = MuscleBaselineDetailState(
                loading = false,
                muscleGroup = muscleGroup,
                weightUnit = weightUnit,
                events = events,
                chartPoints = chartPoints,
                coefficientDeviations = coefficientDeviations,
            )
        }
    }

    private fun parseFeedbacks(csv: String?): List<SetFeedback> =
        csv?.split(',')
            ?.mapNotNull { token -> runCatching { SetFeedback.valueOf(token.trim()) }.getOrNull() }
            ?: emptyList()

    companion object {
        fun factory(muscleGroup: MuscleGroup): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[APPLICATION_KEY] ?: error("No application")
                    return MuscleBaselineDetailViewModel(app, muscleGroup) as T
                }
            }
    }
}
