package io.github.fowles.stochastic_strength.ui.summary

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    viewModel: SummaryViewModel = viewModel(factory = SummaryViewModel.factory(sessionId)),
) {
    val summary by viewModel.summary.collectAsState()

    Scaffold { paddingValues ->
        WorkoutSummaryContent(
            summary = summary,
            modifier = Modifier.padding(paddingValues),
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
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            },
        )
    }
}
