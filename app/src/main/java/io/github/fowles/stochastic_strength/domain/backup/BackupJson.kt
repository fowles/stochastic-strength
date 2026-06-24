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
