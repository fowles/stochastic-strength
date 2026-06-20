package io.github.fowles.stochastic_strength.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity

@Composable
fun ExerciseSetSection(name: String, sets: List<SummarySet>, weightUnit: WeightUnit) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
    sets.forEach { set ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val quantityLabel = formatQuantity(set.targetReps, set.isTimed)
            val weightLabel = if (set.targetWeight > 0f)
                "${WeightFormatter.format(set.targetWeight, weightUnit)} × $quantityLabel"
            else
                quantityLabel
            Text(
                text = "Set ${set.setNumber}: $weightLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            set.summaryFeedbackLabel()?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
