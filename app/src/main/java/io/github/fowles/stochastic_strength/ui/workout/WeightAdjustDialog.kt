package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter

@Composable
internal fun WeightAdjustDialog(
    exerciseName: String,
    startWeight: Float,
    equipment: Equipment,
    weightUnit: WeightUnit,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val increment = WeightFormatter.minIncrement(weightUnit)
    var working by remember { mutableFloatStateOf(startWeight) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(exerciseName) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(onClick = {
                        working = (working - increment).coerceAtLeast(increment)
                    }) { Text("−") }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        WeightFormatter.format(working, weightUnit),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = { working += increment }) { Text("+") }
                }
                if (equipment == Equipment.BARBELL) {
                    WeightFormatter.platesPerSide(working, weightUnit)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(working) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
