package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownLocationDao {
    @Query("SELECT * FROM known_locations")
    suspend fun getAll(): List<KnownLocation>

    @Query("SELECT * FROM known_locations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): KnownLocation?

    @Query("SELECT * FROM known_locations")
    fun observeAll(): Flow<List<KnownLocation>>

    @Insert
    suspend fun insert(location: KnownLocation): Long

    @Update
    suspend fun update(location: KnownLocation)

    @Query("UPDATE known_locations SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("DELETE FROM known_locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM known_locations")
    suspend fun deleteAll()
}
