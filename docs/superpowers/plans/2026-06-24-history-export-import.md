# History Export / Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add export/import of full app state as a JSON file behind a `...` menu in the History screen, for reproducing a prod user's state locally.

**Architecture:** A new `domain/backup/` package holds a framework-free `WorkoutBackup` data holder plus an `org.json`-based builder/parser, and a `BackupManager` that reads/writes every durable input table over the existing DAOs. Destructive import wipes and reloads rows verbatim (ids preserved) inside a Room transaction; additive import merges only sessions/sets, remapping exercises/locations by name. Both finish by calling `WorkoutRepository.replayDerivedState()`. The History screen gets an overflow menu wired to the Storage Access Framework.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room 2.8.4 (+ room-ktx `withTransaction`), `org.json`, Storage Access Framework (`ActivityResultContracts`).

## Global Constraints

- Package root: `io.github.fowles.stochastic_strength`.
- No new dependencies — use `org.json` (already used by `StravaJsonBuilder`), not kotlinx.serialization/Gson.
- `org.json` is NOT available in plain JVM unit tests in this project (no Robolectric / `returnDefaultValues`). **All tests that touch JSON or Room are instrumented tests** under `app/src/androidTest/...` and run with `./gradlew :app:connectedAndroidTest` against the already-running emulator.
- Durable input tables (the complete export set): `exercises`, `known_locations`, `location_excluded_exercises`, `workout_sessions`, `workout_sets`, `user_profile`, `baseline_override`, `exercise_hurt_state`, `exercise_strength_override`. Derived state is never exported — it is rebuilt by `replayDerivedState()`.
- Current Room schema version is **17**; the backup envelope records `dbVersion = 17` and import refuses any mismatch.
- `WorkoutBackup`, `BackupJsonBuilder`, `BackupJsonParser`, `BackupManager` are in package `io.github.fowles.stochastic_strength.domain.backup`.
- No `@ForeignKey` constraints exist on these entities, so delete/insert order is unconstrained.

---

## File Structure

- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/WorkoutBackup.kt` — data holder + `BackupFormatException` + format constants.
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupJson.kt` — `BackupJsonBuilder` (build) + `BackupJsonParser` (parse).
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupManager.kt` — export / importDestructive / importAdditive over `AppDatabase` + `WorkoutRepository`.
- Modify: the 9 input-table DAOs under `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/` — add `getAll()` / `deleteAll()` where missing.
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt` — add lazy `backupManager`.
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryScreen.kt` — overflow menu + SAF launchers + import-mode dialog.
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryViewModel.kt` — export/import state + `exportTo`/`importFrom`.
- Create tests:
  - `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupJsonTest.kt`
  - `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupManagerTest.kt`

---

## Task 1: Backup model + JSON builder/parser

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/WorkoutBackup.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupJson.kt`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupJsonTest.kt`

**Interfaces:**
- Produces:
  - `data class WorkoutBackup(formatVersion: Int, dbVersion: Int, exportedAt: Long, exercises: List<Exercise>, knownLocations: List<KnownLocation>, locationExcludedExercises: List<LocationExcludedExercise>, workoutSessions: List<WorkoutSession>, workoutSets: List<WorkoutSet>, userProfile: List<UserProfile>, baselineOverrides: List<BaselineOverride>, exerciseHurtState: List<ExerciseHurtState>, exerciseStrengthOverrides: List<ExerciseStrengthOverride>)`
  - `class BackupFormatException(message: String) : Exception(message)`
  - constants `WorkoutBackup.FORMAT = "stochastic-strength-backup"`, `WorkoutBackup.FORMAT_VERSION = 1`, `WorkoutBackup.DB_VERSION = 17`
  - `object BackupJsonBuilder { fun build(backup: WorkoutBackup): String }`
  - `object BackupJsonParser { fun parse(json: String): WorkoutBackup }`

- [ ] **Step 1: Write `WorkoutBackup.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.backup

import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/** In-memory snapshot of every durable input table. Derived state is excluded by design. */
data class WorkoutBackup(
    val formatVersion: Int,
    val dbVersion: Int,
    val exportedAt: Long,
    val exercises: List<Exercise>,
    val knownLocations: List<KnownLocation>,
    val locationExcludedExercises: List<LocationExcludedExercise>,
    val workoutSessions: List<WorkoutSession>,
    val workoutSets: List<WorkoutSet>,
    val userProfile: List<UserProfile>,
    val baselineOverrides: List<BaselineOverride>,
    val exerciseHurtState: List<ExerciseHurtState>,
    val exerciseStrengthOverrides: List<ExerciseStrengthOverride>,
) {
    companion object {
        const val FORMAT = "stochastic-strength-backup"
        const val FORMAT_VERSION = 1
        const val DB_VERSION = 17
    }
}

/** Thrown when a backup file is malformed or targets a different DB version. */
class BackupFormatException(message: String) : Exception(message)
```

