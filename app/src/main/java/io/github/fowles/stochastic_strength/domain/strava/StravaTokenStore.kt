package io.github.fowles.stochastic_strength.domain.strava

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class StravaTokenStore(context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        "strava_tokens",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        prefs.edit {
            putString(KEY_ACCESS, accessToken)
            putString(KEY_REFRESH, refreshToken)
            putLong(KEY_EXPIRES_AT, expiresAt)
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    fun getValidAccessToken(): String? {
        val token = prefs.getString(KEY_ACCESS, null)?.takeIf { it.isNotEmpty() } ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return if (expiresAt > System.currentTimeMillis() / 1000 + 60) token else null
    }

    fun isAuthenticated(): Boolean = !getRefreshToken().isNullOrEmpty()

    fun clearTokens() = prefs.edit { clear() }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
