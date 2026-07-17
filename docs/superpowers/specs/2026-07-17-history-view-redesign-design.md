# History View Redesign

**Date:** 2026-07-17
**Status:** Approved (design), pending implementation plan

## Goal

Replace the current History screen — a single `LazyColumn` holding a per-muscle
strength grid + a flat session list — with three purposeful regions:

1. A **Highlight card**: a randomly chosen, motivational strength stat with
   swole-bro humor, leaning into the "stochastic" theme.
2. A **month calendar** that marks workout days and lets the user page between
   months.
3. The existing **session list**, scrollable, grouped by month.

The current muscle matrix (`StrengthGrid`, "Estimated One Rep Max") is removed
entirely; it lives nowhere else after this change.

## Non-goals

- No new persisted Room state. Everything derives from existing data.
- No changes to progression math, beliefs, or the estimator.
- No streak/consistency features (explicitly rejected).
- Export/Import backup, delete-session flow, and session/exercise tap
  navigation are unchanged.

## Screen structure

`HistoryScreen` keeps its `Scaffold` + `BackTopAppBar` (title "History",
overflow menu = Export/Import JSON). The body becomes three stacked regions
instead of one scrolling column:

- **Top (pinned):** Highlight card.
- **Middle (pinned):** one-month calendar grid (one month tall; pages
  left/right).
- **Bottom (scrolls):** the session list in a `LazyColumn`, with month-divider
  rows. Tapping a workout day in the calendar scrolls this list to that day.

Only the bottom region scrolls vertically. The calendar changes months by
swipe / ‹ › arrows, staying one month tall so it never scrolls out of view
(keeps tap-to-jump useful).

## Component 1 — Highlight card (`HistoryHighlight`)

A **pure domain component** in `domain/` (no Android deps) that takes the
already-loaded history data and produces one `HighlightFact` (a display string).
Unit-testable in isolation.

### Candidate facts

Two families, both **positive gains only**:

- **Per lift (absolute weight).** For each exercise the user has trained within
  the window, compute the estimated-1RM delta from the exercise's merged
  progression series (reuse `ExerciseProgressionSeriesBuilder` /
  `ExerciseProgressionSeries.merged`, points `(timestampMs, value)`). Phrase in
  absolute weight in the user's unit:
  *"Your bench press is up 20 lb this month."*
- **Per muscle (percent).** For each `MuscleGroup`, compute the baseline delta
  from `baseline_history` (`repository.getBaselineEvents(muscle)` →
  `BaselineHistory(timestamp, newBaseline)`). Phrase as percent:
  *"Your chest is up 15% this quarter."*

### Windows

Each candidate is evaluated over one of two windows, chosen per candidate:

- **~30 days** → "this month"
- **~90 days** → "this quarter"

Delta = latest value in-window minus the value at/just-before the window start
(nearest earlier sample). A candidate qualifies only if the gain is **positive
and above a meaningful threshold** (large enough to clear sub-grid / rounding
noise — a small fixed percent for muscles, ~one grid increment for lifts).

### Quips

A single pool of self-standing swole-bro non-sequiturs in "Yoked Galileo /
Diesel Tycho Brahe" voice. Each quip must read fine **both** appended to a stat
and standing alone. The rest of the pool is authored during implementation in
this tone; **"The bar does not care about your feelings. Add weight to it
anyway." is a committed quip that must be in the list.** Tone examples:

- "Way to nail the vanity lifts!" (muscle-keyed — biceps/abs/etc.)
- "The bar does not care about your feelings. Add weight to it anyway." (keep)
- "Somewhere, Yoked Galileo is proud."

Some quips are keyed to a specific muscle group and only appear when that
muscle is the subject; the rest are generic.

### Selection

1. Build the list of qualifying candidates.
2. **If any qualify:** usually pick one at random and *sometimes* append a quip
   (generic, or muscle-keyed if it matches the subject) — but **sometimes skip
   the stat entirely and show just a quip**, to keep the card random and
   playful rather than a predictable stat readout.
3. **If none qualify (empty case):** pick a **quip alone** from the same pool —
   there is no separate fallback pool.

