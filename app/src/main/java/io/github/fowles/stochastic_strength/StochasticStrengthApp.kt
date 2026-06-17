package io.github.fowles.stochastic_strength

import android.app.Application
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import io.github.fowles.stochastic_strength.domain.DerivedStateBackfill
import io.github.fowles.stochastic_strength.domain.LastSetAutoregulationHeuristic
import io.github.fowles.stochastic_strength.domain.derived.DerivedStateStore
import io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristic
import io.github.fowles.stochastic_strength.domain.SeedNormalizer
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.strava.StravaExporter
import io.github.fowles.stochastic_strength.domain.strava.StravaJsonBuilder
import io.github.fowles.stochastic_strength.domain.strava.StravaTokenStore
import io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class StochasticStrengthApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase get() = AppDatabase.getInstance(this, applicationScope)
    val workoutSessionBus = WorkoutSessionBus()
    val stravaExporter: StravaExporter by lazy {
        StravaExporter(
            db = database,
            tokenStore = StravaTokenStore(this),
            jsonBuilder = StravaJsonBuilder(),
            context = this,
        )
    }
    val derivedStateStore = DerivedStateStore()
    val workoutRepository: WorkoutRepository by lazy {
        WorkoutRepository(
            database,
            derivedState = derivedStateStore,
            heuristic = EstCoefConsensusHeuristic(),
            normalizer = SeedNormalizer(),
            baselineHeuristic = LastSetAutoregulationHeuristic(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        runBlocking(Dispatchers.IO) {
            try {
                database.workoutSetDao().getFirst()
            } catch (_: Exception) {
                AppDatabase.reset(this@StochasticStrengthApp, applicationScope)
            }
            val existingNames = database.exerciseDao().getNames().toHashSet()
            val missing = ExerciseLibrary.exercises.filter { it.name !in existingNames }
            if (missing.isNotEmpty()) {
                database.exerciseDao().insertAll(missing)
            }
            DebugSeeder.seedIfEmpty(database, workoutRepository)
        }
        applicationScope.launch(Dispatchers.IO) {
            DerivedStateBackfill(database, workoutRepository).run()
        }
    }
}
