package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryContent
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryData
import io.github.fowles.stochastic_strength.ui.YoutubeFormCard

@Composable
fun WorkoutScreen(
    onWorkoutDone: () -> Unit,
    onEditLocation: (locationId: Long) -> Unit,
    onExerciseTap: (exerciseId: Long) -> Unit,
    viewModel: WorkoutViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val workoutCompleted by viewModel.workoutCompleted.collectAsState()
    val doneSummary by viewModel.doneSummary.collectAsState()
    val activity = LocalContext.current as android.app.Activity

    BackHandler(enabled = state is WorkoutState.ActiveSet || state is WorkoutState.Resting) {
        activity.moveTaskToBack(true)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort, workout continues regardless */ }

    LaunchedEffect(state is WorkoutState.PlanPreview) {
        if (state is WorkoutState.PlanPreview) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(workoutCompleted) {
        if (workoutCompleted) onWorkoutDone()
    }

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (val s = state) {
                WorkoutState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is WorkoutState.PlanPreview -> PlanPreviewContent(
                    state = s,
                    weightUnit = weightUnit,
                    onStart = viewModel::startFirstExercise,
                    onReplace = viewModel::replaceExercise,
                    onSetExerciseCount = viewModel::setExerciseCount,
                    onEditLocation = { locationId ->
                        viewModel.onNavigatedToLocationEdit()
                        onEditLocation(locationId)
                    },
                    onExerciseTap = onExerciseTap,
                )
                is WorkoutState.ActiveSet -> if (s.warmupSetIndex != null) {
                    WarmupSetContent(
                        state = s,
                        weightUnit = weightUnit,
                        onDone = viewModel::completeWarmupSet,
                    )
                } else {
                    ActiveSetContent(
                        state = s,
                        weightUnit = weightUnit,
                        onFeedback = viewModel::recordFeedback,
                        onStartTimedSet = viewModel::startTimedSet,
                    )
                }
                is WorkoutState.Resting -> RestingContent(
                    state = s,
                    onSkipRest = viewModel::skipRest,
                    onUndo = viewModel::undoLastSet,
                )
                is WorkoutState.Done -> DoneContent(
                    doneSummary = doneSummary,
                    onUndo = viewModel::undoLastSetFromDone,
                    onDone = viewModel::completeWorkout,
                )
            }
        }
    }
}

