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
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.setObservationLn
import io.github.fowles.stochastic_strength.ui.components.sharedProgressionYRange
import kotlin.math.exp
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

/**
 * One exercise's sets for a single session, the day bucket they fall in, and the factor that
 * scales the aggregate into the target exercise's space (1.0 for the exercise's own sets,
 * `targetCoef / siblingCoef` for a sibling).
 */
internal data class ObservedSession(val day: Long, val scale: Float, val sets: List<WorkoutSet>)

/**
 * One observed estimated-1RM dot **per set**, computed with the same fatigue-corrected implied
 * observation the belief fold consumes: [setObservationLn], rank = 1-based index over the
 * exercise's session sets sorted by id (all rows count, matching the fold's rank rule), scaled into
 * the target exercise's space.
 *
 * Emitting one dot per set — rather than collapsing a session into one aggregate point — mirrors the
 * debug progression chart's observed dots exactly ("every set is its own piece of feedback"), so the
 * two views agree even on a day where several sibling exercises (with very different scale factors)
 * were trained. A set with no feedback/interval produces no dot.
 */
internal fun observedSessionPoints(
    sessions: List<ObservedSession>,
    config: BeliefConfig = BeliefConfig(),
): List<ChartPoint> =
    sessions.flatMap { s ->
        s.sets.sortedBy { it.id }.mapIndexedNotNull { idx, set ->
            setObservationLn(set, rank = idx + 1, config)?.let {
                ChartPoint(dateMs = s.day * 86_400_000L, weightKg = exp(it) * s.scale)
            }
        }
    }.sortedBy { it.dateMs }

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
        val isBodyweight = (ExerciseCoefficients.byName[exercise.name] ?: 0f) <= 0f
        val zone = ZoneId.systemDefault()
        // Bucket each session by its END time — the same `asOf` the progression engine (and the debug
        // chart) stamps a session with — so the two charts place the same session on the same day.
        val sessionAnchorById = repository.getAllSessions().associate { it.id to (it.endTime ?: it.startTime) }
        val primarySets = repository.getAllSetsForExercise(exerciseId)
        val completedPrimary = primarySets.filter { it.completedAt != null }
        val primarySetsByDay = completedPrimary
            .groupBy { ExerciseChartGrouping.sessionDayKey(it, sessionAnchorById, zone) }
        // One dot per session (not per day): a day with two sessions shows two dots, matching the
        // debug chart and the per-session aggregate the engine actually folds in.
        val primarySessions = completedPrimary.groupBy { it.sessionId }.map { (_, sets) ->
            ObservedSession(
                day = ExerciseChartGrouping.sessionDayKey(sets.first(), sessionAnchorById, zone),
                scale = 1f,
                sets = sets,
            )
        }
        val primaryPoints = if (isBodyweight) emptyList() else observedSessionPoints(primarySessions)

        val (shadowPoints, shadowSetsByDay) = computeShadowPoints(exercise, sessionAnchorById, zone)

        // Pin this chart to the same Y range the debug progression chart uses, derived from the
        // shared replay data, so the dots sit at the same height when flipping between the two views.
        val chartYRange = sharedProgressionYRange(repository.getExerciseProgressionData(exerciseId))

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
            chartYRange = chartYRange,
        )
    }

    private suspend fun computeShadowPoints(
        exercise: Exercise,
        sessionAnchorById: Map<Long, Long>,
        zone: ZoneId,
    ): Pair<List<ChartPoint>, Map<Long, List<ExerciseSetEntry>>> {
        val thisCoeff = ExerciseCoefficients.byName[exercise.name] ?: 0f
        val isBodyweight = thisCoeff <= 0f

        val allExercises = repository.observeAllExercises().first()
        val related = allExercises.filter {
            it.primaryMuscle == exercise.primaryMuscle && it.id != exerciseId
        }

        // One observed dot per sibling-session (never averaged across siblings sharing a day),
        // scaled into this exercise's space with the same engine aggregate as the own dots — this
        // is what the debug chart plots, so the two views agree. Entries list every set for the
        // day-detail panel regardless of whether the session yielded a signal.
        val shadowSessions = mutableListOf<ObservedSession>()
        val dayToEntries = mutableMapOf<Long, MutableList<ExerciseSetEntry>>()
        for (rel in related) {
            val relCoeff = ExerciseCoefficients.byName[rel.name] ?: continue
            if (relCoeff <= 0f) continue
            val scaleFactor = if (isBodyweight) 1f else thisCoeff / relCoeff
            val completed = repository.getAllSetsForExercise(rel.id).filter { it.completedAt != null }
            for ((_, sessionSets) in completed.groupBy { it.sessionId }) {
                val dayKey = ExerciseChartGrouping.sessionDayKey(sessionSets.first(), sessionAnchorById, zone)
                sessionSets.forEach { dayToEntries.getOrPut(dayKey) { mutableListOf() }.add(ExerciseSetEntry(rel.name, it, rel.isTimed)) }
                shadowSessions += ObservedSession(day = dayKey, scale = scaleFactor, sets = sessionSets)
            }
        }

        return Pair(observedSessionPoints(shadowSessions), dayToEntries)
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
