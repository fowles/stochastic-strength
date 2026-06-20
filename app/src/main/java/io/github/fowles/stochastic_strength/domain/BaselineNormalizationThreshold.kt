package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit

object BaselineNormalizationThreshold {
    fun forUnit(unit: WeightUnit): Float = when (unit) {
        WeightUnit.KG -> 2f
        WeightUnit.LBS -> 5f
    }
}
