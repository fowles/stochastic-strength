package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup

@Dao
interface BaselineHistoryDao {

    @Query("SELECT * FROM baseline_history ORDER BY timestamp ASC")
    suspend fun getAll(): List<BaselineHistory>

    @Query("SELECT * FROM baseline_history WHERE muscleGroup = :muscleGroup ORDER BY timestamp ASC")
    suspend fun getForMuscle(muscleGroup: MuscleGroup): List<BaselineHistory>

    @Insert
    suspend fun insert(row: BaselineHistory): Long

    @Query("DELETE FROM baseline_history")
    suspend fun deleteAll()
}
