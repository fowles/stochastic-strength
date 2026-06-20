package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pure input — never written by replay.
 *
 * A row is present iff the exercise has been marked at least once. `isHurt` reflects the current
 * state (`true` = marked, `false` = explicitly cleared). The absence of a row is read identically
 * to `isHurt = false`. `asOf` is the timestamp of the most recent state change.
 */
@Entity(tableName = "exercise_hurt_state")
data class ExerciseHurtState(
    @PrimaryKey val exerciseId: Long,
    val isHurt: Boolean,
    val asOf: Long,
)
