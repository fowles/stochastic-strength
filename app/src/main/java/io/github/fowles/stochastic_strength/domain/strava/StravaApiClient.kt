package io.github.fowles.stochastic_strength.domain.strava

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

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

    private val client = OkHttpClient()

    fun buildAuthUrl(clientId: String): String {
        val url = "$AUTH_BASE?client_id=$clientId" +
            "&redirect_uri=${android.net.Uri.encode(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=activity:write" +
            "&approval_prompt=auto"
        android.util.Log.d("StravaApiClient", "Auth URL: $url")
        return url
    }

    suspend fun exchangeCode(clientId: String, clientSecret: String, code: String): TokenResponse =
        withContext(Dispatchers.IO) {
            val body = "client_id=$clientId&client_secret=$clientSecret&code=$code&grant_type=authorization_code"
            postForToken(body)
        }

    suspend fun refreshToken(clientId: String, clientSecret: String, refreshToken: String): TokenResponse =
        withContext(Dispatchers.IO) {
            val body = "client_id=$clientId&client_secret=$clientSecret&refresh_token=$refreshToken&grant_type=refresh_token"
            postForToken(body)
        }

    suspend fun uploadFitFile(accessToken: String, fitFile: File, name: String, description: String): String =
        withContext(Dispatchers.IO) {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data_type", "fit")
                .addFormDataPart("name", name)
                .addFormDataPart("description", description)
                .addFormDataPart(
                    "file",
                    fitFile.name,
                    fitFile.asRequestBody("application/octet-stream".toMediaType()),
                )
                .build()

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .header("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: throw IOException("Empty upload response")
            android.util.Log.d("StravaApiClient", "Upload response ${response.code}: $bodyStr")
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
            android.util.Log.d("StravaApiClient", "Poll response ${response.code}: $bodyStr")
            if (!response.isSuccessful) throw IOException("Poll failed ${response.code}: $bodyStr")

            val json = JSONObject(bodyStr)
            val error = if (json.isNull("error")) null else json.optString("error", "")
            if (!error.isNullOrEmpty()) {
                val message = if (error.contains("duplicate", ignoreCase = true))
                    "Already uploaded to Strava"
                else
                    "Strava upload error: $error"
                throw IOException(message)
            }

            if (json.isNull("activity_id")) null else json.getLong("activity_id")
        }

    private fun postForToken(formBody: String): TokenResponse {
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw IOException("Empty token response")
        if (!response.isSuccessful) throw IOException("Token exchange failed ${response.code}: $bodyStr")

        val json = JSONObject(bodyStr)
        return TokenResponse(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAt = json.getLong("expires_at"),
        )
    }
}
