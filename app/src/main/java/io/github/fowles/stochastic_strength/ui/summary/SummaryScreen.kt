package io.github.fowles.stochastic_strength.ui.summary

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SummaryScreen(
    sessionId: Long,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    onExerciseTap: ((Long) -> Unit)? = null,
    viewModel: SummaryViewModel = viewModel(factory = SummaryViewModel.factory(sessionId)),
) {
    val summary by viewModel.summary.collectAsState()
    val stravaState by viewModel.stravaState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(stravaState) {
        when (val state = stravaState) {
            is StravaExportState.NeedsAuth -> {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.authUrl)))
                viewModel.onAuthUrlLaunched()
            }
            is StravaExportState.Success -> viewModel.onStravaMessageShown()
            is StravaExportState.Error -> viewModel.onStravaMessageShown()
            else -> Unit
        }
    }

    val exportBusy = stravaState is StravaExportState.Exporting ||
        stravaState is StravaExportState.WaitingForAuth

    Scaffold { paddingValues ->
        WorkoutSummaryContent(
            summary = summary,
            modifier = Modifier.padding(paddingValues),
            onExerciseTap = onExerciseTap,
            header = {
                val s = summary
                if (s != null) {
                    val dateLabel = SimpleDateFormat("EEEE, MMM d · h:mm a", Locale.getDefault())
                        .format(Date(s.startTime))
                    Text(dateLabel, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                }
            },
            footer = {
                if (onBack != null) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text("Back")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = { viewModel.onExportToStrava() },
                    enabled = !exportBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (exportBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Export to Strava")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            },
        )
    }
}
