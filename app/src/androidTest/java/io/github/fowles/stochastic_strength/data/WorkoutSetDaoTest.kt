package io.github.fowles.stochastic_strength.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutSetDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * [getAllOrderedById] grouped by sessionId is the one-query replacement for calling
     * [WorkoutSetDao.getSetsForSession] per session (ReplayEngine, the progression series
     * builder, the history exercise-name lookup, the actual-reps backfill). Interleaved sessions
     * (rows for session A and B inserted out of session order) and an uncompleted set (no
     * completedAt) exercise both things the two paths could disagree on: id-ASC ordering and
     * whether a still-open set is included.
     */
    @Test
    fun groupedAllOrderedById_matchesGetSetsForSession_perSession() = runBlocking {
        val dao = db.workoutSetDao()
        val sessionA = db.workoutSessionDao().insert(WorkoutSession(startTime = 1L))
        val sessionB = db.workoutSessionDao().insert(WorkoutSession(startTime = 2L))

        // Interleave inserts across sessions; leave one set uncompleted (completedAt = null).
        dao.insert(baseSet(sessionA, exerciseId = 10L, setNumber = 1, completedAt = 100L))
        dao.insert(baseSet(sessionB, exerciseId = 20L, setNumber = 1, completedAt = 200L))
        dao.insert(baseSet(sessionA, exerciseId = 10L, setNumber = 2, completedAt = null))
        dao.insert(baseSet(sessionB, exerciseId = 20L, setNumber = 2, completedAt = 210L))
        dao.insert(baseSet(sessionA, exerciseId = 11L, setNumber = 1, completedAt = 110L))

        val grouped = dao.getAllOrderedById().groupBy { it.sessionId }

        for (sessionId in listOf(sessionA, sessionB)) {
            assertEquals(dao.getSetsForSession(sessionId), grouped[sessionId].orEmpty())
        }
    }

    private fun baseSet(
        sessionId: Long,
        exerciseId: Long,
        setNumber: Int,
        completedAt: Long?,
    ) = WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = 60f,
        targetReps = 5,
        completedAt = completedAt,
    )
}
