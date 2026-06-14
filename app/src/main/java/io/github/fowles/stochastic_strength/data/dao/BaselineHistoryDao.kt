package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface BaselineHistoryDao {

    @Query("SELECT * FROM baseline_history ORDER BY timestamp ASC")
    suspend fun getAll(): List<BaselineHistory>

    @Query("SELECT * FROM baseline_history WHERE muscleGroup = :muscleGroup ORDER BY timestamp ASC")
    suspend fun getForMuscle(muscleGroup: MuscleGroup): List<BaselineHistory>

    @Query("SELECT * FROM baseline_history WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<BaselineHistory>

    @Query("SELECT * FROM baseline_history ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<BaselineHistory>>

    @Insert
    suspend fun insert(row: BaselineHistory): Long

    @Query("DELETE FROM baseline_history")
    suspend fun deleteAll()

    @Query("DELETE FROM baseline_history WHERE changeReason IN ('PROGRESSION','NORMALIZATION','INITIAL','OVERRIDE')")
    suspend fun deleteDerived()
    // (deleteDerived is equivalent to deleteAll today since all reasons are derived;
    // kept as a named method for clarity in case future reasons are inputs.)
}
