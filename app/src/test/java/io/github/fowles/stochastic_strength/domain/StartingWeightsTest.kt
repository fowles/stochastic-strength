package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import org.junit.Assert.assertTrue
import org.junit.Test

class StartingWeightsTest {

    @Test
    fun allCombinationsReturnPositive() {
        for (sex in Sex.entries) {
            for (level in StrengthLevel.entries) {
                for (muscle in MuscleGroup.entries) {
                    val baseline = StartingWeights.baseline(sex, level, muscle)
                    assertTrue("$sex/$level/$muscle should be positive, was $baseline", baseline > 0f)
                }
            }
        }
    }

    @Test
    fun femaleBaselineAtMostMaleForSameLevelAndMuscle() {
        for (level in StrengthLevel.entries) {
            for (muscle in MuscleGroup.entries) {
                val male = StartingWeights.baseline(Sex.MALE, level, muscle)
                val female = StartingWeights.baseline(Sex.FEMALE, level, muscle)
                assertTrue("$level/$muscle: female ($female) should be <= male ($male)", female <= male)
            }
        }
    }

    @Test
    fun higherStrengthLevelMeansHigherBaseline() {
        for (sex in Sex.entries) {
            for (muscle in MuscleGroup.entries) {
                val low = StartingWeights.baseline(sex, StrengthLevel.LOW, muscle)
                val medium = StartingWeights.baseline(sex, StrengthLevel.MEDIUM, muscle)
                val high = StartingWeights.baseline(sex, StrengthLevel.HIGH, muscle)
                assertTrue("$sex/$muscle: MEDIUM ($medium) should exceed LOW ($low)", medium > low)
                assertTrue("$sex/$muscle: HIGH ($high) should exceed MEDIUM ($medium)", high > medium)
            }
        }
    }
}
