# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

- The progression pool / `seedCoefficients` come from `getActive()`, so an exercise later marked
  inactive drops out of the muscle pool and its coefficient stops moving / stops contributing to the
  level. Matches the prior stack's active-only behavior; documented in case it ever needs revisiting.

- **Override-only sessions** (manual override, no logged sets) don't eagerly re-project display
  history for that muscle. The planner stays correct via `putExerciseEstimates`; only the History
  strength grid lags until the next session writes a projection. Accepted — revisit only if the
  display lag is ever user-visible enough to matter.
