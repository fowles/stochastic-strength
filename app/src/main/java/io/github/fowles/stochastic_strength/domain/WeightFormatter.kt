package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

object WeightFormatter {
    fun formatQuantity(reps: Int, isTimed: Boolean): String =
        if (isTimed) "${reps}s" else "$reps reps"

    fun format(kg: Float, unit: WeightUnit): String {
        return if (unit == WeightUnit.KG) {
            "%.1f kg".format(kg)
        } else {
            "%.0f lbs".format(unit.fromKg(kg))
        }
    }

    // Snaps to the nearest 10 kg (or 10 lb) when within 12%, otherwise 5 kg / 5 lb.
    // Avoids fractional plates (1.25/2.5 kg, 2.5 lb) in warmup sets where precision doesn't matter.
    fun roundForWarmup(kg: Float, unit: WeightUnit): Float {
        return if (unit == WeightUnit.KG) {
            val nearest10 = (kg / 10f).roundToInt() * 10f
            if (nearest10 > 0f && abs(nearest10 - kg) / kg <= 0.12f) nearest10
            else (kg / 5f).roundToInt() * 5f
        } else {
            val lbs = unit.fromKg(kg)
            // Bar is 45 lb; plate-loaded weights always end in 5 (45, 55, 65 ...).
            val nearest = ((lbs - 5f) / 10f).roundToInt() * 10f + 5f
            unit.toKg(nearest)
        }
    }

    /** Rounds DOWN to the prescription grid (used when a clear failure ceiling binds). */
    fun roundDown(kg: Float, unit: WeightUnit): Float {
        return if (unit == WeightUnit.KG) {
            floor(kg / 2.5f + 1e-4f) * 2.5f
        } else {
            val lbs = unit.fromKg(kg)
            unit.toKg(floor(lbs / 5f + 1e-4f) * 5f)
        }
    }

    fun round(kg: Float, unit: WeightUnit): Float {
        return if (unit == WeightUnit.KG) {
            (kg / 2.5f).roundToInt() * 2.5f
        } else {
            val lbs = unit.fromKg(kg)
            val roundedLbs = (lbs / 5f).roundToInt() * 5f
            unit.toKg(roundedLbs)
        }
    }

    /** Smallest rounded increment for the user's weight unit, in kg. */
    fun minIncrement(unit: WeightUnit): Float =
        if (unit == WeightUnit.KG) 2.5f else unit.toKg(5f)

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
                val lbs = unit.fromKg(weightKg)
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
