package io.github.fowles.stochastic_strength.ui.workout

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class WorkoutSessionBus {
    val notificationState = MutableStateFlow<WorkoutNotificationState?>(null)
    val commandFlow = MutableSharedFlow<WorkoutCommand>(extraBufferCapacity = 8)
}
