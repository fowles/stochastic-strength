package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercises")
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val primaryMuscle: MuscleGroup,
    val secondaryMuscles: List<MuscleGroup> = emptyList(),
    val equipment: Equipment,
    val isDisliked: Boolean = false,
    val isUnilateral: Boolean = false,
    val isAsymmetric: Boolean = false,
    val isTimed: Boolean = false,
)

/** True when this lift loads a symmetric bar with plates per side (standard warmup + plate breakdown). */
val Exercise.usesBarPlates: Boolean
    get() = equipment == Equipment.BARBELL && !isAsymmetric
