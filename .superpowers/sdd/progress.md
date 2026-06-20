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
