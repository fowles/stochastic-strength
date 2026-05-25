package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_sessions")
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val locationId: Long? = null,
    val startTime: Long,
    val endTime: Long? = null,
)
