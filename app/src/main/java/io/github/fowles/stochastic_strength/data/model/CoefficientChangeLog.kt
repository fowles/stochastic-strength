package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "coefficient_change_log")
data class CoefficientChangeLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val previousCoefficient: Float? = null,
    val coefficient: Float,
    val heuristicName: String,
    val heuristicMetadata: String? = null,
    val computedAt: Long,
)
