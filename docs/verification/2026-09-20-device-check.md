# Device verification script — 2026-09-20

Everything here needs a human with the app on screen. Nothing in this file can be checked from a
unit or instrumented test; that is exactly why each item is still open.

Seven checks. Five are backlog items from the 2026-09-19 sweep that were written off-device; two
are new behaviour from the 2026-09-20 pass. **Check 3 and Check 7 are the ones most likely to
fail** — they cover code that changed on 2026-09-20 and has never been seen running.

Work through them in order: the setup in Check 2 (a 3-row circuit on plan preview) is reused by
Checks 1, 3 and 4, so you only build it once.

Record each as PASS / FAIL / SKIPPED with a one-line note. What to do with the results is at the
bottom.

---

## Setup

Build and install the current working tree:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installDebug
```

`adb` lives at `~/Library/Android/sdk/platform-tools/adb` if it is not on your PATH.

You need at least three barbell/dumbbell exercises available at your current location, and enough
history that the planner prescribes real weights. A fresh install works — the cold-start seeds give
every exercise a weight.

**Vocabulary used below**, so the steps are unambiguous:

- **Plan preview** — the "Today's Workout" screen, reached by Home → *Start Workout*.
- **Link node** — the small circular control on the left rail *between* two adjacent rows. It is
  owned and drawn by the **lower** of the two rows. Its accessibility label is "Link" when the rows
  are separate and "Unlink" when they are already a circuit.
- **Circuit** — two or more adjacent rows joined by link nodes. A circuit is one draggable block.
- **Block head** — the first row of a circuit, or a solo row. **Only the block head draws the drag
  handle and the rounds chip**; middle and last rows share the head's chip and have no handle of
  their own.
- **Rounds chip** — the `N ×` chip next to the drag handle. Label "Rounds: N" on a circuit,
  "Sets: N" on a solo row.

---

## Check 1 — TalkBack reads the link node *between* the two rows it links

Covers the 2026-09-19 task-4 change (`isTraversalGroup` + `traversalIndex = -1f` on `LinkNodeHost`).
The full rationale and the revert instructions are in
`.superpowers/sdd/2026-09-19-todo-sweep/task-4-report.md`; this is that report's checklist, with the
setup steps filled in.

### Setup

Turn TalkBack on: **Settings → Accessibility → TalkBack → On**. (Or, on an emulator,
`adb shell settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`
then `adb shell settings put secure accessibility_enabled 1`; reverse with
`adb shell settings put secure accessibility_enabled 0`. Note that with TalkBack on, every tap
below becomes double-tap.)

You need **two** arrangements. Build them one at a time on plan preview:

- **(a) One 3-row circuit.** Drag the exercise-count slider right until the plan has at least three
  rows, then tap the link node between rows 1 and 2, and the one between rows 2 and 3. All three
  rows now share one rail.
- **(b) Two 2-row circuits, back to back.** Get the plan to four rows. Link rows 1–2, and separately
  link rows 3–4. Leave the boundary between row 2 and row 3 *un*linked. This is the case that
  crosses a `LazyColumn` item boundary — a single bigger circuit does not exercise it.

### Steps

Swipe right (TalkBack linear navigation) through the list, one element at a time, starting from
above the first circuit.

### PASS

**(a) 3-row circuit** reads:

1. Row 1's content
2. "Link"/"Unlink", button  ← the node between rows 1 and 2
3. Row 2's content
4. "Link"/"Unlink", button  ← the node between rows 2 and 3
5. Row 3's content

**(b) Two 2-row circuits** reads:

1. Circuit A row 1 content
2. "Link"/"Unlink", button
3. Circuit A row 2 content
4. Circuit B row 1 content ← **no node here**; the first row of a block has no boundary above it
5. "Link"/"Unlink", button
6. Circuit B row 2 content

### FAIL

Either arrangement reads content → node → content → node, i.e. each node announced *after* its own
row rather than before it. Or a node is announced next to the wrong circuit's rows.

### On fail

Revert the three semantics lines in `LinkNodeHost` per the "What to revert if it reads wrong"
section of the task-4 report — it is a self-contained commit (`56501b50`).

**Turn TalkBack back off before continuing.** The remaining checks involve drags and swipes that
TalkBack intercepts.

---

## Check 2 — the circuit rail stays continuous through a swiped row

Covers the 2026-09-19 task-3 change (`CircuitRailGutter`, `LinkNodeHost.swipeOffsetPx`, and the
action-row gutter).

### Setup

Plan preview with **one 3-row circuit** — arrangement (a) from Check 1. Keep it for Checks 3 and 4.

### Steps

1. Swipe the **middle** row of the circuit left, far enough that it is rejected and the action row
   ("No gear" / "Hate it" / "Not today") replaces it.
2. Look at the left rail while the action row is showing.
3. Dismiss the action row (pick one of the three buttons).
4. Now swipe the middle row left again but only **part way** — about a third of the width — and
   hold, watching the link node above it.

### PASS

- Step 2: the rail is a single unbroken vertical line down all three rows. The action row's three
  buttons and progress bar are inset from the left by the rail gutter (~36dp), not overlapping it.
- Step 4: the link node above the swiping row **moves with the row** as you drag, then snaps back to
  its resting position the instant the action row takes over.

Also confirm, for contrast: swipe a **solo** row (one outside the circuit) and check its action row
uses the **full** width, with no 36dp inset — the gutter is drawn only for circuit members.

### FAIL

The rail has a gap next to the swiped row; the action row's buttons sit under or across the rail;
the link node stays put while the row slides out from under it; or a solo row's action row is
inset by 36dp for no reason.

---

## Check 3 — block drag still works, with the remembered drag handle ⚠️

**This is new behaviour from 2026-09-20** (`55ccc7d7c` — `Modifier.draggableHandle()` is now
`remember`ed once per `ReorderableItem` instead of rebuilt each composition). It is the change most
likely to be wrong, because it touches how the gesture modifier is created.

Note: the handle is drawn **only on the block head**. A circuit has exactly one handle, on its first
row. That is pre-existing, not part of this change — do not report "no handle on rows 2 and 3" as a
failure.

### Setup

Plan preview with the 3-row circuit from Check 1, **plus** at least one solo row below it.

### Steps

1. Press and drag the circuit's handle (the ⠿ icon on row 1, labelled "Drag to reorder"). Move the
   whole circuit below the solo row. Release.
2. Drag it back up.
3. Drag the solo row's own handle to a different position.
4. Repeat step 1 a second time **without leaving the screen**. (A stale remembered modifier would
   most plausibly show up on the second drag, not the first.)
5. Now do the same on the saved-workout editor: Home → *Workouts* → open any workout with a circuit
   (or build one there), and drag its block.

### PASS

Every drag starts on first press, the whole circuit moves as one unit, the drop lands where you
released, and the second drag behaves identically to the first. The rows' drop shadow appears while
dragging.

### FAIL

Any of: the handle no longer starts a drag; the first drag works but a later one does not; the drag
starts from the wrong position (the item jumps); only part of the circuit moves; the editor behaves
differently from plan preview.

### On fail

Revert `55ccc7d7c`. It is one commit touching two call sites
(`PlanPreviewContent.kt`, `SavedWorkoutEditScreen.kt`) — restoring
`dragHandleModifier = Modifier.draggableHandle()` at both undoes it completely. Nothing depends on
it; it was a recomposition optimisation, not a fix.

---

## Check 4 — swipe to reject / swipe to remove, inside a circuit

Backlog item from the unified-row pass — never exercised on a device.

### Setup

The 3-row circuit from Check 1, still on plan preview.

### Steps

1. Swipe the **first** row of the circuit left to reject it. Note which row is now the block head.
2. Swipe the **last** row of the circuit left to reject it.
3. Reduce the circuit to two rows, then reject one of them.
4. Go to Home → *Workouts* → open a saved workout containing a circuit. Swipe a circuit member left
   (the editor's swipe **removes** rather than replaces — full red background with an ✕).

### PASS

- Rejecting the first row: the remaining two stay a circuit, and the drag handle and rounds chip
  move to the new first row.
- Rejecting the last row: the remaining two stay a circuit, chip and handle unchanged.
- Reducing a 2-row circuit to one row: the survivor becomes a **solo** row — rail gone, its chip now
  reads "Sets: N" rather than "Rounds: N".
- Editor: the swiped row is removed and the block re-forms around it under the same rules.

### FAIL

The rail or the link nodes are left pointing at a row that no longer exists; the handle/chip stays
on a removed row; a 1-member "circuit" keeps drawing a rail; the replacement row (when the planner
restocks) lands outside the circuit it should have joined.

---

## Check 5 — steppers with long exercise names at 360dp

Backlog item from the unified-row pass. 360dp is the narrow-phone width the layout has to survive.

### Setup

```bash
adb shell wm size 1080x2400 && adb shell wm density 480
```

That gives exactly 360dp of width. Reset afterwards with:

```bash
adb shell wm size reset && adb shell wm density reset
```

Build a plan containing the longest exercise names in the library — "Barbell Bench Press",
"Dumbbell Romanian Deadlift", anything similar. Home → *Exercises* will show you which names are
longest if you need to pick.

### Steps

1. On plan preview, look at a long-named row: its name, the reps stepper, and the weight stepper.
2. Tap the weight stepper's − and + a few times. Tap the reps stepper's − and +.
3. Put that long-named row inside a circuit (which costs another 36dp to the rail gutter) and look
   again.
4. Repeat on the saved-workout editor, which additionally shows the dimmed "suggests …" note under
   a pinned weight.

### PASS

Names wrap or ellipsize without pushing the steppers off-screen; both steppers stay fully tappable;
the − and + targets do not overlap; nothing is clipped at the right edge; the row inside a circuit
is no worse than the solo one.

### FAIL

A stepper is cut off, a name shoves the stepper out of the row, the ± buttons are too small or
overlapping to hit reliably, or the "suggests …" note wraps into the row below.

---

## Check 6 — the rest screen shows the circuit round on a staged action ⚠️

**New behaviour from 2026-09-20** (`7e6e26114`). The rest screen's staged-action card used to title
itself "Up next: Barbell Row" with no round, even when the commit target was a circuit member. It
should now read "Up next: Barbell Row · Round 2 of 3".

### Setup

Start a workout whose plan contains a 3-row circuit with at least 2 rounds. Tap **Let's Go** and
work into the circuit — do at least one full round, so the "Round N of M" you see is not round 1.

### Steps

For each of these, trigger the action from the **⋮ menu on the active-set screen**, then read the
card on the rest screen that follows:

1. **End exercise** — ⋮ → *End exercise* while on a circuit member.
2. **Swap** — ⋮ → *Swap — don't like it* while on a circuit member.
3. **Adjust weight** — ⋮ → *Adjust weight*, set a new weight, confirm.
4. **Warmup done** — start a fresh workout and finish the warm-up sets of an exercise that is the
   first member of a circuit.

### PASS

1–3: the card reads `Up next: <exercise> · Round N of M`, and N/M match what the active-set screen
then shows when rest ends.

4: the card reads `First set: <exercise> · Round 1 of M`.

If the commit target is a **solo** exercise, there is correctly **no** round suffix — that is the
existing behaviour and is not a failure.

### FAIL

No round suffix on a circuit member; a round suffix on a solo exercise; or the round shown on the
rest card disagrees with the round the active-set screen shows a moment later.

---

## Check 7 — a mid-circuit swap saves each member at its real rounds ⚠️⚠️

**New behaviour from 2026-09-20** (`ae75c659a`), and the one with a real chance of surprising you,
because you chose it as a design change rather than a bug fix. `saveSessionAsWorkout` no longer
equalizes rounds: each member is saved at the rounds it actually got.

Read the caveat before you start: **the editor's rounds chip shows the block maximum**, so an uneven
circuit does *not* look uneven in the editor, and touching that chip re-levels the whole block. The
unevenness is real in storage and in the session; it is just not surfaced. Confirming that gap is
half the point of this check.

### Setup

Start a workout with a **3-round, 2-member circuit** (link two rows, set the rounds chip to 3).

### Steps

1. Tap **Let's Go**. Complete **round 1** of both members.
2. On member A's round-2 set, ⋮ → *Swap — don't like it*. Accept the replacement (call it C).
3. Finish the workout: complete the remaining rounds of B and C, then ⋮ → *Stop workout*.
4. On the summary screen: ⋮ → *Save as workout…*, give it a name, save.
5. **Fast check (adb).** Read what actually got stored. There is no `sqlite3` on the device, and
   Room runs in WAL mode — the main `.db` file is nearly empty, with the recent writes in the
   `-wal` — so pull all three files and query on the Mac:

```bash
cd "$(mktemp -d)" && for f in stochastic_strength.db stochastic_strength.db-wal stochastic_strength.db-shm; do adb exec-out run-as io.github.fowles.stochastic_strength cat "databases/$f" > "$f"; done && sqlite3 stochastic_strength.db "SELECT w.name, e.name, x.position, x.sets, x.circuitId FROM saved_workout_exercise x JOIN saved_workout w ON w.id = x.workoutId JOIN exercises e ON e.id = x.exerciseId ORDER BY w.id DESC, x.position;"
```

6. **Behavioural check.** Home → *Start Workout* → ⋮ → *Load a workout…* → pick the one you just
   saved → **Let's Go**. Count how many sets each member asks for before the workout moves on.

### PASS

- Step 5: three rows share one `circuitId`. **A** (the one you swapped away) shows the rounds it
  actually did — `1` — while **B** shows `3` and **C** shows `2`. The point is that A is **not** 3.
- Step 6: the session asks for exactly those counts — A once, B three times, C twice — and then
  moves past the circuit.
- Open the saved workout in the editor (Home → *Workouts*) and confirm the chip reads
  **"Rounds: 3"** — the block maximum. That is the intended display (decided 2026-09-20), not a
  gap: an uneven circuit advertises its longest member.

### FAIL

Step 5 shows A at 3 rounds (the old equalizing behaviour — the fix did not take), or the session in
step 6 asks for more rounds of A than were stored, or the three rows do **not** share a `circuitId`
(the circuit was lost on save).

### Also confirm, now that the display is decided

The chip shows the block maximum on an uneven circuit — no range. Tap it and pick a number: the
whole block levels to that number, which is the point of setting one. Check that the levelled value
then survives leaving and re-opening the editor. Losing the unevenness here is intended; losing it
*without* a tap would not be.

---

## After you finish

Reset any display override you set in Check 5, and make sure TalkBack is off.

For each PASS: delete the matching entry from the "Open — needs triage" section of `CLAUDE_TODO.md`.

For each FAIL: the three checks covering 2026-09-20 changes each name their own revert above
(Check 3 → `55ccc7d7c`, Check 6 → `7e6e26114`, Check 7 → `ae75c659a`); all three are standalone
commits with nothing depending on them. Check 1's revert is in the task-4 report. Checks 2, 4 and 5
cover older work with no single commit to revert — write up what you saw and hand it back.

Nothing in this pass is pushed yet, so a revert is still a clean rewrite rather than a follow-up
commit.
