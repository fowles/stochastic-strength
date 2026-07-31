package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel

object StartingWeights {
    private val baselines: Map<Triple<Sex, StrengthLevel, MuscleGroup>, Float> = buildMap {
        fun add(muscle: MuscleGroup, mL: Float, mM: Float, mH: Float, fL: Float, fM: Float, fH: Float) {
            put(Triple(Sex.MALE, StrengthLevel.LOW, muscle), mL)
            put(Triple(Sex.MALE, StrengthLevel.MEDIUM, muscle), mM)
            put(Triple(Sex.MALE, StrengthLevel.HIGH, muscle), mH)
            put(Triple(Sex.FEMALE, StrengthLevel.LOW, muscle), fL)
            put(Triple(Sex.FEMALE, StrengthLevel.MEDIUM, muscle), fM)
            put(Triple(Sex.FEMALE, StrengthLevel.HIGH, muscle), fH)
        }
        // Values are 1RM baselines (10RM × 4/3, rounded to nearest 0.5 kg).
        add(MuscleGroup.CHEST,       40f,   80f, 133.5f,  20f,  46.5f,  80f)
        add(MuscleGroup.BACK,        40f,   80f, 120.0f,  20f,  46.5f,  80f)
        add(MuscleGroup.SHOULDERS,  26.5f,  53.5f, 86.5f, 13.5f,  30f,  53.5f)
        add(MuscleGroup.BICEPS,      20f,   40f,  66.5f,  10f,  23.5f,  40f)
        add(MuscleGroup.TRICEPS,     20f,   40f,  66.5f,  10f,  23.5f,  40f)
        add(MuscleGroup.QUADS,      53.5f, 106.5f, 173.5f, 26.5f, 66.5f, 106.5f)
        add(MuscleGroup.HAMSTRINGS, 53.5f,  93.5f, 146.5f, 26.5f,  60f,  93.5f)
        add(MuscleGroup.GLUTES,     53.5f,  93.5f, 146.5f, 26.5f, 66.5f, 106.5f)
        add(MuscleGroup.CALVES,     33.5f,  66.5f, 100.0f,  20f,  40f,   66.5f)
        add(MuscleGroup.CORE,       26.5f,  53.5f,  80.0f, 13.5f, 26.5f,  46.5f)
    }

    fun baseline(sex: Sex, level: StrengthLevel, muscle: MuscleGroup): Float =
        baselines[Triple(sex, level, muscle)] ?: 0f
}
