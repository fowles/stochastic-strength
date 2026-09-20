package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/** One exercise's earliest completed-set time — the first session it was actually performed in. */
data class ExerciseFirstCompleted(val exerciseId: Long, val firstCompletedAt: Long)

/** One exercise's latest completed-set time — the most recent session it was performed in. */
data class ExerciseLastCompleted(val exerciseId: Long, val lastCompletedAt: Long)

@Dao
interface WorkoutSetDao {
    @Insert
    suspend fun insert(set: WorkoutSet): Long

    @Query("SELECT * FROM workout_sets WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getSetsForSession(sessionId: Long): List<WorkoutSet>

    /**
     * The whole set log, ordered the same way [getSetsForSession] orders one session (id ASC).
     * Since id increases monotonically, grouping this by sessionId reproduces each session's list
     * exactly — the one-query alternative to calling [getSetsForSession] per session.
     */
    @Query("SELECT * FROM workout_sets ORDER BY id ASC")
    suspend fun getAllOrderedById(): List<WorkoutSet>

    @Query("SELECT * FROM workout_sets WHERE exerciseId = :exerciseId ORDER BY completedAt ASC")
    suspend fun getAllForExercise(exerciseId: Long): List<WorkoutSet>

    @Query("DELETE FROM workout_sets WHERE sessionId = :sessionId")
    suspend fun deleteAllForSession(sessionId: Long)

    @Query("""
        SELECT * FROM workout_sets
        WHERE exerciseId IN (:exerciseIds)
          AND completedAt IS NOT NULL
        ORDER BY completedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentSetsForExercises(exerciseIds: List<Long>, limit: Int): List<WorkoutSet>

    /**
     * Every completed set since [sinceMs], for policy facts: the window is bounded by TIME, not a
     * row count, because a row-count limit can silently drop a demonstrated-capacity cap that is
     * still inside its expiry window (the exact failed weight would be re-prescribed).
     */
    @Query("""
        SELECT * FROM workout_sets
        WHERE exerciseId IN (:exerciseIds)
          AND completedAt IS NOT NULL
          AND completedAt >= :sinceMs
    """)
    suspend fun getCompletedSetsForExercisesSince(exerciseIds: List<Long>, sinceMs: Long): List<WorkoutSet>

    @Query("""
        SELECT * FROM workout_sets
        WHERE sessionId IN (:sessionIds)
          AND completedAt IS NOT NULL
    """)
    suspend fun getSetsForSessions(sessionIds: List<Long>): List<WorkoutSet>

    /**
     * Every set row for these sessions, completed or not — unlike [getSetsForSessions], which
     * filters to completed sets only. Used where "does this session have any logged sets at all"
     * matters (an orphan with only an uncompleted set row is not empty).
     */
    @Query("SELECT * FROM workout_sets WHERE sessionId IN (:sessionIds)")
    suspend fun getAllSetsForSessions(sessionIds: List<Long>): List<WorkoutSet>

    @Query("SELECT * FROM workout_sets")
    suspend fun getAll(): List<WorkoutSet>

    @Query("""
        SELECT exerciseId, MIN(completedAt) AS firstCompletedAt
        FROM workout_sets
        WHERE completedAt IS NOT NULL
        GROUP BY exerciseId
    """)
    suspend fun getFirstCompletedAtByExercise(): List<ExerciseFirstCompleted>

    @Query("""
        SELECT exerciseId, MAX(completedAt) AS lastCompletedAt
        FROM workout_sets
        WHERE completedAt IS NOT NULL
        GROUP BY exerciseId
    """)
    suspend fun getLastCompletedAtByExercise(): List<ExerciseLastCompleted>

    @Query("SELECT * FROM workout_sets WHERE id = :id")
    suspend fun getById(id: Long): WorkoutSet?

    @Query("DELETE FROM workout_sets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE workout_sets SET actualReps = :reps WHERE id = :id")
    suspend fun updateActualReps(id: Long, reps: Int?)

    @Query("DELETE FROM workout_sets")
    suspend fun deleteAll()
}
