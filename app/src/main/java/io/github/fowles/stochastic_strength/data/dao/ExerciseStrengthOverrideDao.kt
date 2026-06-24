package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride

@Dao
interface ExerciseStrengthOverrideDao {

    @Query("SELECT * FROM exercise_strength_override WHERE sessionId IS NULL")
    suspend fun getInitials(): List<ExerciseStrengthOverride>

    @Query("SELECT * FROM exercise_strength_override WHERE sessionId IS NOT NULL")
    suspend fun getNonInitials(): List<ExerciseStrengthOverride>

    @Query("SELECT * FROM exercise_strength_override WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<ExerciseStrengthOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ExerciseStrengthOverride): Long

    @Query("DELETE FROM exercise_strength_override WHERE sessionId IS NULL AND exerciseId = :exerciseId")
    suspend fun deleteInitialFor(exerciseId: Long)

    @Query("SELECT * FROM exercise_strength_override")
    suspend fun getAll(): List<ExerciseStrengthOverride>

    @Query("DELETE FROM exercise_strength_override")
    suspend fun deleteAll()
}
