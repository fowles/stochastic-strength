package io.github.fowles.stochastic_strength.ui.summary

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutNaming
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryContent
import io.github.fowles.stochastic_strength.ui.components.NameDialog
import io.github.fowles.stochastic_strength.ui.strava.StravaExportButton
import io.github.fowles.stochastic_strength.ui.strava.StravaExportState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    sessionId: Long,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    onExerciseTap: ((Long) -> Unit)? = null,
    viewModel: SummaryViewModel = viewModel(factory = SummaryViewModel.factory(sessionId)),
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val stravaState by viewModel.stravaState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(stravaState) {
        when (val state = stravaState) {
            is StravaExportState.NeedsAuth -> {
                context.startActivity(Intent(Intent.ACTION_VIEW, state.authUrl.toUri()))
                viewModel.onStravaAuthUrlLaunched()
            }
            is StravaExportState.Error -> viewModel.onStravaMessageShown()
            else -> Unit
        }
    }

    val locale = LocalConfiguration.current.locales[0]
    val dateLabel = summary?.let {
        SimpleDateFormat("EEEE, MMM d · h:mm a", locale).format(Date(it.startTime))
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Keyed on Unit: re-keying on the message would cancel the in-flight showSnackbar.
    LaunchedEffect(Unit) {
        viewModel.message.collect { m ->
            if (m != null) {
                viewModel.clearMessage()
                snackbarHostState.showSnackbar(m)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { if (dateLabel != null) Text(dateLabel) },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Re-export to Strava") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.onReexportToStrava()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Save as workout...") },
                                enabled = summary != null,
                                onClick = { menuExpanded = false; showSaveDialog = true },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        WorkoutSummaryContent(
            summary = summary,
            modifier = Modifier.padding(paddingValues),
            onExerciseTap = onExerciseTap,
            header = {},
            footer = {
                if (onBack != null) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text("Back")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                StravaExportButton(
                    onExportToStrava = viewModel::onExportToStrava,
                    stravaState = stravaState,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            },
        )
    }

    val loaded = summary
    if (showSaveDialog && loaded != null) {
        NameDialog(
            title = "Save as workout",
            placeholder = SavedWorkoutNaming.defaultName(loaded.exercises.map { it.name }),
            onConfirm = { name -> showSaveDialog = false; viewModel.saveAsWorkout(name) },
            onDismiss = { showSaveDialog = false },
        )
    }
}
