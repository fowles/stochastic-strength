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
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.ui.debug.components.DebugChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BaselineEvent(
    val sessionId: Long?,
    val timestamp: Long,
    val previousBaseline: Float,
    val newBaseline: Float,
    val reason: BaselineChangeReason,
    val minReductionFraction: Float?,
    val exercises: List<BaselineEventExercise>,
    val heuristicMetadata: String? = null,
)

data class BaselineEventExercise(
    val name: String,
    val setLines: List<String>,
)

data class CoefficientDeviationRow(
    val name: String,
    val deviation: Float,
)

/**
 * Renders a single set as "<reps>@<weight>" for the change-events feed.
 *
 * RIR feedbacks become an estimated rep count at the prescribed weight using the
 * same +1 / +3 / +7 offsets that [EstCoefConsensusHeuristic] uses to compute the
 * implied 1RM, prefixed with `~` to mark it as an estimate. TOO_HARD shows the
 * actual reps achieved (no tilde — it is observed, not estimated). HURT has no
 * implied rep estimate, so it renders as "hurt@<weight>". Sets with no feedback
 * (warmups or unfinished sets) return null and are skipped.
 */
internal fun formatBaselineSetLine(set: WorkoutSet, weightUnit: WeightUnit): String? {
    val feedback = set.feedback ?: return null
    val repsPart = when (feedback) {
        SetFeedback.RIR_0_1 -> "~${set.targetReps + 1}"
        SetFeedback.RIR_2_4 -> "~${set.targetReps + 3}"
        SetFeedback.RIR_5_PLUS -> "~${set.targetReps + 7}"
        SetFeedback.TOO_HARD -> set.actualReps?.toString() ?: "?"
        SetFeedback.HURT -> "hurt"
    }
    return "$repsPart@${formatWeightCompact(set.targetWeight, weightUnit)}"
}

/** Compact "55lbs" / "25kg" rendering for the change-events feed. */
internal fun formatWeightCompact(kg: Float, weightUnit: WeightUnit): String =
    if (weightUnit == WeightUnit.KG) {
        "%.1fkg".format(kg)
    } else {
        "%.0flbs".format(kg * 2.20462f)
    }

/**
 * Groups a session's sets by exercise (preserving the order in which exercises
 * first appear), then renders each exercise's sets through [formatBaselineSetLine].
 * Exercises with no displayable sets are dropped.
 */
internal fun buildExerciseBlocks(
    sets: List<WorkoutSet>,
    nameByExerciseId: Map<Long, String>,
    weightUnit: WeightUnit,
): List<BaselineEventExercise> {
    val grouped = LinkedHashMap<Long, MutableList<WorkoutSet>>()
    for (s in sets.sortedBy { it.setNumber }) {
        grouped.getOrPut(s.exerciseId) { mutableListOf() }.add(s)
    }
    return grouped.mapNotNull { (exerciseId, exerciseSets) ->
        val name = nameByExerciseId[exerciseId] ?: return@mapNotNull null
        val lines = exerciseSets.mapNotNull { formatBaselineSetLine(it, weightUnit) }
        if (lines.isEmpty()) null else BaselineEventExercise(name = name, setLines = lines)
    }
}

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
            val latestUserCoefficients = repository.getLatestCoefficientPerExercise()
            val coefficientDeviations = computeCoefficientDeviations(
                exercises = allExercises.map { it.id to it.name },
                seedByName = ExerciseCoefficients.byName,
                currentByExerciseId = latestUserCoefficients,
            )

            // Non-weighted (bodyweight) exercises don't enter the baseline computation,
            // so omit them from the per-event display.
            val weightedExercises = allExercises.filter { it.equipment != Equipment.BODYWEIGHT }
            val nameByExerciseId = weightedExercises.associate { it.id to it.name }
            val exerciseIdsForMuscle = nameByExerciseId.keys
            val sessionIds = logs.mapNotNull { it.sessionId }.toSet()
            val setsBySession = app.database.workoutSetDao().getAll()
                .filter { it.sessionId in sessionIds && it.exerciseId in exerciseIdsForMuscle }
                .groupBy { it.sessionId }

            val events = logs.asReversed().map { log ->
                val exerciseBlocks = buildExerciseBlocks(
                    sets = setsBySession[log.sessionId].orEmpty(),
                    nameByExerciseId = nameByExerciseId,
                    weightUnit = weightUnit,
                )
                BaselineEvent(
                    sessionId = log.sessionId,
                    timestamp = log.timestamp,
                    previousBaseline = log.previousBaseline,
                    newBaseline = log.newBaseline,
                    reason = log.changeReason,
                    minReductionFraction = log.minReductionFraction,
                    exercises = exerciseBlocks,
                    heuristicMetadata = log.heuristicMetadata,
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
