# Adaptation engines

The app personalizes itself over time through four estimation/heuristic engines.
Each runs on your logged history and feeds the others; together they keep the
prescribed weights and time estimates tracking your real strength and pace
instead of a fixed plan.

| # | Engine | What it adapts | Source |
|---|--------|----------------|--------|
| 1 | [Time estimation](01-time-estimation.md) | How long each planned exercise (and the session) will take | `domain/ExercisePacingEstimator.kt`, `domain/DurationCalculator.kt` |
| 2 | [Baseline adaptation](02-baseline-adaptation.md) | The per–muscle-group baseline weight, after every session | `domain/LastSetAutoregulationHeuristic.kt` |
| 3 | [Coefficient estimation](03-coefficient-estimation.md) | The per-exercise multiplier that scales a muscle baseline to a specific lift | `domain/EstCoefConsensusHeuristic.kt` |
| 4 | [Coefficient renormalization](04-coefficient-renormalization.md) | Re-attributes shared coefficient drift back into the baseline | `domain/SeedNormalizer.kt` |

## How they relate

- **Baseline × coefficient** is the heart of the system: a muscle's baseline says
  "this muscle is worth roughly this much," and an exercise's coefficient says
  "but for this particular lift you're stronger/weaker by some factor." Their
  product, scaled to the session's rep target, is the weight you're prescribed.
- **At session end** the engines run in sequence: baseline adaptation (#2) updates
  the muscle baselines, coefficient estimation (#3) updates per-exercise
  coefficients, and renormalization (#4) sweeps any muscle-wide coefficient drift
  back into the baseline.
- **They share signals.** All three of the session-end engines read the same
  per-set feedback (reps-in-reserve, too-hard with/without a rep count, pain), but
  interpret it for different purposes.
- **Time estimation (#1)** is independent of the strength loop — it learns pace
  from the timestamps between sets, not from feedback.
