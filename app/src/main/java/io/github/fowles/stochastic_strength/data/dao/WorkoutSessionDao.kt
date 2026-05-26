package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.fowles.stochastic_strength.data.model.WorkoutSession

@Dao
interface WorkoutSessionDao {
    @Insert
    suspend fun insert(session: WorkoutSession): Long

    @Update
    suspend fun update(session: WorkoutSession)

    @Query("SELECT * FROM workout_sessions ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastSession(): WorkoutSession?

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun getById(id: Long): WorkoutSession?

    @Query("UPDATE workout_sessions SET endTime = :endTime WHERE id = :id")
    suspend fun updateEndTime(id: Long, endTime: Long)

    @Query("UPDATE workout_sessions SET locationId = :locationId WHERE id = :id")
    suspend fun updateLocationId(id: Long, locationId: Long)

    @Query("SELECT * FROM workout_sessions ORDER BY startTime DESC")
    suspend fun getAll(): List<WorkoutSession>

    @Query("DELETE FROM workout_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
