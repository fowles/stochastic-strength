package io.github.fowles.stochastic_strength.domain.strava

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StravaUploadBodyTest {
    @Test
    fun externalIdIsKeyedOnSession() {
        // Distinct sessions must produce distinct external ids so Strava does not
        // collapse two different workouts onto one activity.
        assertEquals(
            "stochastic-strength-session-11",
            StravaApiClient.uploadExternalId(11),
        )
        assertTrue(
            StravaApiClient.uploadExternalId(10) != StravaApiClient.uploadExternalId(11),
        )
    }

    @Test
    fun uploadBodyIncludesExternalIdPart() {
        val body = StravaApiClient.buildUploadBody(
            jsonBody = """{"version":"1.0"}""",
            name = "Test Workout",
            description = "desc",
            externalId = "stochastic-strength-session-11",
        )
        val buffer = Buffer()
        body.writeTo(buffer)
        val serialized = buffer.readUtf8()

        assertTrue(
            "multipart body should carry the external_id form field",
            serialized.contains("name=\"external_id\""),
        )
        assertTrue(
            "multipart body should carry the session external id value",
            serialized.contains("stochastic-strength-session-11"),
        )
    }
}
