# Progress: asymmetric-fatigue-aware-signal

Plan: docs/superpowers/plans/2026-06-19-asymmetric-fatigue-aware-signal.md
Base (branch start): 568508efcdbe

Task 1: complete (commits 568508e..9a09e9d, review clean)
  Minor (final-review triage): test tolerance 1e-3f looser than needed since Int delegates to Float (DefaultProgressionEngineTest)
Task 2: complete (commits 9a09e9d..08932b3, review clean)
  Minor (final-review triage): set_signal_maps_each_bucket asserts isFailure=false for RIR_0_1 only (not RIR_5_PLUS/RIR_2_4); targetReps taken from first full-weight set only
Task 3: BLOCKED (uncommitted structural edits in working copy; SessionSignalExtractor unchanged)
  Reason: behavioral asserts infeasible as written. Controller is gauge-conserving -> mean-reverts avg(aggOffset)->0,
  so against a STATIC-strength simulated lifter the persistent Option-2 up-push must be balanced by failures.
  Best in-bounds run: lastSetRir=-0.16 (in band), failRate=0.35 (>0.30 ceiling), trainedErr=4.27, jitter=0.92, conv~4, gauge OK.
  Decision needed (see next user turn): validate under growth vs relax failRate threshold.
Task 3: complete (commit 08932b3..7146697, review clean — validated UNDER GROWTH per user decision)
  Metrics: lastSetRir=1.30, failRate=0.175, gauge 0.999/1.011/1.011; default constants, no prod tuning.
  Minor (final-review triage): lastSetRir=1.30 high in [0,1.5] band — optional re-center toward ~0.8 via RESERVE_RIR_0_1 (deferred, deterministic not flaky); redundant achievableReps recompute in sim hot loop (perf nit)
Task 4: complete (commit 7146697..2b638ed, review clean)
ALL TASKS COMPLETE
