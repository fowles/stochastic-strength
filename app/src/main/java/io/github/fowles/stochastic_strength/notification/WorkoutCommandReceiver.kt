package io.github.fowles.stochastic_strength.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.ui.workout.WorkoutCommand
import kotlinx.coroutines.launch

class WorkoutCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as StochasticStrengthApp
        val command = when (intent.action) {
            WorkoutNotificationService.ACTION_FEEDBACK -> {
                val feedbackName = intent.getStringExtra(WorkoutNotificationService.EXTRA_FEEDBACK)
                    ?: return
                val feedback = runCatching { SetFeedback.valueOf(feedbackName) }.getOrNull()
                    ?: return
                WorkoutCommand.RecordFeedback(feedback)
            }
            WorkoutNotificationService.ACTION_SKIP_REST -> WorkoutCommand.SkipRest
            WorkoutNotificationService.ACTION_COMPLETE_WARMUP -> WorkoutCommand.CompleteWarmupSet
            WorkoutNotificationService.ACTION_START_TIMED_SET -> WorkoutCommand.StartTimedSet
            else -> return
        }
        app.applicationScope.launch {
            app.workoutSessionBus.commandFlow.emit(command)
        }
    }
}
