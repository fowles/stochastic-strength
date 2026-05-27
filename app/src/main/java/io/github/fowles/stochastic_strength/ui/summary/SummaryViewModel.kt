package io.github.fowles.stochastic_strength.ui.summary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

class SummaryViewModel(
    application: Application,
    private val sessionId: Long,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp

    data class ExerciseSummary(
        val name: String,
        val feedback: List<SetFeedback?>,
        val weight: Float,
    )

    data class SummaryData(
        val startTime: Long,
        val durationSeconds: Long,
        val exercises: List<ExerciseSummary>,
        val weightUnit: WeightUnit,
    )

    val summary: StateFlow<SummaryData?> = flow {
        val profile = app.database.userProfileDao().getProfile()
        val weightUnit = profile?.weightUnit ?: WeightUnit.KG
        val session = app.database.workoutSessionDao().getById(sessionId)
        val sets = app.database.workoutSetDao().getSetsForSession(sessionId)
        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val nameById = exerciseIds
            .mapNotNull { id -> app.database.exerciseDao().getById(id)?.let { id to it.name } }
            .toMap()

        val exercises = exerciseIds.map { id ->
            val exerciseSets = sets.filter { it.exerciseId == id }
            ExerciseSummary(
                name = nameById[id] ?: "Unknown",
                feedback = exerciseSets.map { it.feedback },
                weight = exerciseSets.firstOrNull()?.targetWeight ?: 0f,
            )
        }

        val duration = if (session != null && session.endTime != null) {
            (session.endTime - session.startTime) / 1000
        } else 0L

        emit(SummaryData(
            startTime = session?.startTime ?: 0L,
            durationSeconds = duration,
            exercises = exercises,
            weightUnit = weightUnit,
        ))
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

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
