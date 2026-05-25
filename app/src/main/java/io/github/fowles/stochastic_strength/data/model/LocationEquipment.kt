package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity

@Entity(
    tableName = "location_equipment",
    primaryKeys = ["locationId", "equipment"],
)
data class LocationEquipment(
    val locationId: Long,
    val equipment: Equipment,
)
