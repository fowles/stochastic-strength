# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

Only open work belongs here. Something decided, accepted or finished is not a todo — it goes in
`CLAUDE.md` if the next reader needs it, and nowhere if they don't.

## Needs a device to settle

- **`WorkoutNotificationService`'s refused-`startForeground` path is untested.** When the dataSync
  quota is exhausted the system refuses the promotion and the service calls `stopSelf()`; the same
  goes for `onTimeout`. Neither path can be exercised from a unit or instrumented test, so exhaust
  the quota on a device and confirm the service dies quietly instead of raising
  `ForegroundServiceDidNotStartInTimeException`.
