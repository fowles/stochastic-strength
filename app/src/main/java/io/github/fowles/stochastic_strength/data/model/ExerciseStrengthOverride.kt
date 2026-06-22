package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-exercise strength seed/override (replaces the per-muscle baseline_override).
 *
 * - `sessionId = null` is the *initial* estimated 1RM for [exerciseId] (replay starting point).
 * - `sessionId = N` is a user edit or detraining adjustment applied at session N.
 * `e1rm` is an estimated 1RM in kg.
 */
@Entity(tableName = "exercise_strength_override")
data class ExerciseStrengthOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val exerciseId: Long,
    val e1rm: Float,
    val asOf: Long,
    val reason: BaselineChangeReason = BaselineChangeReason.OVERRIDE,
)
