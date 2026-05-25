package io.github.fowles.stochastic_strength

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.fowles.stochastic_strength.ui.AppNavigation
import io.github.fowles.stochastic_strength.ui.theme.StochasticStrengthTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StochasticStrengthTheme {
                AppNavigation()
            }
        }
    }
}