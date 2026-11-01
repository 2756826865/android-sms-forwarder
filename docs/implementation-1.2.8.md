# SMS Forwarder 1.2.8 implementation and verification

Development/test branch: `1.2.8`, version 1.2.8 / 1218. Do not merge main or publish a stable Release/tag.
Pinned main: `0d5f1af2e623187b03ac0e45801dca69cb1efc4d`.
The first integrated batch is commit `0a5054672f266023a70e5fb1c374d8bafedf9680`; its branch build succeeded:
https://github.com/2756826865/android-sms-forwarder/actions/runs/41284649365
PR #29 is included in the test branch but remains unmerged.

## Integrated implementation

- Receiving identity uses structured subscription/slot evidence and fresh permission-aware reads. No default-send-SIM or single-active-card guess. Raw identity is retained, while historical slots remain unknown without evidence.
- An encrypted, additive ingress journal owns broadcast aliases, provider attempts/results, local phases, original routing digests and frozen per-execution SIM snapshots. Existing forwarding submission owners, delays, retries, auto-reply limits and billing guards remain intact.
- Basic local message/conversation persistence precedes contact enrichment. Provider-ID UI repair and conversation revision guards prevent stale reads from overwriting fresh local state.
- Recovery uses isolated registration steps, persisted requests and date/ID checkpoints. It reconciles original queue tasks and does not replay historical remote commands.
- Keep-alive diagnostics distinguish user configuration from actual foreground startup. Network changes wake existing reconnect loops; stale callbacks/handles cannot replace current connection state.

## Follow-up repair of seven review findings

The follow-up is LOCAL until the next integrated push/build succeeds.

1. **Durable admission before executor admission.** Persist an encrypted AtomicFile envelope in `noBackupFilesDir`, then release the broadcast token before slow parsing/SQLite/service handoff. Queue rejection leaves the envelope for recovery. A spool UUID is transactionally mapped to its journal owner; remove a file only after journal commit. The synchronous admission boundary is intentionally limited to the raw PDU envelope (128 KiB maximum), not contacts or SQLite. Physical storage/Keystore unavailability is recorded and also requests a service handoff; no implementation can promise persistence when storage itself is unavailable.
2. **Scoped correlation.** A normal fingerprint alias also requires identical raw PDU hash and a two-second receipt window. A distinct event gets a new UUID; rotating a recent alias does not replace the old event owner. Spool replay uses its exact durable UUID independently of time.
3. **Actionable review.** Uncertain Provider writes are checked read-only for one exact existing row. Zero/ambiguous matches never authorize insertion. Fair review ordering prevents one unresolvable row from hiding later ones. Diagnostics show review items and an explicit per-record dismissal, which never deletes Provider SMS or forwarding queue owners. Routing changes remain paused; no automatic retargeting.
4. **Recovery budget.** Durable replay is exempt from the broadcast-only eight-second budget. A slow replay can finish the original forwarding phase instead of repeatedly deferring behind its own Provider ownership guard.
5. **Notification outbox.** Persist an encrypted alert envelope before slow contact enrichment or ingress completion. Mark publication only after the actual NotificationManager call, including the asynchronous shortcut path. A crash retries the stable notification ID; read/superseded events are suppressed. Failed publication retains the payload, uses delayed retry, and is visible in review. In-process publication leases expire so an abandoned callback cannot block recovery forever.
6. **Semantic routing digest.** Only actual destination/rendering/rule keys participate. Keep-alive, root-service and remote polling state do not. Modern channel credentials are compared after decryption and JSON normalization, so encryption IV changes are not routing changes. A v5 digest is upgraded only when its old value can still be verified unchanged; otherwise it stays in review.
7. **Conservative retention.** After 30 days, expire completed, unreferenced auxiliary data and unused execution snapshots. Protect unfinished alerts/events, every retained retry snapshot, pending submissions and WorkManager references. Preserve compact Provider/event ownership tombstones and original ingress owners. Hourly paged maintenance persists its cursor to avoid starvation behind protected history.

The ingress database advances from schema 5 to 6 by adding notification storage and execution-snapshot timestamps. Existing identities, owners and frozen contents are preserved.

## Validation evidence and delivery gates

- 59 SQL regressions pass, including 20 ingress correlation/outbox/migration/review/retention cases.
- 20 host scenarios exercise the production read-only UI repair worker with simulated Android/WorkManager/Room boundaries.
- New reliability tests exercise a saturated real executor, admission ordering, processing/storage failure, replay budgets, routing-key selection and retained snapshot references. These run in the normal Gradle unit suite as well.
- Receiving/journal/review/outbox/retention code compiles against real API 37 with explicit application-boundary stubs. This is an isolated core check, not whole-app validation.
- The test-branch workflow compiles Android tests and now runs selected isolated database/identity/scan tests on an Android 15 emulator before signing the APK. Reports are uploaded even if instrumentation fails. The emulator action is pinned to the inspected v2 commit.
- Complete branch Gradle/build/signature/emulator results must be recorded after the integrated push. No local/host result is described as a passed phone test. Android 15/16 vendor devices, dual SIM, lock-screen receipt and real delivery still need phone acceptance.
- Pinned main has 342 existing weighted standalone Detekt findings. The first batch has 337; final follow-up comparison must not introduce new file/rule counts or regenerate the baseline to hide issues. Lint is a separate, unverified gate.

During the first batch a selective formatting script split code blocks; it was disabled, affected source restored, and compilation/regressions rerun. Subsequent changes use complete file/function edits and parse checks. The source is backed by the test branch; temporary validation caches are excluded from Git.

## Compatibility and rollback

Never clear forwarding submission claims, unresolved paid-send records, original targets or retry owners. An external timeout is an unknown delivery result, not permission to resend. Unknown historical SIM slots remain unknown. Follow-receive sending does not fall back to a default SIM. Schema migrations preserve old owners; downgrading to an older SQLiteOpenHelper APK is not a safe rollback. Use a compatible forward-fix build.
