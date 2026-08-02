package io.github.fowles.stochastic_strength.ui.history

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.domain.history.HistoryRow
import io.github.fowles.stochastic_strength.domain.history.HistoryRows
import io.github.fowles.stochastic_strength.ui.components.BackTopAppBar
import io.github.fowles.stochastic_strength.ui.components.LoadingBox
import io.github.fowles.stochastic_strength.ui.components.formatDateTime
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalConfiguration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onSessionTap: (Long) -> Unit,
    onExerciseTap: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingImportUri = uri }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            BackTopAppBar(
                title = "History",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Inspire me") },
                            onClick = {
                                menuExpanded = false
                                viewModel.inspireMe()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export history") },
                            onClick = {
                                menuExpanded = false
                                exportLauncher.launch("stochastic-strength-backup.json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Import history") },
                            onClick = {
                                menuExpanded = false
                                importLauncher.launch(arrayOf("application/json"))
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val importUri = pendingImportUri
        if (importUri != null) {
            AlertDialog(
                onDismissRequest = { pendingImportUri = null },
                title = { Text("Import history") },
                text = { Text("Add these workouts to your current history, or replace everything?") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingImportUri = null
                        viewModel.importFrom(importUri, ImportMode.DESTRUCTIVE)
                    }) {
                        Text("Replace all", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            pendingImportUri = null
                            viewModel.importFrom(importUri, ImportMode.ADDITIVE)
                        }) { Text("Add") }
                        TextButton(onClick = { pendingImportUri = null }) { Text("Cancel") }
                    }
                },
            )
        }

        if (state.pendingDeleteSessionId != null) {
            AlertDialog(
                onDismissRequest = { viewModel.cancelDelete() },
                title = { Text("Delete session?") },
                text = { Text("This will permanently remove the session and all its recorded sets.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmDelete() }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelDelete() }) { Text("Cancel") }
                },
            )
        }

        if (state.loading) {
            LoadingBox(contentPadding = padding)
            return@Scaffold
        }

        val zone = ZoneId.systemDefault()
        val entryDates = remember(state.sessions) {
            state.sessions.map { HistoryRows.localDate(it.session.startTime, zone) }
        }
        val rows = remember(state.sessions) { HistoryRows.buildRows(entryDates) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HighlightCard(text = state.highlight)

            MonthCalendar(
                workoutDays = state.workoutDays,
                onDayTap = { date ->
                    HistoryRows.firstRowIndexForDate(rows, date)?.let { index ->
                        scope.launch { listState.animateScrollToItem(index) }
                    }
                },
            )

            if (state.sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No sessions yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(
                        rows,
                        key = { row ->
                            when (row) {
                                is HistoryRow.MonthHeader -> "h-${row.month}"
                                is HistoryRow.Entry -> "s-${state.sessions[row.itemIndex].session.id}"
                            }
                        },
                    ) { row ->
                        when (row) {
                            is HistoryRow.MonthHeader -> MonthDividerRow(row.month)
                            is HistoryRow.Entry -> {
                                val item = state.sessions[row.itemIndex]
                                SessionRow(
                                    item = item,
                                    onClick = { onSessionTap(item.session.id) },
                                    onDelete = { viewModel.requestDelete(item.session.id) },
                                )
                                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthDividerRow(month: YearMonth) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SessionRow(item: SessionListItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatDateTime(item.session.startTime),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (item.locationName != null) {
                Text(
                    text = item.locationName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.exerciseNames.isNotEmpty()) {
                Text(
                    text = item.exerciseNames.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatDuration(item.durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${item.exerciseNames.size} exercises",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete session",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDuration(seconds: Long): String =
    "%d:%02d".format(seconds / 60, seconds % 60)
