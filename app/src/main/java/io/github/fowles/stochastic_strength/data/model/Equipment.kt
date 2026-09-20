package io.github.fowles.stochastic_strength.data.model

enum class Equipment {
    BARBELL, DUMBBELL, CABLE_MACHINE, MACHINE, BODYWEIGHT, KETTLEBELL, BAND;

    /**
     * Whether this equipment loads an external weight the user picks. Used only where no
     * coefficient is known (a user-created exercise); the shipped table decides for everything else.
     */
    val canCarryWeight: Boolean
        get() = this != BODYWEIGHT && this != BAND

    fun displayName(): String =
        name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
}
