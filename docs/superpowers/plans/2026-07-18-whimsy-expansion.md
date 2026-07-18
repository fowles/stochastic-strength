# Whimsy Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rest-timer quips (4% per rest, never on the final rest, muscle-keyed to the upcoming set) plus pantheon diversification and Strava possessive titles.

**Architecture:** A pure selection function (`RestQuips`) in `domain/history/` picks an optional quip when a post-feedback rest begins; the result is stored on `WorkoutState.Resting` so it is stable for that rest, and rendered by `RestingContent`. Pantheon changes are pure string edits/additions to `HistoryHighlight.QUIPS` and `StravaExporter.ADJECTIVES`.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 JVM tests, jj for version control.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-18-whimsy-expansion-design.md` — read it before starting any task.
- No schema change, no new Room tables, no persisted state, no backtest impact.
- Rest-quip probability: exactly `0.04f` per rest.
- The final rest of a workout never shows a quip.
- Muscle-keyed quips are eligible only if the upcoming set's exercise works that muscle (primary or secondary).
- Diesel Tycho Brahe keeps his epithet; all epithets outside the re-epithet table below stay exactly as-is.
- Commits use `jj commit -m "<msg>" <paths>` (this repo uses Jujutsu, not git; `jj commit` takes the message and file paths directly, no staging step).
- Test command: `./gradlew :app:testDebugUnitTest --tests "<fully.qualified.Class>"`; full suite `./gradlew :app:testDebugUnitTest` at the end.

---

### Task 1: Re-epithet the Diesel-heavy canon

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlight.kt` (the `QUIPS` list, lines ~53–129)

**Interfaces:**
- Consumes: nothing.
- Produces: updated quip strings only; `HistoryHighlight.QUIPS: List<Quip>` shape unchanged.

Diesel appears 8× in the pantheon; only Diesel Tycho Brahe keeps it. Apply these exact string replacements inside `QUIPS` (old → new; each old string appears exactly once):

- [x] **Step 1: Apply the eight epithet edits**

| Old text (exact) | New text (exact) |
|---|---|
| `Entropy increases. So do your numbers. Coincidence? Diesel Boltzmann thinks not.` | `Entropy increases. So do your numbers. Coincidence? Burly Boltzmann thinks not.` |
| `Rest is not a reward, it's a requirement. Diesel Boltzmann insists.` | `Rest is not a reward, it's a requirement. Burly Boltzmann insists.` |
| `Diesel Fibonacci added the last two sets and got a bigger one.` | `Farm-Strong Fibonacci added the last two sets and got a bigger one.` |
| `Diesel Descartes: I lift, therefore I am.` | `Dense Descartes: I lift, therefore I am.` |
| `Diesel Avogadro counted your reps. Approximately 6.022 x 10^23, give or take.` | `Anabolic Avogadro counted your reps. Approximately 6.022 x 10^23, give or take.` |
| `Diesel da Vinci sketched the perfect proportions. You're editing his draft.` | `Vascular da Vinci sketched the perfect proportions. You're editing his draft.` |
| `Diesel Atlas held up the sky. You're just holding the row.` | `Mountainous Atlas held up the sky. You're just holding the row.` |
| `Diesel Sisyphus finally reached the top. Turns out it was calf raises.` | `Relentless Sisyphus finally reached the top. Turns out it was calf raises.` |

Do NOT touch `Diesel Tycho Brahe measured the heavens. You measure the gains.` — he is the flagship. The `muscle =` parameter on the Atlas (BACK) and Sisyphus (CALVES) quips stays unchanged.

- [x] **Step 2: Verify no stray Diesel remains**

Run: `rg -n "Diesel" app/src/main/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlight.kt`
Expected: exactly one match — the Diesel Tycho Brahe quip.

- [x] **Step 3: Run the highlight tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.HistoryHighlightTest"`
Expected: PASS (no test pins the edited strings).

- [x] **Step 4: Commit**

```bash
jj commit -m "feat(quips): redistribute Diesel epithets across the pantheon" app/src/main/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlight.kt
```

---

### Task 2: New pantheon members (10 new generic quips)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlight.kt` (append to the generic portion of `QUIPS`)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlightTest.kt`

**Interfaces:**
- Consumes: `Quip(text)` data class as-is.
- Produces: 10 additional generic (no-muscle) entries in `HistoryHighlight.QUIPS`.

