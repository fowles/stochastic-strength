package io.github.fowles.stochastic_strength.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.BuildConfig
import io.github.fowles.stochastic_strength.ui.components.BackTopAppBar

private const val GITHUB_URL = "https://github.com/fowles/stochastic-strength"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onDebug: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = { BackTopAppBar(title = "About", onBack = onBack) },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("View on GitHub")
                }
                OutlinedButton(
                    onClick = onDebug,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Debug and Advanced Stats")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Stochastic Strength", style = MaterialTheme.typography.headlineLarge)
            Text(
                text = "Random workouts. Real progress.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text("How it works", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Every muscle group has a baseline — the app's estimate of your " +
                    "1-rep max for that group. All your working weights are derived from it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Every exercise has a coefficient — how hard that lift is for you " +
                    "relative to the baseline. After each session, your feedback nudges " +
                    "the baseline up or down, and over time the app learns your " +
                    "individual coefficients from your performance.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
