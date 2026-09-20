package io.github.fowles.stochastic_strength.ui.workout

import android.app.Application
import android.content.Intent
import android.location.Geocoder
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.RepRangePicker
import io.github.fowles.stochastic_strength.domain.WorkoutGenerator
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutDetail
import io.github.fowles.stochastic_strength.location.LocationResult
import io.github.fowles.stochastic_strength.location.LocationService
import io.github.fowles.stochastic_strength.notification.WorkoutNotificationService
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryData
import io.github.fowles.stochastic_strength.ui.loadWorkoutSummary
import io.github.fowles.stochastic_strength.ui.strava.StravaExportController
import io.github.fowles.stochastic_strength.ui.strava.StravaExportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.random.Random

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val locationService = LocationService(app)

    private val controller = WorkoutSessionController(
        database = app.database,
        repository = app.workoutRepository,
        bus = app.workoutSessionBus,
        scope = viewModelScope,
        onVibrate = ::vibrate,
    )

    val state: StateFlow<WorkoutState> = controller.state
    val navigationEvent: Flow<NavigationEvent> = controller.navigationEvent

    private val _weightUnit = MutableStateFlow(WeightUnit.KG)
    val weightUnit: StateFlow<WeightUnit> = _weightUnit.asStateFlow()

    private val _doneSummary = MutableStateFlow<WorkoutSummaryData?>(null)
    val doneSummary: StateFlow<WorkoutSummaryData?> = _doneSummary.asStateFlow()

    private val _doneHighlight = MutableStateFlow<String?>(null)
    val doneHighlight: StateFlow<String?> = _doneHighlight.asStateFlow()

    /** Null until the first emission arrives, so the UI can tell "unknown" from "empty". */
    val savedWorkouts: StateFlow<List<SavedWorkoutDetail>?> = app.workoutRepository.observeSavedWorkouts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val allExercises: StateFlow<List<Exercise>> = app.workoutRepository.observeAllExercises()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val stravaController = StravaExportController(app.stravaExporter, app.database, app.applicationScope)
    val stravaState: StateFlow<StravaExportState> = stravaController.state

    private var preferredExerciseCount: Int = WorkoutGenerator.DEFAULT_EXERCISE_COUNT
    private var preferredRepMin: Int = DEFAULT_REP_MIN
    private var preferredRepMax: Int = DEFAULT_REP_MAX

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            _weightUnit.value = profile?.weightUnit ?: WeightUnit.KG
            preferredExerciseCount = profile?.preferredExerciseCount ?: WorkoutGenerator.DEFAULT_EXERCISE_COUNT
            preferredRepMin = profile?.preferredRepMin ?: DEFAULT_REP_MIN
            preferredRepMax = profile?.preferredRepMax ?: DEFAULT_REP_MAX
            val resolved = resolveLocation()
            val routine = controller.initializeSession(
                locationId = resolved.locationId,
                locationName = resolved.locationName,
                preferredExerciseCount = preferredExerciseCount,
                preferredRepMin = preferredRepMin,
                preferredRepMax = preferredRepMax,
                weightUnit = _weightUnit.value,
            )
            // The routine heuristic acted on the user's behalf, so say so — and transiently, since
            // there is nothing here for them to decide.
            if (routine != null) _message.value = "Loaded \"${routine.displayName}\""
            // Reverse geocoding is a (slow, sometimes-stalling) network call and only supplies the
            // location's display name — it never affects equipment filtering, which keys off the
            // locationId resolved above. So never block workout start on it: fill the name in later.
            resolved.pendingGeocode?.let { pending ->
                launch {
                    val name = reverseGeocode(pending.lat, pending.lng)
                    if (name != pending.placeholder) {
                        app.database.knownLocationDao().updateName(pending.locationId, name)
                        controller.updateLocationName(name)
                    }
                }
            }
        }
        viewModelScope.launch {
            app.workoutSessionBus.commandFlow.collect { command ->
                when (command) {
                    is WorkoutCommand.RecordFeedback -> controller.recordFeedback(command.feedback)
                    WorkoutCommand.SkipRest -> controller.skipRest()
                    WorkoutCommand.CompleteWarmupSet -> controller.completeWarmupSet()
                    WorkoutCommand.StartTimedSet -> controller.startTimedSet()
                }
            }
        }
        viewModelScope.launch {
            var serviceStarted = false
            app.workoutSessionBus.notificationState.collect { s ->
                if (!serviceStarted && s != null) {
                    serviceStarted = true
                    app.startForegroundService(Intent(app, WorkoutNotificationService::class.java))
                }
            }
        }
        viewModelScope.launch {
            controller.state.collect { s ->
                when {
                    s is WorkoutState.Done && _doneSummary.value == null -> {
                        _doneSummary.value = loadWorkoutSummary(app.database, s.sessionId)
                        _doneHighlight.value = withContext(Dispatchers.Default) {
                            app.workoutRepository.buildSessionHighlight(
                                sessionId = s.sessionId,
                                weightUnit = _weightUnit.value,
                                nowMs = System.currentTimeMillis(),
                                random = Random(s.sessionId),
                            )
                        }
                    }
                    s !is WorkoutState.Done -> {
                        _doneSummary.value = null
                        _doneHighlight.value = null
                    }
                }
            }
        }
    }

    fun setExerciseCount(targetCount: Int) {
        controller.adjustExerciseCount(targetCount)
        if (targetCount != preferredExerciseCount) {
            preferredExerciseCount = targetCount
            viewModelScope.launch {
                val profile = app.database.userProfileDao().getProfile() ?: return@launch
                app.database.userProfileDao().insert(profile.copy(preferredExerciseCount = targetCount))
            }
        }
    }

    fun setRepRange(repMin: Int, repMax: Int) {
        controller.setRepRange(repMin, repMax)
        if (repMin != preferredRepMin || repMax != preferredRepMax) {
            preferredRepMin = repMin
            preferredRepMax = repMax
            viewModelScope.launch {
                val profile = app.database.userProfileDao().getProfile() ?: return@launch
                app.database.userProfileDao().insert(
                    profile.copy(preferredRepMin = repMin, preferredRepMax = repMax)
                )
            }
        }
    }

    fun clearMessage() { _message.value = null }

    fun addExercise(exerciseId: Long) = controller.addExercise(exerciseId)
    fun loadSavedWorkout(id: Long) = controller.loadSavedWorkout(id)
    fun randomizeWorkout() = controller.randomizeWorkout()
    fun appendSavedWorkout(id: Long) = controller.appendSavedWorkout(id)

    fun saveCurrentPlan(name: String) {
        viewModelScope.launch {
            val saved = controller.saveCurrentPlan(name)
            _message.value = if (saved != null) "Saved \"${saved.displayName}\"" else "Nothing to save"
        }
    }

    fun dismissDetrainingNotice() = controller.dismissDetrainingNotice()
    fun startFirstExercise() = controller.startFirstExercise()
    fun replaceExercise(exerciseId: Long, reason: ExerciseRemovalReason) = controller.replaceExercise(exerciseId, reason)
    fun adjustExerciseWeight(exerciseId: Long, steps: Int) = controller.adjustExerciseWeight(exerciseId, steps)
    fun suggestedWeight(pe: PlannedExercise): Float = controller.suggestedWeight(pe)
    fun setExerciseReps(exerciseId: Long, reps: Int) = controller.setExerciseReps(exerciseId, reps)
    fun resetExerciseReps(exerciseId: Long) = controller.resetExerciseReps(exerciseId)
    fun resetExerciseWeight(exerciseId: Long) = controller.resetExerciseWeight(exerciseId)
    fun moveExercise(from: Int, to: Int) = controller.moveExercise(from, to)
    fun linkExercises(rowIndex: Int) = controller.linkExercises(rowIndex)
    fun unlinkExercises(rowIndex: Int) = controller.unlinkExercises(rowIndex)
    fun setExerciseSets(exerciseId: Long, sets: Int) = controller.setExerciseSets(exerciseId, sets)
    fun completeWarmupSet() = controller.completeWarmupSet()
    fun startTimedSet() = controller.startTimedSet()
    fun recordFeedback(feedback: SetFeedback) = controller.recordFeedback(feedback)
    fun undoLastSet() = controller.undoLastSet()
    fun skipRest() = controller.skipRest()
    fun reduceExerciseWeight(completedReps: Int) = controller.reduceExerciseWeight(completedReps)
    fun completeWorkout() = controller.completeWorkout()
    fun setActiveSetWeight(newWeight: Float) = controller.setActiveSetWeight(newWeight)
    fun swapCurrentExercise(reason: ExerciseRemovalReason) = controller.swapCurrentExercise(reason)
    fun stopWorkout() = controller.stopWorkout()
    fun endCurrentExercise() = controller.endCurrentExercise()

    fun onResumed() {
        if (controller.state.value is WorkoutState.PlanPreview) {
            controller.onLocationRefreshed()
        }
        val done = controller.state.value as? WorkoutState.Done
        if (done != null) stravaController.onResumedWaitingForAuth(done.sessionId, _weightUnit.value)
    }

    fun onExportToStrava() {
        val done = controller.state.value as? WorkoutState.Done ?: return
        stravaController.export(done.sessionId, _weightUnit.value)
    }

    fun onStravaAuthUrlLaunched() = stravaController.onAuthUrlLaunched()
    fun onStravaMessageShown() = stravaController.onMessageShown()

    /** locationId + best-known name, plus a background-geocode request for freshly-seen locations. */
    private data class ResolvedLocation(
        val locationId: Long?,
        val locationName: String?,
        val pendingGeocode: PendingGeocode? = null,
    )

    private data class PendingGeocode(
        val locationId: Long,
        val lat: Double,
        val lng: Double,
        /** Coordinate placeholder written at insert time; a geocode equal to it is a no-op. */
        val placeholder: String,
    )

    private suspend fun resolveLocation(): ResolvedLocation =
        when (val loc = locationService.resolveLocation(app.database)) {
            is LocationResult.Known ->
                ResolvedLocation(loc.locationId, app.database.knownLocationDao().getById(loc.locationId)?.name)
            is LocationResult.Unknown -> {
                // Insert immediately with a coordinate placeholder so the locationId (and thus
                // equipment filtering) is available without waiting on the network geocoder.
                val placeholder = "%.4f, %.4f".format(loc.latitude, loc.longitude)
                val id = app.database.knownLocationDao().insert(
                    KnownLocation(name = placeholder, latitude = loc.latitude, longitude = loc.longitude)
                )
                ResolvedLocation(id, placeholder, PendingGeocode(id, loc.latitude, loc.longitude, placeholder))
            }
            LocationResult.Unavailable -> ResolvedLocation(null, null)
        }

    private suspend fun reverseGeocode(lat: Double, lng: Double): String {
        val fallback = "%.4f, %.4f".format(lat, lng)
        if (!Geocoder.isPresent()) return fallback
        // The async Geocoder listener never fires on emulators / devices without a geocoder
        // backend, which would hang workout start forever. Time out and fall back to coordinates.
        return withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    Geocoder(app).getFromLocation(lat, lng, 1) { addresses ->
                        val addr = addresses.firstOrNull()
                        val name = when {
                            addr?.thoroughfare != null ->
                                listOfNotNull(addr.subThoroughfare, addr.thoroughfare, addr.locality)
                                    .joinToString(" ")
                            addr?.locality != null ->
                                listOfNotNull(addr.locality, addr.adminArea).joinToString(", ")
                            else -> fallback
                        }
                        cont.resume(name)
                    }
                } catch (_: Exception) {
                    cont.resume(fallback)
                }
            }
        } ?: run {
            Log.w(TAG, "reverseGeocode timed out after ${GEOCODE_TIMEOUT_MS}ms; using coordinates")
            fallback
        }
    }

    private fun vibrate() {
        val vibrator = app.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        val effect = VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 80)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 80)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 80)
            .compose()
        vibrator.vibrate(effect)
    }

    companion object {
        private const val TAG = "WorkoutViewModel"
        // Runs in the background off the workout-start path, so we can wait longer for a slow
        // geocoder answer; the bound only exists to avoid leaking a permanently-stalled request.
        private const val GEOCODE_TIMEOUT_MS = 15_000L
        const val DEFAULT_REP_MIN = RepRangePicker.DEFAULT_MIN
        const val DEFAULT_REP_MAX = RepRangePicker.DEFAULT_MAX
    }
}
