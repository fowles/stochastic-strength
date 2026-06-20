package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.BaselineChangeLog
import io.github.fowles.stochastic_strength.data.model.MuscleGroup

@Dao
interface BaselineChangeLogDao {
    @Insert
    suspend fun insert(entry: BaselineChangeLog)

    @Insert
    suspend fun insertAll(entries: List<BaselineChangeLog>)

    @Query("SELECT * FROM baseline_change_log ORDER BY timestamp ASC")
    suspend fun getAll(): List<BaselineChangeLog>

    @Query("SELECT * FROM baseline_change_log WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<BaselineChangeLog>

    @Query("SELECT * FROM baseline_change_log WHERE muscleGroup = :muscleGroup ORDER BY timestamp ASC")
    suspend fun getForMuscleGroup(muscleGroup: MuscleGroup): List<BaselineChangeLog>
}
