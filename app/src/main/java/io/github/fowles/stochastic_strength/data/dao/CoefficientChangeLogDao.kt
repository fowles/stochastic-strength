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

    @Query("""
        SELECT c.* FROM coefficient_change_log c
        INNER JOIN (
            SELECT exerciseId, MAX(computedAt) AS maxComputedAt
            FROM coefficient_change_log
            GROUP BY exerciseId
        ) latest ON c.exerciseId = latest.exerciseId AND c.computedAt = latest.maxComputedAt
        GROUP BY c.exerciseId
    """)
    suspend fun getLatestPerExercise(): List<CoefficientChangeLog>

    @Query("SELECT * FROM coefficient_change_log ORDER BY computedAt DESC LIMIT :limit")
    suspend fun getMostRecent(limit: Int): List<CoefficientChangeLog>

    @Query("SELECT * FROM coefficient_change_log WHERE exerciseId = :exerciseId ORDER BY computedAt ASC")
    suspend fun getForExercise(exerciseId: Long): List<CoefficientChangeLog>
}
