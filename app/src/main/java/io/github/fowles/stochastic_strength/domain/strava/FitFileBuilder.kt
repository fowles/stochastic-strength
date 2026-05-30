package io.github.fowles.stochastic_strength.domain.strava

import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.BenchPressExerciseName
import com.garmin.fit.CalfRaiseExerciseName
import com.garmin.fit.CoreExerciseName
import com.garmin.fit.CrunchExerciseName
import com.garmin.fit.CurlExerciseName
import com.garmin.fit.DeadliftExerciseName
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.Event
import com.garmin.fit.EventType
import com.garmin.fit.ExerciseCategory
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.FitBaseUnit
import com.garmin.fit.FlyeExerciseName
import com.garmin.fit.HipRaiseExerciseName
import com.garmin.fit.HipSwingExerciseName
import com.garmin.fit.HyperextensionExerciseName
import com.garmin.fit.LapMesg
import com.garmin.fit.LateralRaiseExerciseName
import com.garmin.fit.LegCurlExerciseName
import com.garmin.fit.LegRaiseExerciseName
import com.garmin.fit.LungeExerciseName
import com.garmin.fit.Manufacturer
import com.garmin.fit.MessageIndex
import com.garmin.fit.PlankExerciseName
import com.garmin.fit.PullUpExerciseName
import com.garmin.fit.PushUpExerciseName
import com.garmin.fit.RowExerciseName
import com.garmin.fit.SessionMesg
import com.garmin.fit.SetMesg
import com.garmin.fit.SetType
import com.garmin.fit.ShoulderPressExerciseName
import com.garmin.fit.ShrugExerciseName
import com.garmin.fit.SitUpExerciseName
import com.garmin.fit.Sport
import com.garmin.fit.SquatExerciseName
import com.garmin.fit.SubSport
import com.garmin.fit.TricepsExtensionExerciseName
import com.garmin.fit.DateTime as FitDateTime
import com.garmin.fit.File as FitFile
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import java.io.File
import java.util.Date

class FitFileBuilder(private val cacheDir: File) {

    fun build(
        session: WorkoutSession,
        sets: List<WorkoutSet>,
        nameById: Map<Long, String>,
    ): File {
        val startMs = session.startTime
        val endMs = session.endTime ?: startMs
        val elapsedSec = ((endMs - startMs) / 1000f).coerceAtLeast(0f)

        val startDt = FitDateTime(Date(startMs))
        val endDt = FitDateTime(Date(endMs))

        val outFile = File(cacheDir, "strava_export_${session.id}.fit")
        val encoder = FileEncoder(outFile, Fit.ProtocolVersion.V2_0)

        encoder.write(buildFileId(startDt))
        encoder.write(buildDeviceInfo(startDt))
        encoder.write(buildActivity(endDt, elapsedSec))
        encoder.write(buildSession(startDt, endDt, elapsedSec))
        encoder.write(buildLap(startDt, endDt, elapsedSec))

        var setIndex = 0
        for (set in sets) {
            val name = nameById[set.exerciseId] ?: ""
            encoder.write(buildSet(set, name, setIndex++))
        }

        encoder.close()
        return outFile
    }

    private fun buildFileId(startDt: FitDateTime): FileIdMesg {
        val msg = FileIdMesg()
        msg.type = FitFile.ACTIVITY
        msg.manufacturer = Manufacturer.DEVELOPMENT
        msg.product = 0
        msg.timeCreated = startDt
        msg.serialNumber = 1L
        return msg
    }

    private fun buildDeviceInfo(startDt: FitDateTime): DeviceInfoMesg {
        val msg = DeviceInfoMesg()
        msg.timestamp = startDt
        msg.manufacturer = Manufacturer.DEVELOPMENT
        return msg
    }

