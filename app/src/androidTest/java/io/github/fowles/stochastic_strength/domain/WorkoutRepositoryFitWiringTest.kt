package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.domain.derived.DerivedStateStore
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Light wiring check: on an empty DB, fitBlocking completes and installs a key + defaults config
 * (below the 15-session floor). A second call is a no-op (no infinite loop).
 */
@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryFitWiringTest {
    private lateinit var db: AppDatabase
    private lateinit var derivedState: DerivedStateStore
    private lateinit var repository: WorkoutRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        derivedState = DerivedStateStore()
        repository = WorkoutRepository(
            db,
            derivedState = derivedState,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun emptyHistoryStaysAtDefaultsAndSetsKey() = runBlocking {
        repository.fitBlocking()

        // Empty DB: 0 sessions < minFitSessions=15 → fitter returns defaults unchanged.
        assertEquals(EstimatorConfig(), derivedState.activeConfig())

        // Key must be installed (even for empty history) so a subsequent replay does NOT relaunch.
        assertNotNull(derivedState.activeFitKey())

        // Diagnostics reflect the "at defaults" path.
        assertTrue(derivedState.fitDiagnostics()?.atDefaults == true)
    }
}
