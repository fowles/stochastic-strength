package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.usesBarPlates
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import kotlinx.coroutines.flow.first

@Composable
internal fun RestingContent(
    state: WorkoutState.Resting,
    weightUnit: WeightUnit,
    onSkipRest: () -> Unit,
    onUndo: () -> Unit,
    onReduceWeight: (Int) -> Unit,
) {
    val plan = state.plan
    val totalSets = PlannedExercise.DEFAULT_SETS
    val nextSet = state.completedSetIndex + 1

    val targetProgress = state.secondsRemaining / WorkoutSessionController.REST_SECONDS.toFloat()
    val animatedProgress = remember { Animatable(targetProgress) }
    LaunchedEffect(state.secondsRemaining) {
        animatedProgress.animateTo(
            targetValue = targetProgress,
            animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        )
    }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Rest", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                val subtitle = when (state.staged?.kind) {
                    StagedKind.STOP_WORKOUT -> "Finishing workout"
                    StagedKind.END_EXERCISE -> "Exercise stopped"
                    StagedKind.SWAP -> "Swapped exercise"
                    StagedKind.ADJUST_WEIGHT -> "Weight changed"
                    StagedKind.WARMUP_DONE -> "Warmup complete"
                    null -> "Logged: ${state.lastFeedback?.displayLabel ?: ""}"
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
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
                            color = progressColor,
                            startAngle = -90f + (1f - animatedProgress.value) * 360f,
                            sweepAngle = animatedProgress.value * 360f,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            topLeft = topLeft,
                            size = arcSize,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${state.secondsRemaining}", style = MaterialTheme.typography.displayLarge)
                        Text("seconds", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onUndo) { Text("Undo") }
                    OutlinedButton(onClick = onSkipRest) { Text("Skip Rest") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // Card area — always 20% regardless of card visibility
        Box(
            modifier = Modifier
                .weight(0.2f)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val plannedExercise = state.plan.exercises[state.exerciseIndex]
            val moreSetsForThisExercise = state.completedSetIndex < PlannedExercise.DEFAULT_SETS - 1
            val isWeighted = plannedExercise.exercise.equipment != Equipment.BODYWEIGHT
                && plannedExercise.sessionWeight > 0f
            val nextExercise = if (state.exerciseIndex + 1 < plan.exercises.size)
                plan.exercises[state.exerciseIndex + 1] else null
            val weightReduced = plannedExercise.sessionWeight != state.weightAtSetStart

            when {
                state.staged != null -> {
                    val commitTarget = state.staged.commitTarget
                    val up = commitTarget?.let { it.plan.exercises.getOrNull(it.exerciseIndex) }
                    if (up != null) {
                        val isWarmupDone = state.staged.kind == StagedKind.WARMUP_DONE
                        val warmup = if (isWarmupDone) null
                                     else commitTarget?.warmupSetIndex?.let { up.warmupSets.getOrNull(it) }
                        NextExerciseCard(
                            title = if (isWarmupDone) "First set"
                                    else if (warmup != null) "Warm up"
                                    else "Up next",
                            exerciseName = up.exercise.name,
                            weight = warmup?.weight ?: up.sessionWeight,
                            usesBarPlates = up.exercise.usesBarPlates,
                            weightUnit = weightUnit,
                        )
                    }
                }
                state.lastFeedback == SetFeedback.TOO_HARD && !state.weightReductionApplied
                    && !plannedExercise.exercise.isTimed -> {
                    WeightReductionCard(
                        sessionReps = plannedExercise.sessionReps,
                        sessionWeight = plannedExercise.sessionWeight,
                        weightUnit = weightUnit,
                        applied = false,
                        showWeightDelta = moreSetsForThisExercise && isWeighted,
                        onRepsSelected = onReduceWeight,
                    )
                }
                state.lastFeedback == SetFeedback.TOO_HARD && state.weightReductionApplied
                    && moreSetsForThisExercise && weightReduced -> {
                    NextExerciseCard(
                        title = "Reduced weight",
                        exerciseName = plannedExercise.exercise.name,
                        weight = plannedExercise.sessionWeight,
                        usesBarPlates = plannedExercise.exercise.usesBarPlates,
                        weightUnit = weightUnit,
                    )
                }
                !moreSetsForThisExercise && nextExercise != null -> {
                    val warmup = nextExercise.warmupSets.firstOrNull()
                    NextExerciseCard(
                        title = if (warmup != null) "Warm up" else "Next up",
                        exerciseName = nextExercise.exercise.name,
                        weight = warmup?.weight ?: nextExercise.sessionWeight,
                        usesBarPlates = nextExercise.exercise.usesBarPlates,
                        weightUnit = weightUnit,
                    )
                }
            }
        }
        // Exercises — always 20%
        // For a staged action the rest precedes the commit-target exercise, so the
        // "in progress" exercise and its remaining-set count come from the commit
        // target (a freshly inserted/swapped exercise has all its sets to go), not
        // from completedSetIndex which still reflects the originating exercise.
        val commitTarget = state.staged?.commitTarget
        RemainingExerciseList(
            exercises = plan.exercises,
            currentExerciseIndex = commitTarget?.exerciseIndex ?: state.exerciseIndex,
            setsRemainingForCurrent = if (commitTarget != null) {
                totalSets - commitTarget.setIndex
            } else {
                totalSets - nextSet
            },
            modifier = Modifier.weight(0.2f),
        )
    }
}

