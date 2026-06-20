package io.github.fowles.stochastic_strength.domain.strava

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StravaJsonBuilderInstrumentedTest {

    @Test
    fun repetitionsUsesActualRepsWhenPresent() {
        val session = WorkoutSession(id = 1L, startTime = 0L, endTime = 1_000L)
        val sets = listOf(
            WorkoutSet(sessionId = 1L, exerciseId = 10L, setNumber = 1,
                targetWeight = 50f, targetReps = 8, actualReps = 4),
            WorkoutSet(sessionId = 1L, exerciseId = 10L, setNumber = 2,
                targetWeight = 50f, targetReps = 8, actualReps = null),
        )

        val json = StravaJsonBuilder().build(session, sets, mapOf(10L to "Barbell Bench Press"))
        val setsArray = JSONObject(json).getJSONArray("sets")

        assertEquals(4, setsArray.getJSONObject(0).getInt("repetitions"))
        assertEquals(8, setsArray.getJSONObject(1).getInt("repetitions"))
    }
}
