package io.github.fowles.stochastic_strength.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionListItem(
    val session: WorkoutSession,
    val locationName: String?,
    val exerciseNames: List<String>,
    val durationSeconds: Long,
)

data class HistoryState(
    val muscleStrengths: List<MuscleGroupStrength> = emptyList(),
    val referenceExerciseIds: Map<MuscleGroup, Long> = emptyMap(),
    val sessions: List<SessionListItem> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    val loading: Boolean = true,
    val pendingDeleteSessionId: Long? = null,
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            val weightUnit = profile?.weightUnit ?: WeightUnit.KG
            val muscleStrengths = repository.getMuscleGroupStrengths()
                .sortedBy { it.muscleGroup.ordinal }
            val locations = repository.getLocations().associateBy { it.id }
            val rawSessions = repository.getAllSessions()
            val sessions = rawSessions.map { session ->
                SessionListItem(
                    session = session,
                    locationName = session.locationId?.let { locations[it]?.name },
                    exerciseNames = repository.getSessionExerciseNames(session.id),
                    durationSeconds = if (session.endTime != null)
                        (session.endTime - session.startTime) / 1000L
                    else 0L,
                )
            }
            val referenceExerciseIds = repository.observeAllExercises().first()
                .filter { ExerciseCoefficients.byName[it.name] == 1.0f }
                .associate { it.primaryMuscle to it.id }
            _state.value = HistoryState(
                muscleStrengths = muscleStrengths,
                referenceExerciseIds = referenceExerciseIds,
                sessions = sessions,
                weightUnit = weightUnit,
                loading = false,
            )
        }
    }

    fun requestDelete(sessionId: Long) {
        _state.value = _state.value.copy(pendingDeleteSessionId = sessionId)
    }

    fun cancelDelete() {
        _state.value = _state.value.copy(pendingDeleteSessionId = null)
    }

    fun confirmDelete() {
        val sessionId = _state.value.pendingDeleteSessionId ?: return
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            _state.value = _state.value.copy(
                sessions = _state.value.sessions.filter { it.session.id != sessionId },
                pendingDeleteSessionId = null,
            )
        }
    }
}
