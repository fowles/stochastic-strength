package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "known_locations")
data class KnownLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)
