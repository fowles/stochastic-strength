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
        add(MuscleGroup.CHEST,       30f,  60f, 100f,  15f,  35f,  60f)
        add(MuscleGroup.BACK,        30f,  60f,  90f,  15f,  35f,  60f)
        add(MuscleGroup.SHOULDERS,   20f,  40f,  65f,  10f, 22.5f, 40f)
        add(MuscleGroup.BICEPS,      15f,  30f,  50f,  7.5f, 17.5f, 30f)
        add(MuscleGroup.TRICEPS,     15f,  30f,  50f,  7.5f, 17.5f, 30f)
        add(MuscleGroup.QUADS,       40f,  80f, 130f,  20f,  50f,  80f)
        add(MuscleGroup.HAMSTRINGS,  40f,  70f, 110f,  20f,  45f,  70f)
        add(MuscleGroup.GLUTES,      40f,  70f, 110f,  20f,  50f,  80f)
        add(MuscleGroup.CALVES,       0f,   0f,   0f,   0f,   0f,   0f)
        add(MuscleGroup.CORE,         0f,   0f,   0f,   0f,   0f,   0f)
    }

    fun baseline(sex: Sex, level: StrengthLevel, muscle: MuscleGroup): Float =
        baselines[Triple(sex, level, muscle)] ?: 0f
}
