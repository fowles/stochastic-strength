# Strava workout-title word-set expansion

**Date:** 2026-07-18
**Status:** Approved

## Goal

Expand the three word lists that generate Strava workout titles
(`ADJECTIVES × STRENGTHS × WORKOUT_NOUNS` in
`domain/strava/StravaExporter.kt`) from 35/32/32 entries to 50/50/50,
raising the combination count from ~36k to 125k.

## Direction (user-chosen)

- More of the same established flavors: randomness-themed adjectives,
  archaic/funny strength synonyms, epic-journey nouns.
- New sub-genres: statistics/thermodynamics jokes, mythology (fits the
  adjective slot), bureaucratic deadpan (fits the noun slot), physics
  units (fits the strength slot).
- Do **not** grow the possessive-scientist adjectives ("Yoked Galileo's")
  — a recent commit deliberately trimmed those to 5; the ratio shrinks
  further as the list grows, which is intended.

## Additions

Adjectives (+15): Bayesian, Markovian, Ergodic, Annealed, Heuristic,
Volatile, Scattershot, Slapdash, Peripatetic, Rambunctious, Untrammeled,
Feral, Anarchic, Herculean, Promethean

Strengths (+18): Wallop, Beef, Bulk, Pep, Zeal, Chutzpah, Horsepower,
Wattage, Voltage, Thunder, Fury, Valor, Dynamism, Momentum, Leverage,
Traction, Stoutness, Gusto

Nouns (+18): Rite, Vigil, Jubilee, Jamboree, Hootenanny, Rumpus, Shindig,
Kerfuffle, Melee, Skirmish, Siege, Conclave, Audit, Inquest, Referendum,
Filibuster, Safari, Walkabout

Rejected during review: Helter-Skelter, Pell-Mell (weak), Welly,
Elbow-Grease (out).

## Constraints

- Every entry must read well in the frame "〈Adj〉 〈Strength〉 〈Noun〉"
  (e.g. "Bayesian Wallop Referendum", "Feral Horsepower Hootenanny").
- No duplicates within or across the existing lists.
- Lists stay private `companion object` constants; no tests reference
  them and none are added — this is pure content.

## Implementation

Single edit to `StravaExporter.kt` appending the entries to the three
lists. Verify with `./gradlew :app:testDebugUnitTest` (regression only).
