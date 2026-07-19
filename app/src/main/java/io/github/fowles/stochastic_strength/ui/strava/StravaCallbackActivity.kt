package io.github.fowles.stochastic_strength.ui.strava

import androidx.activity.ComponentActivity
import android.os.Bundle
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class StravaCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.data?.getQueryParameter("code")
        val exporter = (application as StochasticStrengthApp).stravaExporter
        lifecycleScope.launch {
            if (code != null) {
                runCatching { exporter.handleAuthCallback(code) }
                    .onFailure { exporter.recordAuthError(it.message ?: it.javaClass.simpleName) }
            }
            finish()
        }
    }
}
