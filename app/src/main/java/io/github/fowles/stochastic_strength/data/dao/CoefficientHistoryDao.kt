package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory

@Dao
interface CoefficientHistoryDao {

    @Query("SELECT * FROM coefficient_history WHERE exerciseId = :exerciseId ORDER BY computedAt ASC")
    suspend fun getForExercise(exerciseId: Long): List<CoefficientHistory>

    @Query(
        "SELECT * FROM coefficient_history c " +
            "WHERE c.computedAt = (SELECT MAX(c2.computedAt) FROM coefficient_history c2 WHERE c2.exerciseId = c.exerciseId)"
    )
    suspend fun getLatestPerExercise(): List<CoefficientHistory>

    @Query("SELECT * FROM coefficient_history ORDER BY computedAt ASC")
    suspend fun getAll(): List<CoefficientHistory>

    @Query("SELECT * FROM coefficient_history ORDER BY computedAt DESC LIMIT :limit")
    suspend fun getMostRecent(limit: Int): List<CoefficientHistory>

    @Insert
    suspend fun insert(row: CoefficientHistory): Long

    @Query("DELETE FROM coefficient_history")
    suspend fun deleteAll()
}
