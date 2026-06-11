package io.github.fowles.stochastic_strength

import android.app.Application
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import io.github.fowles.stochastic_strength.domain.ActualRepsBackfill
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
            DebugSeeder.seedIfEmpty(database)
        }
        applicationScope.launch(Dispatchers.IO) {
            val profile = database.userProfileDao().getProfile() ?: return@launch
            if (profile.actualRepsBackfilled) return@launch
            ActualRepsBackfill(database, profile.weightUnit).run()
            database.userProfileDao().insert(profile.copy(actualRepsBackfilled = true))
        }
    }
}
