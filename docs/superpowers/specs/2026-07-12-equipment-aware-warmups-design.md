# Equipment-Aware Warmup Ramp

## Problem

`WorkoutPlanner.computeWarmupSets` builds every warmup ramp from a barbell
model: a 20 kg / 45 lb "bar" is the anchor and step size, and the ramp follows
a barbell plates-and-quarters sequence (45 / 95 / 135 lb…). The `exercise`
parameter is consulted only for the floor-deadlift special case; equipment type
is otherwise ignored.

For non-barbell lifts this is wrong. A single dumbbell starts at 5 lb and climbs
in small steps; machines and cables have no bar and step by pin-stack
increments. Concretely, a 50 lb dumbbell row produces a 45 lb "warmup" (the bar
anchor is 90% of the working weight), which collapses the entire ramp to one
useless near-working set.

## Design

Branch `computeWarmupSets` on `exercise.equipment`:

- **`BARBELL`** — and `exercise == null`, preserving backward compatibility with
  existing callers and tests — keep the current plates-and-quarters logic
  **unchanged**, including the floor-deadlift special case.
- **All other equipment** (`DUMBBELL`, `MACHINE`, `CABLE_MACHINE`,
  `KETTLEBELL`, `BAND`, `BODYWEIGHT`) — use the new percentage ramp below.

### Percentage ramp

Built by stepping **down** from the working weight `W`, so spacing near the top
stays smooth:

- `step = max(minJump, 0.20 × W)`, where `minJump` = 20 lb in LBS mode, 10 kg in
  KG mode.
- Start at `W − step` and keep subtracting `step`, collecting each stop while
  `stop ≥ 0.40 × W` (the floor).
- Round each stop to the standard 5 lb / 2.5 kg grid (`WeightFormatter.round`,
  **not** the coarse `roundForWarmup` grid — so real dumbbell weights like
  30 / 40 lb are usable).
- Drop duplicates and anything `≥ W`.
- Reverse to ascending order for display.
- **No feeler single** — the down-built ramp already ends close to the working
  weight.

Reps scale with proximity to the working weight (the barbell rule minus the
feeler branch):

- `w < 0.50 × W` → 5 reps
- `w < 0.70 × W` → 3 reps
- otherwise → 2 reps

The 20 lb / 10 kg min-jump plus the 40% floor mean light lifts get 0–1 warmups
and mid lifts get ~3. Because `step` grows with `W`, heavy machine lifts stay
bounded (~3 stops) without needing the barbell "thinning" logic.

### Worked examples (LBS)

| Working weight | Ramp |
| --- | --- |
| 50 lb dumbbell row | 30×3 |
| 100 lb | 40×5, 60×3, 80×2 |
| 150 lb | 60×5, 90×3, 120×2 |
| 40 lb | 20×3 |
| ≤ 30 lb | (none) |

## Testing

- Barbell behavior is untouched, so every existing `computeWarmupSets` test stays
  green (those call the function without an `exercise`, routing to the barbell
  path).
- Add tests for the non-barbell ramp: the worked examples above, the empty-ramp
  case for very light lifts, KG-mode min-jump, and that a dumbbell exercise no
  longer emits the barbell plates-and-quarters sequence.

## Out of scope

- No change to barbell warmups, the feeler logic, or floor-deadlift handling.
- No per-equipment increment table (5 lb / 2.5 kg rounding is a good enough
  approximation for dumbbells, machines, and cables alike).
