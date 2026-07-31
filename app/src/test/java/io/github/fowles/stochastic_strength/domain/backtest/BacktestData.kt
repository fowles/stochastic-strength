package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.WeightUnit
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
class BacktestData(val backup: WorkoutBackup, val weightUnit: WeightUnit) {

    val sessions = backup.workoutSessions
        .filter { it.endTime != null }
        .sortedWith(compareBy({ it.endTime!! }, { it.id }))

    val setsBySession = backup.workoutSets.groupBy { it.sessionId }
        .mapValues { (_, s) -> s.sortedBy { it.id } }

    /** Mirrors ReplaySnapshot.loadStaticFromDb: muscle map from all exercises; seed coefficients
     *  from active (non-disliked) exercises only, exactly like the DAO's getActive(). */
    fun newSnapshot(): ReplaySnapshot = ReplaySnapshot(
        exerciseMuscle = backup.exercises.associate { it.id to it.primaryMuscle },
        seedCoefficients = backup.exercises.filterNot { it.isDisliked }
            .associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) },
    )

    private val seeds = ExerciseSeedExpansion.buildSeeds(
        initialOverrides = backup.baselineOverrides.filter { it.sessionId == null },
        sessionOverrides = backup.baselineOverrides.filter { it.sessionId != null },
        sex = backup.userProfile.firstOrNull()?.sex ?: Sex.MALE,
        level = backup.userProfile.firstOrNull()?.strengthLevel ?: StrengthLevel.MEDIUM,
        exerciseMuscle = backup.exercises.associate { it.id to it.primaryMuscle },
        coefById = backup.exercises.filterNot { it.isDisliked }.associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) },
    )

    val initialSeeds: List<SeedBelief> = seeds.initial
    val sessionSeeds: Map<Long, List<SeedBelief>> = seeds.bySession

    companion object {
        private val dir = File("src/test/resources/backtest")
        fun historyFile(): File = File(dir, "history.json")
        fun baselineFile(): File = File(dir, "phase0_baseline.json")

        fun loadOrNull(): BacktestData? {
            val f = historyFile()
            if (!f.exists()) return null
            return from(BackupJsonParser.parse(f.readText()))
        }

        fun from(backup: WorkoutBackup): BacktestData =
            BacktestData(backup, backup.userProfile.firstOrNull()?.weightUnit ?: WeightUnit.KG)
    }
}
