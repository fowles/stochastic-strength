# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

* `LastSetAutoregulationHeuristic` current rounds to zero.  Should round to
  negative infinity.

- Remove the now-dead `exerciseReductions`/`reductionsBySession` params from
  `WorkoutRepository.finishSession` / `replayDerivedState` and their UI callers
  (mid-set drops now flow through the set log as negative innovations; the reduction
  clamp was dropped with the PI controller).

- `ReplaySnapshot.progressionBaselines` and `ReplaySnapshot.baselineHistoryByMuscle` are
  now write-only (no controller reads them). Remove in a later cleanup pass.
