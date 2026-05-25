package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercise_state")
data class ExerciseState(
    @PrimaryKey val exerciseId: Long,
    val currentWeight: Float = 0f,
    val currentReps: Int = 10,
    val currentSets: Int = 3,
    val lastSessionId: Long? = null,
    val consecutiveRir5PlusSessions: Int = 0,
)
