package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.ui.YoutubeFormCard

@Composable
internal fun ActiveSetContent(
    state: WorkoutState.ActiveSet,
    weightUnit: WeightUnit,
    onFeedback: (SetFeedback) -> Unit,
    onStartTimedSet: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val exercise = state.plannedExercise.exercise
    if (exercise.isTimed) {
        TimedSetContent(state = state, onStartTimedSet = onStartTimedSet, onFeedback = onFeedback, actions = actions)
    } else {
        ExerciseSetLayout(
            exercise = exercise,
            progressLabel = "Set ${state.setIndex + 1} of ${state.totalSets}",
            progressColor = MaterialTheme.colorScheme.primary,
            weight = state.plannedExercise.sessionWeight,
            reps = state.plannedExercise.sessionReps,
            weightUnit = weightUnit,
            menu = actions,
        ) {
            Text("How many more reps could you have done?", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            FeedbackButtons(onFeedback = onFeedback)
        }
    }
}

@Composable
private fun TimedSetContent(
    state: WorkoutState.ActiveSet,
    onStartTimedSet: () -> Unit,
    onFeedback: (SetFeedback) -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val exercise = state.plannedExercise.exercise
    val secondsRemaining = state.timerSecondsRemaining
    val started = secondsRemaining != null

    val targetProgress = if (started) secondsRemaining!! / WorkoutSessionController.TIMED_SET_SECONDS.toFloat() else 1f
    val animatedProgress = remember { Animatable(1f) }
    LaunchedEffect(secondsRemaining) {
        animatedProgress.animateTo(
            targetValue = targetProgress,
            animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        )
    }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val arcColor = if (started) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .then(if (!started) Modifier.clickable { onStartTimedSet() } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            actions()
        }
        Spacer(Modifier.weight(1f))
        Text(exercise.name, style = MaterialTheme.typography.headlineMedium)
        Text(
            "Set ${state.setIndex + 1} of ${state.totalSets}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 12.dp.toPx()
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    topLeft = topLeft,
                    size = arcSize,
                )
                drawArc(
                    color = arcColor,
                    startAngle = -90f + (1f - animatedProgress.value) * 360f,
                    sweepAngle = animatedProgress.value * 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    topLeft = topLeft,
                    size = arcSize,
                )
            }
            if (!started) {
                Text(
                    "TAP TO START",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$secondsRemaining", style = MaterialTheme.typography.displayLarge)
                    Text("seconds", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        val errorColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.TOO_HARD) },
                colors = errorColors,
                modifier = Modifier.weight(1f),
            ) { Text("Too Hard") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.HURT) },
                colors = errorColors,
                modifier = Modifier.weight(1f),
            ) { Text("Hurt") }
        }
        Spacer(Modifier.weight(1f))
        YoutubeFormCard(exerciseName = exercise.name)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun ExerciseSetLayout(
    exercise: Exercise,
    progressLabel: String,
    progressColor: Color,
    weight: Float,
    reps: Int,
    weightUnit: WeightUnit,
    menu: @Composable () -> Unit = {},
    bottomContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            menu()
        }
        Text(exercise.name, style = MaterialTheme.typography.headlineMedium)
        Text(progressLabel, style = MaterialTheme.typography.titleLarge, color = progressColor)

        Spacer(Modifier.weight(1f))

        Text("$reps reps", style = MaterialTheme.typography.displaySmall)
        when {
            weight > 0f -> {
                Text(WeightFormatter.format(weight, weightUnit), style = MaterialTheme.typography.displaySmall)
                if (exercise.equipment == Equipment.BARBELL) {
                    WeightFormatter.platesPerSide(weight, weightUnit)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            exercise.equipment == Equipment.BODYWEIGHT -> Text(
                "Bodyweight",
                style = MaterialTheme.typography.displaySmall,
            )
        }

        Spacer(Modifier.weight(1f))

        bottomContent()
        Spacer(Modifier.height(16.dp))
        YoutubeFormCard(exerciseName = exercise.name)
        Spacer(Modifier.height(16.dp))
    }
}


@Composable
private fun FeedbackButtons(onFeedback: (SetFeedback) -> Unit) {
    val errorColor = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.error,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.RIR_0_1) },
                modifier = Modifier.weight(1f),
            ) { Text("0-1 more") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.RIR_2_4) },
                modifier = Modifier.weight(1f),
            ) { Text("2-4 more") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.RIR_5_PLUS) },
                modifier = Modifier.weight(1f),
            ) { Text("5+ more") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.TOO_HARD) },
                colors = errorColor,
                modifier = Modifier.weight(1f),
            ) { Text("Too Heavy") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.HURT) },
                colors = errorColor,
                modifier = Modifier.weight(1f),
            ) { Text("Hurt") }
        }
    }
}
