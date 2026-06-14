package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * User-authored baseline adjustment.
 *
 * - `sessionId = null` means the *initial* baseline for [muscleGroup], used as the replay
 *   starting point. At most one such row per muscle.
 * - `sessionId = N` means the user manually adjusted the baseline at session N. At most one
 *   row per (sessionId, muscleGroup) pair.
 */
@Entity(tableName = "baseline_override")
data class BaselineOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val muscleGroup: MuscleGroup,
    val baselineWeight: Float,
    val asOf: Long,
)