- [ ] **Step 2: Write `BackupJson.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.backup

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// --- shared helpers ---

private fun obj(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
    return o
}

private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
private fun JSONObject.floatVal(key: String): Float = getDouble(key).toFloat()

private fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private fun List<MuscleGroup>.toJsonArray(): JSONArray =
    JSONArray().also { arr -> forEach { arr.put(it.name) } }

private fun JSONArray.toMuscleGroups(): List<MuscleGroup> =
    (0 until length()).map { MuscleGroup.valueOf(getString(it)) }

object BackupJsonBuilder {
    fun build(backup: WorkoutBackup): String {
        val tables = JSONObject()
            .put("exercises", JSONArray().apply { backup.exercises.forEach { put(exerciseObj(it)) } })
            .put("knownLocations", JSONArray().apply { backup.knownLocations.forEach { put(locationObj(it)) } })
            .put("locationExcludedExercises", JSONArray().apply { backup.locationExcludedExercises.forEach { put(exclusionObj(it)) } })
            .put("workoutSessions", JSONArray().apply { backup.workoutSessions.forEach { put(sessionObj(it)) } })
            .put("workoutSets", JSONArray().apply { backup.workoutSets.forEach { put(setObj(it)) } })
            .put("userProfile", JSONArray().apply { backup.userProfile.forEach { put(profileObj(it)) } })
            .put("baselineOverrides", JSONArray().apply { backup.baselineOverrides.forEach { put(baselineObj(it)) } })
            .put("exerciseHurtState", JSONArray().apply { backup.exerciseHurtState.forEach { put(hurtObj(it)) } })
            .put("exerciseStrengthOverrides", JSONArray().apply { backup.exerciseStrengthOverrides.forEach { put(strengthObj(it)) } })
        return JSONObject()
            .put("format", WorkoutBackup.FORMAT)
            .put("formatVersion", backup.formatVersion)
            .put("dbVersion", backup.dbVersion)
            .put("exportedAt", backup.exportedAt)
            .put("tables", tables)
            .toString(2)
    }

    private fun exerciseObj(e: Exercise) = obj(
        "id" to e.id, "name" to e.name, "primaryMuscle" to e.primaryMuscle.name,
        "secondaryMuscles" to e.secondaryMuscles.toJsonArray(), "equipment" to e.equipment.name,
        "isDisliked" to e.isDisliked, "isUnilateral" to e.isUnilateral, "isTimed" to e.isTimed,
    )

    private fun locationObj(l: KnownLocation) = obj(
        "id" to l.id, "name" to l.name, "latitude" to l.latitude, "longitude" to l.longitude,
    )

    private fun exclusionObj(x: LocationExcludedExercise) = obj(
        "locationId" to x.locationId, "exerciseId" to x.exerciseId,
    )

    private fun sessionObj(s: WorkoutSession) = obj(
        "id" to s.id, "locationId" to s.locationId, "startTime" to s.startTime,
        "endTime" to s.endTime, "stravaActivityId" to s.stravaActivityId,
    )

    private fun setObj(s: WorkoutSet) = obj(
        "id" to s.id, "sessionId" to s.sessionId, "exerciseId" to s.exerciseId,
        "setNumber" to s.setNumber, "targetWeight" to s.targetWeight.toDouble(),
        "targetReps" to s.targetReps, "actualReps" to s.actualReps,
        "feedback" to s.feedback?.name, "completedAt" to s.completedAt,
        "durationSeconds" to s.durationSeconds,
    )

    private fun profileObj(p: UserProfile) = obj(
        "id" to p.id, "sex" to p.sex.name, "strengthLevel" to p.strengthLevel.name,
        "weightUnit" to p.weightUnit.name, "preferredExerciseCount" to p.preferredExerciseCount,
        "preferredRepMin" to p.preferredRepMin, "preferredRepMax" to p.preferredRepMax,
        "perExerciseSeedsBackfilled" to p.perExerciseSeedsBackfilled,
    )

    private fun baselineObj(b: BaselineOverride) = obj(
        "id" to b.id, "sessionId" to b.sessionId, "muscleGroup" to b.muscleGroup.name,
        "baselineWeight" to b.baselineWeight.toDouble(), "asOf" to b.asOf, "reason" to b.reason.name,
    )

    private fun hurtObj(h: ExerciseHurtState) = obj(
        "exerciseId" to h.exerciseId, "isHurt" to h.isHurt, "asOf" to h.asOf,
    )

    private fun strengthObj(s: ExerciseStrengthOverride) = obj(
        "id" to s.id, "sessionId" to s.sessionId, "exerciseId" to s.exerciseId,
        "e1rm" to s.e1rm.toDouble(), "asOf" to s.asOf, "reason" to s.reason.name,
    )
}

object BackupJsonParser {
    fun parse(json: String): WorkoutBackup {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupFormatException("Not a valid backup file: ${e.message}")
        }
        val format = root.optString("format")
        if (format != WorkoutBackup.FORMAT) {
            throw BackupFormatException("Unrecognized file (format=\"$format\").")
        }
        val dbVersion = root.optInt("dbVersion", -1)
        if (dbVersion != WorkoutBackup.DB_VERSION) {
            throw BackupFormatException(
                "This export is from DB v$dbVersion but the app is on v${WorkoutBackup.DB_VERSION}. " +
                    "Update the app, or re-export."
            )
        }
        val tables = root.getJSONObject("tables")
        return try {
            WorkoutBackup(
                formatVersion = root.getInt("formatVersion"),
                dbVersion = dbVersion,
                exportedAt = root.getLong("exportedAt"),
                exercises = tables.getJSONArray("exercises").map { exercise(it) },
                knownLocations = tables.getJSONArray("knownLocations").map { location(it) },
                locationExcludedExercises = tables.getJSONArray("locationExcludedExercises").map { exclusion(it) },
                workoutSessions = tables.getJSONArray("workoutSessions").map { session(it) },
                workoutSets = tables.getJSONArray("workoutSets").map { set(it) },
                userProfile = tables.getJSONArray("userProfile").map { profile(it) },
                baselineOverrides = tables.getJSONArray("baselineOverrides").map { baseline(it) },
                exerciseHurtState = tables.getJSONArray("exerciseHurtState").map { hurt(it) },
                exerciseStrengthOverrides = tables.getJSONArray("exerciseStrengthOverrides").map { strength(it) },
            )
        } catch (e: JSONException) {
            throw BackupFormatException("Malformed backup contents: ${e.message}")
        }
    }

    private fun exercise(o: JSONObject) = Exercise(
        id = o.getLong("id"), name = o.getString("name"),
        primaryMuscle = MuscleGroup.valueOf(o.getString("primaryMuscle")),
        secondaryMuscles = o.getJSONArray("secondaryMuscles").toMuscleGroups(),
        equipment = Equipment.valueOf(o.getString("equipment")),
        isDisliked = o.getBoolean("isDisliked"), isUnilateral = o.getBoolean("isUnilateral"),
        isTimed = o.getBoolean("isTimed"),
    )

    private fun location(o: JSONObject) = KnownLocation(
        id = o.getLong("id"), name = o.getString("name"),
        latitude = o.getDouble("latitude"), longitude = o.getDouble("longitude"),
    )

    private fun exclusion(o: JSONObject) = LocationExcludedExercise(
        locationId = o.getLong("locationId"), exerciseId = o.getLong("exerciseId"),
    )

    private fun session(o: JSONObject) = WorkoutSession(
        id = o.getLong("id"), locationId = o.longOrNull("locationId"),
        startTime = o.getLong("startTime"), endTime = o.longOrNull("endTime"),
        stravaActivityId = o.longOrNull("stravaActivityId"),
    )

    private fun set(o: JSONObject) = WorkoutSet(
        id = o.getLong("id"), sessionId = o.getLong("sessionId"), exerciseId = o.getLong("exerciseId"),
        setNumber = o.getInt("setNumber"), targetWeight = o.floatVal("targetWeight"),
        targetReps = o.getInt("targetReps"), actualReps = o.intOrNull("actualReps"),
        feedback = if (o.isNull("feedback")) null else SetFeedback.valueOf(o.getString("feedback")),
        completedAt = o.longOrNull("completedAt"), durationSeconds = o.intOrNull("durationSeconds"),
    )

    private fun profile(o: JSONObject) = UserProfile(
        id = o.getLong("id"), sex = Sex.valueOf(o.getString("sex")),
        strengthLevel = StrengthLevel.valueOf(o.getString("strengthLevel")),
        weightUnit = WeightUnit.valueOf(o.getString("weightUnit")),
        preferredExerciseCount = o.intOrNull("preferredExerciseCount"),
        preferredRepMin = o.intOrNull("preferredRepMin"), preferredRepMax = o.intOrNull("preferredRepMax"),
        perExerciseSeedsBackfilled = o.getBoolean("perExerciseSeedsBackfilled"),
    )

    private fun baseline(o: JSONObject) = BaselineOverride(
        id = o.getLong("id"), sessionId = o.longOrNull("sessionId"),
        muscleGroup = MuscleGroup.valueOf(o.getString("muscleGroup")),
        baselineWeight = o.floatVal("baselineWeight"), asOf = o.getLong("asOf"),
        reason = BaselineChangeReason.valueOf(o.getString("reason")),
    )

    private fun hurt(o: JSONObject) = ExerciseHurtState(
        exerciseId = o.getLong("exerciseId"), isHurt = o.getBoolean("isHurt"), asOf = o.getLong("asOf"),
    )

    private fun strength(o: JSONObject) = ExerciseStrengthOverride(
        id = o.getLong("id"), sessionId = o.longOrNull("sessionId"), exerciseId = o.getLong("exerciseId"),
        e1rm = o.floatVal("e1rm"), asOf = o.getLong("asOf"),
        reason = BaselineChangeReason.valueOf(o.getString("reason")),
    )
}
```

