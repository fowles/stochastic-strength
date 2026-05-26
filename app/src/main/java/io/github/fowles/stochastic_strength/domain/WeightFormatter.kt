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

    fun round(kg: Float, unit: WeightUnit): Float {
        return if (unit == WeightUnit.KG) {
            (kg / 2.5f).roundToInt() * 2.5f
        } else {
            val lbs = kg * 2.20462f
            val roundedLbs = (lbs / 5f).roundToInt() * 5f
            roundedLbs / 2.20462f
        }
    }

    fun platesPerSide(weightKg: Float, unit: WeightUnit): String? {
        return when (unit) {
            WeightUnit.KG -> {
                val bar = 20f
                if (weightKg <= bar) return null
                val perSide = (weightKg - bar) / 2f
                val result = distributeWeights(perSide, listOf(20f, 15f, 10f, 5f, 2.5f, 1.25f), "kg") ?: return null
                "Bar + $result per side"
            }
            WeightUnit.LBS -> {
                val lbs = weightKg * 2.20462f
                val bar = 45f
                if (lbs <= bar) return null
                val perSide = (lbs - bar) / 2f
                val result = distributeWeights(perSide, listOf(45f, 35f, 25f, 10f, 5f, 2.5f), "lb") ?: return null
                "Bar + $result per side"
            }
        }
    }

    private fun distributeWeights(perSide: Float, plates: List<Float>, suffix: String): String? {
        val parts = mutableListOf<String>()
        var remaining = perSide
        for (plate in plates) {
            val count = (remaining / plate + 0.001f).toInt()
            if (count > 0) {
                val w = if (plate % 1f == 0f) plate.toInt().toString() else "%.1f".format(plate)
                parts.add(if (count > 1) "${count}× $w $suffix" else "$w $suffix")
                remaining -= plate * count
            }
        }
        if (remaining > 0.2f) return null
        return parts.joinToString(" + ").ifEmpty { null }
    }
}
