# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

- **Skippability is checked by reading the Compose compiler report, not by a runtime test**
  (2026-09-20). `app/build/compose_reports/app-composables.txt` lists every composable's
  parameters; one with no `stable` prefix is what stops it skipping. That is a compile-time
  property, so a recomposition-counting harness would cost real work to answer a question the
  build already answers — and when the question was first asked, the answer was "it doesn't skip".
  `ExercisePreviewRow` and `EntryRow` now report every parameter `stable`.

- **Uneven circuits show their largest round count** (decided 2026-09-20). `saveSessionAsWorkout`
  keeps each member at the rounds it actually got, so a saved circuit can be uneven (a swapped-away
  member at 1, its replacement at 2, an untouched member at 3). The rounds chip shows the block
  maximum — no range, no per-member annotation: a circuit advertises its longest member. Setting the
  chip (`CircuitEdits.setRounds`) levels the whole block, which is the coherent reading of editing
  the one number on display. Verified on a device 2026-09-20 (Check 7 of
  `docs/verification/2026-09-20-device-check.md`).

## Open — needs triage

_(empty — the 2026-09-20 device pass closed the last of these; see
`docs/verification/2026-09-20-device-check.md`.)_
