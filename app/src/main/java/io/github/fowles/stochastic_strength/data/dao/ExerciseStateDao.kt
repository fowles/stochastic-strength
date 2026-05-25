package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.ExerciseState

@Dao
interface ExerciseStateDao {
    @Query("SELECT * FROM exercise_state WHERE exerciseId = :exerciseId")
    suspend fun getState(exerciseId: Long): ExerciseState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ExerciseState)

    @Query("SELECT * FROM exercise_state")
    suspend fun getAll(): List<ExerciseState>
}
