package io.github.fowles.stochastic_strength.data.model

enum class WeightUnit {
    KG, LBS;

    fun fromKg(kg: Float): Float = if (this == KG) kg else kg * KG_TO_LBS
    fun toKg(display: Float): Float = if (this == KG) display else display / KG_TO_LBS

    companion object {
        const val KG_TO_LBS = 2.20462f
    }
}
