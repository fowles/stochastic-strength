package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSparklines

/**
 * A tiny static sparkline of [values] (self-normalized to its own min/max): a thin [color] line
 * with a faint vertical gradient fill beneath. Renders nothing for fewer than 2 values.
 *
 * Deliberately drawn on a Compose [Canvas] rather than a Vico chart — a Vico chart is too heavy to
 * instantiate per row in a LazyColumn; a static sparkline needs only two paths.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    height: Dp = 28.dp,
    strokeWidth: Dp = 1.5.dp,
) {
    val offsets = ExerciseSparklines.normalize(values)
    if (offsets.size < 2) return
    Canvas(modifier = modifier.size(width, height)) {
        val stepX = size.width / (offsets.size - 1)
        // Inset top and bottom by the stroke width so the peak/trough aren't clipped.
        val pad = strokeWidth.toPx()
        val usableH = size.height - pad * 2
        fun pointAt(i: Int): Offset {
            // offset 1 = max = top of the box; flip to screen y.
            val y = pad + (1f - offsets[i]) * usableH
            return Offset(i * stepX, y)
        }
        val line = Path().apply {
            val first = pointAt(0)
            moveTo(first.x, first.y)
            for (i in 1 until offsets.size) {
                val pt = pointAt(i)
                lineTo(pt.x, pt.y)
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)),
            ),
        )
        drawPath(path = line, color = color, style = Stroke(width = strokeWidth.toPx()))
    }
}
