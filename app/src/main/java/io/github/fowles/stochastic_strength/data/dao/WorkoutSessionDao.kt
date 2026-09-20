package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.WorkoutSession

@Dao
interface WorkoutSessionDao {
    @Insert
    suspend fun insert(session: WorkoutSession): Long

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun getById(id: Long): WorkoutSession?

    @Query("""
        SELECT * FROM workout_sessions
        WHERE endTime IS NOT NULL
        ORDER BY startTime DESC
        LIMIT :limit
    """)
    suspend fun getRecentCompletedSessions(limit: Int): List<WorkoutSession>

    @Query("UPDATE workout_sessions SET endTime = :endTime WHERE id = :id")
    suspend fun updateEndTime(id: Long, endTime: Long)

    @Query("SELECT * FROM workout_sessions ORDER BY startTime DESC")
    suspend fun getAll(): List<WorkoutSession>

    @Query("DELETE FROM workout_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE workout_sessions SET stravaActivityId = :activityId WHERE id = :id")
    suspend fun updateStravaActivityId(id: Long, activityId: Long)

    @Query("DELETE FROM workout_sessions")
    suspend fun deleteAll()
}
