package io.github.fowles.stochastic_strength.domain.strava

import io.github.fowles.stochastic_strength.BuildConfig
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import kotlinx.coroutines.delay
import java.io.IOException

class StravaExporter(
    private val db: AppDatabase,
    private val tokenStore: StravaTokenStore,
    private val fitBuilder: FitFileBuilder,
) {
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
        val nameById = exerciseIds
            .mapNotNull { id -> db.exerciseDao().getById(id)?.let { id to it.name } }
            .toMap()

        val fitFile = fitBuilder.build(session, sets, nameById)
        try {
            val durationMs = (session.endTime ?: session.startTime) - session.startTime
            val description = buildDescription(sets, nameById, durationMs, weightUnit)
            val uploadId = StravaApiClient.uploadFitFile(accessToken, fitFile, description)

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
}
