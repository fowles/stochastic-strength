package io.github.fowles.stochastic_strength.ui.exercises

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
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ChartPoint(val dateMs: Long, val weightKg: Float)

data class ExerciseSetEntry(val exerciseName: String, val set: WorkoutSet, val isTimed: Boolean = false)

data class ExerciseDetailState(
    val exercise: Exercise? = null,
    val primaryPoints: List<ChartPoint> = emptyList(),
    val shadowPoints: List<ChartPoint> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    val allSets: List<WorkoutSet> = emptyList(),
    val shadowSetsByDay: Map<Long, List<ExerciseSetEntry>> = emptyMap(),
    val selectedDay: Long? = null,
)

class ExerciseDetailViewModel(
    application: Application,
    private val exerciseId: Long,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = WorkoutRepository(app.database)

    private val _state = MutableStateFlow(ExerciseDetailState())
    val state: StateFlow<ExerciseDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            val weightUnit = profile?.weightUnit ?: WeightUnit.KG
            val exercise = repository.getExerciseById(exerciseId) ?: return@launch
            _state.value = ExerciseDetailState(exercise = exercise, weightUnit = weightUnit)
            loadChartData(exercise)
        }
    }

    private suspend fun loadChartData(exercise: Exercise) {
        val isBodyweight = (ExerciseCoefficients.byName[exercise.name] ?: 0f) <= 0f
        val primarySets = repository.getAllSetsForExercise(exerciseId)
        val primaryPoints = if (isBodyweight) emptyList() else primarySets
            .filter { it.completedAt != null }
            .groupBy { it.completedAt!! / 86_400_000L }
            .map { (day, sets) ->
                ChartPoint(
                    dateMs = day * 86_400_000L,
                    weightKg = sets.map { it.targetWeight }.average().toFloat(),
                )
            }
            .sortedBy { it.dateMs }

        val (shadowPoints, shadowSetsByDay) = computeShadowPoints(exercise)

        _state.value = _state.value.copy(
            primaryPoints = primaryPoints,
            shadowPoints = shadowPoints,
            allSets = primarySets,
            shadowSetsByDay = shadowSetsByDay,
        )
    }

    private suspend fun computeShadowPoints(
        exercise: Exercise,
    ): Pair<List<ChartPoint>, Map<Long, List<ExerciseSetEntry>>> {
        val thisCoeff = ExerciseCoefficients.byName[exercise.name]
            ?: return Pair(emptyList(), emptyMap())
        val isBodyweight = thisCoeff <= 0f

        val allExercises = repository.observeAllExercises().first()
        val related = allExercises.filter {
            it.primaryMuscle == exercise.primaryMuscle && it.id != exerciseId
        }

        val dayToWeights = mutableMapOf<Long, MutableList<Float>>()
        val dayToEntries = mutableMapOf<Long, MutableList<ExerciseSetEntry>>()
        for (rel in related) {
            val relCoeff = ExerciseCoefficients.byName[rel.name] ?: continue
            if (relCoeff <= 0f) continue
            val scaleFactor = if (isBodyweight) 1f else thisCoeff / relCoeff
            val sets = repository.getAllSetsForExercise(rel.id)
            for (set in sets) {
                val completedAt = set.completedAt ?: continue
                val dayKey = completedAt / 86_400_000L
                dayToWeights.getOrPut(dayKey) { mutableListOf() }.add(set.targetWeight * scaleFactor)
                dayToEntries.getOrPut(dayKey) { mutableListOf() }.add(ExerciseSetEntry(rel.name, set, rel.isTimed))
            }
        }

        val points = dayToWeights
            .map { (day, weights) ->
                ChartPoint(
                    dateMs = day * 86_400_000L,
                    weightKg = weights.average().toFloat(),
                )
            }
            .sortedBy { it.dateMs }
        return Pair(points, dayToEntries)
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

    fun clearHurtFlag() {
        val exercise = _state.value.exercise ?: return
        viewModelScope.launch {
            val updated = exercise.copy(hurtFlag = false)
            repository.updateExercise(updated)
            _state.value = _state.value.copy(exercise = updated)
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
