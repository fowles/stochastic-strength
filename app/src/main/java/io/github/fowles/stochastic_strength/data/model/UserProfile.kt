package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Long = 1,
    val sex: Sex,
    val strengthLevel: StrengthLevel,
    val weightUnit: WeightUnit,
    val preferredExerciseCount: Int? = null,
    // Monotonically increasing counter for one-shot upgrade passes that re-derive state
    // (backfills, normalizer wire-ups, etc.). See DerivedStateBackfill.
    val derivedStateVersion: Int = 0,
)
