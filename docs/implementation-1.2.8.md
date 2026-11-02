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

## Second review follow-up (local until branch CI verifies it)

Restore finally-based wake-lock release and non-foreground completion registration. Replace incoming full-thread materialization with indexed latest-message and aggregate unread queries. Recovery gathers missing thread repairs and syncs each once outside the receipt lock, with a SQL page limit; do not checkpoint the page before its UI repairs finish. Incoming transactions advance the revision before commit, protecting against stale recovery snapshots.

External recovery triggers use KEEP plus a delayed coalesced follow-up, while only a running worker may append a page continuation. Notification publication retries run under an independent unique worker. Persistent scan requests remain authoritative, including across process death; the periodic worker remains a recovery backstop.

Coalesce foreground message/conversation events per frame without removing support for message-only events. During initial history loading, read a separate local snapshot without cancelling history. Thread resume re-reads messages instead of marking an unseen entire thread read. Visible-thread read acknowledgements and actual notification publication share bounded per-thread guards off the UI thread; successful UI synchronization cancels its old pending-sync notification.

Normalize legacy encrypted credentials as well as modern channel JSON before routing comparison. Record encrypted spool write latency, and run cold/warm admission measurements in Android instrumentation. These measurements characterize the CI emulator, not vivo/other phone hardware. If both storage and service handoff fail, record an explicit unconfirmed-handoff error rather than treating a false service result as success.

The two-second correlation boundary remains a conservative evidence policy: identical independent wire messages without distinguishing receiving evidence cannot be proven distinct by changing a timeout. Real delayed dual-broadcast and ambiguous-SIM acceptance remain required; no claim is made that these physical ambiguity cases or vendor process resurrection are eliminated.


## Third review: pure snapshots and confirmed recovery repairs

Routing snapshots decode the stored legacy PushPlus field without migration, deletion or key creation. Android instrumentation covers a legacy-only configuration, an unreadable credential, and re-encryption of the same token. Historical digest upgrades still require the unchanged old digest as evidence.

Recovery preloads bounded page-local Room IDs before taking the receipt lock. Thread repairs use strict Provider reads, fetch older missing SMS IDs explicitly, reject superseded revisions, and verify required messages plus conversation metadata before checkpointing. Provider failure or a skipped revision is a retry, not successful progress. Page continuation remains one continuation per active page; it is not an external per-broadcast append chain.

An old read notification is suppressed without cancelling the shared thread notification. Actual publication and suppression have separate acknowledgements, so suppression does not trigger the published-notification popup. Read acknowledgements cancel the thread notification only after no unread inbox messages remain; initial page refresh no longer cancels before read acknowledgement. A replaceable delayed notification wakeup closes the primary KEEP worker completion window without appending unbounded work.

When spool persistence and service admission both fail, a reserved nonqueued executor attempts Journal persistence while retaining the asynchronous broadcast token until staging finishes. If Journal encryption/storage also fails, or immediate emergency admission is exhausted, there is no confirmed durable copy: this remains an explicitly diagnosed terminal intake failure, not a claimed guarantee under total storage failure.

The third-review local SQL suite contains 61 cases; phone lifecycle, OEM killing and physically indistinguishable dual broadcasts remain device acceptance requirements. A successful older commit's CI is never substituted for the final pushed commit.


## Unified follow-up after 0c7f2d8

Broadcast admission performs spool encryption and recovery registration on a bounded I/O executor, retaining goAsync ownership until durable spool/service/Journal handoff. A reserved immediate executor and service fallback cover admission saturation; complete storage/key/service failure remains explicit, never disguised as persistence.

Both home UIs observe Room invalidation with lifecycle cleanup and cancel superseded local snapshot jobs. Local notifications do not invoke Provider reconciliation. Provider validation remains on existing foreground/manual paths. Contact enrichment is independent KEEP work keyed by message ID, scheduled after the minimal receipt transaction for FULL and degraded paths. It updates only sender display columns and noncustom nongroup conversation title/photo, preserving body/read/SIM and forwarding decisions.

Historical import commits each 500-row page independently, inserts only missing records to avoid replacing live receipt state, checks receipt revision before page/metadata commits, and yields between pages. An interrupted import keeps committed pages and does not mark full import complete. OEM fallback limits cursor consumption but cannot force provider-side query planning.

Regression additions exercise actual production display-only SQL and Room invalidation. The integrated branch workflow runs selected ingress instrumentation on Android 15 and 16 before one release APK build. No OEM-specific behavior is claimed from emulator results. Main and formal Release remain untouched.


## Release-readiness follow-up after 6341d88

Both home UIs now use a shared coalescing refresh runner. Invalidations never cancel an active Room read: they request one subsequent read. A continuous stream therefore still publishes periodically, while pause/clear cancellation stops the owning job and resume can read dirty state again.

Each broadcast receives an independent eight-second framework lease before entering admission queues. Durable admission remains on bounded I/O pools; the main-thread disk fallback is removed. If queued or stalled I/O exceeds the lease, the framework token is released once and an unconfirmed-admission error is reported. The in-memory task may still persist afterwards, but a timeout/service start is never described as a successful durable handoff. Full storage failure or exhausted resources cannot guarantee retention; no cancellation attempts to interrupt Keystore/AtomicFile during commit. Error persistence is best effort on a bounded background executor.

The complete contact enrichment operation, including first Room access, is inside bounded retry handling; cancellation propagates unchanged. The new host harness compiles the production Worker and injects first-read, contact and transaction failures, terminal failures and cancellation at each stage (18 scenarios). Five JVM regressions exercise continuous invalidations, follow-up reads, pause/resume and exactly-once broadcast lease completion. Existing SQL/UI/device regressions remain required before the integrated test APK is accepted.
