# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

Only open work belongs here. Something decided, accepted or finished is not a todo — it goes in
`CLAUDE.md` if the next reader needs it, and nowhere if they don't.

## Needs a device to settle

- **TalkBack reads the saved-workout editor row's exercise name twice.** The row's focus stop
  carries a `contentDescription` and the name is also an inline `Text`, so both are announced.
  Which one to drop (or whether the inline text should be `clearAndSetSemantics`) depends on how
  the row actually sounds — pick the fix while listening to it on a device with TalkBack on.
- **`WorkoutNotificationService`'s refused-`startForeground` path is untested.** When the dataSync
  quota is exhausted the system refuses the promotion and the service calls `stopSelf()`; the same
  goes for `onTimeout`. Neither path can be exercised from a unit or instrumented test, so exhaust
  the quota on a device and confirm the service dies quietly instead of raising
  `ForegroundServiceDidNotStartInTimeException`.
