package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup

@Dao
interface BaselineOverrideDao {

    @Query("SELECT * FROM baseline_override WHERE sessionId IS NULL")
    suspend fun getInitials(): List<BaselineOverride>

    @Query("SELECT * FROM baseline_override WHERE sessionId IS NOT NULL")
    suspend fun getNonInitials(): List<BaselineOverride>

    @Query("SELECT * FROM baseline_override WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<BaselineOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(override: BaselineOverride): Long

    @Query("DELETE FROM baseline_override WHERE sessionId IS NULL AND muscleGroup = :muscleGroup")
    suspend fun deleteInitialFor(muscleGroup: MuscleGroup)
}