- [ ] **Step 3: Write the failing instrumented round-trip test**

Create `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupJsonTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupJsonTest {

    private fun sampleBackup() = WorkoutBackup(
        formatVersion = WorkoutBackup.FORMAT_VERSION,
        dbVersion = WorkoutBackup.DB_VERSION,
        exportedAt = 1_719_000_000_000L,
        exercises = listOf(
            Exercise(id = 5, name = "Bench Press", primaryMuscle = MuscleGroup.CHEST,
                secondaryMuscles = listOf(MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS),
                equipment = Equipment.BARBELL, isDisliked = true, isUnilateral = false, isTimed = false),
        ),
        knownLocations = listOf(KnownLocation(id = 2, name = "Home", latitude = 1.5, longitude = -3.25)),
        locationExcludedExercises = listOf(LocationExcludedExercise(locationId = 2, exerciseId = 5)),
        workoutSessions = listOf(
            WorkoutSession(id = 9, locationId = 2, startTime = 100, endTime = 200, stravaActivityId = null),
            WorkoutSession(id = 10, locationId = null, startTime = 300, endTime = null, stravaActivityId = 77),
        ),
        workoutSets = listOf(
            WorkoutSet(id = 1, sessionId = 9, exerciseId = 5, setNumber = 1, targetWeight = 60.5f,
                targetReps = 5, actualReps = 4, feedback = SetFeedback.RIR_2_4, completedAt = 150,
                durationSeconds = null),
            WorkoutSet(id = 2, sessionId = 9, exerciseId = 5, setNumber = 2, targetWeight = 60.5f,
                targetReps = 5, actualReps = null, feedback = null, completedAt = null, durationSeconds = 42),
        ),
        userProfile = listOf(UserProfile(id = 1, sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM,
            weightUnit = WeightUnit.LBS, preferredExerciseCount = 6, preferredRepMin = null,
            preferredRepMax = 8, perExerciseSeedsBackfilled = true)),
        baselineOverrides = listOf(BaselineOverride(id = 3, sessionId = null, muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 80.25f, asOf = 0, reason = BaselineChangeReason.OVERRIDE)),
        exerciseHurtState = listOf(ExerciseHurtState(exerciseId = 5, isHurt = true, asOf = 500)),
        exerciseStrengthOverrides = listOf(ExerciseStrengthOverride(id = 4, sessionId = 9, exerciseId = 5,
            e1rm = 95.5f, asOf = 600, reason = BaselineChangeReason.DETRAIN)),
    )

    @Test
    fun roundTrip_preservesEveryField() {
        val original = sampleBackup()
        val parsed = BackupJsonParser.parse(BackupJsonBuilder.build(original))
        assertEquals(original, parsed)
    }

    @Test
    fun roundTrip_emptyTables() {
        val empty = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0, exercises = emptyList(), knownLocations = emptyList(),
            locationExcludedExercises = emptyList(), workoutSessions = emptyList(), workoutSets = emptyList(),
            userProfile = emptyList(), baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
            exerciseStrengthOverrides = emptyList(),
        )
        assertEquals(empty, BackupJsonParser.parse(BackupJsonBuilder.build(empty)))
    }

    @Test
    fun parse_rejectsWrongFormat() {
        val ex = assertThrows(BackupFormatException::class.java) {
            BackupJsonParser.parse("""{"format":"something-else","dbVersion":17,"tables":{}}""")
        }
        assert(ex.message!!.contains("Unrecognized"))
    }

    @Test
    fun parse_rejectsWrongDbVersion() {
        val ex = assertThrows(BackupFormatException::class.java) {
            BackupJsonParser.parse("""{"format":"stochastic-strength-backup","dbVersion":16,"tables":{}}""")
        }
        assert(ex.message!!.contains("v16"))
    }
}
```

