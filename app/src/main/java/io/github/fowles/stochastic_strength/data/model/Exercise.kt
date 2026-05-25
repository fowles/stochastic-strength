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
    val defaultSets: Int = 3,
    val isDisliked: Boolean = false,
    val hurtFlag: Boolean = false,
)
