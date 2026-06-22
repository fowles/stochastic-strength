package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansion

/**
 * One-time expansion of the legacy per-muscle baseline_override rows into per-exercise
 * exercise_strength_override rows, using the seed coefficients. Idempotent: gated by the
 * user_profile.perExerciseSeedsBackfilled flag. Runs once at launch from [DerivedStateBackfill].
 */
class ExerciseStrengthOverrideBackfill(private val db: AppDatabase) {

    suspend fun run() {
        val profile = db.userProfileDao().getProfile() ?: return
        if (profile.perExerciseSeedsBackfilled) return

        val muscleOverrides =
            (db.baselineOverrideDao().getInitials() + db.baselineOverrideDao().getNonInitials())
                .map {
                    ExerciseSeedExpansion.MuscleOverrideRow(
                        sessionId = it.sessionId,
                        muscleGroup = it.muscleGroup,
                        baselineWeight = it.baselineWeight,
                        asOf = it.asOf,
                        reason = it.reason,
                    )
                }
        val exercises = db.exerciseDao().getAll()
        val rows = planBackfill(alreadyDone = false, muscleOverrides = muscleOverrides, exercises = exercises)
        for (row in rows) db.exerciseStrengthOverrideDao().insert(row)
        db.userProfileDao().insert(profile.copy(perExerciseSeedsBackfilled = true))
    }
}

internal fun planBackfill(
    alreadyDone: Boolean,
    muscleOverrides: List<ExerciseSeedExpansion.MuscleOverrideRow>,
    exercises: List<Exercise>,
): List<ExerciseStrengthOverride> =
    if (alreadyDone) emptyList()
    else ExerciseSeedExpansion.expand(muscleOverrides, exercises, ExerciseCoefficients)