(Constants used here are verified against `data/model/`: `BaselineChangeReason.DETRAIN`/`OVERRIDE`, `MuscleGroup.CHEST/TRICEPS/SHOULDERS`, `Equipment.BARBELL`, `Sex.MALE`, `StrengthLevel.MEDIUM`, `SetFeedback.RIR_2_4`.)

- [ ] **Step 4: Run the test to verify it fails (compiles, asserts)**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.backup.BackupJsonTest"`
Expected: PASS once Steps 1–2 are in place. If `BackupJson.kt`/`WorkoutBackup.kt` are absent it fails to compile — that is the "red" state proving the test exercises real code.

- [ ] **Step 5: Make tests pass**

If any enum constant names differed, fix the test (and only the test) per the NOTE, then re-run Step 4 until green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/WorkoutBackup.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupJson.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupJsonTest.kt
git commit -m "feat: backup model + JSON builder/parser"
```

---

## Task 2: DAO additions + BackupManager export & destructive import

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/ExerciseDao.kt` (add `deleteAll`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/KnownLocationDao.kt` (add `deleteAll`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSessionDao.kt` (add `deleteAll`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSetDao.kt` (add `deleteAll`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/UserProfileDao.kt` (add `deleteAll`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/LocationExcludedExerciseDao.kt` (add `getAll`, `deleteAll`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineOverrideDao.kt` (add `getAll`, `deleteAll`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/ExerciseHurtStateDao.kt` (add `getAll`, `deleteAll`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/ExerciseStrengthOverrideDao.kt` (add `getAll`, `deleteAll`)
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupManager.kt`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupManagerTest.kt`

**Interfaces:**
- Consumes: `WorkoutBackup`, `BackupJsonBuilder`, `BackupJsonParser` (Task 1); `WorkoutRepository.replayDerivedState()`.
- Produces:
  - `class BackupManager(db: AppDatabase, repository: WorkoutRepository)`
  - `suspend fun BackupManager.export(): WorkoutBackup`
  - `suspend fun BackupManager.importDestructive(backup: WorkoutBackup)`
  - DAO methods `getAll()` / `deleteAll()` listed above.

- [ ] **Step 1: Add the DAO methods**

In `ExerciseDao.kt`, `KnownLocationDao.kt`, `WorkoutSessionDao.kt`, `WorkoutSetDao.kt` (each already has `getAll()`), add:

```kotlin
    @Query("DELETE FROM exercises")
    suspend fun deleteAll()
```
(use table names `known_locations`, `workout_sessions`, `workout_sets` respectively.)

In `UserProfileDao.kt` add:
```kotlin
    @Query("SELECT * FROM user_profile")
    suspend fun getAll(): List<UserProfile>

    @Query("DELETE FROM user_profile")
    suspend fun deleteAll()
```

In `LocationExcludedExerciseDao.kt` add:
```kotlin
    @Query("SELECT * FROM location_excluded_exercises")
    suspend fun getAll(): List<LocationExcludedExercise>

    @Query("DELETE FROM location_excluded_exercises")
    suspend fun deleteAll()
```

In `BaselineOverrideDao.kt` add:
```kotlin
    @Query("SELECT * FROM baseline_override")
    suspend fun getAll(): List<BaselineOverride>

    @Query("DELETE FROM baseline_override")
    suspend fun deleteAll()
```

In `ExerciseHurtStateDao.kt` add:
```kotlin
    @Query("SELECT * FROM exercise_hurt_state")
    suspend fun getAll(): List<ExerciseHurtState>

    @Query("DELETE FROM exercise_hurt_state")
    suspend fun deleteAll()
```

In `ExerciseStrengthOverrideDao.kt` add:
```kotlin
    @Query("SELECT * FROM exercise_strength_override")
    suspend fun getAll(): List<ExerciseStrengthOverride>

    @Query("DELETE FROM exercise_strength_override")
    suspend fun deleteAll()
```

Ensure each DAO file imports the entity type it now returns (Android Studio / the existing import block will already have most). For `UserProfileDao` add `import ...data.model.UserProfile` if missing.

- [ ] **Step 2: Write `BackupManager.kt` (export + destructive only)**

```kotlin
package io.github.fowles.stochastic_strength.domain.backup

import androidx.room.withTransaction
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.domain.WorkoutRepository

class BackupManager(
    private val db: AppDatabase,
    private val repository: WorkoutRepository,
) {
    suspend fun export(): WorkoutBackup = WorkoutBackup(
        formatVersion = WorkoutBackup.FORMAT_VERSION,
        dbVersion = WorkoutBackup.DB_VERSION,
        exportedAt = System.currentTimeMillis(),
        exercises = db.exerciseDao().getAll(),
        knownLocations = db.knownLocationDao().getAll(),
        locationExcludedExercises = db.locationExcludedExerciseDao().getAll(),
        workoutSessions = db.workoutSessionDao().getAll(),
        workoutSets = db.workoutSetDao().getAll(),
        userProfile = db.userProfileDao().getAll(),
        baselineOverrides = db.baselineOverrideDao().getAll(),
        exerciseHurtState = db.exerciseHurtStateDao().getAll(),
        exerciseStrengthOverrides = db.exerciseStrengthOverrideDao().getAll(),
    )

    /** Wipes all input tables and reloads the backup verbatim (ids preserved), then replays. */
    suspend fun importDestructive(backup: WorkoutBackup) {
        db.withTransaction {
            db.workoutSetDao().deleteAll()
            db.workoutSessionDao().deleteAll()
            db.exerciseHurtStateDao().deleteAll()
            db.exerciseStrengthOverrideDao().deleteAll()
            db.baselineOverrideDao().deleteAll()
            db.locationExcludedExerciseDao().deleteAll()
            db.userProfileDao().deleteAll()
            db.exerciseDao().deleteAll()
            db.knownLocationDao().deleteAll()

            backup.exercises.forEach { db.exerciseDao().insert(it) }
            backup.knownLocations.forEach { db.knownLocationDao().insert(it) }
            db.locationExcludedExerciseDao().insertAll(backup.locationExcludedExercises)
            backup.workoutSessions.forEach { db.workoutSessionDao().insert(it) }
            backup.workoutSets.forEach { db.workoutSetDao().insert(it) }
            backup.userProfile.forEach { db.userProfileDao().insert(it) }
            backup.baselineOverrides.forEach { db.baselineOverrideDao().insert(it) }
            backup.exerciseHurtState.forEach { db.exerciseHurtStateDao().upsert(it) }
            backup.exerciseStrengthOverrides.forEach { db.exerciseStrengthOverrideDao().insert(it) }
        }
        repository.replayDerivedState()
    }
}
```

- [ ] **Step 3: Write failing instrumented test for export + destructive**

Create `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupManagerTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = WorkoutRepository(db)
        manager = BackupManager(db, repository)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.exerciseDao().insert(Exercise(id = 0, name = "Bench Press",
            primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL))
        db.exerciseDao().insert(Exercise(id = 0, name = "Squat",
            primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL))
        val sid = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000, endTime = 2000))
        db.workoutSetDao().insert(WorkoutSet(sessionId = sid, exerciseId = 1, setNumber = 1,
            targetWeight = 60f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = 1500))
    }

    @Test
    fun destructiveImport_reproducesRowsAndIds() = runBlocking {
        seed()
        val backup = manager.export()

        // Mutate the DB so we can prove the import replaced it.
        db.workoutSetDao().deleteAll()
        db.workoutSessionDao().deleteAll()
        db.exerciseDao().deleteAll()

        manager.importDestructive(backup)

        assertEquals(backup.exercises, db.exerciseDao().getAll())
        assertEquals(backup.workoutSessions, db.workoutSessionDao().getAll())
        assertEquals(backup.workoutSets, db.workoutSetDao().getAll())
    }
}
```

(`MuscleGroup.QUADS` and `SetFeedback.RIR_2_4` are verified. `WorkoutSessionDao.getAll()` orders by `startTime DESC` — the single-session assertion is order-independent here.)

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.backup.BackupManagerTest"`
Expected: PASS. (Before Step 1–2 it won't compile — the red state.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/dao/ \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupManager.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupManagerTest.kt
git commit -m "feat: backup export + destructive import"
```

---

## Task 3: Additive import (name remapping)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupManager.kt`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupManagerTest.kt` (add cases)

**Interfaces:**
- Produces:
  - `data class AdditiveResult(val sessionsAdded: Int, val exercisesCreated: Int, val locationsCreated: Int, val setsSkipped: Int)`
  - `suspend fun BackupManager.importAdditive(backup: WorkoutBackup): AdditiveResult`

- [ ] **Step 1: Add `AdditiveResult` + `importAdditive` to `BackupManager.kt`**

Add this data class above the class and the method inside it:

```kotlin
data class AdditiveResult(
    val sessionsAdded: Int,
    val exercisesCreated: Int,
    val locationsCreated: Int,
    val setsSkipped: Int,
)
```

```kotlin
    /**
     * Merges only the backup's sessions + sets into the current data. Exercises and locations
     * are matched to the local library by name; missing ones are created. Each imported session
     * gets a fresh local id; its sets are remapped accordingly. Profile/overrides/hurt-state are
     * left untouched. Sets whose exercise cannot be resolved are skipped.
     */
    suspend fun importAdditive(backup: WorkoutBackup): AdditiveResult {
        var exercisesCreated = 0
        var locationsCreated = 0
        var setsSkipped = 0
        var sessionsAdded = 0

        db.withTransaction {
            // name -> local exercise id
            val exerciseByName = db.exerciseDao().getAll().associate { it.name to it.id }.toMutableMap()
            val backupExerciseById = backup.exercises.associateBy { it.id }
            // name -> local location id
            val locationByName = db.knownLocationDao().getAll().associate { it.name to it.id }.toMutableMap()
            val backupLocationById = backup.knownLocations.associateBy { it.id }

            suspend fun resolveExerciseId(backupExerciseId: Long): Long? {
                val def = backupExerciseById[backupExerciseId] ?: return null
                exerciseByName[def.name]?.let { return it }
                val newId = db.exerciseDao().insert(def.copy(id = 0))
                exerciseByName[def.name] = newId
                exercisesCreated++
                return newId
            }

            suspend fun resolveLocationId(backupLocationId: Long?): Long? {
                if (backupLocationId == null) return null
                val def = backupLocationById[backupLocationId] ?: return null
                locationByName[def.name]?.let { return it }
                val newId = db.knownLocationDao().insert(def.copy(id = 0))
                locationByName[def.name] = newId
                locationsCreated++
                return newId
            }

            val setsBySession = backup.workoutSets.groupBy { it.sessionId }
            for (session in backup.workoutSessions) {
                val newLocationId = resolveLocationId(session.locationId)
                val newSessionId = db.workoutSessionDao().insert(
                    session.copy(id = 0, locationId = newLocationId)
                )
                sessionsAdded++
                for (set in setsBySession[session.id].orEmpty()) {
                    val newExerciseId = resolveExerciseId(set.exerciseId)
                    if (newExerciseId == null) {
                        setsSkipped++
                        continue
                    }
                    db.workoutSetDao().insert(
                        set.copy(id = 0, sessionId = newSessionId, exerciseId = newExerciseId)
                    )
                }
            }
        }
        repository.replayDerivedState()
        return AdditiveResult(sessionsAdded, exercisesCreated, locationsCreated, setsSkipped)
    }
```

- [ ] **Step 2: Add failing instrumented tests to `BackupManagerTest.kt`**

```kotlin
    @Test
    fun additiveImport_matchesByName_andCreatesMissing() = runBlocking {
        // Local library: "Bench Press" exists locally with a different id than in the backup.
        val localBench = db.exerciseDao().insert(Exercise(id = 0, name = "Bench Press",
            primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL))

        // Backup references "Bench Press" at id 5 and a brand-new "Deadlift" at id 6.
        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0,
            exercises = listOf(
                Exercise(id = 5, name = "Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
                Exercise(id = 6, name = "Deadlift", primaryMuscle = MuscleGroup.BACK, equipment = Equipment.BARBELL),
            ),
            knownLocations = emptyList(), locationExcludedExercises = emptyList(),
            workoutSessions = listOf(WorkoutSession(id = 9, startTime = 1000, endTime = 2000)),
            workoutSets = listOf(
                WorkoutSet(id = 1, sessionId = 9, exerciseId = 5, setNumber = 1, targetWeight = 60f, targetReps = 5),
                WorkoutSet(id = 2, sessionId = 9, exerciseId = 6, setNumber = 1, targetWeight = 100f, targetReps = 5),
            ),
            userProfile = emptyList(), baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
            exerciseStrengthOverrides = emptyList(),
        )

        val result = manager.importAdditive(backup)

        assertEquals(1, result.sessionsAdded)
        assertEquals(1, result.exercisesCreated) // only Deadlift
        assertEquals(0, result.setsSkipped)

        val sessions = db.workoutSessionDao().getAll()
        assertEquals(1, sessions.size)
        val newSessionId = sessions.first().id

        val sets = db.workoutSetDao().getAll().sortedBy { it.setNumber }
        assertEquals(2, sets.size)
        // Bench set remapped to the pre-existing local id; all sets point at the new session.
        assertEquals(localBench, sets[0].exerciseId)
        assert(sets.all { it.sessionId == newSessionId })
    }

    @Test
    fun additiveImport_leavesProfileUntouched() = runBlocking {
        db.userProfileDao().insert(io.github.fowles.stochastic_strength.data.model.UserProfile(
            id = 1, sex = io.github.fowles.stochastic_strength.data.model.Sex.MALE,
            strengthLevel = io.github.fowles.stochastic_strength.data.model.StrengthLevel.MEDIUM,
            weightUnit = io.github.fowles.stochastic_strength.data.model.WeightUnit.KG,
        ))
        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION, dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0, exercises = emptyList(), knownLocations = emptyList(),
            locationExcludedExercises = emptyList(), workoutSessions = emptyList(), workoutSets = emptyList(),
            userProfile = listOf(io.github.fowles.stochastic_strength.data.model.UserProfile(
                id = 1, sex = io.github.fowles.stochastic_strength.data.model.Sex.FEMALE,
                strengthLevel = io.github.fowles.stochastic_strength.data.model.StrengthLevel.LOW,
                weightUnit = io.github.fowles.stochastic_strength.data.model.WeightUnit.LBS,
            )),
            baselineOverrides = emptyList(), exerciseHurtState = emptyList(),
            exerciseStrengthOverrides = emptyList(),
        )

        manager.importAdditive(backup)

        // Local profile preserved (KG/MALE), not overwritten by the backup's LBS/FEMALE.
        assertEquals(io.github.fowles.stochastic_strength.data.model.WeightUnit.KG,
            db.userProfileDao().getProfile()!!.weightUnit)
    }
```

(Verified: `MuscleGroup.BACK`, `Sex.FEMALE`/`MALE`, `StrengthLevel.LOW`/`MEDIUM`.)

- [ ] **Step 3: Run the tests**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.backup.BackupManagerTest"`
Expected: PASS (all four cases).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupManager.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/backup/BackupManagerTest.kt
git commit -m "feat: additive backup import with name remapping"
```

---

## Task 4: App wiring + History UI (overflow menu, SAF, import dialog)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryViewModel.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryScreen.kt`

**Interfaces:**
- Consumes: `BackupManager`, `BackupJsonBuilder`, `BackupJsonParser`, `BackupFormatException`, `AdditiveResult` (Tasks 1–3).
- Produces: `StochasticStrengthApp.backupManager`; VM `exportTo(uri)`, `importFrom(uri, ImportMode)`; `enum class ImportMode { ADDITIVE, DESTRUCTIVE }`.

- [ ] **Step 1: Add `backupManager` to `StochasticStrengthApp.kt`**

Add the import and a lazy property next to `workoutRepository`:

```kotlin
import io.github.fowles.stochastic_strength.domain.backup.BackupManager
```
```kotlin
    val backupManager: BackupManager by lazy {
        BackupManager(database, workoutRepository)
    }
```

- [ ] **Step 2: Add export/import to `HistoryViewModel.kt`**

Add `ImportMode`, a one-shot message field on `HistoryState`, and the two methods. Insert near the top of the file:

```kotlin
import android.net.Uri
import io.github.fowles.stochastic_strength.domain.backup.BackupFormatException
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonBuilder
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ImportMode { ADDITIVE, DESTRUCTIVE }
```

Add to `HistoryState`:
```kotlin
    val message: String? = null,
```

Add inside the class:
```kotlin
    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                val backup = app.backupManager.export()
                val json = BackupJsonBuilder.build(backup)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: error("Could not open file for writing")
                }
                _state.value = _state.value.copy(message = "History exported.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Export failed: ${e.message}")
            }
        }
    }

    fun importFrom(uri: Uri, mode: ImportMode) {
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("Could not open file for reading")
                }
                val backup = BackupJsonParser.parse(json)
                val summary = when (mode) {
                    ImportMode.DESTRUCTIVE -> {
                        app.backupManager.importDestructive(backup)
                        "History replaced."
                    }
                    ImportMode.ADDITIVE -> {
                        val r = app.backupManager.importAdditive(backup)
                        "Imported ${r.sessionsAdded} sessions (" +
                            "${r.exercisesCreated} new exercises, ${r.setsSkipped} sets skipped)."
                    }
                }
                reload()
                _state.value = _state.value.copy(message = summary)
            } catch (e: BackupFormatException) {
                _state.value = _state.value.copy(message = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Import failed: ${e.message}")
            }
        }
    }
```

Refactor the existing `init { viewModelScope.launch { ... } }` body into a private `suspend fun reloadInternal()` and call it from both `init` and a new `private fun reload() { viewModelScope.launch { reloadInternal() } }`, so import can refresh the list. Concretely: move the body of the `init` launch (everything that computes and assigns `_state.value = HistoryState(...)`) into `private suspend fun reloadInternal()`, then make `init { viewModelScope.launch { reloadInternal() } }` and add `private fun reload() { viewModelScope.launch { reloadInternal() } }`. Preserve `message` across reloads by using `_state.value.copy(...)` is unnecessary — `reloadInternal` builds a fresh `HistoryState`; set its `message` from the caller after `reload()` returns as shown above (the `reload()` call is fire-and-forget, so assign `message` in the same `_state.value.copy` after — acceptable since reload completes quickly; if ordering matters, fold the message into `reloadInternal` via a parameter `message: String? = null`).

To keep ordering deterministic, give `reloadInternal` a `message` parameter:
```kotlin
    private suspend fun reloadInternal(message: String? = null) {
        // ... existing computation ...
        _state.value = HistoryState(
            muscleStrengths = muscleStrengths,
            referenceExerciseIds = referenceExerciseIds,
            sessions = sessions,
            weightUnit = weightUnit,
            loading = false,
            message = message,
        )
    }
```
and in `importFrom`, replace the `reload(); _state.value = _state.value.copy(message = summary)` lines with `reloadInternal(summary)` (drop the separate `reload()` helper if unused).

- [ ] **Step 3: Add the overflow menu, SAF launchers, and import dialog to `HistoryScreen.kt`**

Add imports:
```kotlin
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

Inside `HistoryScreen`, after `val state by viewModel.state.collectAsState()`, add local UI state and launchers:
```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingImportUri = uri }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
```

Change the `Scaffold` to add the actions slot and a snackbar host:
```kotlin
    Scaffold(
        topBar = {
            BackTopAppBar(
                title = "History",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Export history") },
                            onClick = {
                                menuExpanded = false
                                exportLauncher.launch("stochastic-strength-backup.json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Import history") },
                            onClick = {
                                menuExpanded = false
                                importLauncher.launch(arrayOf("application/json"))
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
```

Add the import-mode dialog near the existing delete `AlertDialog` (inside the `Scaffold` content lambda, before the `if (state.loading)` block):
```kotlin
        val importUri = pendingImportUri
        if (importUri != null) {
            AlertDialog(
                onDismissRequest = { pendingImportUri = null },
                title = { Text("Import history") },
                text = { Text("Add these workouts to your current history, or replace everything?") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingImportUri = null
                        viewModel.importFrom(importUri, ImportMode.DESTRUCTIVE)
                    }) {
                        Text("Replace all", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            pendingImportUri = null
                            viewModel.importFrom(importUri, ImportMode.ADDITIVE)
                        }) { Text("Add") }
                        TextButton(onClick = { pendingImportUri = null }) { Text("Cancel") }
                    }
                },
            )
        }
```

(`Row` and `MoreVert`/`Icon`/`IconButton`/`Text`/`TextButton`/`MaterialTheme` are already imported or added above. Ensure `androidx.compose.foundation.layout.Row` is imported — it is, since `Row` is used in `SessionRow`.)

- [ ] **Step 4: Build to verify everything compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual smoke test (on device/emulator)**

Launch the app → History → `⋮` → Export history → pick a location → confirm the snackbar "History exported." Then `⋮` → Import history → pick that file → choose **Add** (snackbar reports counts) and separately **Replace all** (snackbar "History replaced."). Confirm a non-backup file shows the parse-error message and a v-mismatch file shows the version message (can hand-edit `dbVersion` in the exported JSON to test).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryViewModel.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryScreen.kt
git commit -m "feat: history export/import UI behind overflow menu"
```

---

## Task 5: Full regression check

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the full instrumented suite**

Run: `./gradlew :app:connectedAndroidTest`
Expected: BUILD SUCCESSFUL (includes `BackupJsonTest`, `BackupManagerTest`).

- [ ] **Step 3: Lint**

Run: `./gradlew :app:lint`
Expected: no new errors introduced by these files.

---

## Self-Review Notes

- **Spec coverage:** export-all-input-tables (Task 2 `export`), JSON format + envelope/version refusal (Task 1), destructive import preserving ids + replay (Task 2), additive name-matching + create-missing + fresh ids + profile untouched + counts (Task 3), `...` overflow menu + SAF + mode dialog + confirmation + result toast (Task 4), tests (Tasks 1–3, 5). `LocationEquipment` correctly omitted (not a registered entity).
- **Test environment:** all JSON/Room tests are instrumented because `org.json` is unavailable in this project's plain JVM unit tests — a deliberate deviation from the spec's "JVM unit tests" wording, documented in Global Constraints.
- **Enum constants:** all referenced `MuscleGroup`/`Equipment`/`Sex`/`StrengthLevel`/`SetFeedback`/`BaselineChangeReason` constants were verified against `data/model/` while writing the plan (`StrengthLevel` is `LOW/MEDIUM/HIGH`; `SetFeedback` is `TOO_HARD/HURT/RIR_0_1/RIR_2_4/RIR_5_PLUS`; `BaselineChangeReason` includes `DETRAIN`).
