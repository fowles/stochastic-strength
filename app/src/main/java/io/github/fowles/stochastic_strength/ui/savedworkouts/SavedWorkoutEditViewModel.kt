package io.github.fowles.stochastic_strength.ui.savedworkouts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.CircuitEdits
import io.github.fowles.stochastic_strength.domain.RowSuggester
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutNaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    constructor(application: Application, workoutId: Long, savedStateHandle: SavedStateHandle) :
        this(application, workoutId, (application as StochasticStrengthApp).workoutRepository, savedStateHandle)

    /** Null until a new workout has been written once; then the row every later save updates. */
    private var persistedId: Long? = workoutId.takeIf { it != NEW_WORKOUT_ID }

    /** True when a prior instance of this editor already wrote a snapshot to restore from. */
    private val hasSnapshot: Boolean = savedStateHandle.contains(KEY_NAME)

    private val _state = MutableStateFlow(
        // A brand-new, never-snapshotted workout has nothing to load: it starts empty and becomes
        // a row on first save. Anything else — an existing row, or a snapshot to restore — loads
        // asynchronously in `init`, so it must start LOADING (never a premature empty LOADED).
        if (persistedId == null && !hasSnapshot) SavedWorkoutEditState(LoadStatus.LOADED) else SavedWorkoutEditState()
    )
    val state: StateFlow<SavedWorkoutEditState> = _state.asStateFlow()

    /**
     * Whether the current state has diverged from what's persisted. Explicit (not derived by
     * comparing against a remembered snapshot) so it can be written to [savedStateHandle]
     * verbatim and read back after process death without reconstructing a "what it was" state.
     */
    private var dirty = false

    /** True when leaving without Done would lose something. Only Done persists; back discards. */
    fun hasUnsavedChanges(): Boolean = _state.value.status == LoadStatus.LOADED && dirty

    val allExercises: StateFlow<List<Exercise>> = repository.observeAllExercises()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Null until loaded; not part of [SavedWorkoutEditState] so its loading can't affect [hasUnsavedChanges]. */
    private val _suggester = MutableStateFlow<RowSuggester?>(null)
    val suggester: StateFlow<RowSuggester?> = _suggester.asStateFlow()

    /**
     * Read on its own so a pinned weight can render before the (much slower) planner build lands.
     * Null until that read lands: an lbs user must never see (or step) a weight on the kg grid.
     */
    private val _weightUnit = MutableStateFlow<WeightUnit?>(null)
    val weightUnit: StateFlow<WeightUnit?> = _weightUnit.asStateFlow()

    init {
        viewModelScope.launch { _weightUnit.value = repository.weightUnit() }
        viewModelScope.launch { _suggester.value = repository.rowSuggester() }

        if (hasSnapshot) {
            // A snapshot survives process death; restore from it instead of the stored workout,
            // so an edit not yet saved with Done isn't silently dropped. Re-read each exercise by
            // id rather than trusting a Parcelable — a row whose exercise was deleted meanwhile
            // is dropped, same as any other stale reference.
            viewModelScope.launch {
                val name: String = savedStateHandle[KEY_NAME] ?: ""
                val entries = decodeEntries(savedStateHandle[KEY_ENTRIES] ?: "")
                dirty = savedStateHandle[KEY_DIRTY] ?: false
                _state.value = SavedWorkoutEditState(LoadStatus.LOADED, name, entries)
            }
        } else {
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
                dirty = false
                persistSnapshot()
            } else {
                // A new workout starts LOADED and empty already: snapshot that baseline so a
                // process death before any edit still restores an editor rather than a reload.
                persistSnapshot()
            }
        }
    }

    fun setName(name: String) = mutate { it.copy(name = name) }

    fun addExercise(exerciseId: Long) {
        val exercise = allExercises.value.firstOrNull { it.id == exerciseId } ?: return
        if (_state.value.entries.any { it.exercise.id == exerciseId }) return
        mutate { it.copy(entries = it.entries + SavedWorkoutEntry(exercise, null)) }
    }

    fun setReps(exerciseId: Long, reps: Int?) = mutate {
        it.copy(entries = it.entries.map { e -> if (e.exercise.id == exerciseId) e.copy(reps = reps) else e })
    }

    fun setWeight(exerciseId: Long, weight: Float?) = mutate {
        it.copy(entries = it.entries.map { e -> if (e.exercise.id == exerciseId) e.copy(weight = weight) else e })
    }

    private fun editEntries(edit: (List<SavedWorkoutEntry>) -> List<SavedWorkoutEntry>) =
        mutate { it.copy(entries = edit(it.entries)) }

    /** Every edit marks the editor dirty and re-snapshots, so process death can't lose it. */
    private fun mutate(transform: (SavedWorkoutEditState) -> SavedWorkoutEditState) {
        _state.value = transform(_state.value)
        dirty = true
        persistSnapshot()
    }

    /** Writes name/entries/dirty to [savedStateHandle]; a no-op unless the editor is usable. */
    private fun persistSnapshot() {
        val s = _state.value
        if (s.status != LoadStatus.LOADED) return
        savedStateHandle[KEY_NAME] = s.name
        savedStateHandle[KEY_ENTRIES] = encodeEntries(s.entries)
        savedStateHandle[KEY_DIRTY] = dirty
    }

    /** One line per entry, `|`-separated; an absent optional field is an empty segment. */
    private fun encodeEntries(entries: List<SavedWorkoutEntry>): String = entries.joinToString("\n") { e ->
        listOf(e.exercise.id, e.reps ?: "", e.sets, e.circuitId ?: "", e.weight ?: "").joinToString("|")
    }

    /** The inverse of [encodeEntries]; a row whose exercise id no longer resolves is dropped. */
    private suspend fun decodeEntries(encoded: String): List<SavedWorkoutEntry> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split("\n").mapNotNull { line ->
            val (exerciseId, reps, sets, circuitId, weight) = line.split("|")
            val exercise = repository.getExerciseById(exerciseId.toLong()) ?: return@mapNotNull null
            SavedWorkoutEntry(
                exercise = exercise,
                reps = reps.toIntOrNull(),
                sets = sets.toInt(),
                circuitId = circuitId.toIntOrNull(),
                weight = weight.toFloatOrNull(),
            )
        }
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
    private val saveMutex = Mutex()

    /**
     * One save at a time, and the write-then-remember-the-id step is never torn by cancellation:
     * a second save that read `persistedId == null` while the first insert was still in flight
     * would insert the workout twice.
     */
    private suspend fun performSave() = saveMutex.withLock {
        val s = _state.value
        if (s.status != LoadStatus.LOADED) return@withLock
        val name = s.name.trim()
        // A new workout the user never touched is never written; an existing one is never
        // silently deleted, even when emptied — that is what the list's delete button is for.
        if (persistedId == null && s.entries.isEmpty() && name.isEmpty()) return@withLock
        // Empty stays empty: the list and pickers derive a name from the exercises.
        withContext(NonCancellable) {
            persistedId = repository.saveWorkout(persistedId, name, s.entries)
        }
        dirty = false
        persistSnapshot()
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

        private const val KEY_NAME = "savedWorkoutEdit.name"
        private const val KEY_ENTRIES = "savedWorkoutEdit.entries"
        private const val KEY_DIRTY = "savedWorkoutEdit.dirty"

        /** Route argument for the editor when there is no row yet. */
        const val NEW_WORKOUT_ID = 0L

        fun factory(workoutId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[APPLICATION_KEY] ?: error("No application")
                return SavedWorkoutEditViewModel(app, workoutId, extras.createSavedStateHandle()) as T
            }
        }
    }
}
