package io.github.fowles.stochastic_strength

import android.app.Application
import io.github.fowles.stochastic_strength.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class StochasticStrengthApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { AppDatabase.getInstance(this, applicationScope) }
}
