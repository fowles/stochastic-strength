package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "baseline_history")
data class BaselineHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    val muscleGroup: MuscleGroup,
    val previousBaseline: Float,
    val newBaseline: Float,
    val changeReason: BaselineChangeReason,
    val feedbacks: String? = null,
    val sessionReps: Int? = null,
    val minReductionFraction: Float? = null,
    val timestamp: Long,
    val heuristicName: String? = null,
    val heuristicMetadata: String? = null,
)