- [x] **Step 1: Write the failing test**

Add to `HistoryHighlightTest.kt` (follows the existing `committed quip is present in the pool` pattern):

```kotlin
@Test
fun `new pantheon members are present and generic`() {
    val members = listOf(
        "Colossal Katherine Johnson", "Beefy al-Khwarizmi", "Chiseled Chien-Shiung Wu",
        "Titanic Tu Youyou", "Girthy George Washington Carver", "Peak Rosalind Franklin",
        "Unbreakable Emmy Noether", "Astro-Jacked Mae Jemison", "Granite Ibn al-Haytham",
        "Bulletproof Bose",
    )
    for (member in members) {
        val quip = HistoryHighlight.QUIPS.find { it.text.contains(member) }
        assertTrue("missing pantheon member: $member", quip != null)
        assertEquals("pantheon quips must be generic: $member", null, quip!!.muscle)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.HistoryHighlightTest"`
Expected: FAIL with `missing pantheon member: Colossal Katherine Johnson`

- [x] **Step 3: Add the ten quips**

Append these entries to `QUIPS` immediately after the `Buff Hypatia mapped the stars` quip (keeping the generic block together, before the muscle-keyed block that starts with `Curls for the girls`):

```kotlin
Quip("Colossal Katherine Johnson computed the trajectory. It points up."),
Quip("Beefy al-Khwarizmi invented algebra so someone could finally count your plates."),
Quip("Chiseled Chien-Shiung Wu broke parity. Your left and right sides are both on notice."),
Quip("Titanic Tu Youyou read two thousand ancient remedies. The strongest one was showing up."),
Quip("Girthy George Washington Carver found 300 uses for the peanut. You found one for the barbell: all of them."),
Quip("Peak Rosalind Franklin saw the structure everyone else missed. Yours is looking solid."),
Quip("Unbreakable Emmy Noether says every symmetry conserves something. Yours conserves gains."),
Quip("Astro-Jacked Mae Jemison made it to orbit. Your numbers are on the same flight path."),
Quip("Granite Ibn al-Haytham invented the experiment. Today's hypothesis: one more rep."),
Quip("Bulletproof Bose counted indistinguishable particles. Every one of your reps still counts."),
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.HistoryHighlightTest"`
Expected: PASS

- [x] **Step 5: Commit**

```bash
jj commit -m "feat(quips): ten new pantheon members, chosen for diversity" app/src/main/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlight.kt app/src/test/java/io/github/fowles/stochastic_strength/domain/history/HistoryHighlightTest.kt
```

---

