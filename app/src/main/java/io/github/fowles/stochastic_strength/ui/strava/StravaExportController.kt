package io.github.fowles.stochastic_strength.ui.strava

import android.util.Log
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.strava.StravaAuthException
import io.github.fowles.stochastic_strength.domain.strava.StravaExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StravaExportController(
    private val exporter: StravaExporter,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<StravaExportState>(StravaExportState.Idle)
    val state: StateFlow<StravaExportState> = _state.asStateFlow()

    fun setState(state: StravaExportState) {
        _state.value = state
    }

    fun export(sessionId: Long, weightUnit: WeightUnit) {
        if (!exporter.isAuthenticated()) {
            _state.value = StravaExportState.NeedsAuth(exporter.getAuthUrl())
            return
        }
        launch(sessionId, weightUnit)
    }

    fun reexport(sessionId: Long, weightUnit: WeightUnit) {
        exporter.clearTokens()
        export(sessionId, weightUnit)   // now unauthenticated → NeedsAuth → connect flow
    }

    fun onAuthUrlLaunched() {
        if (_state.value is StravaExportState.NeedsAuth)
            _state.value = StravaExportState.WaitingForAuth
    }

    fun onResumedWaitingForAuth(sessionId: Long, weightUnit: WeightUnit) {
        if (_state.value is StravaExportState.WaitingForAuth && exporter.isAuthenticated())
            launch(sessionId, weightUnit)
    }

    fun onMessageShown() {
        if (_state.value !is StravaExportState.Success)
            _state.value = StravaExportState.Idle
    }

    private fun launch(sessionId: Long, weightUnit: WeightUnit) {
        _state.value = StravaExportState.Exporting
        scope.launch {
            runCatching { exporter.exportSession(sessionId, weightUnit) }
                .onSuccess { activityId ->
                    database.workoutSessionDao().updateStravaActivityId(sessionId, activityId)
                    _state.value = StravaExportState.Success(activityId)
                    exporter.notifyUploadResult(success = true)
                }
                .onFailure { e ->
                    Log.e("StravaExport", "Export failed", e)
                    if (e is StravaAuthException) {
                        _state.value = StravaExportState.NeedsAuth(exporter.getAuthUrl())
                    } else {
                        val msg = e.message ?: "Export to Strava failed"
                        _state.value = StravaExportState.Error(msg)
                        exporter.notifyUploadResult(success = false, error = msg)
                    }
                }
        }
    }
}
