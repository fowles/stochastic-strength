package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.CoefficientChangeLog

@Dao
interface CoefficientChangeLogDao {
    @Insert
    suspend fun insert(entry: CoefficientChangeLog)

    @Query("SELECT * FROM coefficient_change_log ORDER BY id ASC")
    suspend fun getAll(): List<CoefficientChangeLog>

    @Query("SELECT * FROM coefficient_change_log WHERE id IN (SELECT MAX(id) FROM coefficient_change_log GROUP BY exerciseId)")
    suspend fun getLatestPerExercise(): List<CoefficientChangeLog>
}
