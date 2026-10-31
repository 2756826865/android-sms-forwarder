# SMS Forwarder 1.2.8 implementation checkpoint

Version: 1.2.8 / 1218. Development and test delivery branch: `1.2.8` (supersedes the earlier `1.2.7` destination). Do not merge main or publish a stable release.

Baseline: 0d5f1af2e623187b03ac0e45801dca69cb1efc4d.
PR #29 patch: 5d8b4fe642e1bdd2be7f91c7ad48ca7fa0aa6f58, cherry-picked locally; PR remains unmerged.
Development branch: 1.2.8. Existing dirty workspaces are preserved.

## Completed in this checkpoint

- Integrated PR #29 display patch without merging main.
- Set version 1.2.8 / 1218.
- Added pure receiving-evidence policy and eight JUnit cases. Not wired into ingress yet: changing subscription resolution before durable identity reconciliation can duplicate processing.
- Verified 35 existing SQL tests across conversations, forwarding history, direct-send guards, queue repair and forwarding integration.
- Pure receiving policy compiled with Kotlin 2.4.10 on JVM 17; all eight JUnit cases passed.
- Existing UI worker host harness passed 17 simulated scenarios.
- git diff --check passed.

## Verification limits

Android Gradle unit-test command could not download Gradle 9.7.0 because the network was unreachable. Android SDK was not found. Pure JVM JUnit cases and UI host scenarios passed, but full Android Gradle tests, APK builds and phone tests have NOT run.

## Remaining implementation gates

1. Structured Android subscription reader and diagnostics; snapshot schema compatibility; durable identity aliases before activating the new resolver. Preserve old queue IDs and sending claims.
2. Provider provenance, ingress stage ownership, bounded payload staging and asynchronous broadcast fallback. Preserve default-role checks.
3. Complete PR #29 integration with local commit/refresh ordering and existing code-alert/call patches; retain unread and conversation semantics.
4. Isolate recovery steps, report actual foreground-service state, preserve explicitly disabled preferences.
5. Persistent scan requests and checkpoints, keyset pagination, shared periodic/immediate ownership and runner-exit race handling; no history-command replay.
6. Network callbacks and generation ownership in existing runtime manager; keep protocol offsets and existing backoff.
7. Diagnostic redaction, Android 15/16 and vivo/HyperOS device matrix, migrations, rollback compatibility and Detekt/Lint/full build gates.

Changes are now developed on `1.2.8`; only after these gates should a test build be produced. Inspected build-custom-apk: Release publishing is gated to main/master; dispatch on `1.2.8` uploads Actions artifacts without publishing a stable Release. The plain `1.2.8` branch requires explicit dispatch because push triggers only cover main and build/**. No stable tag or main merge is authorized.

## Recovery isolation checkpoint

Independent boot/update recovery steps now isolate ordinary exceptions and report a subsystem name and exception class without payloads. Cancellation and fatal errors propagate. Eleven pure JVM JUnit tests passed (eight identity policy, three recovery isolation). Android Receiver integration, full Gradle checks and phone tests remain unverified.
