# Progress: detraining-baseline-reduction

Plan: docs/superpowers/plans/2026-06-20-detraining-baseline-reduction.md
Base (branch start): 1fd458cef043dc06ab44083e4f92d97de79f4a1c

(no tasks complete yet)

Task 1: complete (commits 1fd458ce..ec111eb2, review clean — 7/7)
  Brief had arithmetic bug (week+6d=13d floors to 1, not 0); implementer corrected test to 6d→0, preserving sub-week intent. Reviewer verified.
  Minor (final-review triage): test recomputes week constant instead of referencing DetrainingModel.WEEK_MILLIS; suggestedFraction(negative) handled but untested.

Task 2: complete (commits ec111eb2..80d42dfc, review clean — 2/2 instrumented)
  WARNING: git commit 80d42dfc swept in pre-existing staged index cruft (schema JSONs 9-15, untracked manifest/MigrationTest/WorkoutSessionControllerTest, main/DebugSeeder removal). User chose to continue + reshape in jj. On-disk correct, builds green. Future subagents: stage ONLY their own files (explicit git add paths). New Migration15To16Test.kt is on disk + jj-tracked but git-untracked.

Task 3: complete (commits 80d42dfc..a5b69f8c, review clean — 7/7 instrumented; staging clean, only 2 files)
  Minor (final-review triage): test uses .first{} (throws NoSuchElementException vs AssertionError on absence); applyDetrainingReduction not withContext(IO) — consistent with rest of class, not a regression.

Task 4: complete (commits a5b69f8c..d407de9d, review clean — 4/4 unit + assembleDebug; staging clean, 3 files incl new WorkoutPlanTest)
  No findings. 4 buildPlanner sites updated (brief said ~3; adjustExerciseWeight was the 4th). merge order detrainOverrides+strengthOverrides = manual wins.

Task 5: complete (commits d407de9d..bdfedcb1, review clean — 22/22 instrumented; staging clean 4 files)
  Side effect: WorkoutSessionControllerTest.kt re-tracked in git (was untracked by Task 2 cruft) — good.
  Minor (final-review triage): applyDetraining test uses fixed delay(200) (fragile vs awaitState helper); fresh-DB setup duplicated across 3 tests; VM delegation blank-line nit.

Task 6: complete (commits bdfedcb1..84c831d1, review clean — assembleDebug green; staging clean 2 files incl new DetrainingDialog.kt)
  No findings. Manual click-through deferred to human. Live StrengthGrid reactive via remembered fraction state.

Task 7: complete (regression pass) — full JVM green; full instrumented 70/70 green; lint green.
  Regression found+fixed: comprehensive MigrationTest had 4 hand-rolled addMigrations lists ending at MIGRATION_14_15; opening through Room at v16 needed MIGRATION_15_16. Fixed in commit abb29011 (MigrationTest 70/70). PLAN GAP: Task 2 should have updated MigrationTest's forward lists.
ALL IMPLEMENTATION TASKS COMPLETE (HEAD abb29011)

Final review (opus): READY TO MERGE. No Critical/Important.
  Minor (new, do-not-block): onLocationRefreshed (WorkoutSessionController.kt ~436) reconstructs PlanPreview via fresh constructor, dropping repMin/repMax (→5/10) and clearing `detraining` prompt on resume. Pre-existing repMin/repMax bug that the new detraining field now also rides; detrainOverrides on the plan itself are preserved so reduction correctness is unaffected. Fix: use preview.copy(plan=, locationName=). Optional.
  Carried minors (all do-not-block): test recomputes WEEK_MILLIS; .first{} vs firstOrNull; applyDetraining test delay(200) vs awaitState + dup setup + VM blank-line nit.

Post-review fix: onLocationRefreshed now uses preview.copy (commit 3fe33e83) — preserves repMin/repMax + detraining prompt across location refresh. Controller tests 22/22.
FEATURE COMPLETE & MERGE-READY (HEAD 3fe33e83).
