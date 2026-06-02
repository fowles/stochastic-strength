package io.github.fowles.stochastic_strength.ui.summary

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.ui.SummaryExercise
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryData
import io.github.fowles.stochastic_strength.ui.strava.StravaExportState
import io.github.fowles.stochastic_strength.ui.toSummarySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


class SummaryViewModel(
    application: Application,
    private val sessionId: Long,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp

    val summary: StateFlow<WorkoutSummaryData?> = flow {
        val profile = app.database.userProfileDao().getProfile()
        val weightUnit = profile?.weightUnit ?: WeightUnit.KG
        val session = app.database.workoutSessionDao().getById(sessionId)
        val sets = app.database.workoutSetDao().getSetsForSession(sessionId)
        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val exerciseById = exerciseIds
            .mapNotNull { id -> app.database.exerciseDao().getById(id)?.let { id to it } }
            .toMap()
        val setsByExercise = sets.groupBy { it.exerciseId }

        val exercises = exerciseIds.map { id ->
            val exercise = exerciseById[id]
            SummaryExercise(
                name = exercise?.name ?: "Unknown",
                exerciseId = id,
                sets = (setsByExercise[id] ?: emptyList()).sortedBy { it.setNumber }
                    .map { it.toSummarySet(exercise?.isTimed ?: false) },
            )
        }

        val duration = if (session != null && session.endTime != null) {
            (session.endTime - session.startTime) / 1000
        } else 0L

        emit(WorkoutSummaryData(
            startTime = session?.startTime ?: 0L,
            durationSeconds = duration,
            exercises = exercises,
            weightUnit = weightUnit,
        ))
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _stravaState = MutableStateFlow<StravaExportState>(StravaExportState.Idle)
    val stravaState: StateFlow<StravaExportState> = _stravaState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = app.database.workoutSessionDao().getById(sessionId)
            if (session?.stravaActivityId != null) {
                _stravaState.value = StravaExportState.Success(session.stravaActivityId)
            }
        }
    }

    fun onExportToStrava() {
        if (!app.stravaExporter.isAuthenticated()) {
            _stravaState.value = StravaExportState.NeedsAuth(app.stravaExporter.getAuthUrl())
            return
        }
        launchExport()
    }

    fun onStravaAuthUrlLaunched() {
        if (_stravaState.value is StravaExportState.NeedsAuth) {
            _stravaState.value = StravaExportState.WaitingForAuth
        }
    }

    fun onResumed() {
        if (_stravaState.value is StravaExportState.WaitingForAuth &&
            app.stravaExporter.isAuthenticated()
        ) {
            launchExport()
        }
    }

    fun onStravaMessageShown() {
        if (_stravaState.value !is StravaExportState.Success) {
            _stravaState.value = StravaExportState.Idle
        }
    }

    private fun launchExport() {
        _stravaState.value = StravaExportState.Exporting
        app.applicationScope.launch {
            val weightUnit = summary.value?.weightUnit ?: WeightUnit.KG
            runCatching { app.stravaExporter.exportSession(sessionId, weightUnit) }
                .onSuccess { activityId ->
                    app.database.workoutSessionDao().updateStravaActivityId(sessionId, activityId)
                    _stravaState.value = StravaExportState.Success(activityId)
                    app.stravaExporter.notifyUploadResult(success = true)
                }
                .onFailure { e ->
                    Log.e("StravaExport", "Export failed", e)
                    val msg = e.message ?: "Export to Strava failed"
                    _stravaState.value = StravaExportState.Error(msg)
                    app.stravaExporter.notifyUploadResult(success = false, error = msg)
                }
        }
    }

    companion object {
        fun factory(sessionId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[APPLICATION_KEY] ?: error("No application")
                    return SummaryViewModel(app, sessionId) as T
                }
            }
    }
}
