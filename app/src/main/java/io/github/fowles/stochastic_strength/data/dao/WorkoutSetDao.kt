package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

@Dao
interface WorkoutSetDao {
    @Insert
    suspend fun insert(set: WorkoutSet): Long

    @Update
    suspend fun update(set: WorkoutSet)

    @Query("SELECT * FROM workout_sets WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getSetsForSession(sessionId: Long): List<WorkoutSet>

    @Query("SELECT * FROM workout_sets WHERE exerciseId = :exerciseId ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getRecentSetsForExercise(exerciseId: Long, limit: Int): List<WorkoutSet>

    @Query("SELECT * FROM workout_sets WHERE exerciseId = :exerciseId ORDER BY completedAt ASC")
    suspend fun getAllForExercise(exerciseId: Long): List<WorkoutSet>

    @Query("DELETE FROM workout_sets WHERE sessionId = :sessionId AND exerciseId = :exerciseId AND setNumber = :setNumber")
    suspend fun deleteSet(sessionId: Long, exerciseId: Long, setNumber: Int)

    @Query("DELETE FROM workout_sets WHERE sessionId = :sessionId")
    suspend fun deleteAllForSession(sessionId: Long)

    @Query("""
        SELECT * FROM workout_sets
        WHERE exerciseId IN (:exerciseIds)
          AND completedAt IS NOT NULL
        ORDER BY completedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentSetsForExercises(exerciseIds: List<Long>, limit: Int): List<WorkoutSet>

    @Query("SELECT * FROM workout_sets LIMIT 1")
    suspend fun getFirst(): List<WorkoutSet>
}
