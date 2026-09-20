# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

Only open work belongs here. Something decided, accepted or finished is not a todo — it goes in
`CLAUDE.md` if the next reader needs it, and nowhere if they don't.

From the 2026-09-20 whole-project review. Each needs a decision or is bigger than a drive-by.

## UI
- **The editor and plan preview still duplicate the block list** (reorderable state, `items` by
  block, elevation, link-node wiring, swipe background). Extract a `CircuitBlockList` into
  `CircuitChrome.kt`. The editor also omits `swipeOffsetPx`, so its link node stays put while a
  swiped row slides away.
- **Swipe and drag have no accessible alternative**: add `customActions` (Remove / Move up /
  Move down) wired to `CircuitEdits`.
- **The exercise-detail chart re-derives prescription math**: `buildPrescribedPoints` plots
  baseline × coefficient (the old model) and scales sibling dots by seed coefficients. Plot
  `series.merged` / `series.siblingObservations` from the pipeline, as the debug chart does.
