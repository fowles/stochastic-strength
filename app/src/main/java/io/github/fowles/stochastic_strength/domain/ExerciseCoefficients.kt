package io.github.fowles.stochastic_strength.domain

object ExerciseCoefficients {
    val byName: Map<String, Float> = mapOf(
        // CHEST
        "Barbell Bench Press"          to 1.00f,
        "Incline Barbell Bench Press"  to 0.85f,
        "Dumbbell Fly"                 to 0.25f,
        "Push-Up"                      to 0.00f,
        "Cable Chest Fly"              to 0.25f,
        // BACK
        "Barbell Row"                  to 1.00f,
        "Pull-Up"                      to 0.00f,
        "Lat Pulldown"                 to 0.80f,
        "Seated Cable Row"             to 0.75f,
        "Dumbbell Row"                 to 0.50f,
        "Face Pull"                    to 0.30f,
        // SHOULDERS
        "Overhead Press"               to 1.00f,
        "Dumbbell Lateral Raise"       to 0.15f,
        "Dumbbell Overhead Press"      to 0.45f,
        "Arnold Press"                 to 0.45f,
        // BICEPS
        "Barbell Curl"                 to 1.00f,
        "Dumbbell Curl"                to 0.40f,
        "Cable Curl"                   to 0.60f,
        // TRICEPS
        "Skull Crusher"                to 1.00f,
        "Tricep Pushdown"              to 0.50f,
        "Dips"                         to 0.00f,
        "Overhead Tricep Extension"    to 0.35f,
        // QUADS
        "Barbell Squat"                to 1.00f,
        "Leg Press"                    to 2.50f,
        "Lunge"                        to 0.00f,
        "Goblet Squat"                 to 0.25f,
        // HAMSTRINGS
        "Romanian Deadlift"            to 1.00f,
        "Leg Curl"                     to 0.40f,
        "Stiff-Leg Deadlift"           to 0.90f,
        // GLUTES
        "Hip Thrust"                   to 1.00f,
        "Cable Kickback"               to 0.10f,
        // CALVES (reference: Seated Calf Raise)
        "Standing Calf Raise"          to 0.00f,
        "Seated Calf Raise"            to 1.00f,
        // CORE (reference: Cable Crunch)
        "Plank"                        to 0.00f,
        "Hanging Leg Raise"            to 0.00f,
        "Cable Crunch"                 to 1.00f,
    )
}
