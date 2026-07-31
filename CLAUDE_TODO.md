# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

## Open — needs triage

- Pre-existing (not caused by task-2 detrain-by-inference work): `./gradlew :app:testDebugUnitTest`
  fails 3 backtest tests (`BeliefFitTest`, `BeliefPolicyBacktestTest`, `BeliefScoreTest`) with
  `BackupFormatException: This export is from DB v19 but the app is on v18` — `AppDatabase.kt`
  pins `version = 18` but the checked-in backtest history export
  (`src/test/resources/backtest/history.json`) is stamped DB v19. Someone needs to either bump
  `AppDatabase.VERSION` (with a proper migration) or re-export the backtest fixture at v18.
