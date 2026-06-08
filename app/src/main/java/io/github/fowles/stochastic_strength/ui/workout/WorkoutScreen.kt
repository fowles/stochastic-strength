package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
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
import kotlinx.coroutines.flow.first
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
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.ProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryContent
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryData
import io.github.fowles.stochastic_strength.ui.YoutubeFormCard
import io.github.fowles.stochastic_strength.ui.strava.StravaExportButton
import io.github.fowles.stochastic_strength.ui.strava.StravaExportState

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
    val stravaState by viewModel.stravaState.collectAsState()
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

    LaunchedEffect(stravaState) {
        when (val s = stravaState) {
            is StravaExportState.NeedsAuth -> {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.authUrl)))
                viewModel.onStravaAuthUrlLaunched()
            }
            is StravaExportState.Error -> viewModel.onStravaMessageShown()
            else -> Unit
        }
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
                    onAdjustWeight = viewModel::adjustExerciseWeight,
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
                    weightUnit = weightUnit,
                    onSkipRest = viewModel::skipRest,
                    onUndo = viewModel::undoLastSet,
                    onReduceWeight = viewModel::reduceExerciseWeight,
                )
                is WorkoutState.Done -> DoneContent(
                    doneSummary = doneSummary,
                    stravaState = stravaState,
                    onUndo = viewModel::undoLastSetFromDone,
                    onExportToStrava = viewModel::onExportToStrava,
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
    onAdjustWeight: (index: Int, delta: Float) -> Unit,
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
                val index = plan.exercises.indexOf(planned)
                ExercisePreviewRow(
                    planned = planned,
                    weightUnit = weightUnit,
                    onReplace = { reason -> onReplace(index, reason) },
                    onWeightDecrement = if (planned.sessionWeight > 0f) {
                        { onAdjustWeight(index, -2.5f) }
                    } else null,
                    onWeightIncrement = if (planned.sessionWeight > 0f) {
                        { onAdjustWeight(index, +2.5f) }
                    } else null,
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
    onWeightDecrement: (() -> Unit)?,
    onWeightIncrement: (() -> Unit)?,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onTap)
                    .padding(vertical = 12.dp),
            ) {
                val weightLabel = when {
                    planned.sessionWeight > 0f -> WeightFormatter.format(planned.sessionWeight, weightUnit)
                    planned.exercise.equipment == Equipment.BODYWEIGHT -> "Bodyweight"
                    else -> null
                }
                val repsLabel = formatQuantity(planned.sessionReps, planned.exercise.isTimed)
                Column(modifier = Modifier.weight(1f)) {
                    Text(planned.exercise.name, style = MaterialTheme.typography.titleMedium)
                    val detail = buildString {
                        append("${PlannedExercise.DEFAULT_SETS} sets × $repsLabel")
                        if (onWeightDecrement == null && weightLabel != null) append(" · $weightLabel")
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onWeightDecrement != null && onWeightIncrement != null && weightLabel != null) {
                    OutlinedButton(
                        onClick = onWeightDecrement,
                        modifier = Modifier.size(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("−", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        weightLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 64.dp),
                    )
                    OutlinedButton(
                        onClick = onWeightIncrement,
                        modifier = Modifier.size(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("+", style = MaterialTheme.typography.labelLarge)
                    }
                }
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
        exercise = exercise,
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
            exercise = exercise,
            progressLabel = "Set ${state.setIndex + 1} of ${state.totalSets}",
            progressColor = MaterialTheme.colorScheme.primary,
            weight = state.plannedExercise.sessionWeight,
            reps = state.plannedExercise.sessionReps,
            weightUnit = weightUnit,
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
    ) {
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
private fun ExerciseSetLayout(
    exercise: Exercise,
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

@Composable
private fun WeightReductionCard(
    sessionReps: Int,
    sessionWeight: Float,
    weightUnit: WeightUnit,
    equipment: Equipment,
    applied: Boolean,
    weightReduced: Boolean,
    onRepsSelected: (Int) -> Unit,
) {
    val errorColor = MaterialTheme.colorScheme.error
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Question content always in the layout tree so the card never shrinks when applied
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
                        maxOf(0.5f, ProgressionEngine.scaleWeight(sessionWeight, maxOf(1, reps), sessionReps)),
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
                            if (delta > 0f) {
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
        // Confirmation content overlaid on top when applied and weight actually changed
        if (applied && weightReduced) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Weight reduced to ${WeightFormatter.format(sessionWeight, weightUnit)}",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
                if (equipment == Equipment.BARBELL) {
                    WeightFormatter.platesPerSide(sessionWeight, weightUnit)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneContent(
    doneSummary: WorkoutSummaryData?,
    stravaState: StravaExportState,
    onUndo: () -> Unit,
    onExportToStrava: () -> Unit,
    onDone: () -> Unit,
) {
    WorkoutSummaryContent(
        summary = doneSummary,
        header = {
            Text("Workout Complete!", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
        },
        footer = {
            OutlinedButton(
                onClick = onUndo,
                enabled = !stravaState.undoBlocked,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Undo") }
            Spacer(Modifier.height(8.dp))
            StravaExportButton(
                onExportToStrava = onExportToStrava,
                stravaState = stravaState,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        },
    )
}

private const val MAX_EXERCISE_COUNT = 15

@Composable
private fun RestingContent(
    state: WorkoutState.Resting,
    weightUnit: WeightUnit,
    onSkipRest: () -> Unit,
    onUndo: () -> Unit,
    onReduceWeight: (Int) -> Unit,
) {
    val plan = state.plan
    val totalSets = PlannedExercise.DEFAULT_SETS
    val nextSet = state.completedSetIndex + 1

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
            val hasMoreSets = state.completedSetIndex < PlannedExercise.DEFAULT_SETS - 1
            val isWeighted = plannedExercise.exercise.equipment != Equipment.BODYWEIGHT
                && plannedExercise.sessionWeight > 0f
            if (state.lastFeedback == SetFeedback.TOO_HARD && hasMoreSets && isWeighted) {
                WeightReductionCard(
                    sessionReps = plannedExercise.sessionReps,
                    sessionWeight = plannedExercise.sessionWeight,
                    weightUnit = weightUnit,
                    equipment = plannedExercise.exercise.equipment,
                    applied = state.weightReductionApplied,
                    weightReduced = state.plan.exercises[state.exerciseIndex].sessionWeight != state.weightAtSetStart,
                    onRepsSelected = onReduceWeight,
                )
            }
        }
        // Exercises — always 20%
        RemainingExerciseList(
            exercises = plan.exercises,
            currentExerciseIndex = state.exerciseIndex,
            setsRemainingForCurrent = totalSets - nextSet,
            modifier = Modifier.weight(0.2f),
        )
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
