package io.github.fowles.stochastic_strength.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val SCRAMBLE_DURATION_MS = 900L
private const val SCRAMBLE_FRAME_MS = 40L
private const val SCRAMBLE_GLYPHS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#@%&+?!"

@Composable
fun HighlightCard(text: String, modifier: Modifier = Modifier) {
    var displayed by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        val frames = (SCRAMBLE_DURATION_MS / SCRAMBLE_FRAME_MS).toInt()
        for (frame in 0..frames) {
            val revealed = text.length * frame / frames
            displayed = buildString(text.length) {
                text.forEachIndexed { i, c ->
                    append(if (i < revealed || c == ' ') c else SCRAMBLE_GLYPHS.random(Random))
                }
            }
            if (frame < frames) delay(SCRAMBLE_FRAME_MS)
        }
    }
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = displayed,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
