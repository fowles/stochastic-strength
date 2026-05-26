package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.LocationEquipment

@Dao
interface LocationEquipmentDao {
    @Query("SELECT equipment FROM location_equipment WHERE locationId = :locationId")
    suspend fun getEquipmentForLocation(locationId: Long): List<Equipment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(equipment: List<LocationEquipment>)

    @Query("DELETE FROM location_equipment WHERE locationId = :locationId AND equipment = :equipment")
    suspend fun deleteEquipment(locationId: Long, equipment: Equipment)

    @Query("DELETE FROM location_equipment WHERE locationId = :locationId")
    suspend fun deleteAllForLocation(locationId: Long)
}
