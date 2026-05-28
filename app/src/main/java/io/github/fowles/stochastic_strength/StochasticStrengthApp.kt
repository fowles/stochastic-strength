package io.github.fowles.stochastic_strength

import android.app.Application
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.seed.ExerciseLibrary
import io.github.fowles.stochastic_strength.ui.workout.WorkoutCommand
import io.github.fowles.stochastic_strength.ui.workout.WorkoutNotificationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class StochasticStrengthApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase get() = AppDatabase.getInstance(this, applicationScope)
    val workoutCommandFlow = MutableSharedFlow<WorkoutCommand>(extraBufferCapacity = 8)
    val workoutNotificationState = MutableStateFlow<WorkoutNotificationState?>(null)

    override fun onCreate() {
        super.onCreate()
        runBlocking(Dispatchers.IO) {
            try {
                database.workoutSetDao().getFirst()
            } catch (_: Exception) {
                AppDatabase.reset(this@StochasticStrengthApp, applicationScope)
            }
            if (database.exerciseDao().count() == 0) {
                database.exerciseDao().insertAll(ExerciseLibrary.exercises)
            }
            DebugSeeder.seedIfEmpty(database)
        }
    }
}
