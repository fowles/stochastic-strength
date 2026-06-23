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

- Note (intended, not a bug): the progression pool / `seedCoefficients` come from
  `getActive()`, so an exercise later marked inactive drops out of the muscle pool and its
  coefficient stops moving / stops contributing to the gauge. Matches the prior stack's
  active-only behavior; documented here in case it ever needs revisiting.

- `WorkoutSessionController.deriveNotificationState` (Resting arm) derives the
  "Next: Set N · <name>" foreground-notification label from `completedSetIndex`/
  `exerciseIndex` even for staged-action rests (stop-workout / end-exercise / swap /
  adjust-weight), so the notification can momentarily read e.g. "Next: Set 2" during a
  stop-workout rest. Cosmetic only (no crash). Make the label staged-aware later.

## Per-exercise-estimate progression — deferred follow-ups (final-review FOLLOW-UPs, none blocking)

- **Goal-3 read-path boundary (test gap):** `failure_drops_next_prescription_below_failed_weight`
  only covers a single-exercise muscle + a clear 30% failure. The accepted-by-design soft edge is a
  *marginal* (~1-rep) failure on a multi-sibling muscle whose post-fold drop can round back to the
  same 2.5 kg / 5 lb grid weight (confident non-failed siblings pull it up via `MuscleStrengthProjector`
  shrink). Add a multi-sibling marginal-failure assert to pin that boundary.
- **`applyManualBaselineOverrides` is now a misnomer** — it writes per-exercise `ExerciseStrengthOverride`
  rows, not per-muscle baselines. Rename (it + `applyDetrainingReduction` callers) for clarity.
- **`baseline_history` has no epsilon-dedupe** in `writeLevelUpdate` (coefficient history does). Float-noise
  level rows are possible; intended for the chart but consider deduping like coefficients.
- **Override-only sessions** (manual override, no logged sets) don't eagerly re-project display history for
  that muscle (planner stays correct via `putExerciseEstimates`).
- **`seedInitialWeights` loop is untransacted** — self-healing via per-exercise `deleteInitialFor` +
  idempotent re-run, but wrap in `withTransaction` for parity with `ExerciseStrengthOverrideBackfill`.
- **Stale doc/comment cleanup:** `FatigueNoDownwardBiasReplayTest.kt:31` KDoc still references the deleted
  `RollingConservingProgressionController`; `WorkoutPlannerTest` has stale "strengthOverrides" section
  comments (~lines 392, 538, 349); `recomputeExercise`'s coeff lookup is now only a loaded/zero guard
  (misleading variable name).
- **Test hardening (Minor):** no `MuscleStrengthProjector` multi-exercise `fallbackLevel` test; no
  `ExerciseSeedExpansion` null-coef exclusion test; `migrate16To17`'s hand-built v16 DDL omits
  `baseline_history`/`coefficient_history` (raw-SQL, skips Room hash validation; backstopped by the longer
  chain migration tests); unused `day` field in `ExerciseEstimateUpdaterTest`.
