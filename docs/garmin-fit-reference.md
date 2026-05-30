# Garmin FIT Java SDK — cheat sheet

## Dependency
com.garmin:fit:21.176.0  (Maven Central)
source: vendor/garmin-fit/

## FIT Epoch
FIT timestamps are seconds since 1989-12-31 00:00:00 UTC.
DateTime helper: DateTime(date)  // wraps java.util.Date
Current time: DateTime(Date())   // DateTime.now() does NOT exist

## Key imports
import com.garmin.fit.*

## File creation
val encoder = FileEncoder(File(path), Fit.ProtocolVersion.V2_0)
encoder.write(msg)   // call for each message
encoder.close()

## Required message order for a strength activity
1. FileIdMesg        — always first; sets file type + creation time
2. DeviceInfoMesg    — optional but good practice
3. WorkoutMesg       — defines the workout (name, sport)
4. WorkoutStepMesg   — one per exercise group (optional; aids display)
5. ActivityMesg      — outer wrapper
6. SessionMesg       — sport=TRAINING, subSport=STRENGTH_TRAINING
7. LapMesg           — one lap covering the whole session
8. SetMesg×N         — one per set (what Strava reads for strength data)

## FileIdMesg
val msg = FileIdMesg()
msg.type = File.ACTIVITY
msg.manufacturer = Manufacturer.DEVELOPMENT
msg.product = 0
msg.timeCreated = DateTime(Date())
msg.serialNumber = 1L

## SessionMesg (strength training)
val msg = SessionMesg()
msg.sport = Sport.TRAINING           // Java enum
msg.subSport = SubSport.STRENGTH_TRAINING  // Java enum
msg.startTime = startDt
msg.totalElapsedTime = elapsedSeconds.toFloat()
msg.totalTimerTime   = elapsedSeconds.toFloat()
msg.messageIndex = MessageIndex.RESERVED   // int constant 0x7000
msg.firstLapIndex = 0
msg.numLaps = 1

## LapMesg
val msg = LapMesg()
msg.startTime = startDt
msg.totalElapsedTime = elapsedSeconds.toFloat()
msg.messageIndex = MessageIndex.RESERVED
msg.sport = Sport.TRAINING

## SetMesg (one per set)
val msg = SetMesg()
msg.timestamp = endDt          // when the set finished
msg.startTime = startDt        // when the set started
msg.setType = SetType.ACTIVE   // short constant (ACTIVE=1, REST=0)
msg.messageIndex = setIndex    // 0-based Int
msg.repetitions = reps
msg.weight = weightKg          // Float, in kg always
msg.weightDisplayUnit = FitBaseUnit.KILOGRAM  // int constant (KILOGRAM=1, POUND=2)
msg.duration = durationSec.toFloat()
// exercise category — both ExerciseCategory and *ExerciseName are int constants, NOT enums:
msg.setCategory(0, ExerciseCategory.BENCH_PRESS)
msg.setCategorySubtype(0, BenchPressExerciseName.BARBELL_BENCH_PRESS)  // no .value

## Common ExerciseCategory values (with subtypes)
BENCH_PRESS (0)      → BenchPressExerciseName.*
SQUAT (28)           → SquatExerciseName.*
DEADLIFT (8)         → DeadliftExerciseName.*
ROW (23)             → RowExerciseName.*
SHOULDER_PRESS (24)  → ShoulderPressExerciseName.*
PULL_UP (21)         → PullUpExerciseName.*
LUNGE (17)           → LungeExerciseName.*
CORE (5)             → CoreExerciseName.*
UNKNOWN (65534)      → use when no mapping exists; exercise name still shows in description

## ActivityMesg
val msg = ActivityMesg()
msg.timestamp = endDt
msg.numSessions = 1
msg.type = Activity.MANUAL    // Java enum
msg.event = Event.ACTIVITY    // Java enum
msg.eventType = EventType.STOP  // Java enum
