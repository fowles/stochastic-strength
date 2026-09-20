package io.github.fowles.stochastic_strength.ui.exercises

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.ui.components.sharedProgressionYRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId

data class ExerciseSetEntry(
    val exerciseName: String,
    val set: WorkoutSet,
    val isTimed: Boolean = false,
    val isBodyweight: Boolean = false,
)

data class ExerciseDetailState(
    val exercise: Exercise? = null,
    val isHurt: Boolean = false,
    val primaryPoints: List<ChartPoint> = emptyList(),
    val shadowPoints: List<ChartPoint> = emptyList(),
    val estimatePoints: List<ChartPoint> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    val primarySetsByDay: Map<Long, List<WorkoutSet>> = emptyMap(),
    val shadowSetsByDay: Map<Long, List<ExerciseSetEntry>> = emptyMap(),
    val selectedDay: Long? = null,
    val chartYRange: ClosedFloatingPointRange<Double>? = null,
)

class ExerciseDetailViewModel(
    application: Application,
    private val exerciseId: Long,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(ExerciseDetailState())
    val state: StateFlow<ExerciseDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            val weightUnit = profile?.weightUnit ?: WeightUnit.KG
            val exercise = repository.getExerciseById(exerciseId) ?: return@launch
            val isHurt = app.database.exerciseHurtStateDao().get(exerciseId)?.isHurt ?: false
            _state.value = ExerciseDetailState(exercise = exercise, isHurt = isHurt, weightUnit = weightUnit)
            loadChartData(exercise)
        }
    }

    private suspend fun loadChartData(exercise: Exercise) {
        val zone = ZoneId.systemDefault()
        // Bucket each session by its END time — the same `asOf` the belief replay stamps a session
        // with — so a tapped day lists exactly the sets whose dots sit on it.
        val sessionAnchorById = repository.getAllSessions().associate { it.id to (it.endTime ?: it.startTime) }
        val primarySetsByDay = repository.getAllSetsForExercise(exerciseId)
            .filter { it.completedAt != null }
            .groupBy { ExerciseChartGrouping.sessionDayKey(it, sessionAnchorById, zone) }
        val shadowSetsByDay = siblingSetsByDay(exercise, sessionAnchorById, zone)

        // The plotted series ARE the pipeline's — the pooled estimate line and the per-set
        // observations the fold consumed — so this chart and the debug progression chart (same
        // data, same shared Y range) agree point for point.
        val data = repository.getExerciseProgressionData(exerciseId)
        val series = exerciseChartSeries(data, zone)

        _state.value = _state.value.copy(
            primaryPoints = series.own,
            shadowPoints = series.siblings,
            estimatePoints = series.estimate,
            primarySetsByDay = primarySetsByDay,
            shadowSetsByDay = shadowSetsByDay,
            chartYRange = sharedProgressionYRange(data),
        )
    }

    /**
     * Every same-muscle sibling's completed sets, bucketed by chart day for the day-detail panel.
     * Lists a session's sets regardless of whether they yielded a signal; zero-coefficient siblings
     * are skipped, since the pipeline never scales them into this exercise's space either.
     */
    private suspend fun siblingSetsByDay(
        exercise: Exercise,
        sessionAnchorById: Map<Long, Long>,
        zone: ZoneId,
    ): Map<Long, List<ExerciseSetEntry>> {
        val related = repository.observeAllExercises().first().filter {
            it.primaryMuscle == exercise.primaryMuscle && it.id != exerciseId
        }
        val dayToEntries = mutableMapOf<Long, MutableList<ExerciseSetEntry>>()
        for (rel in related) {
            if ((ExerciseCoefficients.byName[rel.name] ?: 0f) <= 0f) continue
            val completed = repository.getAllSetsForExercise(rel.id).filter { it.completedAt != null }
            for (set in completed) {
                val dayKey = ExerciseChartGrouping.sessionDayKey(set, sessionAnchorById, zone)
                dayToEntries.getOrPut(dayKey) { mutableListOf() }
                    .add(ExerciseSetEntry(rel.name, set, rel.isTimed, rel.equipment == Equipment.BODYWEIGHT))
            }
        }
        return dayToEntries
    }

    fun selectDay(day: Long?) {
        _state.value = _state.value.copy(selectedDay = day)
    }

    fun toggleDisliked() {
        val exercise = _state.value.exercise ?: return
        viewModelScope.launch {
            val updated = exercise.copy(isDisliked = !exercise.isDisliked)
            repository.updateExercise(updated)
            _state.value = _state.value.copy(exercise = updated)
        }
    }

    fun toggleHurtFlag() {
        val exercise = _state.value.exercise ?: return
        viewModelScope.launch {
            val newIsHurt = !_state.value.isHurt
            app.database.exerciseHurtStateDao().upsert(
                ExerciseHurtState(
                    exerciseId = exercise.id,
                    isHurt = newIsHurt,
                    asOf = System.currentTimeMillis(),
                )
            )
            _state.value = _state.value.copy(isHurt = newIsHurt)
        }
    }

    companion object {
        fun factory(exerciseId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[APPLICATION_KEY] ?: error("No application")
                    return ExerciseDetailViewModel(app, exerciseId) as T
                }
            }
    }
}
