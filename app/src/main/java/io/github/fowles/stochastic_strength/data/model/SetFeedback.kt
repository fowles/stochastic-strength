package io.github.fowles.stochastic_strength.data.model

enum class SetFeedback {
    TOO_HARD, HURT, RIR_0_1, RIR_2_4, RIR_5_PLUS;

    val displayLabel: String get() = when (this) {
        TOO_HARD  -> "Too Heavy"
        HURT      -> "Hurt"
        RIR_0_1   -> "0–1 more"
        RIR_2_4   -> "2–4 more"
        RIR_5_PLUS -> "5+ more"
    }
}
