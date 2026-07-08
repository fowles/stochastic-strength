package io.github.fowles.stochastic_strength.ui.workout

import android.app.Application
import android.content.Intent
import android.location.Geocoder
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WorkoutGenerator
import io.github.fowles.stochastic_strength.location.LocationResult
import io.github.fowles.stochastic_strength.location.LocationService
import io.github.fowles.stochastic_strength.notification.WorkoutNotificationService
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryData
import io.github.fowles.stochastic_strength.ui.loadWorkoutSummary
import io.github.fowles.stochastic_strength.ui.strava.StravaExportController
import io.github.fowles.stochastic_strength.ui.strava.StravaExportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
            val locationId = resolveLocation()
            val locationName = locationId?.let { app.database.knownLocationDao().getById(it)?.name }
            controller.initializeSession(
                locationId = locationId,
                locationName = locationName,
                preferredExerciseCount = preferredExerciseCount,
                preferredRepMin = preferredRepMin,
                preferredRepMax = preferredRepMax,
                weightUnit = _weightUnit.value,
            )
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
                    s is WorkoutState.Done && _doneSummary.value == null ->
                        _doneSummary.value = loadWorkoutSummary(app.database, s.sessionId)
                    s !is WorkoutState.Done ->
                        _doneSummary.value = null
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

    fun startFirstExercise() = controller.startFirstExercise()
    fun replaceExercise(exerciseId: Long, reason: ExerciseRemovalReason) = controller.replaceExercise(exerciseId, reason)
    fun adjustExerciseWeight(exerciseId: Long, delta: Float) = controller.adjustExerciseWeight(exerciseId, delta)
    fun moveExercise(from: Int, to: Int) = controller.moveExercise(from, to)
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

    private suspend fun resolveLocation(): Long? = when (val loc = locationService.resolveLocation(app.database)) {
        is LocationResult.Known -> loc.locationId
        is LocationResult.Unknown -> createLocation(loc.latitude to loc.longitude)
        LocationResult.Unavailable -> null
    }

    private suspend fun createLocation(coords: Pair<Double, Double>): Long =
        app.database.knownLocationDao().insert(
            KnownLocation(
                name = reverseGeocode(coords.first, coords.second),
                latitude = coords.first,
                longitude = coords.second,
            )
        )

    private suspend fun reverseGeocode(lat: Double, lng: Double): String {
        val fallback = "%.4f, %.4f".format(lat, lng)
        if (!Geocoder.isPresent()) return fallback
        return suspendCancellableCoroutine { cont ->
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
        const val DEFAULT_REP_MIN = 5
        const val DEFAULT_REP_MAX = 10
    }
}