### Task 3: `RestQuips` selection function (TDD)

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/history/RestQuips.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/history/RestQuipsTest.kt`

**Interfaces:**
- Consumes: `HistoryHighlight.QUIPS`, `Quip`, `MuscleGroup`.
- Produces: `object RestQuips` with `const val QUIP_PROBABILITY = 0.04f` and `fun pick(upcomingMuscles: Set<MuscleGroup>?, random: Random): String?`. Task 4 calls exactly this signature. `upcomingMuscles == null` means "this is the final rest" and must always return null.

- [x] **Step 1: Write the failing tests**

Create `RestQuipsTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.history

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RestQuipsTest {

    @Test
    fun `final rest never quips regardless of seed`() {
        repeat(1000) { s ->
            assertNull(RestQuips.pick(upcomingMuscles = null, random = Random(s.toLong())))
        }
    }

    @Test
    fun `fires at roughly the configured probability`() {
        val trials = 10_000
        val hits = (0 until trials).count { s ->
            RestQuips.pick(setOf(MuscleGroup.CHEST), Random(s.toLong())) != null
        }
        // 4% of 10k = 400; wide band to avoid flakiness while catching 7% (700) or 0%.
        assertTrue("hit rate $hits/10000", hits in 250..550)
    }

    @Test
    fun `picked quips are always eligible for the upcoming muscles`() {
        val upcoming = setOf(MuscleGroup.QUADS)
        (0 until 10_000).forEach { s ->
            val text = RestQuips.pick(upcoming, Random(s.toLong())) ?: return@forEach
            val quip = HistoryHighlight.QUIPS.first { it.text == text }
            assertTrue(text, quip.muscle == null || quip.muscle == MuscleGroup.QUADS)
        }
    }

    @Test
    fun `muscle-keyed quips are reachable when their muscle is upcoming`() {
        val sawMuscleKeyed = (0 until 20_000).any { s ->
            val text = RestQuips.pick(setOf(MuscleGroup.BICEPS), Random(s.toLong()))
            text != null && HistoryHighlight.QUIPS.first { it.text == text }.muscle == MuscleGroup.BICEPS
        }
        assertTrue("expected at least one biceps quip across seeds", sawMuscleKeyed)
    }

    @Test
    fun `same seed gives same result`() {
        val a = RestQuips.pick(setOf(MuscleGroup.BACK), Random(42))
        val b = RestQuips.pick(setOf(MuscleGroup.BACK), Random(42))
        assertEquals(a, b)
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.RestQuipsTest"`
Expected: compilation FAILURE — `RestQuips` unresolved.

- [x] **Step 3: Write the implementation**

Create `RestQuips.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.history

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import kotlin.random.Random

/**
 * Occasional quip for the rest-timer screen. Deliberately scarce: at ~15 rests per
 * workout, 4% averages under one sighting per workout, so no repeat-avoidance
 * state is needed. [upcomingMuscles] is the muscle set of the exercise the rest
 * precedes; null means this is the final rest (the Done screen's HighlightCard
 * follows immediately, so never quip there).
 */
object RestQuips {
    const val QUIP_PROBABILITY = 0.04f

    fun pick(upcomingMuscles: Set<MuscleGroup>?, random: Random): String? {
        if (upcomingMuscles == null) return null
        if (random.nextFloat() >= QUIP_PROBABILITY) return null
        val eligible = HistoryHighlight.QUIPS.filter {
            it.muscle == null || it.muscle in upcomingMuscles
        }
        return eligible.random(random).text
    }
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.RestQuipsTest"`
Expected: PASS (5 tests)

- [x] **Step 5: Commit**

```bash
jj commit -m "feat(quips): RestQuips scarce selection for the rest timer" app/src/main/java/io/github/fowles/stochastic_strength/domain/history/RestQuips.kt app/src/test/java/io/github/fowles/stochastic_strength/domain/history/RestQuipsTest.kt
```

---

### Task 4: Wire the quip into the Resting state and screen

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt` (add field to `Resting`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt` (`recordFeedback`, new private helper)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/RestingContent.kt` (render the quip)

**Interfaces:**
- Consumes: `RestQuips.pick(upcomingMuscles: Set<MuscleGroup>?, random: Random): String?` from Task 3.
- Produces: `WorkoutState.Resting.restQuip: String?` (default null), read only by `RestingContent`.

The pick happens once, inside `recordFeedback`, when the Resting state is constructed — so it is stable across recomposition and countdown ticks (each tick uses `copy()`, preserving the field). Staged-action rests (`stageRest`) keep the default null: their subtitle slot already carries action-specific copy. Undo deletes the set and leaves Resting entirely, so a fresh pick on the next feedback is correct.

- [x] **Step 1: Add the field to `WorkoutState.Resting`**

In `WorkoutState.kt`, add the last parameter to the `Resting` data class:

```kotlin
    data class Resting(
        val plan: WorkoutPlan,
        val exerciseIndex: Int,
        val completedSetIndex: Int,
        val sessionId: Long,
        val secondsRemaining: Int,
        val lastFeedback: SetFeedback?,
        val weightReductionApplied: Boolean = false,
        val weightAtSetStart: Float,
        val currentSetRowId: Long,
        val staged: StagedAction? = null,
        val restQuip: String? = null,
    ) : WorkoutState
```

- [x] **Step 2: Pick the quip in `recordFeedback`**

In `WorkoutSessionController.kt`:

Add imports:

```kotlin
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.history.RestQuips
import kotlin.random.Random
```

Add a private helper next to `advanceAfterRest` (it mirrors that function's next-step logic; null = final rest):

```kotlin
    /** Muscles of the exercise the upcoming rest precedes; null when the rest is the workout's last. */
    private fun upcomingMusclesAfterRest(
        plan: WorkoutPlan,
        exerciseIndex: Int,
        completedSetIndex: Int,
    ): Set<MuscleGroup>? {
        val exercise = when {
            completedSetIndex + 1 < PlannedExercise.DEFAULT_SETS -> plan.exercises[exerciseIndex]
            exerciseIndex + 1 < plan.exercises.size -> plan.exercises[exerciseIndex + 1]
            else -> return null
        }.exercise
        return setOf(exercise.primaryMuscle) + exercise.secondaryMuscles
    }
```

In `recordFeedback`, extend the `WorkoutState.Resting` construction (after `currentSetRowId = rowId,`):

```kotlin
                currentSetRowId = rowId,
                restQuip = RestQuips.pick(
                    upcomingMusclesAfterRest(current.plan, current.exerciseIndex, completedSetIndex),
                    Random.Default,
                ),
```

Note: `completedSetIndex` here is the local val computed just above (HURT jumps it to `totalSets - 1`, which correctly makes the upcoming exercise the *next* one — or null when HURT ends the last exercise).

- [x] **Step 3: Render the quip in `RestingContent`**

In `RestingContent.kt`, add import `androidx.compose.ui.text.font.FontStyle`, then insert after the Undo/Skip button `Row` (directly below `Row(horizontalArrangement = ...) { ... }` inside the countdown `Column`):

```kotlin
                state.restQuip?.let { quip ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        quip,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
```

- [x] **Step 4: Build and run the app-side tests**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.history.RestQuipsTest" --tests "io.github.fowles.stochastic_strength.domain.history.HistoryHighlightTest"`
Expected: PASS

- [x] **Step 5: Commit**

```bash
jj commit -m "feat(workout): occasional quip on the rest timer (4%, never final rest)" app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/RestingContent.kt
```

---

### Task 5: Strava possessive titles + full verification

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/strava/StravaExporter.kt` (`ADJECTIVES`)

**Interfaces:**
- Consumes: nothing new; `buildWorkoutName()` already does `"${ADJECTIVES.random()} ${STRENGTHS.random()} ${WORKOUT_NOUNS.random()}"`.
- Produces: 10 possessive entries appended to `ADJECTIVES` (35 → 45; pantheon titles ~1 in 4.5 exports, e.g. "Chiseled Chien-Shiung Wu's Entropic Gauntlet").

- [x] **Step 1: Append the possessives**

In `StravaExporter.kt`, extend `ADJECTIVES` by adding one final line before the closing parenthesis:

```kotlin
        private val ADJECTIVES = listOf(
            "Stochastic", "Capricious", "Serendipitous", "Haphazard", "Aleatory",
            "Mercurial", "Erratic", "Fortuitous", "Whimsical", "Impromptu",
            "Spontaneous", "Arbitrary", "Incidental", "Unpredictable", "Chaotic",
            "Probabilistic", "Nondeterministic", "Random", "Entropic", "Brownian",
            "Quantum", "Unforeseen", "Improvised", "Freewheeling", "Extemporaneous",
            "Wayward", "Untamed", "Emergent", "Turbulent", "Kaleidoscopic",
            "Roving", "Vagrant", "Dicey", "Monte-Carlo", "Unscripted",
            "Yoked Galileo's", "Diesel Tycho Brahe's", "Massive Marie Curie's",
            "Jacked Ada Lovelace's", "Ripped Ramanujan's", "Beefy al-Khwarizmi's",
            "Chiseled Chien-Shiung Wu's", "Unbreakable Emmy Noether's",
            "Buff Hypatia's", "Stacked Turing's",
        )
```

- [x] **Step 2: Run the strava tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.strava.StravaDescriptionTest"`
Expected: PASS (titles are untested data; description tests must stay green).

- [x] **Step 3: Full unit-test suite (global CLAUDE.md requirement)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests PASS, including the backtest gate (`BeliefScoreTest`) — this change is display-strings only, so any backtest movement means something went wrong.

- [x] **Step 4: Commit**

```bash
jj commit -m "feat(strava): pantheon possessives join the title adjectives" app/src/main/java/io/github/fowles/stochastic_strength/domain/strava/StravaExporter.kt
```

---

### Task 6: Instrumented tests

- [x] **Step 1: Run instrumented tests** (emulator is typically already running — attempt directly)

Run: `./gradlew :app:connectedAndroidTest`
Expected: PASS. If no device is connected, report that to the user rather than skipping silently.

- [x] **Step 2: No commit needed** (verification only).
