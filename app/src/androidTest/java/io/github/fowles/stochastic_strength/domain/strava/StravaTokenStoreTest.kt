package io.github.fowles.stochastic_strength.domain.strava

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Tink AEAD round-trip against the on-device AndroidKeyStore.
 */
@RunWith(AndroidJUnit4::class)
class StravaTokenStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clearState() {
        context.getSharedPreferences("strava_tokens", 0).edit().clear().commit()
        context.getSharedPreferences("strava_tink_prefs", 0).edit().clear().commit()
    }

    @Before fun setup() = clearState()
    @After fun teardown() = clearState()

    @Test
    fun roundTripsTokensThroughEncryption() {
        val store = StravaTokenStore(context)
        val future = System.currentTimeMillis() / 1000 + 3600

        store.saveTokens("access-abc", "refresh-xyz", future)

        assertEquals("access-abc", store.getAccessToken())
        assertEquals("refresh-xyz", store.getRefreshToken())
        assertEquals("access-abc", store.getValidAccessToken())
        assertTrue(store.isAuthenticated())
    }

    @Test
    fun ciphertextAtRestIsNotThePlaintext() {
        StravaTokenStore(context).saveTokens("super-secret", "refresh-secret", 0L)

        val raw = context.getSharedPreferences("strava_tokens", 0)
            .getString("access_token", null)
        assertFalse(raw.isNullOrEmpty())
        assertFalse(raw!!.contains("super-secret"))
    }

    @Test
    fun persistsAcrossInstances() {
        val future = System.currentTimeMillis() / 1000 + 3600
        StravaTokenStore(context).saveTokens("access-1", "refresh-1", future)

        // A fresh instance must reuse the same keyset/master key to decrypt.
        val reopened = StravaTokenStore(context)
        assertEquals("access-1", reopened.getAccessToken())
        assertEquals("refresh-1", reopened.getRefreshToken())
    }

    @Test
    fun expiredAccessTokenIsWithheld() {
        val past = System.currentTimeMillis() / 1000 - 3600
        val store = StravaTokenStore(context)
        store.saveTokens("access-expired", "refresh-live", past)

        assertNull(store.getValidAccessToken())
        assertEquals("access-expired", store.getAccessToken()) // still decryptable
        assertTrue(store.isAuthenticated())
    }

    @Test
    fun clearRemovesTokens() {
        val store = StravaTokenStore(context)
        store.saveTokens("a", "b", 0L)
        store.clearTokens()

        assertNull(store.getAccessToken())
        assertNull(store.getRefreshToken())
        assertFalse(store.isAuthenticated())
    }
}
