package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter

@Composable
internal fun <T : Any> StrengthGrid(
    strengths: List<MuscleGroupStrength>,
    tapTargets: Map<MuscleGroup, T>,
    weightUnit: WeightUnit,
    onTap: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        strengths.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEach { strength ->
                    StrengthCard(
                        strength = strength,
                        tapTarget = tapTargets[strength.muscleGroup],
                        weightUnit = weightUnit,
                        onTap = onTap,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun <T : Any> StrengthCard(
    strength: MuscleGroupStrength,
    tapTarget: T?,
    weightUnit: WeightUnit,
    onTap: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val cardContent: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = strength.muscleGroup.displayName(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = WeightFormatter.format(strength.baselineWeight, weightUnit),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (tapTarget != null) {
        Card(onClick = { onTap(tapTarget) }, modifier = modifier, colors = cardColors, content = cardContent)
    } else {
        Card(modifier = modifier, colors = cardColors, content = cardContent)
    }
}
