package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength

@Dao
interface MuscleGroupStrengthDao {
    @Query("SELECT * FROM muscle_group_strength")
    suspend fun getAll(): List<MuscleGroupStrength>

    @Query("SELECT * FROM muscle_group_strength WHERE muscleGroup = :muscleGroup")
    suspend fun get(muscleGroup: MuscleGroup): MuscleGroupStrength?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(strength: MuscleGroupStrength)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(strengths: List<MuscleGroupStrength>)
}
