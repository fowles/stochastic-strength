# Per–muscle-group baseline adaptation

Source: `domain/LastSetAutoregulationHeuristic.kt`
Design note: `docs/superpowers/specs/2026-06-17-last-set-baseline-controller-design.md`
Applied by: `WorkoutRepository.applySessionProgression`

Each muscle group carries a single "baseline" number that represents roughly how
strong you are for that muscle right now. Every exercise's working weight is
derived from its muscle's baseline. This engine's job is to nudge that baseline up
or down after each session based on how the sets actually felt — it's the
autoregulation loop that keeps the app tracking your real strength instead of a
fixed plan.

It runs once per session, grouping all of that session's sets by the muscle they
trained, and decides a new baseline for each muscle independently.

## Pain trumps everything

If any set for a muscle was flagged as having hurt, the engine ignores all the
normal logic and simply knocks that muscle's baseline down by a fixed fraction.
Safety first; nothing else gets a vote that session.

## Otherwise, it reads the "last hard set" of each exercise

For a given muscle, it looks at each exercise you did and finds that exercise's
*governing* set — essentially the last set you performed at full, un-reduced
weight. It then translates how that set felt into a desired direction and size of
move:

- Lots left in the tank → a big increase.
- A moderate amount left → a moderate increase.
- Almost nothing left → a small increase.
- You fell short of the target reps by more than a hair → a small decrease.
- You fell *just* short (within about a rep of target) → treat it as "right on the
  money," no change.

Then it averages those per-exercise desires across all the exercises for that
muscle to get one overall percentage move, and applies it to the baseline.

## Guards that keep the signal honest

- **Dropped-weight sets don't count.** If you reduced the weight partway through an
  exercise, that exercise gives no upward signal at all — the engine assumes you
  struggled, and lets the separate downward clamp (below) handle it rather than
  rewarding you.
- **Unloadable exercises stay silent.** Bodyweight, banded, or wall-sit type
  movements (coefficient 0) have no real load tie to the baseline, so they
  contribute nothing in either direction.
- **An "easy" set only counts if it was genuinely at baseline weight.** Before
  letting a good-feeling set push the baseline up, it checks that the weight you
  actually lifted was at least what the current baseline would have prescribed.
  This stops imported or backfilled history — logged at light weights — from
  reading as trivially easy and ratcheting the baseline upward in a runaway loop.
  Downward signals get no such gate: failing even at a light weight is still
  informative.

## Sanitizing the move

The averaged percentage is converted into an actual weight change, then floored to
whole equipment increments (2.5 kg / 5 lb). The flooring is **symmetric and always
toward zero**: it truncates the *magnitude* of the move down to a whole number of
increments and then re-applies the sign, so a downward move is shrunk the same way
an upward one is. A computed −3 kg drop becomes −2.5 kg (the smaller decrease), not
−5 kg, just as a +3 kg rise becomes +2.5 kg.

## The reduction clamp is a separate, authoritative downward gate

The toward-zero flooring only softens the heuristic's *own* proposed move. After
it, the **mid-session reduction clamp** runs independently and can pull the
baseline lower regardless: if you had to drop weight during the session, the
baseline cannot end up higher than that drop allows. So a downward outcome can come
from two sources — the heuristic's own (gently floored) move, and this clamp, which
is the final word on how low the baseline may go.

If the net result rounds back to the same number, the baseline is left untouched.

## Output

A proposed new baseline per muscle (with a short note explaining why — `hurt`,
`clamp`, or the averaged percentage and sample count), which the repository then
persists and logs as a `BaselineHistory` row.
