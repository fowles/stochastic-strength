# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

## Open — needs triage

- `WorkoutRepository.saveSessionAsWorkout`: after a swap inside a circuit, both the abandoned
  original and its replacement are saved as members, each at full rounds (`equalizeRounds`). That
  follows the spec ("rounds = max over members") but is probably not what the user wants.
- `WorkoutSessionController.onLocationRefreshed` sets state after the `withRowFlags` suspend, so a
  plan edit made during that suspend is overwritten. Pre-existing pattern, but with per-row sets and
  circuits more kinds of edit can now be lost.
- Rest screen "Next up" card omits the round for a circuit member (the notification includes it),
  and after a too-hard weight reduction inside a circuit the card says "Reduced weight: A" although
  the next set is B's.