@Composable
private fun PlanPreviewContent(
    state: WorkoutState.PlanPreview,
    weightUnit: WeightUnit,
    onStart: () -> Unit,
    onReplace: (index: Int, reason: ExerciseRemovalReason) -> Unit,
    onSetExerciseCount: (Int) -> Unit,
    onEditLocation: (locationId: Long) -> Unit,
    onExerciseTap: (exerciseId: Long) -> Unit,
) {
    val plan = state.plan
    val totalSets = plan.exercises.size * PlannedExercise.DEFAULT_SETS
    val durationMin = plan.estimatedDurationSeconds / 60

    var sliderValue by remember { mutableFloatStateOf(plan.exercises.size.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text("Today's Workout", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "$durationMin min · ${plan.exercises.size} exercises · $totalSets sets",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val locationId = state.plan.locationId
        val locationName = state.locationName
        if (locationId != null && locationName != null) {
            Spacer(Modifier.height(4.dp))
            AssistChip(
                onClick = { onEditLocation(locationId) },
                label = { Text(locationName) },
                leadingIcon = {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Shorter",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSetExerciseCount(sliderValue.roundToInt()) },
                valueRange = 1f..MAX_EXERCISE_COUNT.toFloat(),
                steps = MAX_EXERCISE_COUNT - 2,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                "Longer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text(
            "Swipe left to reject an exercise",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(plan.exercises, key = { it.exercise.id }) { planned ->
                ExercisePreviewRow(
                    planned = planned,
                    weightUnit = weightUnit,
                    onReplace = { reason -> onReplace(plan.exercises.indexOf(planned), reason) },
                    onTap = { onExerciseTap(planned.exercise.id) },
                    modifier = Modifier.animateItem(),
                )
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStart, enabled = plan.exercises.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Let's Go")
        }
    }
}

@Composable
private fun ExercisePreviewRow(
    planned: io.github.fowles.stochastic_strength.domain.model.PlannedExercise,
    weightUnit: WeightUnit,
    onReplace: (ExerciseRemovalReason) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showActions by remember(planned.exercise.id) { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.currentValue }
            .collect { if (it != SwipeToDismissBoxValue.Settled) showActions = true }
    }

    if (showActions) {
        ExerciseActionRow(
            name = planned.exercise.name,
            onAction = { reason ->
                showActions = false
                onReplace(reason)
            },
            modifier = modifier,
        )
    } else {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                val alpha = dismissState.progress.coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = alpha)),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError.copy(alpha = alpha),
                        modifier = Modifier
                            .padding(end = 24.dp)
                            .size(36.dp),
                    )
                }
            },
            enableDismissFromStartToEnd = false,
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onTap)
                    .padding(vertical = 12.dp),
            ) {
                Text(planned.exercise.name, style = MaterialTheme.typography.titleMedium)
                val weightLabel = when {
                    planned.sessionWeight > 0f -> WeightFormatter.format(planned.sessionWeight, weightUnit)
                    planned.exercise.equipment == Equipment.BODYWEIGHT -> "Bodyweight"
                    else -> null
                }
                val detail = buildString {
                    val repsLabel = formatQuantity(planned.sessionReps, planned.exercise.isTimed)
                    append("${PlannedExercise.DEFAULT_SETS} sets × $repsLabel")
                    if (weightLabel != null) append(" · $weightLabel")
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExerciseActionRow(
    name: String,
    onAction: (ExerciseRemovalReason) -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoSkipProgress = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        autoSkipProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 4000, easing = LinearEasing),
        )
        onAction(ExerciseRemovalReason.SKIP_TODAY)
    }

    Column(modifier = modifier.padding(vertical = 12.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onAction(ExerciseRemovalReason.NO_EQUIPMENT) },
                modifier = Modifier.weight(1f),
            ) { Text("No gear", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = { onAction(ExerciseRemovalReason.DISLIKE) },
                modifier = Modifier.weight(1f),
            ) { Text("Hate it", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = { onAction(ExerciseRemovalReason.SKIP_TODAY) },
                modifier = Modifier.weight(1f),
            ) { Text("Not today", style = MaterialTheme.typography.labelSmall) }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { autoSkipProgress.value },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WarmupSetContent(
    state: WorkoutState.ActiveSet,
    weightUnit: WeightUnit,
    onDone: () -> Unit,
) {
    val warmupSet = state.currentWarmupSet ?: return
    val exercise = state.plannedExercise.exercise
    val totalWarmups = state.plannedExercise.warmupSets.size
    ExerciseSetLayout(
        exerciseName = exercise.name,
        equipment = exercise.equipment,
        progressLabel = "Warm-up ${state.warmupSetIndex!! + 1} of $totalWarmups",
        progressColor = MaterialTheme.colorScheme.secondary,
        weight = warmupSet.weight,
        reps = warmupSet.reps,
        weightUnit = weightUnit,
    ) {
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.warmupSetIndex + 1 < totalWarmups) "Next Warm-up" else "Start Working Sets")
        }
    }
}

