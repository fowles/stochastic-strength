package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise

/**
 * The shipped runtime coefficient table: per-exercise 1RM ratios vs. each muscle's reference lift.
 *
 * These are a fitted artifact. The legible round-number priors and the structural compression that
 * shaped them (`coef = guess^0.75`, which pulls extreme guesses toward the reference to correct the
 * over-confidence of hand-picked ratios) now live only in the test tree — see `CoefficientGuesses`
 * and `CoefficientCompression`, which the λ sweep (`CoefExponentFitTest`) still uses as an analysis
 * tool. What ships here is the already-baked result, so there is no runtime compression step. The
 * `ExerciseCoefficientsTest` consistency guard verifies these literals still equal
 * `compress(CoefficientGuesses.raw, 0.75)`. Reference (1.0) and bodyweight (0.0) lifts are anchors,
 * unchanged by compression. Editing the table is a pure code change — nothing coefficient-derived is
 * stored per user, so no migration is needed.
 */
object ExerciseCoefficients : CoefficientSource {
    val byName: Map<String, Float> = mapOf(
        // CHEST (reference: Barbell Bench Press)
        "Barbell Bench Press"             to 1.0f,
        "Incline Barbell Bench Press"     to 0.88524646f,
        "Decline Bench Press"             to 0.9622606f,
        "Dumbbell Bench Press"            to 0.5029734f,
        "Incline Dumbbell Press"          to 0.45504153f,
        "Dumbbell Fly"                    to 0.40536007f,
        "Push-Up"                         to 0.0f,
        "Burpee"                          to 0.0f,
        "Cable Chest Fly"                 to 0.45504153f,
        "Pec Deck"                        to 0.5029734f,
        "Machine Chest Press"             to 0.92402107f,
        "Banded Chest Press"              to 0.0f,
        // BACK (reference: Barbell Row)
        "Barbell Row"                     to 1.0f,
        "T-Bar Row"                       to 0.92402107f,
        "Pull-Up"                         to 0.0f,
        "Chin-Up"                         to 0.0f,
        "Inverted Row"                    to 0.0f,
        "Superman"                        to 0.0f,
        "Back Extension"                  to 0.0f,
        "Lat Pulldown"                    to 0.845897f,
        "Seated Cable Row"                to 0.80592746f,
        "Face Pull"                       to 0.35355338f,
        "Dumbbell Row"                    to 0.68173164f,
        "Chest-Supported Dumbbell Row"    to 0.5494262f,
        "Straight-Arm Pulldown"           to 0.40536007f,
        "Band Pull-Apart"                 to 0.0f,
        "Banded Row"                      to 0.0f,
        // SHOULDERS (reference: Overhead Press)
        "Overhead Press"                  to 1.0f,
        "Upright Row"                     to 0.76528555f,
        "Dumbbell Lateral Raise"          to 0.24102853f,
        "Dumbbell Overhead Press"         to 0.45504153f,
        "Arnold Press"                    to 0.45504153f,
        "Rear Delt Fly"                   to 0.24102853f,
        "Front Raise"                     to 0.2038853f,
        "Cable Lateral Raise"             to 0.24102853f,
        "Machine Lateral Raise"           to 0.29906976f,
        "Push Press"                      to 1.1465313f,
        "Landmine Press"                  to 0.59460354f,
        "Pike Push-Up"                    to 0.0f,
        "Banded Lateral Raise"            to 0.0f,
        "External Rotation"               to 0.0f,
        "Kettlebell Clean and Press"      to 0.5029734f,
        // BICEPS (reference: Barbell Curl)
        "Barbell Curl"                    to 1.0f,
        "Preacher Curl"                   to 0.88524646f,
        "Dumbbell Curl"                   to 0.5494262f,
        "Hammer Curl"                     to 0.5494262f,
        "Concentration Curl"              to 0.45504153f,
        "Cable Curl"                      to 0.68173164f,
        "Banded Curl"                     to 0.0f,
        "EZ Bar Curl"                     to 0.92402107f,
        "Incline Dumbbell Curl"           to 0.45504153f,
        // TRICEPS (reference: Skull Crusher)
        "Skull Crusher"                   to 1.0f,
        "Close-Grip Bench Press"          to 1.2174679f,
        "Tricep Pushdown"                 to 0.68173164f,
        "Dips"                            to 0.0f,
        "Diamond Push-Up"                 to 0.0f,
        "Overhead Tricep Extension"       to 0.45504153f,
        "Tricep Kickback"                 to 0.40536007f,
        "Cable Overhead Tricep Extension" to 0.5029734f,
        "Banded Tricep Extension"         to 0.0f,
        // QUADS (reference: Barbell Squat)
        "Barbell Squat"                   to 1.0f,
        "Front Squat"                     to 0.845897f,
        "Leg Press"                       to 1.9881768f,
        "Leg Extension"                   to 0.5494262f,
        "Hack Squat"                      to 1.5540121f,
        "Lunge"                           to 0.0f,
        "Walking Lunge"                   to 0.0f,
        "Reverse Lunge"                   to 0.0f,
        "Dumbbell Lunge"                  to 0.35355338f,
        "Bodyweight Squat"                to 0.0f,
        "Banded Squat"                    to 0.0f,
        "Jump Squat"                      to 0.0f,
        "Wall Sit"                        to 0.0f,
        "Goblet Squat"                    to 0.45504153f,
        "Bulgarian Split Squat"           to 0.40536007f,
        "Step-Up"                         to 0.29906976f,
        // HAMSTRINGS (reference: Romanian Deadlift)
        "Deadlift"                        to 1.252421f,
        "Romanian Deadlift"               to 1.0f,
        "Sumo Deadlift"                   to 1.1821771f,
        "Good Morning"                    to 0.6386634f,
        "Leg Curl"                        to 0.59460354f,
        "Nordic Curl"                     to 0.0f,
        "Single-Leg Romanian Deadlift"    to 0.35355338f,
        "Stiff-Leg Deadlift"              to 0.92402107f,
        // GLUTES (reference: Hip Thrust)
        "Hip Thrust"                      to 1.0f,
        "Cable Kickback"                  to 0.17782794f,
        "Glute Bridge"                    to 0.0f,
        "Single-Leg Glute Bridge"         to 0.0f,
        "Donkey Kick"                     to 0.0f,
        "Lateral Band Walk"               to 0.0f,
        "Clamshell"                       to 0.0f,
        "Kettlebell Swing"                to 0.45504153f,
        // CALVES (reference: Seated Calf Raise)
        "Standing Calf Raise"             to 0.0f,
        "Seated Calf Raise"               to 1.0f,
        "Leg Press Calf Raise"            to 1.6817929f,
        // CORE (reference: Cable Crunch)
        "Plank"                           to 0.0f,
        "Mountain Climber"                to 0.0f,
        "Bicycle Crunch"                  to 0.0f,
        "Sit-Up"                          to 0.0f,
        "Hanging Leg Raise"               to 0.0f,
        "Ab Wheel Rollout"                to 0.0f,
        "Russian Twist"                   to 0.0f,
        "Dead Bug"                        to 0.0f,
        "Cable Crunch"                    to 1.0f,
        "Pallof Press"                    to 0.35355338f,
        "Turkish Get-Up"                  to 0.45504153f,
        "Farmer's Carry"                  to 0.59460354f,
        "Suitcase Carry"                  to 0.5494262f,
    )

    override fun get(exercise: Exercise): Float? = byName[exercise.name]
}
