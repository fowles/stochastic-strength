package io.github.fowles.stochastic_strength.ui.summary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryData
import io.github.fowles.stochastic_strength.ui.loadWorkoutSummary
import io.github.fowles.stochastic_strength.ui.strava.StravaExportController
import io.github.fowles.stochastic_strength.ui.strava.StravaExportState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


class SummaryViewModel(
    application: Application,
    private val sessionId: Long,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp

    val summary: StateFlow<WorkoutSummaryData?> = flow {
        emit(loadWorkoutSummary(app.database, sessionId))
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val stravaController = StravaExportController(app.stravaExporter, app.database, app.applicationScope)
    val stravaState: StateFlow<StravaExportState> = stravaController.state

    init {
        viewModelScope.launch {
            val session = app.database.workoutSessionDao().getById(sessionId)
            if (session?.stravaActivityId != null) {
                stravaController.setState(StravaExportState.Success(session.stravaActivityId))
            }
        }
    }

    fun onExportToStrava() {
        val weightUnit = summary.value?.weightUnit ?: WeightUnit.KG
        stravaController.export(sessionId, weightUnit)
    }

    fun onReexportToStrava() {
        val weightUnit = summary.value?.weightUnit ?: WeightUnit.KG
        stravaController.reexport(sessionId, weightUnit)
    }

    fun onStravaAuthUrlLaunched() = stravaController.onAuthUrlLaunched()

    fun onResumed() {
        val weightUnit = summary.value?.weightUnit ?: WeightUnit.KG
        stravaController.onResumedWaitingForAuth(sessionId, weightUnit)
    }

    fun onStravaMessageShown() = stravaController.onMessageShown()

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