    private fun buildActivity(endDt: FitDateTime, elapsedSec: Float): ActivityMesg {
        val msg = ActivityMesg()
        msg.timestamp = endDt
        msg.numSessions = 1
        msg.type = Activity.MANUAL
        msg.event = Event.ACTIVITY
        msg.eventType = EventType.STOP
        msg.totalTimerTime = elapsedSec
        return msg
    }

    private fun buildSession(startDt: FitDateTime, endDt: FitDateTime, elapsedSec: Float): SessionMesg {
        val msg = SessionMesg()
        msg.messageIndex = MessageIndex.RESERVED
        msg.timestamp = endDt
        msg.startTime = startDt
        msg.sport = Sport.TRAINING
        msg.subSport = SubSport.STRENGTH_TRAINING
        msg.totalElapsedTime = elapsedSec
        msg.totalTimerTime = elapsedSec
        msg.firstLapIndex = 0
        msg.numLaps = 1
        msg.event = Event.SESSION
        msg.eventType = EventType.STOP
        return msg
    }

    private fun buildLap(startDt: FitDateTime, endDt: FitDateTime, elapsedSec: Float): LapMesg {
        val msg = LapMesg()
        msg.messageIndex = MessageIndex.RESERVED
        msg.timestamp = endDt
        msg.startTime = startDt
        msg.sport = Sport.TRAINING
        msg.subSport = SubSport.STRENGTH_TRAINING
        msg.totalElapsedTime = elapsedSec
        msg.totalTimerTime = elapsedSec
        return msg
    }

    private fun buildSet(set: WorkoutSet, exerciseName: String, index: Int): SetMesg {
        val msg = SetMesg()
        msg.messageIndex = index
        msg.setType = SetType.ACTIVE
        msg.duration = set.targetReps * 3f
        msg.repetitions = set.targetReps
        msg.weight = set.targetWeight
        msg.weightDisplayUnit = FitBaseUnit.KILOGRAM

        if (set.completedAt != null) {
            msg.timestamp = FitDateTime(Date(set.completedAt))
        }

        val (category, subtype) = exerciseNameToFitCategory(exerciseName)
        msg.setCategory(0, category)
        msg.setCategorySubtype(0, subtype)

        return msg
    }

