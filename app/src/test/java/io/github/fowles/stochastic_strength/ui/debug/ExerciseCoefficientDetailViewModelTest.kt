package io.github.fowles.stochastic_strength.ui.debug

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.progression.ObservedSet
import io.github.fowles.stochastic_strength.domain.progression.ProgressionFrame
import io.github.fowles.stochastic_strength.domain.progression.SessionExerciseObservation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ExerciseCoefficientDetailViewModelTest {

    @Test fun tooltipStacksNameThenSetsTargetFirst() {
        val obs = listOf(
            SessionExerciseObservation(1L, "Deadlift", listOf(
                ObservedSet(reps = 11, isEstimate = true, weightKg = 56.7f),
                ObservedSet(reps = 11, isEstimate = true, weightKg = 56.7f),
                ObservedSet(reps = 9, isEstimate = false, weightKg = 56.7f),
            )),
        )
        val tip = formatTooltip(obs, WeightUnit.LBS).toString()
        // Name header then one line per set; "~" only on estimates.
        assertEquals("Deadlift\n~11@125 lbs\n~11@125 lbs\n9@125 lbs", tip)
    }

    @Test fun buildFrameViewsKeysByEpochDayAndDefaultsToLatest() {
        val zone = ZoneId.of("UTC")
        val dayMs = 86_400_000L
        val frames = listOf(
            ProgressionFrame(timestampMs = dayMs * 10, own = 100f, siblings = 90f, merged = 95f, crossTuning = emptyList(), observations = emptyList()),
            ProgressionFrame(timestampMs = dayMs * 20, own = 110f, siblings = 92f, merged = 99f, crossTuning = emptyList(), observations = emptyList()),
        )
        val (map, default) = buildFrameViews(frames, WeightUnit.KG, zone)
        assertEquals(2, map.size)
        assertEquals(20L, default)            // latest frame's epoch-day
        assertEquals("110.0 kg", map.getValue(20L).headerOwn)
    }
}
