package io.github.fowles.stochastic_strength.ui.summary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.domain.WeightFormatter
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val s = summary
            if (s == null) {
                CircularProgressIndicator()
            } else {
                val dateLabel = SimpleDateFormat("EEEE, MMM d · h:mm a", Locale.getDefault())
                    .format(Date(s.startTime))
                Text(dateLabel, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                val minutes = s.durationSeconds / 60
                val seconds = s.durationSeconds % 60
                Text(
                    "Duration: ${minutes}m ${seconds}s",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))

                s.exercises.forEach { ex ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ex.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (ex.weight > 0f) {
                            Text(
                                WeightFormatter.format(ex.weight, s.weightUnit),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    ex.feedback.forEachIndexed { i, fb ->
                        Text(
                            "  Set ${i + 1}: ${fb?.displayLabel ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                }
            }

            Spacer(Modifier.weight(1f))
            if (onBack != null) {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

