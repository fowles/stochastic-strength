package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backup.WorkoutBackup

/** Builders for synthetic histories. Exercise names must exist in ExerciseCoefficients.byName. */
object BacktestFixtures {
    const val DAY_MS = 24L * 60 * 60 * 1000

    fun backup(
        exercises: List<Exercise>,
        sessions: List<WorkoutSession>,
        sets: List<WorkoutSet>,
        baselineOverrides: List<BaselineOverride> = emptyList(),
    ): WorkoutBackup = WorkoutBackup(
        formatVersion = WorkoutBackup.FORMAT_VERSION,
        dbVersion = WorkoutBackup.DB_VERSION,
        exportedAt = 0L,
        exercises = exercises,
        knownLocations = emptyList(),
        locationExcludedExercises = emptyList(),
        workoutSessions = sessions,
        workoutSets = sets,
        userProfile = emptyList(),
        baselineOverrides = baselineOverrides,
        exerciseHurtState = emptyList(),
    )
}
