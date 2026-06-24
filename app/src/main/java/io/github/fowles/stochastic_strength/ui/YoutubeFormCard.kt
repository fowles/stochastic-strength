package io.github.fowles.stochastic_strength.ui

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun YoutubeFormCard(exerciseName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        onClick = {
            val query = Uri.encode("$exerciseName proper form tutorial")
            val intent = Intent(Intent.ACTION_VIEW, "https://www.youtube.com/results?search_query=$query".toUri())
            context.startActivity(intent)
        },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFCC0000)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.size(10.dp))
            Text(
                "Watch form guide",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
    }
}
