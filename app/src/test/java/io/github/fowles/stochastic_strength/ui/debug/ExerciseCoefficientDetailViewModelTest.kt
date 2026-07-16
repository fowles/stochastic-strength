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

    @Test fun buildFrameViewsKeysByEpochDayAndDefaultsToPredicted() {
        val zone = ZoneId.of("UTC")
        val dayMs = 86_400_000L
        val trace10 = io.github.fowles.stochastic_strength.domain.belief.PrescriptionTrace(emptyList(), 10f)
        val trace20 = io.github.fowles.stochastic_strength.domain.belief.PrescriptionTrace(emptyList(), 20f)
        val tracePredicted = io.github.fowles.stochastic_strength.domain.belief.PrescriptionTrace(emptyList(), 30f)
        val frames = listOf(
            ProgressionFrame(timestampMs = dayMs * 10, own = 100f, siblings = 90f, merged = 95f, crossTuning = emptyList(), observations = emptyList(), trace = trace10),
            ProgressionFrame(timestampMs = dayMs * 20, own = 110f, siblings = 92f, merged = 99f, crossTuning = emptyList(), observations = emptyList(), trace = trace20),
        )
        val predicted = ProgressionFrame(timestampMs = dayMs * 30, own = 120f, siblings = 95f, merged = 105f, crossTuning = emptyList(), observations = emptyList(), trace = tracePredicted)
        val (map, default) = buildFrameViews(frames, predicted, WeightUnit.KG, zone)
        assertEquals(3, map.size)
        assertEquals(30L, default)            // predicted frame's epoch-day is the default
        assertEquals("110.0 kg", map.getValue(20L).headerOwn)
        assertEquals(30f, map.getValue(30L).trace!!.finalWeightKg, 0f)
        assertEquals(10f, map.getValue(10L).trace!!.finalWeightKg, 0f)
    }
}
