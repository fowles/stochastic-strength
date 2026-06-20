package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.model.WarmupSet
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationCalculatorTest {

    private fun exercise(
        equipment: Equipment = Equipment.BARBELL,
        isTimed: Boolean = false,
        isUnilateral: Boolean = false,
    ) = Exercise(
        id = 1L,
        name = "Ex",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = equipment,
        isTimed = isTimed,
        isUnilateral = isUnilateral,
    )

    @Test
    fun bodyweight_threeSetsTenReps_noWarmups_usesDefaultPerRep() {
        // Bodyweight: plateChangeSec = 0. perRep = 3 (default).
        // working = 3 * (3*10 + 90) = 360. warmups/plate = 0. Total = 360.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BODYWEIGHT),
            sessionReps = 10,
            numSets = 3,
            warmupSets = emptyList(),
            secondsPerRep = null,
        )
        assertEquals(360, seconds)
    }

    @Test
    fun barbell_threeSetsTenReps_twoWarmups_includesPlateChanges() {
        // perRep = 3. working = 3 * (30 + 90) = 360.
        // warmup work = (8 + 5) * 3 = 39. warmup rest = 2 * 30 = 60.
        // plate changes = 25 * (2 + 1) = 75. Total = 360 + 39 + 60 + 75 = 534.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BARBELL),
            sessionReps = 10,
            numSets = 3,
            warmupSets = listOf(WarmupSet(40f, 8), WarmupSet(60f, 5)),
            secondsPerRep = null,
        )
        assertEquals(534, seconds)
    }

    @Test
    fun barbell_repsSweep_scalesWorkLinearly() {
        // numSets = 3, perRep = 3, no warmups, plate = 25 * (0+1) = 25.
        // estimate = 3 * (reps*3 + 90) + 25
        val expected = mapOf(
            1 to 304, 3 to 322, 5 to 340, 8 to 367, 10 to 385, 15 to 430, 20 to 475,
        )
        for ((reps, want) in expected) {
            val got = DurationCalculator.estimate(
                exercise = exercise(equipment = Equipment.BARBELL),
                sessionReps = reps,
                numSets = 3,
                warmupSets = emptyList(),
                secondsPerRep = null,
            )
            assertEquals("reps=$reps", want, got)
        }
    }

    @Test
    fun unilateral_doublesWorkTime() {
        // perRep = 3, sides = 2. workPerSet = 3*10*2 = 60.
        // working = 3 * (60 + 90) = 450. plate = 25. Total = 475.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BARBELL, isUnilateral = true),
            sessionReps = 10,
            numSets = 3,
            warmupSets = emptyList(),
            secondsPerRep = null,
        )
        assertEquals(475, seconds)
    }

    @Test
    fun timed_usesSessionRepsAsDurationAndIgnoresLearning() {
        // sessionReps = 60 seconds. numSets = 3. Total = 3 * (60 + 90) = 450.
        // secondsPerRep override should be ignored for timed.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BODYWEIGHT, isTimed = true),
            sessionReps = 60,
            numSets = 3,
            warmupSets = emptyList(),
            secondsPerRep = 5.0f,
        )
        assertEquals(450, seconds)
    }

    @Test
    fun learnedSecondsPerRep_overridesDefault() {
        // Same shape as bodyweight test but with perRep = 5 instead of default 3.
        // working = 3 * (5*10 + 90) = 420. Total = 420.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BODYWEIGHT),
            sessionReps = 10,
            numSets = 3,
            warmupSets = emptyList(),
            secondsPerRep = 5.0f,
        )
        assertEquals(420, seconds)
    }

    @Test
    fun plateChangeSec_matchesTable() {
        assertEquals(25, DurationCalculator.plateChangeSec(Equipment.BARBELL))
        assertEquals(8, DurationCalculator.plateChangeSec(Equipment.DUMBBELL))
        assertEquals(5, DurationCalculator.plateChangeSec(Equipment.KETTLEBELL))
        assertEquals(5, DurationCalculator.plateChangeSec(Equipment.MACHINE))
        assertEquals(5, DurationCalculator.plateChangeSec(Equipment.CABLE_MACHINE))
        assertEquals(0, DurationCalculator.plateChangeSec(Equipment.BODYWEIGHT))
        assertEquals(0, DurationCalculator.plateChangeSec(Equipment.BAND))
    }
}