The "stat vs. quip-only" and "append a quip vs. not" choices are driven by the
same seeded `Random` (§Selection > Randomness), so they stay deterministic
under test and stable-per-day in release.

**Randomness source:** a seed derived from **today's `LocalDate`** in release
builds (stable within a day, new pick each day). In `BuildConfig.DEBUG`, re-roll
on every screen open (a fresh random seed) so the developer can exercise the
whole pool. The seed is injected into `HistoryHighlight` (e.g. a `Random` or
seed value passed by the ViewModel) so the pure function stays deterministic
under test.

## Component 2 — Month calendar (`MonthCalendar`)

A composable rendering a standard weekday-aligned grid for one `YearMonth`:

- Days with **≥1 completed session** get a **large filled circle** behind the
  date number (theme accent). Non-workout days render the plain number.
- Header shows the month/year with ‹ › arrows; horizontal **swipe** also pages
  months. Paging changes the shown `YearMonth` only — the widget stays one
  month tall.
- Tapping a **workout day** emits that `LocalDate` upward (→ scroll the list).
  Tapping an empty day does nothing.

Inputs: `shownMonth: YearMonth`, `workoutDays: Set<LocalDate>`, callbacks
`onMonthChange`, `onDayTap`.

## Component 3 — Session list (month-grouped)

Same `SessionRow` rendering as today. Additions:

- Sessions are grouped by calendar month (derived in the composable from
  `session.startTime`, newest first as today).
- Each month group is preceded by a lightweight **month-divider row**
  (e.g. "July 2026") so months are scannable while scrolling.
- The list uses a `LazyListState`. Calendar day-tap resolves the target date to
  the list index of that day's (first) session and animates a scroll to it.

## ViewModel & data changes

`HistoryState` changes:

- **Remove:** `muscleStrengths: List<MuscleGroupStrength>`,
  `referenceExerciseIds: Map<MuscleGroup, Long>`.
- **Add:** `highlight: HighlightFact` (the resolved display string/model),
  `workoutDays: Set<LocalDate>` (dates with ≥1 completed session, from the
  loaded sessions).
- **Keep:** `sessions: List<SessionListItem>`, `weightUnit`, `loading`,
  `pendingDeleteSessionId`, `message`.

`HistoryViewModel.reloadInternal()`:

- Stop calling `getMuscleGroupStrengths()` / building `referenceExerciseIds`.
- Assemble `HistoryHighlight` inputs: per-muscle baseline series via
  `getBaselineEvents(muscle)` for each `MuscleGroup`; per-lift merged
  progression series via the existing per-exercise builder, **only for
  exercises with a session in the widest window** (bounds the cost — the
  history screen is not a hot path, but avoid building progression for every
  exercise in the library).
- Compute `workoutDays` from `sessions` (map `startTime` → `LocalDate` in the
  device zone).
- Pass the date-derived seed (release) or a fresh random (debug) into
  `HistoryHighlight`.

`weightUnit` continues to drive weight formatting in the highlight card
(`WeightFormatter`).

## Testing

- **`HistoryHighlight` (pure, primary coverage):**
  - positive-only filtering (flat/negative candidates excluded),
  - window boundary + nearest-earlier-sample delta math,
  - threshold gating (sub-threshold gains excluded),
  - daily-seed determinism (same date + data → same pick),
  - empty case returns a quip from the pool (no crash, no separate pool),
  - muscle-keyed quips only attach to their muscle,
  - quip-only outcome is reachable even when a qualifying stat exists (the
    playful "skip the stat" branch).
- **Mapping helpers:** `workoutDays` derivation from sessions; month-grouping of
  the session list; calendar day → list-index resolution.
- Existing history export/import and delete tests continue to pass.

## Risks / open implementation details

- **Per-lift series cost:** building progression per exercise is the heaviest
  step; bounded by filtering to exercises active in the window. Revisit if slow.
- **Exact thresholds, window lengths, and quip wording** are tuning details
  finalized in implementation; the design fixes their *shape*, not final values.
- **Scroll-to-day index resolution** must handle a tapped day whose session was
  filtered/deleted between load and tap (no-op gracefully).
