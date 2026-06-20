package io.github.fowles.stochastic_strength

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.domain.WorkoutRepository

object DebugSeeder {
    @Suppress("UnusedParameter")
    suspend fun seedIfEmpty(db: AppDatabase, repository: WorkoutRepository) = Unit
}
