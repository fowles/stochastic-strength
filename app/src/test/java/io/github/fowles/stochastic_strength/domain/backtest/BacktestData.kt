package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.CoefficientCompression
import io.github.fowles.stochastic_strength.domain.CoefficientGuesses
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonParser
import io.github.fowles.stochastic_strength.domain.backup.WorkoutBackup
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansion
import io.github.fowles.stochastic_strength.domain.progression.SeedBelief
import java.io.File

/**
 * The parsed real (or synthetic) history for backtesting. history.json is a full app backup
 * (personal data, gitignored) exported via HistoryScreen; tests skip when it is absent.
 */
class BacktestData private constructor(
    val backup: WorkoutBackup,
    val weightUnit: WeightUnit,
    /** Active (non-disliked) exercise id → coefficient. THE single coefficient source for replay. */
    val coefById: Map<Long, Float>,
) {
    val sessions = backup.workoutSessions
        .filter { it.endTime != null }
        .sortedWith(compareBy({ it.endTime!! }, { it.id }))

    val setsBySession = backup.workoutSets.groupBy { it.sessionId }
        .mapValues { (_, s) -> s.sortedBy { it.id } }

    private val exerciseMuscle = backup.exercises.associate { it.id to it.primaryMuscle }

    /** Mirrors ReplaySnapshot.loadStaticFromDb: muscle map from all exercises; coefficients from
     *  active exercises plus any disliked lift with completed sets (exactly loadStaticFromDb). */
    fun newSnapshot(): ReplaySnapshot = ReplaySnapshot(
        exerciseMuscle = exerciseMuscle,
        seedCoefficients = coefById,
    )

    private val seeds = ExerciseSeedExpansion.buildSeeds(
        initialOverrides = backup.baselineOverrides.filter { it.sessionId == null },
        sessionOverrides = backup.baselineOverrides.filter { it.sessionId != null },
        sex = backup.userProfile.firstOrNull()?.sex ?: Sex.MALE,
        level = backup.userProfile.firstOrNull()?.strengthLevel ?: StrengthLevel.MEDIUM,
        exerciseMuscle = exerciseMuscle,
        coefById = coefById,
    )

    val initialSeeds: List<SeedBelief> = seeds.initial
    val sessionSeeds: Map<Long, List<SeedBelief>> = seeds.bySession

    /** A copy with coefficients recomputed as `CoefficientGuesses.raw^lambda` — for the λ fit sweep.
     *  Recompiles BOTH the snapshot coefficients and the prebuilt seeds from one map. */
    fun withCoefLambda(lambda: Float): BacktestData =
        BacktestData(backup, weightUnit, compressedCoef(backup, lambda))

    companion object {
        private val dir = File("src/test/resources/backtest")
        fun historyFile(): File = File(dir, "history.json")
        fun baselineFile(): File = File(dir, "phase0_baseline.json")

        /** Active plus disliked-but-trained exercise ids (mirrors ReplaySnapshot.loadStaticFromDb). */
        private fun seedExercises(backup: WorkoutBackup): List<io.github.fowles.stochastic_strength.data.model.Exercise> {
            val trainedIds = backup.workoutSets.filter { it.completedAt != null }.mapTo(HashSet()) { it.exerciseId }
            return backup.exercises.filter { !it.isDisliked || it.id in trainedIds }
        }

        /** Shipped table (already compressed at ExerciseCoefficients.LAMBDA). */
        private fun shippedCoef(backup: WorkoutBackup): Map<Long, Float> =
            seedExercises(backup).associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) }

        /** Raw guesses compressed by an arbitrary λ, keyed by seed exercise id. */
        private fun compressedCoef(backup: WorkoutBackup, lambda: Float): Map<Long, Float> =
            seedExercises(backup)
                .associate { it.id to CoefficientCompression.compress(CoefficientGuesses.raw[it.name] ?: 0f, lambda) }

        fun loadOrNull(): BacktestData? {
            val f = historyFile()
            if (!f.exists()) return null
            return from(BackupJsonParser.parse(f.readText()))
        }

        fun from(backup: WorkoutBackup): BacktestData =
            BacktestData(
                backup,
                backup.userProfile.firstOrNull()?.weightUnit ?: WeightUnit.KG,
                shippedCoef(backup),
            )
    }
}
