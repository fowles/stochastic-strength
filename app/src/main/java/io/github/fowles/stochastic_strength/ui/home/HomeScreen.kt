package io.github.fowles.stochastic_strength.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.WeightUnit

@Composable
fun HomeScreen(
    onStartWorkout: () -> Unit,
    onHistory: () -> Unit,
    onExercises: () -> Unit,
    onLocations: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val onStartWorkoutState = rememberUpdatedState(onStartWorkout)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> onStartWorkoutState.value() }

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                HomeState.Loading -> CircularProgressIndicator()
                HomeState.ProfileSetup -> ProfileSetupContent(
                    onConfirm = { sex, level, unit -> viewModel.submitProfile(sex, level, unit) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                )
                HomeState.Ready -> ReadyContent(
                    onStart = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            onStartWorkoutState.value()
                        } else {
                            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    onHistory = onHistory,
                    onExercises = onExercises,
                    onLocations = onLocations,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onExercises: () -> Unit,
    onLocations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Stochastic Strength", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Random workouts. Real progress.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start Workout")
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Text("History")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onExercises, modifier = Modifier.fillMaxWidth()) {
            Text("Exercises")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onLocations, modifier = Modifier.fillMaxWidth()) {
            Text("Locations")
        }
    }
}

@Composable
private fun ProfileSetupContent(
    onConfirm: (Sex, StrengthLevel, WeightUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSex by remember { mutableStateOf<Sex?>(null) }
    var selectedLevel by remember { mutableStateOf<StrengthLevel?>(null) }
    var selectedUnit by remember { mutableStateOf<WeightUnit?>(null) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Welcome", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tell us a bit about yourself so we can set your starting weights.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        Text("Hormonal sex", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SelectableButton(
                label = "Male",
                selected = selectedSex == Sex.MALE,
                onClick = { selectedSex = Sex.MALE },
                modifier = Modifier.weight(1f),
            )
            SelectableButton(
                label = "Female",
                selected = selectedSex == Sex.FEMALE,
                onClick = { selectedSex = Sex.FEMALE },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("Current strength level", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SelectableButton(
                label = "Beginner",
                selected = selectedLevel == StrengthLevel.LOW,
                onClick = { selectedLevel = StrengthLevel.LOW },
                modifier = Modifier.weight(1f),
            )
            SelectableButton(
                label = "Intermediate",
                selected = selectedLevel == StrengthLevel.MEDIUM,
                onClick = { selectedLevel = StrengthLevel.MEDIUM },
                modifier = Modifier.weight(1f),
            )
            SelectableButton(
                label = "Advanced",
                selected = selectedLevel == StrengthLevel.HIGH,
                onClick = { selectedLevel = StrengthLevel.HIGH },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("Preferred units", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SelectableButton(
                label = "kg",
                selected = selectedUnit == WeightUnit.KG,
                onClick = { selectedUnit = WeightUnit.KG },
                modifier = Modifier.weight(1f),
            )
            SelectableButton(
                label = "lbs",
                selected = selectedUnit == WeightUnit.LBS,
                onClick = { selectedUnit = WeightUnit.LBS },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(40.dp))
        val sex = selectedSex
        val level = selectedLevel
        val unit = selectedUnit
        Button(
            onClick = { if (sex != null && level != null && unit != null) onConfirm(sex, level, unit) },
            enabled = sex != null && level != null && unit != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Get Started")
        }
    }
}

@Composable
private fun SelectableButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
