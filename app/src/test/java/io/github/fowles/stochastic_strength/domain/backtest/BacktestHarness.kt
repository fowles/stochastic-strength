package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonParser
import io.github.fowles.stochastic_strength.domain.backup.WorkoutBackup
import io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilder
import io.github.fowles.stochastic_strength.domain.policy.PooledBelief
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.ReplayEngine
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Replays a real exported backup (app/src/test/resources/backtest/history.json — gitignored,
 * personal data) and computes per-session prescriptions at a fixed reference rep count.
 * baseline_prescriptions.json freezes the pre-reframe prescriptions; later phases compare
 * against it. Both files live only on the developer machine.
 */
object BacktestHarness {
    const val REFERENCE_REPS = 10

    private val dir = File("src/test/resources/backtest")
    fun historyFile(): File = File(dir, "history.json")
    fun baselineFile(): File = File(dir, "baseline_prescriptions.json")

    data class Row(val sessionId: Long, val exerciseId: Long, val weightKg: Float)

    class BacktestData(val backup: WorkoutBackup, val weightUnit: WeightUnit, val history: ReplayHistory) {
        fun newSnapshot(): ReplaySnapshot = ReplaySnapshot(
            exerciseMuscle = backup.exercises.associate { it.id to it.primaryMuscle },
            // Production loads seed coefficients from getActive() (isDisliked = 0); mirror that so
            // disliked exercises neither fold nor vote in pooling, exactly as in the app.
            seedCoefficients = backup.exercises.filterNot { it.isDisliked }
                .associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) },
            exerciseEquipment = backup.exercises.associate { it.id to it.equipment },
        )
    }

    fun load(): BacktestData? {
        val f = historyFile()
        if (!f.exists()) return null
        val backup = BackupJsonParser.parse(f.readText())
        val history = ReplayHistory(
            sessions = backup.workoutSessions.filter { it.endTime != null },
            setsBySession = backup.workoutSets.groupBy { it.sessionId }
                .mapValues { (_, s) -> s.sortedBy { it.id } },
            initialOverrides = backup.exerciseStrengthOverrides.filter { it.sessionId == null },
            sessionOverrides = backup.exerciseStrengthOverrides.filter { it.sessionId != null }
                .groupBy { it.sessionId!! },
        )
        val unit = backup.userProfile.firstOrNull()?.weightUnit ?: WeightUnit.KG
        return BacktestData(backup, unit, history)
    }

    /** Prescriptions right after each session via the production policy path (belief-based semantics). */
    fun replayPolicyPrescriptions(data: BacktestData): List<Row> {
        val snapshot = data.newSnapshot()
        val projector = MuscleStrengthProjector()
        val builder = PolicyStateBuilder()
        val exercisesById = data.backup.exercises.associateBy { it.id }
        val rows = mutableListOf<Row>()
        ReplayEngine().run(data.history, snapshot) { sessionId, asOf, sets, snap, _ ->
            builder.onSession(asOf, sets, snap)
            val policyState = builder.build(snap.muscleLastObs.toMap())
            for ((muscle, ids) in snap.muscleExerciseIds) {
                val proj = projector.project(snap.currentBeliefs, snap.seedCoefficients, ids, asOf, policyState.muscleLastObs[muscle])
                val pooledMap = proj.effectiveE1rm.entries.associate { (id, e1rm) ->
                    id to PooledBelief(e1rm, proj.pooledSigma[id] ?: 0f)
                }
                val policy = PrescriptionPolicy(
                    pooled = pooledMap,
                    state = policyState,
                    config = EstimatorConfig(),
                    progressionEngine = DefaultProgressionEngine,
                    weightUnit = data.weightUnit,
                    nowMs = asOf,
                )
                for (id in ids.sorted()) {
                    val exercise = exercisesById[id] ?: continue
                    val w = policy.prescribe(exercise, REFERENCE_REPS) ?: continue
                    rows += Row(sessionId, id, w)
                }
            }
        }
        return rows
    }

    fun writeBaseline(rows: List<Row>) {
        val arr = JSONArray()
        for (r in rows) {
            arr.put(JSONObject().put("s", r.sessionId).put("e", r.exerciseId).put("w", r.weightKg.toDouble()))
        }
        baselineFile().writeText(JSONObject().put("referenceReps", REFERENCE_REPS).put("rows", arr).toString(2))
    }

    fun readBaseline(): List<Row>? {
        val f = baselineFile()
        if (!f.exists()) return null
        val root = JSONObject(f.readText())
        val arr = root.getJSONArray("rows")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Row(o.getLong("s"), o.getLong("e"), o.getDouble("w").toFloat())
        }
    }
}