@Composable
private fun ActiveSetContent(
    state: WorkoutState.ActiveSet,
    weightUnit: WeightUnit,
    onFeedback: (SetFeedback) -> Unit,
    onStartTimedSet: () -> Unit,
) {
    val exercise = state.plannedExercise.exercise
    if (exercise.isTimed) {
        TimedSetContent(state = state, onStartTimedSet = onStartTimedSet, onFeedback = onFeedback)
    } else {
        ExerciseSetLayout(
            exerciseName = exercise.name,
            equipment = exercise.equipment,
            progressLabel = "Set ${state.setIndex + 1} of ${state.totalSets}",
            progressColor = MaterialTheme.colorScheme.primary,
            weight = state.plannedExercise.sessionWeight,
            reps = state.plannedExercise.sessionReps,
            weightUnit = weightUnit,
        ) {
            Text("How did that feel?", style = MaterialTheme.typography.labelLarge)
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
) {
    val exercise = state.plannedExercise.exercise
    val secondsRemaining = state.timerSecondsRemaining
    val started = secondsRemaining != null

    val targetProgress = if (started) secondsRemaining!! / WorkoutViewModel.TIMED_SET_SECONDS.toFloat() else 1f
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
        verticalArrangement = Arrangement.Center,
    ) {
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
    }
}

@Composable
private fun ExerciseSetLayout(
    exerciseName: String,
    equipment: Equipment,
    progressLabel: String,
    progressColor: Color,
    weight: Float,
    reps: Int,
    weightUnit: WeightUnit,
    bottomContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(exerciseName, style = MaterialTheme.typography.headlineMedium)
        Text(progressLabel, style = MaterialTheme.typography.titleLarge, color = progressColor)

        Spacer(Modifier.weight(1f))

        Text("$reps reps", style = MaterialTheme.typography.displaySmall)
        when {
            weight > 0f -> {
                Text(WeightFormatter.format(weight, weightUnit), style = MaterialTheme.typography.displaySmall)
                if (equipment == Equipment.BARBELL) {
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
            equipment == Equipment.BODYWEIGHT -> Text(
                "Bodyweight",
                style = MaterialTheme.typography.displaySmall,
            )
        }

        Spacer(Modifier.weight(1f))

        bottomContent()
        Spacer(Modifier.height(16.dp))
        YoutubeFormCard(exerciseName = exerciseName)
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
            ) { Text("0-1 left") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.RIR_2_4) },
                modifier = Modifier.weight(1f),
            ) { Text("2-4 left") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.RIR_5_PLUS) },
                modifier = Modifier.weight(1f),
            ) { Text("5+ left") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.TOO_HARD) },
                colors = errorColor,
                modifier = Modifier.weight(1f),
            ) { Text("Too Hard") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.HURT) },
                colors = errorColor,
                modifier = Modifier.weight(1f),
            ) { Text("Hurt") }
        }
    }
}

@Composable
private fun DoneContent(
    doneSummary: WorkoutSummaryData?,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    WorkoutSummaryContent(
        summary = doneSummary,
        header = {
            Text("Workout Complete!", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
        },
        footer = {
            OutlinedButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text("Undo") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        },
    )
}

private const val MAX_EXERCISE_COUNT = 15

@Composable
private fun RestingContent(
    state: WorkoutState.Resting,
    onSkipRest: () -> Unit,
    onUndo: () -> Unit,
) {
    val plan = state.plan
    val totalSets = PlannedExercise.DEFAULT_SETS
    val nextSet = state.completedSetIndex + 1

    val upNextLabel = when {
        nextSet < totalSets -> "Set ${nextSet + 1} of $totalSets — ${plan.exercises[state.exerciseIndex].exercise.name}"
        state.exerciseIndex + 1 < plan.exercises.size ->
            "Next exercise: ${plan.exercises[state.exerciseIndex + 1].exercise.name}"
        else -> "Last set — almost done!"
    }

    val targetProgress = state.secondsRemaining / WorkoutViewModel.REST_SECONDS.toFloat()
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
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Rest", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Logged: ${state.lastFeedback.displayLabel}",
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
        Spacer(Modifier.height(32.dp))
        Text(
            upNextLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onUndo) { Text("Undo") }
            OutlinedButton(onClick = onSkipRest) { Text("Skip Rest") }
        }
    }
}
