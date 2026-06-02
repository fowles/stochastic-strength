package io.github.fowles.stochastic_strength.ui.strava

sealed interface StravaExportState {
    val isBusy: Boolean get() = false
    val undoBlocked: Boolean get() = isBusy

    object Idle : StravaExportState
    data class NeedsAuth(val authUrl: String) : StravaExportState
    object WaitingForAuth : StravaExportState { override val isBusy = true }
    object Exporting : StravaExportState { override val isBusy = true }
    data class Success(val activityId: Long) : StravaExportState { override val undoBlocked = true }
    data class Error(val message: String) : StravaExportState
}
