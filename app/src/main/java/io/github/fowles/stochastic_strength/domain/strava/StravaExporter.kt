package io.github.fowles.stochastic_strength.domain.strava

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import io.github.fowles.stochastic_strength.BuildConfig
import io.github.fowles.stochastic_strength.R
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

class StravaExporter(
    private val db: AppDatabase,
    private val tokenStore: StravaTokenStore,
    private val fitBuilder: FitFileBuilder,
    private val context: Context,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Strava Export", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Notifications for Strava workout exports" }
        )
    }

    fun notifyUploadResult(success: Boolean, error: String? = null) {
        val (title, text) = if (success)
            "Exported to Strava!" to "Your workout has been uploaded."
        else
            "Strava export failed" to (error ?: "Unknown error")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, title, Toast.LENGTH_SHORT).show()
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(5_000)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    fun getAuthUrl(): String = StravaApiClient.buildAuthUrl(BuildConfig.STRAVA_CLIENT_ID)

    fun isAuthenticated(): Boolean = tokenStore.isAuthenticated()

    suspend fun handleAuthCallback(code: String) {
        val tokens = StravaApiClient.exchangeCode(
            BuildConfig.STRAVA_CLIENT_ID,
            BuildConfig.STRAVA_CLIENT_SECRET,
            code,
        )
        tokenStore.saveTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
    }

    suspend fun exportSession(sessionId: Long, weightUnit: WeightUnit): Long {
        val accessToken = ensureValidToken()

        val session = db.workoutSessionDao().getById(sessionId)
            ?: throw IOException("Session $sessionId not found")
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val exerciseById = exerciseIds
            .mapNotNull { id -> db.exerciseDao().getById(id)?.let { id to it } }
            .toMap()
        val nameById = exerciseById.mapValues { (_, ex) -> ex.name }

        val fitFile = fitBuilder.build(session, sets, nameById)
        context.getExternalFilesDir(null)?.let { dir ->
            fitFile.copyTo(File(dir, fitFile.name), overwrite = true)
        }
        try {
            val durationMs = (session.endTime ?: session.startTime) - session.startTime
            val name = buildWorkoutName(exerciseIds.mapNotNull { exerciseById[it]?.primaryMuscle })
            val description = buildDescription(sets, nameById, durationMs, weightUnit)
            val uploadId = StravaApiClient.uploadFitFile(accessToken, fitFile, name, description)
            repeat(20) {
                delay(1500)
                val activityId = StravaApiClient.pollUpload(accessToken, uploadId)
                if (activityId != null) return activityId
            }
            throw IOException("Timed out waiting for Strava to process the upload")
        } finally {
            fitFile.delete()
        }
    }

    private suspend fun ensureValidToken(): String {
        if (tokenStore.hasValidAccessToken()) {
            return tokenStore.getAccessToken()!!
        }
        val refreshToken = tokenStore.getRefreshToken()
            ?: throw IOException("Not authenticated with Strava")
        val tokens = StravaApiClient.refreshToken(
            BuildConfig.STRAVA_CLIENT_ID,
            BuildConfig.STRAVA_CLIENT_SECRET,
            refreshToken,
        )
        tokenStore.saveTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
        return tokens.accessToken
    }

    private fun buildWorkoutName(@Suppress("UNUSED_PARAMETER") primaryMuscles: List<MuscleGroup>): String {
        val adjectives = listOf(
            "Stochastic", "Capricious", "Serendipitous", "Haphazard", "Aleatory",
            "Mercurial", "Erratic", "Fortuitous", "Whimsical", "Impromptu",
            "Spontaneous", "Arbitrary", "Incidental", "Unpredictable", "Chaotic",
        )
        val strengths = listOf(
            "Power", "Might", "Brawn", "Vigor", "Grit", "Strength",
            "Mettle", "Fortitude", "Prowess", "Sinew", "Tenacity",
        )
        val workouts = listOf(
            "Gauntlet", "Odyssey", "Quest", "Ritual", "Grind", "Workout",
            "Endeavor", "Foray", "Sortie", "Romp", "Reckoning", "Session"
        )
        return "${adjectives.random()} ${strengths.random()} ${workouts.random()}"
    }

    private fun buildDescription(
        sets: List<io.github.fowles.stochastic_strength.data.model.WorkoutSet>,
        nameById: Map<Long, String>,
        durationMs: Long,
        weightUnit: WeightUnit,
    ): String {
        val exerciseIds = sets.map { it.exerciseId }.distinct()
        val sb = StringBuilder()

        for (id in exerciseIds) {
            val name = nameById[id] ?: "Unknown"
            val exerciseSets = sets.filter { it.exerciseId == id }
            val reps = exerciseSets.firstOrNull()?.targetReps ?: 0
            val weight = exerciseSets.firstOrNull()?.targetWeight ?: 0f
            val weightStr = WeightFormatter.format(weight, weightUnit)

            sb.append("$name — ${exerciseSets.size} sets × $reps @ $weightStr\n")
            sb.append("  ")
            sb.append(exerciseSets.joinToString(" ") { feedbackEmoji(it.feedback) })
            sb.append("\n\n")
        }

        val totalSec = durationMs / 1000
        val mins = totalSec / 60
        val secs = totalSec % 60
        sb.append("Duration: $mins:%02d".format(secs))
        sb.append("\n\nUploaded from Stochastic Strength")

        return sb.toString()
    }

    private fun feedbackEmoji(feedback: SetFeedback?): String = when (feedback) {
        SetFeedback.TOO_HARD -> "🔴"
        SetFeedback.HURT -> "🤕"
        SetFeedback.RIR_0_1 -> "✅"
        SetFeedback.RIR_2_4 -> "💪"
        SetFeedback.RIR_5_PLUS -> "😌"
        null -> "—"
    }

    companion object {
        private const val CHANNEL_ID = "strava_export"
        private const val NOTIFICATION_ID = 1002
    }
}
