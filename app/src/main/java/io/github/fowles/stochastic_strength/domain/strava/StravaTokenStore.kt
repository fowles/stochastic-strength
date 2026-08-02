package io.github.fowles.stochastic_strength.domain.strava

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException

/**
 * Persists Strava OAuth tokens encrypted at rest.
 *
 * Tink holds an AES256-GCM keyset in private SharedPreferences, itself wrapped by a
 * master key in the AndroidKeyStore; we AEAD-encrypt each token string and store the
 * base64 ciphertext in plain SharedPreferences. Replaces the deprecated
 * androidx.security:security-crypto EncryptedSharedPreferences.
 */
class StravaTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("strava_tokens", Context.MODE_PRIVATE)

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun saveTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        prefs.edit {
            putString(KEY_ACCESS, encrypt(accessToken))
            putString(KEY_REFRESH, encrypt(refreshToken))
            putLong(KEY_EXPIRES_AT, expiresAt) // not secret; stored plain
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)?.let(::decryptOrNull)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)?.let(::decryptOrNull)

    fun getValidAccessToken(): String? {
        val token = getAccessToken()?.takeIf { it.isNotEmpty() } ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return if (expiresAt > System.currentTimeMillis() / 1000 + 60) token else null
    }

    fun isAuthenticated(): Boolean = !getRefreshToken().isNullOrEmpty()

    fun clearTokens() = prefs.edit { clear() }

    private fun encrypt(plaintext: String): String =
        Base64.encodeToString(
            aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA),
            Base64.NO_WRAP,
        )

    // Tink verifies the GCM tag on decrypt; any failure (tamper, or a missing keyset
    // after a device restore) throws. Tokens are revocable, so treat that as logged-out
    // rather than crashing — the user simply re-connects to Strava.
    private fun decryptOrNull(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        String(aead.decrypt(bytes, ASSOCIATED_DATA), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null // malformed base64
    }

    companion object {
        private const val KEYSET_NAME = "strava_tink_keyset"
        private const val KEYSET_PREF_FILE = "strava_tink_prefs"
        private const val MASTER_KEY_URI = "android-keystore://strava_token_master_key"
        private val ASSOCIATED_DATA = ByteArray(0)
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
