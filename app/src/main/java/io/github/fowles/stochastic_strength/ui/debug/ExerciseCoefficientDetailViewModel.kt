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
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.progression.CrossTuningRow
import io.github.fowles.stochastic_strength.domain.progression.ObservedSet
import io.github.fowles.stochastic_strength.domain.progression.ProgressionFrame
import io.github.fowles.stochastic_strength.domain.progression.SessionExerciseObservation
import io.github.fowles.stochastic_strength.ui.debug.components.DebugChartPoint
import io.github.fowles.stochastic_strength.ui.debug.components.ProgressionChartSeries
import io.github.fowles.stochastic_strength.ui.debug.components.ProgressionColorRole
import io.github.fowles.stochastic_strength.ui.debug.components.ProgressionSeriesStyle
import io.github.fowles.stochastic_strength.ui.debug.components.timestampToLocalEpochDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

data class FrameView(
    val timestampMs: Long,
    val headerOwn: String,
    val headerSiblings: String,
    val headerMerged: String,
    val crossTuning: List<CrossTuningRow>,
    val tooltip: CharSequence,
)

internal fun formatObservedSet(s: ObservedSet, unit: WeightUnit): String {
    val prefix = if (s.isEstimate) "~" else ""
    return "$prefix${s.reps}@${WeightFormatter.format(s.weightKg, unit)}"
}

internal fun formatTooltip(observations: List<SessionExerciseObservation>, unit: WeightUnit): CharSequence =
    observations.joinToString("\n") { obs ->
        (listOf(obs.name) + obs.sets.map { formatObservedSet(it, unit) }).joinToString("\n")
    }

private fun headerValue(v: Float?, unit: WeightUnit): String =
    v?.let { WeightFormatter.format(it, unit) } ?: "—"

internal fun buildFrameViews(
    frames: List<ProgressionFrame>,
    unit: WeightUnit,
    zone: ZoneId,
): Pair<Map<Long, FrameView>, Long?> {
    if (frames.isEmpty()) return emptyMap<Long, FrameView>() to null
    val byEpochDay = LinkedHashMap<Long, FrameView>()
    for (f in frames) {
        val epochDay = timestampToLocalEpochDay(f.timestampMs, zone)
        byEpochDay[epochDay] = FrameView(   // later same-day frame overwrites; nearest/last wins
            timestampMs = f.timestampMs,
            headerOwn = headerValue(f.own, unit),
            headerSiblings = headerValue(f.siblings, unit),
            headerMerged = headerValue(f.merged, unit),
            crossTuning = f.crossTuning,
            tooltip = formatTooltip(f.observations, unit),
        )
    }
    val defaultEpochDay = timestampToLocalEpochDay(frames.maxBy { it.timestampMs }.timestampMs, zone)
    return byEpochDay to defaultEpochDay
}

data class ExerciseCoefficientDetailState(
    val loading: Boolean = true,
    val exercise: Exercise? = null,
    val progressionSeries: List<ProgressionChartSeries> = emptyList(),
    val framesByEpochDay: Map<Long, FrameView> = emptyMap(),
    val defaultEpochDay: Long? = null,
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

            val data = repository.getExerciseProgressionData(exerciseId)
            val series = data.series
            val (framesByEpochDay, defaultEpochDay) =
                buildFrameViews(data.frames, weightUnit, ZoneId.systemDefault())
            fun pts(list: List<io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint>) =
                list.map { DebugChartPoint(it.timestampMs, it.value) }
            val progressionSeries = listOf(
                ProgressionChartSeries("Own estimate", pts(series.ownEstimate), ProgressionSeriesStyle.LINE, ProgressionColorRole.OWN),
                ProgressionChartSeries("Siblings", pts(series.siblingsEstimate), ProgressionSeriesStyle.LINE, ProgressionColorRole.SIBLINGS),
                ProgressionChartSeries("Merged", pts(series.merged), ProgressionSeriesStyle.LINE, ProgressionColorRole.MERGED),
                ProgressionChartSeries("Sessions", pts(series.ownObservations), ProgressionSeriesStyle.FILLED_DOTS, ProgressionColorRole.OWN_OBS),
                ProgressionChartSeries("Siblings (scaled)", pts(series.siblingObservations), ProgressionSeriesStyle.HOLLOW_DOTS, ProgressionColorRole.SIBLING_OBS),
            )

            _state.value = ExerciseCoefficientDetailState(
                loading = false,
                exercise = exercise,
                progressionSeries = progressionSeries,
                framesByEpochDay = framesByEpochDay,
                defaultEpochDay = defaultEpochDay,
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
