package io.github.fowles.stochastic_strength.data.model

enum class MuscleGroup {
    CHEST, BACK, SHOULDERS, BICEPS, TRICEPS, QUADS, HAMSTRINGS, GLUTES, CALVES, CORE;

    fun displayName(): String =
        name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
}
