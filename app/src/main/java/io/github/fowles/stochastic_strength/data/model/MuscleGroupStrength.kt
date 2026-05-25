package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "muscle_group_strength")
data class MuscleGroupStrength(
    @PrimaryKey val muscleGroup: MuscleGroup,
    val baselineWeight: Float,
)
