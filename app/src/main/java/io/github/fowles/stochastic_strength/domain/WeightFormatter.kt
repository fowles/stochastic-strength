package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import kotlin.math.roundToInt

object WeightFormatter {
    fun format(kg: Float, unit: WeightUnit): String {
        return if (unit == WeightUnit.KG) {
            "%.1f kg".format(kg)
        } else {
            val lbs = kg * 2.20462f
            "%.0f lbs".format(lbs)
        }
    }

    /**
     * Rounds a KG weight to the nearest increment appropriate for the user's unit.
     */
    fun round(kg: Float, unit: WeightUnit): Float {
        return if (unit == WeightUnit.KG) {
            (kg / 2.5f).roundToInt() * 2.5f
        } else {
            val lbs = kg * 2.20462f
            val roundedLbs = (lbs / 5f).roundToInt() * 5f
            roundedLbs / 2.20462f
        }
    }
}
