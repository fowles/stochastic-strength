package io.github.fowles.stochastic_strength.ui.savedworkouts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.domain.CircuitEdits
import io.github.fowles.stochastic_strength.domain.RowSuggester
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutNaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the workout row behind the editor has been read yet, and whether it was still there. */
enum class LoadStatus { LOADING, LOADED, MISSING }

data class SavedWorkoutEditState(
    val status: LoadStatus = LoadStatus.LOADING,
    val name: String = "",
    val entries: List<SavedWorkoutEntry> = emptyList(),
)

class SavedWorkoutEditViewModel(
    application: Application,
    workoutId: Long,
    private val repository: WorkoutRepository,
) : AndroidViewModel(application) {
    constructor(application: Application, workoutId: Long) :
        this(application, workoutId, (application as StochasticStrengthApp).workoutRepository)

    /** Null until a new workout has been written once; then the row every later save updates. */
    private var persistedId: Long? = workoutId.takeIf { it != NEW_WORKOUT_ID }

    private val _state = MutableStateFlow(
        // A new workout has nothing to load: it starts empty and becomes a row on first save.
        if (persistedId == null) SavedWorkoutEditState(LoadStatus.LOADED) else SavedWorkoutEditState()
    )
    val state: StateFlow<SavedWorkoutEditState> = _state.asStateFlow()

    /** What the editor showed when it opened (or last saved); anything else is an unsaved edit. */
    private var savedSnapshot: SavedWorkoutEditState = _state.value

    /** True when leaving without Done would lose something. Only Done persists; back discards. */
    fun hasUnsavedChanges(): Boolean = _state.value.status == LoadStatus.LOADED && _state.value != savedSnapshot

    val allExercises: StateFlow<List<Exercise>> = repository.observeAllExercises()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Null until loaded; not part of [SavedWorkoutEditState] so its loading can't affect [hasUnsavedChanges]. */
    private val _suggester = MutableStateFlow<RowSuggester?>(null)
    val suggester: StateFlow<RowSuggester?> = _suggester.asStateFlow()

    init {
        viewModelScope.launch { _suggester.value = repository.rowSuggester() }

        val existingId = persistedId
        if (existingId != null) viewModelScope.launch {
            val detail = repository.getSavedWorkout(existingId)
            _state.value = if (detail == null) {
                // Deleted underneath us (or a stale nav argument): say so instead of spinning forever.
                SavedWorkoutEditState(LoadStatus.MISSING)
            } else {
                // An unnamed workout edits as empty text, with the derived name as placeholder.
                val name = if (SavedWorkoutNaming.isPlaceholder(detail.name)) "" else detail.name
                SavedWorkoutEditState(LoadStatus.LOADED, name, detail.entries)
            }
            savedSnapshot = _state.value
        }
    }

    fun setName(name: String) { _state.value = _state.value.copy(name = name) }

    fun addExercise(exerciseId: Long) {
        val exercise = allExercises.value.firstOrNull { it.id == exerciseId } ?: return
        if (_state.value.entries.any { it.exercise.id == exerciseId }) return
        _state.value = _state.value.copy(entries = _state.value.entries + SavedWorkoutEntry(exercise, null))
    }

    fun setReps(exerciseId: Long, reps: Int?) {
        _state.value = _state.value.copy(entries = _state.value.entries.map {
            if (it.exercise.id == exerciseId) it.copy(reps = reps) else it
        })
    }

    fun setWeight(exerciseId: Long, weight: Float?) {
        _state.value = _state.value.copy(entries = _state.value.entries.map {
            if (it.exercise.id == exerciseId) it.copy(weight = weight) else it
        })
    }

    private fun editEntries(edit: (List<SavedWorkoutEntry>) -> List<SavedWorkoutEntry>) {
        _state.value = _state.value.copy(entries = edit(_state.value.entries))
    }

    fun removeExercise(exerciseId: Long) = editEntries { rows ->
        CircuitEdits.remove(rows, rows.indexOfFirst { it.exercise.id == exerciseId })
    }

    /** Block indices: a circuit moves as a unit. */
    fun move(fromBlock: Int, toBlock: Int) = editEntries { CircuitEdits.moveBlock(it, fromBlock, toBlock) }

    fun link(rowIndex: Int) = editEntries { CircuitEdits.link(it, rowIndex) }

    fun unlink(rowIndex: Int) = editEntries { CircuitEdits.unlink(it, rowIndex) }

    /** Sets the rounds of the row's block — for a solo row, its set count. */
    fun setSets(exerciseId: Long, sets: Int) = editEntries { rows ->
        CircuitEdits.setRounds(rows, rows.indexOfFirst { it.exercise.id == exerciseId }, sets)
    }

    private var saveJob: Job? = null

    private suspend fun performSave() {
        val s = _state.value
        if (s.status != LoadStatus.LOADED) return
        val name = s.name.trim()
        // A new workout the user never touched is never written; an existing one is never
        // silently deleted, even when emptied — that is what the list's delete button is for.
        if (persistedId == null && s.entries.isEmpty() && name.isEmpty()) return
        // Empty stays empty: the list and pickers derive a name from the exercises.
        persistedId = repository.saveWorkout(persistedId, name, s.entries)
        savedSnapshot = s
    }

    /** Persist (the Done button). Safe to call more than once. */
    fun save() {
        saveJob = viewModelScope.launch {
            performSave()
        }
    }

    override fun onCleared() {
        val hasPendingSave = saveJob?.isActive == true
        super.onCleared() // cancels viewModelScope
        if (hasPendingSave) {
            // Detached from viewModelScope: nothing above us can catch a throw (e.g. the workout
            // was deleted underneath us), so it would take the process down.
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { performSave() }
                    .onFailure { Log.w(TAG, "saved-workout save after clear failed", it) }
            }
        }
    }

    companion object {
        private const val TAG = "SavedWorkoutEdit"

        /** Route argument for the editor when there is no row yet. */
        const val NEW_WORKOUT_ID = 0L

        fun factory(workoutId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[APPLICATION_KEY] ?: error("No application")
                return SavedWorkoutEditViewModel(app, workoutId) as T
            }
        }
    }
}
