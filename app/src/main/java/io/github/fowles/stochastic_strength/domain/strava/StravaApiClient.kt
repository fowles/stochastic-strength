package io.github.fowles.stochastic_strength.domain.strava

import android.net.Uri
import android.util.Log
import io.github.fowles.stochastic_strength.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class StravaAuthException(message: String) : java.io.IOException(message)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)

object StravaApiClient {
    private const val REDIRECT_URI = "https://io.github.fowles.stochastic_strength/strava/callback"
    private const val AUTH_BASE = "https://www.strava.com/oauth/authorize"
    private const val TOKEN_URL = "https://www.strava.com/oauth/token"
    private const val UPLOAD_URL = "https://www.strava.com/api/v3/uploads"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun buildAuthUrl(clientId: String): String =
        Uri.Builder()
            .scheme("https")
            .authority("www.strava.com")
            .appendPath("oauth")
            .appendPath("authorize")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "activity:write")
            .appendQueryParameter("approval_prompt", "auto")
            .build()
            .toString()

    suspend fun exchangeCode(clientId: String, clientSecret: String, code: String): TokenResponse =
        withContext(Dispatchers.IO) {
            postForToken(FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .build())
        }

    suspend fun refreshToken(clientId: String, clientSecret: String, refreshToken: String): TokenResponse =
        withContext(Dispatchers.IO) {
            postForToken(FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build())
        }

    suspend fun uploadJson(accessToken: String, jsonBody: String, name: String, description: String): String =
        withContext(Dispatchers.IO) {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data_type", "json")
                .addFormDataPart("sport_type", "WeightTraining")
                .addFormDataPart("name", name)
                .addFormDataPart("description", description)
                .addFormDataPart(
                    "file",
                    "strava_export.json",
                    jsonBody.toByteArray().toRequestBody("application/octet-stream".toMediaType()),
                )
                .build()

            if (BuildConfig.DEBUG) Log.d("StravaApiClient", "JSON upload body: ${jsonBody.take(1000)}")

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .header("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: throw IOException("Empty upload response")
            if (BuildConfig.DEBUG) Log.d("StravaApiClient", "Upload response ${response.code}: $bodyStr")
            if (!response.isSuccessful) throw IOException("Upload failed ${response.code}: $bodyStr")

            val json = JSONObject(bodyStr)
            json.optString("id_str").ifEmpty { json.getLong("id").toString() }
        }

    suspend fun pollUpload(accessToken: String, uploadId: String): Long? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$UPLOAD_URL/$uploadId")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: throw IOException("Empty poll response")
            if (BuildConfig.DEBUG) Log.d("StravaApiClient", "Poll response ${response.code}: $bodyStr")
            if (!response.isSuccessful) throw IOException("Poll failed ${response.code}: $bodyStr")

            val json = JSONObject(bodyStr)
            val error = if (json.isNull("error")) null else json.optString("error", "")
            if (!error.isNullOrEmpty()) {
                val isDuplicate = error.contains("duplicate", ignoreCase = true)
                if (isDuplicate && !json.isNull("activity_id")) {
                    return@withContext json.getLong("activity_id")
                }
                throw IOException(if (isDuplicate) "Already uploaded to Strava" else "Strava upload error: $error")
            }

            if (json.isNull("activity_id")) null else json.getLong("activity_id")
        }

    private fun postForToken(body: FormBody): TokenResponse {
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw IOException("Empty token response")
        if (!response.isSuccessful) {
            val msg = "Token exchange failed ${response.code}: $bodyStr"
            if (response.code == 400 || response.code == 401) throw StravaAuthException(msg)
            throw IOException(msg)
        }

        val json = JSONObject(bodyStr)
        return TokenResponse(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAt = json.getLong("expires_at"),
        )
    }
}
