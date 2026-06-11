package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_sets")
data class WorkoutSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val setNumber: Int,
    val targetWeight: Float,
    val targetReps: Int,
    val actualReps: Int? = null,
    val feedback: SetFeedback? = null,
    val completedAt: Long? = null,
    val durationSeconds: Int? = null,
)
