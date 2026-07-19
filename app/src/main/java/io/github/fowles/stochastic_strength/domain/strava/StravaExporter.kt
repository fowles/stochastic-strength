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
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import kotlin.random.Random

class StravaExporter(
    private val db: AppDatabase,
    private val tokenStore: StravaTokenStore,
    private val jsonBuilder: StravaJsonBuilder,
    private val workoutRepository: WorkoutRepository,
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

    fun clearTokens() = tokenStore.clearTokens()

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
        val exerciseById = db.exerciseDao().getByIds(exerciseIds).associateBy { it.id }
        val nameById = exerciseById.mapValues { (_, ex) -> ex.name }

        val durationMs = (session.endTime ?: session.startTime) - session.startTime
        val name = buildWorkoutName()
        val highlight = workoutRepository.buildSessionHighlight(
            sessionId = sessionId,
            weightUnit = weightUnit,
            nowMs = System.currentTimeMillis(),
            random = Random(sessionId),
        )
        val description = buildDescription(highlight, sets, exerciseById, durationMs, weightUnit)
        val jsonBody = jsonBuilder.build(session, sets, nameById)
        val uploadId = StravaApiClient.uploadJson(accessToken, jsonBody, name, description)
        repeat(20) {
            val activityId = StravaApiClient.pollUpload(accessToken, uploadId)
            if (activityId != null) return activityId
            delay(1500)
        }
        throw IOException("Timed out waiting for Strava to process the upload")
    }

    private suspend fun ensureValidToken(): String {
        tokenStore.getValidAccessToken()?.let { return it }
        val refreshToken = tokenStore.getRefreshToken()
            ?: throw IOException("Not authenticated with Strava")
        try {
            val tokens = StravaApiClient.refreshToken(
                BuildConfig.STRAVA_CLIENT_ID,
                BuildConfig.STRAVA_CLIENT_SECRET,
                refreshToken,
            )
            tokenStore.saveTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
            return tokens.accessToken
        } catch (e: StravaAuthException) {
            tokenStore.clearTokens()
            throw e
        }
    }

    private fun buildWorkoutName(): String =
        "${ADJECTIVES.random()} ${STRENGTHS.random()} ${WORKOUT_NOUNS.random()}"

    companion object {
        private const val CHANNEL_ID = "strava_export"
        private const val NOTIFICATION_ID = 1002

        /**
         * Pure builder for the Strava activity description: an optional inspirational
         * highlight at the top, then each exercise (in workout order — [sets] is grouped
         * by first appearance), then duration and footer.
         */
        internal fun buildDescription(
            highlight: String,
            sets: List<WorkoutSet>,
            exerciseById: Map<Long, Exercise>,
            durationMs: Long,
            weightUnit: WeightUnit,
        ): String {
            // groupBy preserves first-appearance order, so exercises list in workout order.
            val setsByExercise = sets.groupBy { it.exerciseId }
            val sb = StringBuilder()

            if (highlight.isNotBlank()) {
                sb.append(highlight).append("\n\n")
            }

            for ((id, exerciseSets) in setsByExercise) {
                val exercise = exerciseById[id] ?: continue
                sb.append(exercise.name).append('\n')
                for (set in exerciseSets) {
                    val quantity = if (set.durationSeconds != null) "${set.durationSeconds}s"
                        else formatQuantity(set.actualReps ?: set.targetReps, exercise.isTimed)
                    val weightSuffix = if (set.targetWeight > 0f)
                        " @ ${WeightFormatter.format(set.targetWeight, weightUnit)}"
                    else ""
                    sb.append("$quantity$weightSuffix - ${feedbackEmoji(set.feedback)}\n")
                }
                sb.append('\n')
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

        private val ADJECTIVES = listOf(
            "Stochastic", "Capricious", "Serendipitous", "Haphazard", "Aleatory",
            "Mercurial", "Erratic", "Fortuitous", "Whimsical", "Impromptu",
            "Spontaneous", "Arbitrary", "Incidental", "Unpredictable", "Chaotic",
            "Probabilistic", "Nondeterministic", "Random", "Entropic", "Brownian",
            "Quantum", "Unforeseen", "Improvised", "Freewheeling", "Extemporaneous",
            "Wayward", "Untamed", "Emergent", "Turbulent", "Kaleidoscopic",
            "Roving", "Vagrant", "Dicey", "Monte-Carlo", "Unscripted",
            "Bayesian", "Markovian", "Ergodic", "Annealed", "Heuristic",
            "Volatile", "Scattershot", "Slapdash", "Peripatetic", "Rambunctious",
            "Untrammeled", "Feral", "Anarchic", "Herculean", "Promethean",
            "Yoked Galileo's", "Diesel Tycho Brahe's", "Massive Marie Curie's",
            "Jacked Ada Lovelace's", "Ripped Ramanujan's",
        )
        private val STRENGTHS = listOf(
            "Power", "Might", "Brawn", "Vigor", "Grit", "Strength",
            "Mettle", "Fortitude", "Prowess", "Sinew", "Tenacity",
            "Muscle", "Thew", "Potency", "Puissance", "Iron",
            "Steel", "Moxie", "Gumption", "Hustle", "Ferocity",
            "Wrath", "Gains", "Swole", "Heft", "Oomph",
            "Vim", "Verve", "Torque", "Payload", "Clout",
            "Hypertrophy", "Wallop", "Beef", "Bulk", "Pep",
            "Zeal", "Chutzpah", "Horsepower", "Wattage", "Voltage",
            "Thunder", "Fury", "Valor", "Dynamism", "Momentum",
            "Leverage", "Traction", "Stoutness", "Gusto",
        )
        private val WORKOUT_NOUNS = listOf(
            "Gauntlet", "Odyssey", "Quest", "Ritual", "Grind", "Workout",
            "Endeavor", "Foray", "Sortie", "Romp", "Reckoning", "Session",
            "Escapade", "Expedition", "Pilgrimage", "Crusade", "Campaign",
            "Excursion", "Caper", "Undertaking", "Saga", "Voyage",
            "Jaunt", "Symposium", "Ordeal", "Bacchanal", "Communion",
            "Séance", "Summit", "Tribunal", "Recital", "Pageant",
            "Rite", "Vigil", "Jubilee", "Jamboree", "Hootenanny",
            "Rumpus", "Shindig", "Kerfuffle", "Melee", "Skirmish",
            "Siege", "Conclave", "Audit", "Inquest", "Referendum",
            "Filibuster", "Safari", "Walkabout",
        )
    }
}
