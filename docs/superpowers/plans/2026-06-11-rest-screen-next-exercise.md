# Rest Screen: Next Exercise Preview — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During the rest between sets, show a titled card with the upcoming exercise's weight and plate breakdown — "Reduced weight" when weight was just lowered, "Warm up" or "Next up" after the last set of an exercise.

**Architecture:** All changes are in `WorkoutScreen.kt`. A new private composable `NextExerciseCard` is added and wired into two places: the applied-confirmation branch of `WeightReductionCard`, and the last-set branch of the card area in `RestingContent`. No ViewModel or domain changes are needed — all required data is already present in `WorkoutState.Resting` and `PlannedExercise`.

**Tech Stack:** Jetpack Compose, Material3. Build with `./gradlew :app:assembleDebug`. Run on device with `./gradlew :app:connectedAndroidTest`.

---

### Task 1: Add `NextExerciseCard` composable

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`

- [x] **Step 1: Add `NextExerciseCard` after the `WeightReductionCard` composable (around line 766)**

  Insert this private composable immediately after the closing brace of `WeightReductionCard`:

  ```kotlin
  @Composable
  private fun NextExerciseCard(
      title: String,
      exerciseName: String,
      weight: Float,
      equipment: Equipment,
      weightUnit: WeightUnit,
  ) {
      Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
          Text(
              title,
              style = MaterialTheme.typography.labelLarge,
              textAlign = TextAlign.Center,
          )
          Text(
              exerciseName,
              style = MaterialTheme.typography.titleMedium,
              textAlign = TextAlign.Center,
          )
          if (weight > 0f) {
              Text(
                  WeightFormatter.format(weight, weightUnit),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
              )
              if (equipment == Equipment.BARBELL) {
                  WeightFormatter.platesPerSide(weight, weightUnit)?.let {
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
  ```

- [x] **Step 2: Verify it compiles**

  ```
  ./gradlew :app:assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: Commit**

  ```
  jj commit -m "feat: add NextExerciseCard composable"
  ```

---

### Task 2: Wire `WeightReductionCard` "Reduced weight" state to `NextExerciseCard`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`

The `WeightReductionCard` composable currently has a bespoke confirmation overlay (`if (applied && weightReduced) { Column { Text("Weight reduced to ...") ... } }`). Replace it with `NextExerciseCard` and add `exerciseName` as a parameter.

- [x] **Step 1: Add `exerciseName: String` parameter to `WeightReductionCard`**

  Change the signature from:

  ```kotlin
  private fun WeightReductionCard(
      sessionReps: Int,
      sessionWeight: Float,
      weightUnit: WeightUnit,
      equipment: Equipment,
      applied: Boolean,
      weightReduced: Boolean,
      onRepsSelected: (Int) -> Unit,
  )
  ```

  to:

  ```kotlin
  private fun WeightReductionCard(
      exerciseName: String,
      sessionReps: Int,
      sessionWeight: Float,
      weightUnit: WeightUnit,
      equipment: Equipment,
      applied: Boolean,
      weightReduced: Boolean,
      onRepsSelected: (Int) -> Unit,
  )
  ```

- [x] **Step 2: Replace the bespoke confirmation Column with `NextExerciseCard`**

  Find the block near the bottom of `WeightReductionCard`:

  ```kotlin
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
  ```

  Replace it with:

  ```kotlin
  if (applied && weightReduced) {
      NextExerciseCard(
          title = "Reduced weight",
          exerciseName = exerciseName,
          weight = sessionWeight,
          equipment = equipment,
          weightUnit = weightUnit,
      )
  }
  ```

- [x] **Step 3: Update the call site in `RestingContent` to pass `exerciseName`**

  Find the existing `WeightReductionCard(...)` call inside `RestingContent`. Add `exerciseName = plannedExercise.exercise.name` as the first argument:

  ```kotlin
  WeightReductionCard(
      exerciseName = plannedExercise.exercise.name,
      sessionReps = plannedExercise.sessionReps,
      sessionWeight = plannedExercise.sessionWeight,
      weightUnit = weightUnit,
      equipment = plannedExercise.exercise.equipment,
      applied = state.weightReductionApplied,
      weightReduced = state.plan.exercises[state.exerciseIndex].sessionWeight != state.weightAtSetStart,
      onRepsSelected = onReduceWeight,
  )
  ```

- [x] **Step 4: Verify it compiles**

  ```
  ./gradlew :app:assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL`

- [x] **Step 5: Commit**

  ```
  jj commit -m "feat: replace WeightReductionCard confirmation with NextExerciseCard"
  ```

---

### Task 3: Show next-exercise preview after the last set

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`

In `RestingContent`, the card-area `Box` currently renders `WeightReductionCard` or nothing. Add an `else if` branch for the last-set case.

- [x] **Step 1: Add next-exercise preview to the card area in `RestingContent`**

  Find the card area block in `RestingContent` (the `Box` with `weight(0.2f)`). It currently contains:

  ```kotlin
  val plannedExercise = state.plan.exercises[state.exerciseIndex]
  val hasMoreSets = state.completedSetIndex < PlannedExercise.DEFAULT_SETS - 1
  val isWeighted = plannedExercise.exercise.equipment != Equipment.BODYWEIGHT
      && plannedExercise.sessionWeight > 0f
  if (state.lastFeedback == SetFeedback.TOO_HARD && hasMoreSets && isWeighted) {
      WeightReductionCard(
          exerciseName = plannedExercise.exercise.name,
          ...
      )
  }
  ```

  Replace with:

  ```kotlin
  val plannedExercise = state.plan.exercises[state.exerciseIndex]
  val hasMoreSets = state.completedSetIndex < PlannedExercise.DEFAULT_SETS - 1
  val isWeighted = plannedExercise.exercise.equipment != Equipment.BODYWEIGHT
      && plannedExercise.sessionWeight > 0f
  val nextExercise = if (state.exerciseIndex + 1 < plan.exercises.size)
      plan.exercises[state.exerciseIndex + 1] else null
  if (state.lastFeedback == SetFeedback.TOO_HARD && hasMoreSets && isWeighted) {
      WeightReductionCard(
          exerciseName = plannedExercise.exercise.name,
          sessionReps = plannedExercise.sessionReps,
          sessionWeight = plannedExercise.sessionWeight,
          weightUnit = weightUnit,
          equipment = plannedExercise.exercise.equipment,
          applied = state.weightReductionApplied,
          weightReduced = state.plan.exercises[state.exerciseIndex].sessionWeight != state.weightAtSetStart,
          onRepsSelected = onReduceWeight,
      )
  } else if (!hasMoreSets && nextExercise != null) {
      val warmup = nextExercise.warmupSets.firstOrNull()
      if (warmup != null) {
          NextExerciseCard(
              title = "Warm up",
              exerciseName = nextExercise.exercise.name,
              weight = warmup.weight,
              equipment = nextExercise.exercise.equipment,
              weightUnit = weightUnit,
          )
      } else {
          NextExerciseCard(
              title = "Next up",
              exerciseName = nextExercise.exercise.name,
              weight = nextExercise.sessionWeight,
              equipment = nextExercise.exercise.equipment,
              weightUnit = weightUnit,
          )
      }
  }
  ```

- [x] **Step 2: Verify it compiles**

  ```
  ./gradlew :app:assembleDebug
  ```

  Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: Run unit tests to check for regressions**

  ```
  ./gradlew :app:testDebugUnitTest
  ```

  Expected: `BUILD SUCCESSFUL` with all tests passing.

- [x] **Step 4: Commit**

  ```
  jj commit -m "feat: show next exercise weight card after last set during rest"
  ```
