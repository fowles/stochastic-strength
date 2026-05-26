package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.fowles.stochastic_strength.data.model.KnownLocation

@Dao
interface KnownLocationDao {
    @Query("SELECT * FROM known_locations")
    suspend fun getAll(): List<KnownLocation>

    @Insert
    suspend fun insert(location: KnownLocation): Long

    @Update
    suspend fun update(location: KnownLocation)

    @Query("DELETE FROM known_locations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
