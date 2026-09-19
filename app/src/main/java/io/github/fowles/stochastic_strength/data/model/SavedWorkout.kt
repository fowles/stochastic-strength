package io.github.fowles.stochastic_strength.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-authored workout: an ordered list of exercises with optional per-row reps. Pure input. */
@Entity(tableName = "saved_workout")
data class SavedWorkout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/**
 * One row of a [SavedWorkout]. `reps == null` means "use the session's rep pick". Adjacent rows
 * sharing a non-null [circuitId] form a circuit whose rounds are [sets].
 */
@Entity(tableName = "saved_workout_exercise", indices = [Index("workoutId")])
data class SavedWorkoutExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: Long,
    val position: Int,
    val reps: Int?,
    @ColumnInfo(defaultValue = "3") override val sets: Int = 3,
    override val circuitId: Int? = null,
) : CircuitRow<SavedWorkoutExercise> {
    override fun withStructure(sets: Int, circuitId: Int?) = copy(sets = sets, circuitId = circuitId)
}
