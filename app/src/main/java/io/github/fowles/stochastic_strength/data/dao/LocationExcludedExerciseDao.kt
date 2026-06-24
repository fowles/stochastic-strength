package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise

@Dao
interface LocationExcludedExerciseDao {
    @Query("SELECT exerciseId FROM location_excluded_exercises WHERE locationId = :locationId")
    suspend fun getExcludedIds(locationId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(exclusion: LocationExcludedExercise)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(exclusions: List<LocationExcludedExercise>)

    @Query("DELETE FROM location_excluded_exercises WHERE locationId = :locationId")
    suspend fun deleteAllForLocation(locationId: Long)

    @Query("SELECT * FROM location_excluded_exercises")
    suspend fun getAll(): List<LocationExcludedExercise>

    @Query("DELETE FROM location_excluded_exercises")
    suspend fun deleteAll()
}
