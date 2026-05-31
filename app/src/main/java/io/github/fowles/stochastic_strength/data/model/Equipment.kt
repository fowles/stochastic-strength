package io.github.fowles.stochastic_strength.data.model

enum class Equipment {
    BARBELL, DUMBBELL, CABLE_MACHINE, MACHINE, BODYWEIGHT, KETTLEBELL, BAND;

    fun displayName(): String =
        name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
}