@Composable
private fun WeightReductionCard(
    sessionReps: Int,
    sessionWeight: Float,
    weightUnit: WeightUnit,
    applied: Boolean,
    showWeightDelta: Boolean,
    onRepsSelected: (Int) -> Unit,
) {
    val errorColor = MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (applied) 0f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "How many reps did you complete?",
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(sessionReps) { reps ->
                val newWeight = WeightFormatter.round(
                    maxOf(0.5f, DefaultProgressionEngine.scaleReps(sessionWeight, from = maxOf(1, reps), to = sessionReps)),
                    weightUnit,
                )
                val delta = sessionWeight - newWeight
                OutlinedButton(
                    onClick = { onRepsSelected(reps) },
                    enabled = !applied,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$reps", style = MaterialTheme.typography.labelLarge)
                        if (showWeightDelta && delta > 0f) {
                            Text(
                                "↓ ${WeightFormatter.format(delta, weightUnit)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = errorColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextExerciseCard(
    title: String,
    exerciseName: String,
    weight: Float,
    usesBarPlates: Boolean,
    weightUnit: WeightUnit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "$title: $exerciseName",
            style = MaterialTheme.typography.labelLarge,
        )
        if (weight > 0f) {
            val plates = if (usesBarPlates)
                WeightFormatter.platesPerSide(weight, weightUnit) else null
            val weightLine = buildString {
                append(WeightFormatter.format(weight, weightUnit))
                if (plates != null) append(" • $plates")
            }
            Text(
                weightLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class ExerciseProgress { COMPLETED, IN_PROGRESS, PENDING }

@Composable
private fun RemainingExerciseList(
    exercises: List<PlannedExercise>,
    currentExerciseIndex: Int,
    setsRemainingForCurrent: Int,
    modifier: Modifier = Modifier,
) {
    val totalSets = PlannedExercise.DEFAULT_SETS
    val inProgressIndex = when {
        setsRemainingForCurrent > 0 -> currentExerciseIndex
        currentExerciseIndex + 1 < exercises.size -> currentExerciseIndex + 1
        else -> -1
    }
    val inProgressRemaining = if (setsRemainingForCurrent > 0) setsRemainingForCurrent else totalSets
    val listState = rememberLazyListState()
    LaunchedEffect(inProgressIndex) {
        if (inProgressIndex >= 0) {
            // +1 because item 0 is the sticky header; negative offset places item at ~1/3 from top
            val viewportHeight = snapshotFlow { listState.layoutInfo.viewportSize.height }
                .first { it > 0 }
            listState.scrollToItem(
                index = inProgressIndex + 1,
                scrollOffset = -(viewportHeight * 2 / 5),
            )
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Bottom,
    ) {
        stickyHeader(key = "header") {
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                Text(
                    "Exercises",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        exercises.forEachIndexed { i, planned ->
            val progress = when {
                inProgressIndex < 0 || i < inProgressIndex -> ExerciseProgress.COMPLETED
                i == inProgressIndex -> ExerciseProgress.IN_PROGRESS
                else -> ExerciseProgress.PENDING
            }
            val detail = when (progress) {
                ExerciseProgress.COMPLETED -> "done"
                ExerciseProgress.IN_PROGRESS -> "$inProgressRemaining left"
                ExerciseProgress.PENDING -> "$totalSets sets"
            }
            item(key = planned.exercise.id) {
                RemainingExerciseRow(name = planned.exercise.name, detail = detail, progress = progress)
            }
        }
    }
}

@Composable
private fun RemainingExerciseRow(name: String, detail: String, progress: ExerciseProgress) {
    val iconTint = when (progress) {
        ExerciseProgress.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val nameColor = when (progress) {
        ExerciseProgress.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (progress) {
                ExerciseProgress.COMPLETED -> Icons.Filled.CheckCircle
                ExerciseProgress.IN_PROGRESS -> Icons.Filled.PlayArrow
                ExerciseProgress.PENDING -> Icons.Outlined.CheckCircle
            },
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = nameColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
