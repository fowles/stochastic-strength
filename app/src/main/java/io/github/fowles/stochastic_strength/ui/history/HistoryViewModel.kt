package io.github.fowles.stochastic_strength.ui.history

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.BuildConfig
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.domain.backup.BackupFormatException
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonBuilder
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonParser
import io.github.fowles.stochastic_strength.domain.history.HistoryHighlight
import io.github.fowles.stochastic_strength.domain.history.HistoryRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

enum class ImportMode { ADDITIVE, DESTRUCTIVE }

data class SessionListItem(
    val session: WorkoutSession,
    val locationName: String?,
    val exerciseNames: List<String>,
    val durationSeconds: Long,
)

data class HistoryState(
    val highlight: String = "",
    val workoutDays: Set<LocalDate> = emptySet(),
    val sessions: List<SessionListItem> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    val loading: Boolean = true,
    val pendingDeleteSessionId: Long? = null,
    val message: String? = null,
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reloadInternal() }
    }

    private suspend fun reloadInternal(message: String? = null) {
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val profile = app.database.userProfileDao().getProfile()
        val weightUnit = profile?.weightUnit ?: WeightUnit.KG
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
        val workoutDays = HistoryRows.workoutDays(
            rawSessions.filter { it.endTime != null }.map { it.startTime }, zone,
        )
        // Daily-stable pick in release; re-roll on every open in debug for easy testing.
        val seed = if (BuildConfig.DEBUG) System.nanoTime() else LocalDate.now(zone).toEpochDay()
        val highlight = withContext(Dispatchers.Default) {
            HistoryHighlight.pick(
                series = repository.buildHighlightSeries(nowMs),
                weightUnit = weightUnit,
                nowMs = nowMs,
                random = Random(seed),
            )
        }
        _state.value = HistoryState(
            highlight = highlight,
            workoutDays = workoutDays,
            sessions = sessions,
            weightUnit = weightUnit,
            loading = false,
            message = message,
        )
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                val backup = app.backupManager.export()
                val json = BackupJsonBuilder.build(backup)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: error("Could not open file for writing")
                }
                _state.value = _state.value.copy(message = "History exported.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Export failed: ${e.message}")
            }
        }
    }

    fun importFrom(uri: Uri, mode: ImportMode) {
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("Could not open file for reading")
                }
                val backup = BackupJsonParser.parse(json)
                val summary = when (mode) {
                    ImportMode.DESTRUCTIVE -> {
                        app.backupManager.importDestructive(backup)
                        "History replaced."
                    }
                    ImportMode.ADDITIVE -> {
                        val r = app.backupManager.importAdditive(backup)
                        "Imported ${r.sessionsAdded} sessions (" +
                            "${r.exercisesCreated} new exercises, ${r.setsSkipped} sets skipped)."
                    }
                }
                reloadInternal(summary)
            } catch (e: BackupFormatException) {
                _state.value = _state.value.copy(message = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Import failed: ${e.message}")
            }
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
