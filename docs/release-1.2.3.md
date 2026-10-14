# SMS Forwarder 1.2.3

Version code: 1203. This release retains the SMS ordering, blocked-message visibility and recent-task settings from 1.2.2.

## Changes

- Call reminders preserve the receiving subscription observed while ringing when the idle broadcast lacks it. Default messages explicitly display the resolved physical SIM slot and custom label, plus the local receiving number when available. `[sim]` is the receiving SIM, `[receiver]` is the local number, and `[number]` remains the caller's number. Unknown subscriptions are displayed as unknown rather than inferred from the default SIM.
- Call reminder text and its simulated test are marked as already rendered so the SMS template does not replace their content. Duplicate ringing broadcasts preserve the initial ringing time.
- Enabling call reminders requests phone-state and call-log permissions and performs an actual read-only call-log query in the background. The page reports missing permissions, readable/empty records, hidden numbers, unavailable providers, denied access and query failures. Failed access prevents that enable attempt; a separate diagnostic button does not enable forwarding or send historical records.
- Notification forwarding no longer classifies `FLAG_LOCAL_ONLY` as an ongoing notification. An empty expanded-text field falls back to ordinary text, inbox lines, messaging-style text or subtext.
- Both interfaces share expanded tutorials for call permissions, SIM identity, notification forwarding and Meizu OTP troubleshooting.

## Validation

- 14 targeted host regression tests cover call-access decisions and notification text extraction, using the Android API jar for compilation. These are not on-device call-log or OEM notification tests.
- Signed release compilation and APK signature verification are required by the GitHub build workflow.

## Device checks still needed

- On a dual-SIM phone, call SIM1 and SIM2 separately and check the slot/label and receiving number, including while the screen is off. Broadcast subscription availability and overlapping calls remain device-dependent.
- On the affected Meizu phone, record model/Flyme/Android versions and check whether the OTP exists in the SMS inbox or only as a notification. Notification forwarding cannot reconstruct OTP content withheld by the system; this is not a claim of universal Meizu compatibility.
- Simulated channel tests prove submission to a channel, not real call broadcasts, caller identification, SIM identity or notification access.
