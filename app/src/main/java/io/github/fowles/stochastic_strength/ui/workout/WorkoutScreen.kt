package io.github.fowles.stochastic_strength.ui.workout

import android.Manifest
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
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

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { onWorkoutDone() }
    }

    LaunchedEffect(stravaState) {
        when (val s = stravaState) {
            is StravaExportState.NeedsAuth -> {
                activity.startActivity(Intent(Intent.ACTION_VIEW, s.authUrl.toUri()))
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
                is WorkoutState.PlanPreview -> {
                    PlanPreviewContent(
                        state = s,
                        weightUnit = weightUnit,
                        onStart = viewModel::startFirstExercise,
                        onReplace = viewModel::replaceExercise,
                        onSetExerciseCount = viewModel::setExerciseCount,
                        onSetRepRange = viewModel::setRepRange,
                        onAdjustWeight = viewModel::adjustExerciseWeight,
                        onMove = viewModel::moveExercise,
                        onEditLocation = { locationId ->
                            onEditLocation(locationId)
                        },
                        onExerciseTap = onExerciseTap,
                    )
                    s.detraining?.let { prompt ->
                        DetrainingDialog(
                            prompt = prompt,
                            weightUnit = weightUnit,
                            onApply = viewModel::applyDetraining,
                            onSkip = viewModel::skipDetraining,
                        )
                    }
                }
                is WorkoutState.ActiveSet -> {
                    var showWeightDialog by rememberSaveable(s.exerciseIndex, s.setIndex, s.warmupSetIndex) {
                        mutableStateOf(false)
                    }
                    val planned = s.plannedExercise
                    val weightAdjustable = planned.sessionWeight > 0f && !planned.exercise.isTimed
                    val actions: @Composable () -> Unit = {
                        SetActionsMenu(
                            weightAdjustable = weightAdjustable,
                            onAdjustWeight = { showWeightDialog = true },
                            onSwapNoEquipment = { viewModel.swapCurrentExercise(ExerciseRemovalReason.NO_EQUIPMENT) },
                            onSwapDislike = { viewModel.swapCurrentExercise(ExerciseRemovalReason.DISLIKE) },
                            onEndExercise = { viewModel.endCurrentExercise() },
                            onStopWorkout = { viewModel.stopWorkout() },
                        )
                    }
                    if (showWeightDialog) {
                        WeightAdjustDialog(
                            exerciseName = planned.exercise.name,
                            startWeight = planned.sessionWeight,
                            equipment = planned.exercise.equipment,
                            weightUnit = weightUnit,
                            onConfirm = { newWeight ->
                                showWeightDialog = false
                                viewModel.setActiveSetWeight(newWeight)
                            },
                            onDismiss = { showWeightDialog = false },
                        )
                    }
                    if (s.warmupSetIndex != null) {
                        WarmupSetContent(
                            state = s,
                            weightUnit = weightUnit,
                            onDone = viewModel::completeWarmupSet,
                            actions = actions,
                        )
                    } else {
                        ActiveSetContent(
                            state = s,
                            weightUnit = weightUnit,
                            onFeedback = viewModel::recordFeedback,
                            onStartTimedSet = viewModel::startTimedSet,
                            actions = actions,
                        )
                    }
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
                    onExportToStrava = viewModel::onExportToStrava,
                    onDone = viewModel::completeWorkout,
                )
            }
        }
    }
}
