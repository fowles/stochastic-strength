# Whimsy Expansion: Rest-Timer Quips + Pantheon Growth

**Date:** 2026-07-18
**Status:** Approved design

## Goal

Extend the app's whimsy (currently: Strava mad-lib titles, Strava description quip,
history/done-screen HighlightCard quips) to two new surfaces:

1. **Rest-timer quips** — an occasional quip on the 90-second Resting screen.
2. **Pantheon expansion** — grow the buff-scientist roster, diversify it, vary the
   epithets, and let pantheon members appear in Strava titles.

No schema change, no backtest impact, no new Room tables.

## 1. Rest-timer quip

- `RestingContent` gains an optional quip line under the countdown.
- **Selection at rest start, fixed for that rest** — no re-roll on recomposition,
  undo, or process churn within the rest.
- **Scarcity:** 4% chance per rest (1-in-25), so an average workout (~15 rests)
  sees a quip a bit more often than every other workout. Many workouts see none —
  the whimsy is itself stochastic, which is on-brand.
- **Muscle eligibility:** muscle-keyed quips are eligible only when the upcoming
  set (the one this rest precedes) works that muscle — the quip talks about what
  you're about to do, not the session at large.
- **No quip on the final rest:** the last rest of the workout never shows a quip;
  the Done screen's HighlightCard immediately follows and would make it feel spammy.
- **Shuffle bag:** when the 4% chance fires, the quip is drawn from a persisted
  no-repeat shuffle bag (below), not an independent random pick.

## 2. Shuffle bag (`QuipBag`)

- Lives in `domain/history/` next to the quip pool.
- A shuffled permutation of quip indices plus a cursor; drawing returns the next
  eligible index (skipping muscle-ineligible ones without consuming them — they
  stay in the bag for a rest that precedes an exercise working their muscle).
- Persisted via SharedPreferences, keyed by pool size: growing the pool later
  invalidates the bag and triggers a reshuffle. No Room schema change.
- On exhaustion: reshuffle and start over.

## 3. Pantheon

### Re-epitheted canon (edit existing quip strings; Diesel was over-represented)

| Old | New |
|---|---|
| Diesel Boltzmann | Burly Boltzmann |
| Diesel Fibonacci | Farm-Strong Fibonacci |
| Diesel Descartes | Dense Descartes |
| Diesel Avogadro | Anabolic Avogadro |
| Diesel da Vinci | Vascular da Vinci |
| Diesel Atlas | Mountainous Atlas |
| Diesel Sisyphus | Relentless Sisyphus |

**Diesel Tycho Brahe keeps his epithet** — he is the flagship.

All other canon epithets are locked as-is: Yoked Galileo, Swole Archimedes,
Jacked Newton, Ripped Copernicus, Buff Kepler, Shredded Darwin, Massive Mendel,
Yoked Euclid, Herculean Heisenberg, Stacked Galois, Brawny Brunel, Swole Lavoisier,
Swole Pythagoras, Ripped Faraday, Buff Pascal, Jacked Ada Lovelace,
Massive Marie Curie, Yoked Tesla, Shredded Schrödinger, Stacked Turing,
Ripped Ramanujan, Swole Sagan, Jacked Gauss, Buff Hypatia.

### New members (introduced via new quips; chosen for diversity of era, culture, gender)

| Member | Epithet | Hook |
|---|---|---|
| Katherine Johnson | Colossal | NASA orbital mechanics |
| al-Khwarizmi | Beefy | father of algebra |
| Chien-Shiung Wu | Chiseled | experimental physics |
| Tu Youyou | Titanic | Nobel laureate, artemisinin |
| George Washington Carver | Girthy | agricultural science |
| Rosalind Franklin | Peak | DNA crystallography |
| Emmy Noether | Unbreakable | conservation laws |
| Mae Jemison | Astro-Jacked | astronaut-physician |
| Ibn al-Haytham | Granite | father of the scientific method |
| Satyendra Bose | Bulletproof | Bose–Einstein statistics |

Epithet inventory rule: each epithet used at most ~3 times across the pantheon;
prefer one-offs and alliteration.

## 4. Quip pool growth

- Expand the shared `HistoryHighlight.QUIPS` list (history highlights, done-screen
  HighlightCard, and the new rest timer all draw from it) with new pantheon-voiced
  entries, targeting **~60–80 generic quips total** so the bag lasts a couple of
  months at under one sighting per workout.
- New-member quips follow the existing voice: gym non-sequiturs, science puns,
  warm not mocking.

## 5. Strava titles

- Append **10 possessive pantheon forms to the existing `ADJECTIVES` list** in
  `StravaExporter` (no separate pool): Yoked Galileo's, Diesel Tycho Brahe's,
  Massive Marie Curie's, Jacked Ada Lovelace's, Ripped Ramanujan's,
  Beefy al-Khwarizmi's, Chiseled Chien-Shiung Wu's, Unbreakable Emmy Noether's,
  Buff Hypatia's, Stacked Turing's.
- With ~35 existing adjectives this yields pantheon titles roughly 1 in 4.5
  exports, e.g. "Chiseled Chien-Shiung Wu's Entropic Gauntlet".

## Testing

- `QuipBag` unit tests: no repeats until exhaustion; reshuffle on exhaustion;
  bag invalidation when the pool grows; muscle-ineligible quips skipped without
  being consumed.
- Rest-quip selection: decided once per rest (stable across recomposition/undo);
  ~4% rate exercised with a seeded Random; final rest of a workout never quips;
  muscle-keyed quips only appear before a set that works that muscle.
- Existing highlight tests and the backtest gate untouched (display strings only).
