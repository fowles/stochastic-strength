package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseHurtStateDao {

    @Query("SELECT * FROM exercise_hurt_state WHERE exerciseId = :exerciseId")
    suspend fun get(exerciseId: Long): ExerciseHurtState?

    @Query("SELECT * FROM exercise_hurt_state")
    fun observeAll(): Flow<List<ExerciseHurtState>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ExerciseHurtState)

    @Query("SELECT * FROM exercise_hurt_state")
    suspend fun getAll(): List<ExerciseHurtState>

    @Query("DELETE FROM exercise_hurt_state")
    suspend fun deleteAll()
}
