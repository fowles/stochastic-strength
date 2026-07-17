package io.github.fowles.stochastic_strength.domain.strava

import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class StravaJsonBuilder {
    fun build(
        session: WorkoutSession,
        sets: List<WorkoutSet>,
        nameById: Map<Long, String>,
    ): String {
        val startMs = session.startTime
        val endMs = session.endTime ?: startMs
        val elapsedSec = ((endMs - startMs) / 1000).coerceAtLeast(0).toInt()

        val zoneId = ZoneId.systemDefault()
        val startInstant = Instant.ofEpochMilli(startMs).truncatedTo(ChronoUnit.SECONDS)
        val startTimeStr = DateTimeFormatter.ISO_INSTANT.format(startInstant)
        val utcOffsetSec = zoneId.rules.getOffset(startInstant).totalSeconds

        val setsArray = JSONArray()
        for (set in sets) {
            val setObj = JSONObject()
            setObj.put("exercise_type", exerciseNameToJsonType(nameById[set.exerciseId] ?: ""))
            if (set.durationSeconds != null) {
                setObj.put("duration", set.durationSeconds)
            } else {
                setObj.put("repetitions", set.actualReps ?: set.targetReps)
            }
            if (set.targetWeight > 0f) setObj.put("weight", set.targetWeight.toDouble())
            set.completedAt?.let {
                setObj.put("start_time", DateTimeFormatter.ISO_INSTANT.format(
                    Instant.ofEpochMilli(it).truncatedTo(ChronoUnit.SECONDS)
                ))
            }
            setsArray.put(setObj)
        }

        return JSONObject()
            .put("version", "1.0")
            .put("start_time", startTimeStr)
            .put("utc_offset", utcOffsetSec)
            .put("elapsed_time", elapsedSec)
            .put("creator", JSONObject().put("name", "Stochastic Strength"))
            .put("sets", setsArray)
            .toString()
    }

    companion object {
        internal fun exerciseNameToJsonType(name: String): String = when (name) {
            // CHEST — Bench Press
            "Barbell Bench Press"             -> "BARBELL_BENCH_PRESS"
            "Incline Barbell Bench Press"     -> "INCLINE_BARBELL_BENCH_PRESS"
            "Decline Bench Press"             -> "BENCH_PRESS_GENERIC"
            "Dumbbell Bench Press"            -> "DUMBBELL_BENCH_PRESS"
            "Incline Dumbbell Press"          -> "INCLINE_DUMBBELL_BENCH_PRESS"
            "Close-Grip Bench Press"          -> "CLOSE_GRIP_BARBELL_BENCH_PRESS"
            "Machine Chest Press"             -> "MACHINE_CHEST_PRESS"
            "Banded Chest Press"              -> "CHEST_PRESS"
            // CHEST — Flye
            "Dumbbell Fly"                    -> "DUMBBELL_FLYE"
            "Cable Chest Fly"                 -> "CABLE_CROSSOVER"
            "Pec Deck"                        -> "PEC_DECK_BUTTERFLY"
            // CHEST — Push-Up
            "Push-Up"                         -> "PUSH_UP_GENERIC"
            "Diamond Push-Up"                 -> "DIAMOND_PUSH_UP"
            "Pike Push-Up"                    -> "SHOULDER_FOCUSED_PRESS_UP"
            "Burpee"                          -> "BURPEE"
            // BACK — Row
            "Barbell Row"                     -> "BENT_OVER_BARBELL_ROW"
            "T-Bar Row"                       -> "T_BAR_ROW"
            "Dumbbell Row"                    -> "DUMBBELL_ROW"
            "Chest-Supported Dumbbell Row"    -> "CHEST_SUPPORTED_ROW"
            "Seated Cable Row"                -> "SEATED_CABLE_ROW"
            "Face Pull"                       -> "FACE_PULL"
            "Inverted Row"                    -> "ROW_GENERIC"
            "Banded Row"                      -> "ROW_GENERIC"
            "Superman"                        -> "SUPERMAN"
            "Back Extension"                  -> "BACK_EXTENSION"
            "Band Pull-Apart"                 -> "BAND_PULLAPARTS"
            // BACK — Pull-Up
            "Pull-Up"                         -> "PULL_UP_GENERIC"
            "Chin-Up"                         -> "CLOSE_GRIP_CHIN_UP"
            "Lat Pulldown"                    -> "LAT_PULLDOWN"
            "Straight-Arm Pulldown"           -> "STRAIGHT_ARM_PULLDOWN"
            // SHOULDERS — Press
            "Overhead Press"                  -> "OVERHEAD_BARBELL_PRESS"
            "Dumbbell Overhead Press"         -> "OVERHEAD_DUMBBELL_PRESS"
            "Arnold Press"                    -> "ARNOLD_PRESS"
            "Push Press"                      -> "PUSH_PRESS"
            "Landmine Press"                  -> "STANDING_SINGLE_ARM_SHOULDER_PRESS"
            "Kettlebell Clean and Press"      -> "SINGLE_ARM_CLEAN_AND_PRESS"
            // SHOULDERS — Lateral Raise
            "Dumbbell Lateral Raise"          -> "LATERAL_RAISE_GENERIC"
            "Cable Lateral Raise"             -> "CABLE_LATERAL_RAISE"
            "Machine Lateral Raise"           -> "LATERAL_RAISE_GENERIC"
            "Front Raise"                     -> "FRONT_RAISE"
            "Rear Delt Fly"                   -> "DUMBBELL_REAR_DELT_FLY"
            "Banded Lateral Raise"            -> "LATERAL_RAISE_GENERIC"
            "External Rotation"               -> "SHOULDER_STABILITY_GENERIC"
            // SHOULDERS — Shrug
            "Upright Row"                     -> "BARBELL_UPRIGHT_ROW"
            // BICEPS
            "Barbell Curl"                    -> "BARBELL_BICEPS_CURL"
            "Preacher Curl"                   -> "EZ_BAR_PREACHER_CURL"
            "Dumbbell Curl"                   -> "STANDING_DUMBBELL_BICEPS_CURL"
            "Hammer Curl"                     -> "DUMBBELL_HAMMER_CURL"
            "Concentration Curl"              -> "CONCENTRATION_CURL"
            "Cable Curl"                      -> "CABLE_CURL"
            "Banded Curl"                     -> "CURL_GENERIC"
            "EZ Bar Curl"                     -> "STANDING_EZ_BAR_BICEPS_CURL"
            "Incline Dumbbell Curl"           -> "INCLINE_DUMBBELL_BICEPS_CURL"
            // TRICEPS
            "Skull Crusher"                   -> "SKULL_CRUSHER"
            "Tricep Pushdown"                 -> "TRICEPS_PRESSDOWN"
            "Overhead Tricep Extension"       -> "OVERHEAD_DUMBBELL_TRICEPS_EXTENSION"
            "Tricep Kickback"                 -> "DUMBBELL_KICKBACK"
            "Cable Overhead Tricep Extension" -> "CABLE_OVERHEAD_TRICEPS_EXTENSION"
            "Dips"                            -> "BODY_WEIGHT_DIP"
            "Banded Tricep Extension"         -> "TRICEPS_EXTENSION_GENERIC"
            // HAMSTRINGS — Deadlift
            "Deadlift"                        -> "BARBELL_DEADLIFT"
            "Romanian Deadlift"               -> "BARBELL_ROMANIAN_DEADLIFT"
            "Sumo Deadlift"                   -> "SUMO_DEADLIFT"
            "Stiff-Leg Deadlift"              -> "BARBELL_STRAIGHT_LEG_DEADLIFT"
            "Single-Leg Romanian Deadlift"    -> "SINGLE_LEG_ROMANIAN_DEADLIFTS"
            "Good Morning"                    -> "GOOD_MORNING"
            "Leg Curl"                        -> "LEG_CURL_GENERIC"
            "Nordic Curl"                     -> "NORDIC_CURL"
            // QUADS — Squat
            "Barbell Squat"                   -> "BARBELL_BACK_SQUAT"
            "Front Squat"                     -> "BARBELL_FRONT_SQUAT"
            "Hack Squat"                      -> "MACHINE_HACK_SQUAT"
            "Goblet Squat"                    -> "GOBLET_SQUAT"
            "Bodyweight Squat"                -> "AIR_SQUAT"
            "Banded Squat"                    -> "SQUAT_GENERIC"
            "Jump Squat"                      -> "BODY_WEIGHT_JUMP_SQUAT"
            "Wall Sit"                        -> "WALL_SIT"
            "Leg Press"                       -> "MACHINE_LEG_PRESS"
            "Leg Extension"                   -> "MACHINE_LEG_EXTENSION"
            // QUADS — Lunge
            "Lunge"                           -> "LUNGE_GENERIC"
            "Walking Lunge"                   -> "WALKING_LUNGE"
            "Reverse Lunge"                   -> "REVERSE_LUNGE"
            "Dumbbell Lunge"                  -> "DUMBBELL_WALKING_LUNGES"
            "Bulgarian Split Squat"           -> "DUMBBELL_BULGARIAN_SPLIT_SQUATS"
            "Step-Up"                         -> "STEP_UP"
            // GLUTES
            "Hip Thrust"                      -> "BARBELL_HIP_THRUST"
            "Glute Bridge"                    -> "GLUTE_BRIDGE"
            "Single-Leg Glute Bridge"         -> "SINGLE_LEG_GLUTE_BRIDGE"
            "Cable Kickback"                  -> "MACHINE_GLUTE_KICKBACK"
            "Donkey Kick"                     -> "GLUTE_KICKBACK_ON_FLOOR"
            "Lateral Band Walk"               -> "LATERAL_WALK"
            "Clamshell"                       -> "CLAMS"
            "Kettlebell Swing"                -> "KETTLEBELL_SWING"
            // CALVES
            "Standing Calf Raise"             -> "STANDING_CALF_RAISE"
            "Seated Calf Raise"               -> "SEATED_CALF_RAISE"
            "Leg Press Calf Raise"            -> "MACHINE_CALF_PRESS"
            // CORE
            "Plank"                           -> "PLANK_HOLD"
            "Mountain Climber"                -> "MOUNTAIN_CLIMBER"
            "Dead Bug"                        -> "DEADBUG"
            "Bicycle Crunch"                  -> "BICYCLE_CRUNCH"
            "Cable Crunch"                    -> "CABLE_CRUNCH"
            "Russian Twist"                   -> "RUSSIAN_TWIST"
            "Sit-Up"                          -> "SIT_UP_GENERIC"
            "Hanging Leg Raise"               -> "HANGING_LEG_RAISE"
            "Ab Wheel Rollout"                -> "AB_WHEEL_ROLLOUT"
            "Pallof Press"                    -> "PALLOF_PRESS"
            "Turkish Get-Up"                  -> "TURKISH_GET_UP"
            // CARRY
            "Farmer's Carry"                  -> "FARMERS_CARRY"
            "Suitcase Carry"                  -> "SUITCASE_CARRY"
            else                              -> "TOTAL_BODY_GENERIC"
        }
    }
}
