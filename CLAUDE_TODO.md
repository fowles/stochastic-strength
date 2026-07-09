# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — cleanup

- **Chart μ-drift parity (phase-2 follow-up, 2026-07-09).** `ExerciseProgressionSeriesBuilder` calls `MuscleStrengthProjector.project(...)` without `muscleLastObs`, so the exercise-detail chart's lines/dots age variance (q) but skip detraining μ-drift, while the planner path applies it. After a long layoff the chart line sits above the weight the planner will actually prescribe. Cosmetic; fix by threading the muscle clock into the series builder (it replays from scratch, so it can compute muscleLastObs) for chart/planner parity. Found by the phase-2 whole-branch review.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

