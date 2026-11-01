# SMS Forwarder 1.2.8 implementation checkpoint

Development/test branch: `1.2.8`. Version 1.2.8 / 1218. Do not merge main or publish a stable release.
Baseline main: 0d5f1af2e623187b03ac0e45801dca69cb1efc4d. PR #29 patch 5d8b4fe642e1bdd2be7f91c7ad48ca7fa0aa6f58 is already in the local branch; the PR itself remains unmerged.
Remote initial checkpoint: 0c7d06071d574dc6d7c3e36bb5a4a8f79a293998. The following integrated work is LOCAL, not yet pushed. User requires completion before one integrated push/build.

## Implemented locally, awaiting complete validation

- Structured subscription evidence, Int/Long support, invalid/sentinel rejection, conflict/permission/empty/error distinctions; remove single-active-SIM guesses and undocumented sim_id interpretation. Retain raw evidence; distinguish subscription identity from historical slot mapping.
- Stable receiving aliases and an additive ingress SQLite migration with encrypted PDU staging, provider-attempt/result markers, stage timings, notification claims and frozen execution snapshots. Incoming intake and slow processing use separate locks. Pending payloads are retained until processing completes; encrypted journal is excluded from cloud/device backup.
- Asynchronous broadcast handoff, a separate bounded fallback executor, short wake-lock lifecycle protection, read-only behavior on role loss. Uncertain provider writes are reconciled rather than blindly reinserted.
- PR #29 display fix, immediate local message/conversation transaction before slow contact enrichment, delayed-query revision guard, exact Provider ID UI repair and ambiguity rejection. Code-alert UI and fallback notifications share a durable alert claim; existing auto-reply/queue/billing claims remain intact.
- Recovery step isolation, actual keep-alive startup status and failure reporting, preserve default-enabled and explicit-disabled preferences.
- Persistent scan requests/cursors, ordered date/id pages, short continuation tasks instead of replacement/cumulative retry backoff, first-install floor, shared scan ownership, no historical command replay.
- Network callback lifecycle/debounce, wake existing reconnect loops, reject stale WebSocket callbacks and old Telegram/email handles; keep existing offsets/receipts. Feishu SDK retains ownership of its built-in reconnect.
- Read-only diagnostics show receiving reason/source, actual service state, scan checkpoint and latest event phase timings. Call identity may use a unique matching call/account mapping; never parse an account ID as a subscription number.
- Add host and Android integration tests, SQL journal/migration regressions and test-branch build trigger. Publishing remains restricted to main/master; no stable Release is authorized.

## Verification checkpoint — 2026-11-01

- 50 SQLite regression tests pass (including 11 ingress journal/correlation cases).
- The production UI repair worker passes 20 host scenarios with simulated Provider/WorkManager/Room boundaries.
- Remote runtime/whitelist host suite passes 18 tests; pure identity/recovery/reconnect suite passes 20 tests.
- Receiving identity, encrypted journal/snapshots, recovery cursor and Provider reconciliation compile against real API 37 with explicit Android application boundary stubs. This is an isolated core check, not a whole-application build.
- All edited Kotlin sources parse. A dependency-incomplete whole-source compiler comparison against unchanged main found no new missing local symbols; it is not a successful application compilation.
- Standalone Detekt with the same config/plugins/baseline reports 337 weighted findings versus 342 on pinned main. No file/rule count increased in that comparison. Existing findings remain; Detekt is not green, and the baseline was not regenerated.
- `git diff --check` passes. The full batch is ready for the test-branch build; complete Gradle unit tests, Android-test compilation and signed APK assembly must be verified by that build.

During review, a selective formatting script was found to have split code blocks. It was disabled, affected declarations/blocks were restored, and syntax, core compilation and relevant host regressions were rerun. A follow-up whitespace normalization verifies that the complete token sequence is preserved. The environment had also restarted earlier, losing temporary build dependencies but preserving workspace source. No unsuccessful or interrupted full build is reported as a pass.

## Remaining delivery gates

1. Push the integrated batch to `1.2.8` and follow the branch Actions build. Fix any actual compilation or regression failures. Do not merge main or create a stable tag/Release.
2. Record Gradle/unit/Android-test compile, signing and artifact outcomes. Lint and phone acceptance are not implied by host tests or a successful APK assembly.
3. Android 15/16, vivo/HyperOS, two-SIM receiving, process interruption, OTP display and real channel delivery still require phone acceptance. Do not mark them passed without execution.

## Conservative behavior and rollback

If routing settings changed during an interrupted receipt, retain it as configuration_changed for review rather than route it through new targets. A legacy/app-authored Provider sub_id is not independent proof. Unknown history remains unknown; follow-receive sending does not fall back to a default card. An external timeout can mean delivery status unknown, not permission to resend.

The ingress schema migration is additive; old owners/claims remain. Rollback requires a compatible forward-fix build; blindly downgrading an old SQLiteOpenHelper APK is not guaranteed safe. Existing queue owners, delays, original targets, submission claims and uncertain delivery records must not be cleared.
