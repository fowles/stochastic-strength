package io.github.fowles.stochastic_strength.domain

object ExerciseCoefficients {
    val byName: Map<String, Float> = mapOf(
        // CHEST (reference: Barbell Bench Press)
        "Barbell Bench Press"          to 1.00f,
        "Incline Barbell Bench Press"  to 0.85f,
        "Decline Bench Press"          to 0.95f,
        "Dumbbell Bench Press"         to 0.40f,  // per dumbbell
        "Incline Dumbbell Press"       to 0.35f,  // per dumbbell
        "Dumbbell Fly"                 to 0.25f,
        "Push-Up"                      to 0.00f,
        "Cable Chest Fly"              to 0.25f,
        "Pec Deck"                     to 0.30f,
        // BACK (reference: Barbell Row)
        "Deadlift"                     to 1.50f,
        "Barbell Row"                  to 1.00f,
        "T-Bar Row"                    to 0.90f,
        "Good Morning"                 to 0.60f,
        "Pull-Up"                      to 0.00f,
        "Chin-Up"                      to 0.00f,
        "Lat Pulldown"                 to 0.80f,
        "Seated Cable Row"             to 0.75f,
        "Face Pull"                    to 0.30f,
        "Dumbbell Row"                 to 0.50f,
        "Chest-Supported Dumbbell Row" to 0.45f,
        // SHOULDERS (reference: Overhead Press)
        "Overhead Press"               to 1.00f,
        "Upright Row"                  to 0.70f,
        "Dumbbell Lateral Raise"       to 0.15f,
        "Dumbbell Overhead Press"      to 0.45f,
        "Arnold Press"                 to 0.45f,
        "Rear Delt Fly"                to 0.15f,
        "Front Raise"                  to 0.15f,
        "Cable Lateral Raise"          to 0.15f,
        // BICEPS (reference: Barbell Curl)
        "Barbell Curl"                 to 1.00f,
        "Preacher Curl"                to 0.85f,
        "Dumbbell Curl"                to 0.40f,
        "Hammer Curl"                  to 0.40f,
        "Concentration Curl"           to 0.35f,
        "Cable Curl"                   to 0.60f,
        // TRICEPS (reference: Skull Crusher)
        "Skull Crusher"                to 1.00f,
        "Close-Grip Bench Press"       to 1.30f,
        "Tricep Pushdown"              to 0.50f,
        "Dips"                         to 0.00f,
        "Overhead Tricep Extension"    to 0.35f,
        "Tricep Kickback"              to 0.15f,
        // QUADS (reference: Barbell Squat)
        "Barbell Squat"                to 1.00f,
        "Front Squat"                  to 0.80f,
        "Leg Press"                    to 2.50f,
        "Leg Extension"                to 0.35f,
        "Hack Squat"                   to 1.80f,
        "Lunge"                        to 0.00f,
        "Goblet Squat"                 to 0.25f,
        "Bulgarian Split Squat"        to 0.30f,  // per dumbbell
        // HAMSTRINGS (reference: Romanian Deadlift)
        "Romanian Deadlift"            to 1.00f,
        "Sumo Deadlift"                to 1.40f,
        "Leg Curl"                     to 0.40f,
        "Nordic Curl"                  to 0.00f,
        "Stiff-Leg Deadlift"           to 0.90f,
        // GLUTES (reference: Hip Thrust)
        "Hip Thrust"                   to 1.00f,
        "Cable Kickback"               to 0.10f,
        "Glute Bridge"                 to 0.00f,
        // CALVES (reference: Seated Calf Raise)
        "Standing Calf Raise"          to 0.00f,
        "Seated Calf Raise"            to 1.00f,
        // CORE (reference: Cable Crunch)
        "Plank"                        to 0.00f,
        "Hanging Leg Raise"            to 0.00f,
        "Ab Wheel Rollout"             to 0.00f,
        "Russian Twist"                to 0.00f,
        "Dead Bug"                     to 0.00f,
        "Cable Crunch"                 to 1.00f,
        "Pallof Press"                 to 0.25f,
    )
}
