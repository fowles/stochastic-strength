package io.github.fowles.stochastic_strength.ui.exercises

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class ChartPoint(val dateMs: Long, val weightKg: Float)

/**
 * Reconstructs the planner's prescribed estimated-1RM (`baseline × coefficient`)
 * over time, sampled at [dayKeys] (the days that already have an achieved dot, so
 * the chart's x-grid and marker stay unchanged).
 *
 * `baseline(day)` is the `newBaseline` of the latest [BaselineHistory] whose local
 * day is ≤ `day`; before the first event it falls back to that event's
 * `previousBaseline` only when positive (an INITIAL assessment has
 * `previousBaseline == 0`, which would drag the line to zero, so those days are
 * dropped). `coefficient(day)` is the latest [CoefficientHistory] ≤ `day`, else
 * [seedCoefficient]. The product is the true, pre-rounding 1RM target — it is read
 * straight from history, never from the rounded session weight.
 *
 * Returns empty for unloadable exercises (`seedCoefficient ≤ 0`).
 */
internal fun buildPrescribedPoints(
    baselineEvents: List<BaselineHistory>,
    coefficientEvents: List<CoefficientHistory>,
    seedCoefficient: Float,
    dayKeys: Collection<Long>,
    zone: ZoneId,
): List<ChartPoint> {
    if (seedCoefficient <= 0f) return emptyList()
    fun epochDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    val baselineByDay = baselineEvents.map { epochDay(it.timestamp) to it }
    val coeffByDay = coefficientEvents.map { epochDay(it.computedAt) to it }
    val leadingBaseline = baselineEvents.firstOrNull()?.previousBaseline?.takeIf { it > 0f }
    return dayKeys.sorted().mapNotNull { day ->
        val baseline = baselineByDay.lastOrNull { it.first <= day }?.second?.newBaseline
            ?: leadingBaseline
            ?: return@mapNotNull null
        val coeff = coeffByDay.lastOrNull { it.first <= day }?.second?.coefficient ?: seedCoefficient
        ChartPoint(dateMs = day * 86_400_000L, weightKg = baseline * coeff)
    }
}

data class ExerciseSetEntry(val exerciseName: String, val set: WorkoutSet, val isTimed: Boolean = false)

data class ExerciseDetailState(
    val exercise: Exercise? = null,
    val isHurt: Boolean = false,
    val primaryPoints: List<ChartPoint> = emptyList(),
    val shadowPoints: List<ChartPoint> = emptyList(),
    val prescribedPoints: List<ChartPoint> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    val primarySetsByDay: Map<Long, List<WorkoutSet>> = emptyMap(),
    val shadowSetsByDay: Map<Long, List<ExerciseSetEntry>> = emptyMap(),
    val selectedDay: Long? = null,
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
        val isBodyweight = (ExerciseCoefficients.byName[exercise.name] ?: 0f) <= 0f
        val zone = ZoneId.systemDefault()
        val sessionStartById = repository.getAllSessions().associate { it.id to it.startTime }
        val primarySets = repository.getAllSetsForExercise(exerciseId)
        val primarySetsByDay = primarySets
            .filter { it.completedAt != null }
            .groupBy { ExerciseChartGrouping.sessionDayKey(it, sessionStartById, zone) }
        val primaryPoints = if (isBodyweight) emptyList() else primarySetsByDay
            .map { (day, sets) ->
                ChartPoint(
                    dateMs = day * 86_400_000L,
                    weightKg = sets.map { DefaultProgressionEngine.toOneRepMax(it.targetWeight, it.targetReps) }.average().toFloat(),
                )
            }
            .sortedBy { it.dateMs }

        val (shadowPoints, shadowSetsByDay) = computeShadowPoints(exercise, sessionStartById, zone)

        val seedCoefficient = ExerciseCoefficients.byName[exercise.name] ?: 0f
        val prescribedPoints = buildPrescribedPoints(
            baselineEvents = repository.getBaselineEvents(exercise.primaryMuscle),
            coefficientEvents = repository.getCoefficientEvents(exerciseId),
            seedCoefficient = seedCoefficient,
            // Sample over every charted day (this exercise's days plus the shadow
            // days from related exercises). baseline × coefficient is defined on
            // every day, and a line needs ≥2 points — sampling only this
            // exercise's own days collapses to a single invisible point whenever
            // it was performed on just one day. The union stays on the already
            // plotted x-grid, so the marker / day-selection is unaffected.
            dayKeys = primarySetsByDay.keys + shadowSetsByDay.keys,
            zone = zone,
        )

        _state.value = _state.value.copy(
            primaryPoints = primaryPoints,
            shadowPoints = shadowPoints,
            prescribedPoints = prescribedPoints,
            primarySetsByDay = primarySetsByDay,
            shadowSetsByDay = shadowSetsByDay,
        )
    }

    private suspend fun computeShadowPoints(
        exercise: Exercise,
        sessionStartById: Map<Long, Long>,
        zone: ZoneId,
    ): Pair<List<ChartPoint>, Map<Long, List<ExerciseSetEntry>>> {
        val thisCoeff = ExerciseCoefficients.byName[exercise.name] ?: 0f
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
                if (set.completedAt == null) continue
                val dayKey = ExerciseChartGrouping.sessionDayKey(set, sessionStartById, zone)
                dayToWeights.getOrPut(dayKey) { mutableListOf() }.add(DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps) * scaleFactor)
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
