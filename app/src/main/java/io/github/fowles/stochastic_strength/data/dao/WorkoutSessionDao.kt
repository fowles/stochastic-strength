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
}
