package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.fowles.stochastic_strength.data.model.Exercise
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE isDisliked = 0")
    fun observeActive(): Flow<List<Exercise>>

    @Query("SELECT * FROM exercises WHERE isDisliked = 0")
    suspend fun getActive(): List<Exercise>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): Exercise?

    @Insert
    suspend fun insertAll(exercises: List<Exercise>)

    @Update
    suspend fun update(exercise: Exercise)

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun count(): Int
}
