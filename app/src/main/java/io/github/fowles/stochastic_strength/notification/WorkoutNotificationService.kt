package io.github.fowles.stochastic_strength.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.fowles.stochastic_strength.MainActivity
import io.github.fowles.stochastic_strength.R
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.ui.workout.WorkoutNotificationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WorkoutNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notificationManager: NotificationManager
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
        val app = application as StochasticStrengthApp
        collectJob?.cancel()
        collectJob = serviceScope.launch {
            app.workoutNotificationState.collect { state ->
                if (state == null) {
                    stopSelf()
                    return@collect
                }
                notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active Workout",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows workout progress while exercising"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun tapIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Workout active")
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun buildNotification(state: WorkoutNotificationState): Notification = when (state) {
        is WorkoutNotificationState.WarmupSet -> buildWarmupNotification(state)
        is WorkoutNotificationState.ActiveSet -> buildActiveSetNotification(state)
        is WorkoutNotificationState.TimedActiveSet -> buildTimedActiveSetNotification(state)
        is WorkoutNotificationState.Resting -> buildRestingNotification(state)
    }

    private fun buildWarmupNotification(state: WorkoutNotificationState.WarmupSet): Notification {
        val donePi = PendingIntent.getBroadcast(
            this, REQUEST_COMPLETE_WARMUP,
            Intent(ACTION_COMPLETE_WARMUP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Warming up · ${state.exerciseName}")
            .setContentText(state.warmupSetLabel)
            .setOngoing(true)
            .setContentIntent(tapIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Done", donePi)
            .build()
    }

    private fun buildActiveSetNotification(state: WorkoutNotificationState.ActiveSet): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${state.exerciseName} · ${state.setLabel}")
            .setContentText("${state.repsLabel} · ${state.weightLabel}")
            .setOngoing(true)
            .setContentIntent(tapIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "0-1 more", feedbackPendingIntent(SetFeedback.RIR_0_1, REQUEST_RIR_0_1))
            .addAction(0, "2-4 more", feedbackPendingIntent(SetFeedback.RIR_2_4, REQUEST_RIR_2_4))
            .addAction(0, "5+ more", feedbackPendingIntent(SetFeedback.RIR_5_PLUS, REQUEST_RIR_5_PLUS))
            .build()
    }

    private fun buildTimedActiveSetNotification(state: WorkoutNotificationState.TimedActiveSet): Notification {
        val secondsRemaining = state.secondsRemaining
        val displaySeconds = secondsRemaining ?: state.progressMax
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${state.exerciseName} · ${state.setLabel}")
            .setContentText("${displaySeconds}s")
            .setOngoing(true)
            .setContentIntent(tapIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (secondsRemaining != null) {
            builder.setProgress(state.progressMax, secondsRemaining, false)
        } else {
            val startPi = PendingIntent.getBroadcast(
                this, REQUEST_START_TIMED_SET,
                Intent(ACTION_START_TIMED_SET).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Start", startPi)
        }
        return builder.build()
    }

    private fun buildRestingNotification(state: WorkoutNotificationState.Resting): Notification {
        val skipPi = PendingIntent.getBroadcast(
            this, REQUEST_SKIP,
            Intent(ACTION_SKIP_REST).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Resting · ${state.secondsRemaining}s")
            .setContentText(state.upNextLabel)
            .setProgress(state.progressMax, state.secondsRemaining, false)
            .setOngoing(true)
            .setContentIntent(tapIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Skip", skipPi)
            .build()
    }

    private fun feedbackPendingIntent(feedback: SetFeedback, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this, requestCode,
            Intent(ACTION_FEEDBACK).setPackage(packageName)
                .putExtra(EXTRA_FEEDBACK, feedback.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val CHANNEL_ID = "workout_active"
        const val NOTIFICATION_ID = 1001
        const val ACTION_FEEDBACK = "io.github.fowles.stochastic_strength.ACTION_FEEDBACK"
        const val ACTION_SKIP_REST = "io.github.fowles.stochastic_strength.ACTION_SKIP_REST"
        const val ACTION_COMPLETE_WARMUP = "io.github.fowles.stochastic_strength.ACTION_COMPLETE_WARMUP"
        const val ACTION_START_TIMED_SET = "io.github.fowles.stochastic_strength.ACTION_START_TIMED_SET"
        const val EXTRA_FEEDBACK = "feedback"
        private const val REQUEST_RIR_0_1 = 101
        private const val REQUEST_RIR_2_4 = 102
        private const val REQUEST_RIR_5_PLUS = 103
        private const val REQUEST_SKIP = 104
        private const val REQUEST_COMPLETE_WARMUP = 105
        private const val REQUEST_START_TIMED_SET = 106
    }
}