    private fun exerciseNameToFitCategory(name: String): Pair<Int, Int> = when (name) {
        // CHEST — Bench Press
        "Barbell Bench Press" -> ExerciseCategory.BENCH_PRESS to BenchPressExerciseName.BARBELL_BENCH_PRESS
        "Incline Barbell Bench Press" -> ExerciseCategory.BENCH_PRESS to BenchPressExerciseName.INCLINE_BARBELL_BENCH_PRESS
        "Decline Bench Press" -> ExerciseCategory.BENCH_PRESS to BenchPressExerciseName.DECLINE_DUMBBELL_BENCH_PRESS
        "Dumbbell Bench Press" -> ExerciseCategory.BENCH_PRESS to BenchPressExerciseName.DUMBBELL_BENCH_PRESS
        "Incline Dumbbell Press" -> ExerciseCategory.BENCH_PRESS to BenchPressExerciseName.INCLINE_DUMBBELL_BENCH_PRESS
        "Close-Grip Bench Press" -> ExerciseCategory.BENCH_PRESS to BenchPressExerciseName.CLOSE_GRIP_BARBELL_BENCH_PRESS
        // CHEST — Flye
        "Dumbbell Fly" -> ExerciseCategory.FLYE to FlyeExerciseName.DUMBBELL_FLYE
        "Cable Chest Fly" -> ExerciseCategory.FLYE to FlyeExerciseName.CABLE_CROSSOVER
        "Pec Deck" -> ExerciseCategory.FLYE to FlyeExerciseName.DUMBBELL_FLYE
        // CHEST — Push-Up
        "Push-Up" -> ExerciseCategory.PUSH_UP to PushUpExerciseName.PUSH_UP
        "Burpee" -> ExerciseCategory.PUSH_UP to PushUpExerciseName.PUSH_UP
        "Diamond Push-Up" -> ExerciseCategory.PUSH_UP to PushUpExerciseName.DIAMOND_PUSH_UP
        "Pike Push-Up" -> ExerciseCategory.PUSH_UP to PushUpExerciseName.PIKE_PUSH_UP
        "Machine Chest Press" -> ExerciseCategory.BENCH_PRESS to BenchPressExerciseName.BARBELL_BENCH_PRESS
        // BACK — Deadlift
        "Deadlift" -> ExerciseCategory.DEADLIFT to DeadliftExerciseName.BARBELL_DEADLIFT
        "Romanian Deadlift" -> ExerciseCategory.DEADLIFT to DeadliftExerciseName.ROMANIAN_DEADLIFT
        "Sumo Deadlift" -> ExerciseCategory.DEADLIFT to DeadliftExerciseName.SUMO_DEADLIFT
        "Stiff-Leg Deadlift" -> ExerciseCategory.DEADLIFT to DeadliftExerciseName.BARBELL_STRAIGHT_LEG_DEADLIFT
        // BACK — Row
        "Barbell Row" -> ExerciseCategory.ROW to RowExerciseName.BARBELL_ROW
        "T-Bar Row" -> ExerciseCategory.ROW to RowExerciseName.T_BAR_ROW
        "Dumbbell Row" -> ExerciseCategory.ROW to RowExerciseName.DUMBBELL_ROW
        "Chest-Supported Dumbbell Row" -> ExerciseCategory.ROW to RowExerciseName.DUMBBELL_ROW
        "Seated Cable Row" -> ExerciseCategory.ROW to RowExerciseName.SEATED_CABLE_ROW
        "Face Pull" -> ExerciseCategory.ROW to RowExerciseName.FACE_PULL
        "Inverted Row" -> ExerciseCategory.ROW to RowExerciseName.MODIFIED_INVERTED_ROW
        "Straight-Arm Pulldown" -> ExerciseCategory.PULL_UP to PullUpExerciseName.LAT_PULLDOWN
        // BACK — Pull-Up / Lat Pulldown
        "Pull-Up" -> ExerciseCategory.PULL_UP to PullUpExerciseName.PULL_UP
        "Chin-Up" -> ExerciseCategory.PULL_UP to PullUpExerciseName.CHIN_UP
        "Lat Pulldown" -> ExerciseCategory.PULL_UP to PullUpExerciseName.LAT_PULLDOWN
        // BACK — Hyperextension
        "Good Morning" -> ExerciseCategory.HYPEREXTENSION to HyperextensionExerciseName.BACK_EXTENSION_WITH_OPPOSITE_ARM_AND_LEG_REACH
        "Superman" -> ExerciseCategory.HYPEREXTENSION to HyperextensionExerciseName.BACK_EXTENSION_WITH_OPPOSITE_ARM_AND_LEG_REACH
        // SHOULDERS — Press
        "Overhead Press" -> ExerciseCategory.SHOULDER_PRESS to ShoulderPressExerciseName.OVERHEAD_BARBELL_PRESS
        "Dumbbell Overhead Press" -> ExerciseCategory.SHOULDER_PRESS to ShoulderPressExerciseName.OVERHEAD_DUMBBELL_PRESS
        "Arnold Press" -> ExerciseCategory.SHOULDER_PRESS to ShoulderPressExerciseName.ARNOLD_PRESS
        "Push Press" -> ExerciseCategory.SHOULDER_PRESS to ShoulderPressExerciseName.BARBELL_PUSH_PRESS
        "Kettlebell Clean and Press" -> ExerciseCategory.SHOULDER_PRESS to ShoulderPressExerciseName.ALTERNATING_DUMBBELL_SHOULDER_PRESS
        // SHOULDERS — Lateral Raise
        "Dumbbell Lateral Raise" -> ExerciseCategory.LATERAL_RAISE to LateralRaiseExerciseName.DUMBBELL_LATERAL_RAISE
        "Cable Lateral Raise" -> ExerciseCategory.LATERAL_RAISE to LateralRaiseExerciseName.ONE_ARM_CABLE_LATERAL_RAISE
        "Machine Lateral Raise" -> ExerciseCategory.LATERAL_RAISE to LateralRaiseExerciseName.DUMBBELL_LATERAL_RAISE
        "Front Raise" -> ExerciseCategory.LATERAL_RAISE to LateralRaiseExerciseName.FRONT_RAISE
        "Rear Delt Fly" -> ExerciseCategory.LATERAL_RAISE to LateralRaiseExerciseName.BENT_OVER_LATERAL_RAISE
        // SHOULDERS — Shrug / Upright Row
        "Upright Row" -> ExerciseCategory.SHRUG to ShrugExerciseName.BARBELL_UPRIGHT_ROW
        // BICEPS
        "Barbell Curl" -> ExerciseCategory.CURL to CurlExerciseName.BARBELL_BICEPS_CURL
        "Preacher Curl" -> ExerciseCategory.CURL to CurlExerciseName.EZ_BAR_PREACHER_CURL
        "Dumbbell Curl" -> ExerciseCategory.CURL to CurlExerciseName.DUMBBELL_BICEPS_CURL
        "Hammer Curl" -> ExerciseCategory.CURL to CurlExerciseName.DUMBBELL_HAMMER_CURL
        "Concentration Curl" -> ExerciseCategory.CURL to CurlExerciseName.ONE_ARM_CONCENTRATION_CURL
        "Cable Curl" -> ExerciseCategory.CURL to CurlExerciseName.CABLE_BICEPS_CURL
        "EZ Bar Curl" -> ExerciseCategory.CURL to CurlExerciseName.CLOSE_GRIP_EZ_BAR_BICEPS_CURL
        "Incline Dumbbell Curl" -> ExerciseCategory.CURL to CurlExerciseName.INCLINE_DUMBBELL_BICEPS_CURL
        // TRICEPS
        "Skull Crusher" -> ExerciseCategory.TRICEPS_EXTENSION to TricepsExtensionExerciseName.LYING_EZ_BAR_TRICEPS_EXTENSION
        "Tricep Pushdown" -> ExerciseCategory.TRICEPS_EXTENSION to TricepsExtensionExerciseName.TRICEPS_PRESSDOWN
        "Overhead Tricep Extension" -> ExerciseCategory.TRICEPS_EXTENSION to TricepsExtensionExerciseName.OVERHEAD_DUMBBELL_TRICEPS_EXTENSION
        "Tricep Kickback" -> ExerciseCategory.TRICEPS_EXTENSION to TricepsExtensionExerciseName.DUMBBELL_KICKBACK
        "Cable Overhead Tricep Extension" -> ExerciseCategory.TRICEPS_EXTENSION to TricepsExtensionExerciseName.CABLE_OVERHEAD_TRICEPS_EXTENSION
        "Dips" -> ExerciseCategory.TRICEPS_EXTENSION to TricepsExtensionExerciseName.BODY_WEIGHT_DIP
        // QUADS
        "Barbell Squat" -> ExerciseCategory.SQUAT to SquatExerciseName.BARBELL_BACK_SQUAT
        "Front Squat" -> ExerciseCategory.SQUAT to SquatExerciseName.BARBELL_FRONT_SQUAT
        "Hack Squat" -> ExerciseCategory.SQUAT to SquatExerciseName.BARBELL_HACK_SQUAT
        "Goblet Squat" -> ExerciseCategory.SQUAT to SquatExerciseName.GOBLET_SQUAT
        "Bodyweight Squat" -> ExerciseCategory.SQUAT to SquatExerciseName.BACK_SQUAT_WITH_BODY_BAR
        "Jump Squat" -> ExerciseCategory.SQUAT to SquatExerciseName.SQUAT_JUMPS_IN_N_OUT
        "Leg Press" -> ExerciseCategory.SQUAT to SquatExerciseName.LEG_PRESS
        "Leg Extension" -> ExerciseCategory.SQUAT to SquatExerciseName.LEG_PRESS
        // QUADS — Lunge
        "Lunge" -> ExerciseCategory.LUNGE to LungeExerciseName.WALKING_LUNGE
        "Bulgarian Split Squat" -> ExerciseCategory.LUNGE to LungeExerciseName.DUMBBELL_BULGARIAN_SPLIT_SQUAT
        "Step-Up" -> ExerciseCategory.LUNGE to LungeExerciseName.ALTERNATING_BARBELL_FORWARD_LUNGE
        // HAMSTRINGS
        "Leg Curl" -> ExerciseCategory.LEG_CURL to LegCurlExerciseName.LEG_CURL
        "Nordic Curl" -> ExerciseCategory.LEG_CURL to LegCurlExerciseName.LEG_CURL
        // GLUTES
        "Hip Thrust" -> ExerciseCategory.HIP_RAISE to HipRaiseExerciseName.BARBELL_HIP_THRUST_WITH_BENCH
        "Glute Bridge" -> ExerciseCategory.HIP_RAISE to HipRaiseExerciseName.HIP_RAISE
        "Single-Leg Glute Bridge" -> ExerciseCategory.HIP_RAISE to HipRaiseExerciseName.SINGLE_LEG_HIP_RAISE
        "Cable Kickback" -> ExerciseCategory.HIP_RAISE to HipRaiseExerciseName.HIP_RAISE
        "Donkey Kick" -> ExerciseCategory.HIP_RAISE to HipRaiseExerciseName.HIP_RAISE
        // GLUTES — Kettlebell Swing
        "Kettlebell Swing" -> ExerciseCategory.HIP_SWING to HipSwingExerciseName.SINGLE_ARM_KETTLEBELL_SWING
        // CALVES
        "Standing Calf Raise" -> ExerciseCategory.CALF_RAISE to CalfRaiseExerciseName._3_WAY_CALF_RAISE
        "Seated Calf Raise" -> ExerciseCategory.CALF_RAISE to CalfRaiseExerciseName.SEATED_CALF_RAISE
        "Leg Press Calf Raise" -> ExerciseCategory.CALF_RAISE to CalfRaiseExerciseName.SEATED_CALF_RAISE
        // CORE — Plank
        "Plank" -> ExerciseCategory.PLANK to PlankExerciseName._45_DEGREE_PLANK
        "Mountain Climber" -> ExerciseCategory.PLANK to PlankExerciseName.CROSS_BODY_MOUNTAIN_CLIMBER
        "Dead Bug" -> ExerciseCategory.PLANK to PlankExerciseName._45_DEGREE_PLANK
        // CORE — Crunch
        "Bicycle Crunch" -> ExerciseCategory.CRUNCH to CrunchExerciseName.BICYCLE_CRUNCH
        "Cable Crunch" -> ExerciseCategory.CRUNCH to CrunchExerciseName.CABLE_CRUNCH
        "Russian Twist" -> ExerciseCategory.CRUNCH to CrunchExerciseName.BICYCLE_CRUNCH
        // CORE — Sit-Up
        "Sit-Up" -> ExerciseCategory.SIT_UP to SitUpExerciseName.SIT_UP
        // CORE — Leg Raise
        "Hanging Leg Raise" -> ExerciseCategory.LEG_RAISE to LegRaiseExerciseName.HANGING_LEG_RAISE
        // CORE — Core
        "Ab Wheel Rollout" -> ExerciseCategory.CORE to CoreExerciseName.BARBELL_ROLLOUT
        "Pallof Press" -> ExerciseCategory.CORE to CoreExerciseName.CABLE_CORE_PRESS
        "Turkish Get-Up" -> ExerciseCategory.CORE to CoreExerciseName.ALTERNATING_PLATE_REACH
        else -> ExerciseCategory.BENCH_PRESS to 0
    }
}
