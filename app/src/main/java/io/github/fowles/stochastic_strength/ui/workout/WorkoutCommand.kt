package io.github.fowles.stochastic_strength.ui.workout

import io.github.fowles.stochastic_strength.data.model.SetFeedback

sealed interface WorkoutCommand {
    data class RecordFeedback(val feedback: SetFeedback) : WorkoutCommand
    data object SkipRest : WorkoutCommand
    data object CompleteWarmupSet : WorkoutCommand
}
