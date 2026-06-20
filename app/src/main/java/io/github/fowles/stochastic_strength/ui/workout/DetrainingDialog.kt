package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.DetrainingModel
import io.github.fowles.stochastic_strength.ui.components.StrengthGrid
import kotlin.math.roundToInt

@Composable
internal fun DetrainingDialog(
    prompt: DetrainingPrompt,
    weightUnit: WeightUnit,
    onApply: (Float) -> Unit,
    onSkip: () -> Unit,
) {
    var fraction by remember { mutableFloatStateOf(prompt.suggestedFraction) }
    val percent = (fraction * 100f).roundToInt()
    val reduced = prompt.currentStrengths.map {
        it.copy(baselineWeight = DetrainingModel.reduce(it.baselineWeight, fraction))
    }

    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.95f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onSkip,
        title = { Text("Welcome back") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val weeks = prompt.weeksOff
                Text(
                    "It's been $weeks ${if (weeks == 1) "week" else "weeks"} since your last " +
                        "workout. We can ease your baselines down to match.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Reduce by $percent%",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = fraction,
                    onValueChange = { fraction = it },
                    valueRange = 0f..DetrainingModel.MAX_FRACTION,
                    steps = 9, // 0,5,...,50 in 5% steps
                )
                Spacer(Modifier.height(4.dp))
                StrengthGrid(
                    strengths = reduced,
                    tapTargets = emptyMap<io.github.fowles.stochastic_strength.data.model.MuscleGroup, Unit>(),
                    weightUnit = weightUnit,
                    onTap = {},
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(fraction) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}
