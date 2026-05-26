package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity

@Entity(tableName = "location_excluded_exercises", primaryKeys = ["locationId", "exerciseId"])
data class LocationExcludedExercise(
    val locationId: Long,
    val exerciseId: Long,
)
