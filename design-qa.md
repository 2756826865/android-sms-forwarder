# Design QA

- Source visual truth:
  - `design/reference-main-screen.png` (clean target generated from the annotated device screenshot)
  - `/workspace/scratch/935faf66eda7/upload/04-3882.jpg` (original annotated reference)
- Implementation screenshot: unavailable; this is a native Android APK and the workspace has no Android emulator or attached device.
- Reference viewport: 841 × 1872 portrait.
- Target state: inbox main screen, settings screen, forwarding channels screen, new message and batch-send flows.

## Implemented visual contract

- Fixed white inbox background and white system bars.
- Neutral `#F2F2F2` rounded search field; no cyan/blue fill.
- Fully opaque `#111111` primary text, `#666666` secondary text and `#8A8A8A` timestamps.
- Android system sans-serif typography; no Fossify custom-font rendering in the inbox list.
- Unknown senders use one neutral person silhouette; no generated number/letter avatars.
- Green `#19C95A` FAB opens new SMS immediately.
- Top actions are mark-all-read and settings only.
- No promotion tab or duplicate compose popup.
- Settings use grouped white cards on `#F7F7F7`, with batch sending, delay, blocking, recent deletion, full sync, forwarding and about.

## Interaction checks encoded

- Mark-all-read updates provider and local databases, then refreshes the conversation list.
- FAB opens `NewConversationActivity` directly.
- Recent deletion is enabled by default before message deletion can occur.
- Forwarding supports PushPlus, DingTalk webhook, Feishu webhook, WeCom application messages and SMTP-over-SSL email.
- Credentials are encrypted with Android Keystore.

## Blocking verification item

- [P1] Same-device visual comparison is unavailable.
  - Impact: build success cannot prove exact EMUI/HarmonyOS font metrics, status-bar inset behavior, or OEM switch/dialog rendering.
  - Required next evidence: install build 1005 on the Huawei device and capture the inbox, settings, forwarding channel and compose screens at the same viewport.

final result: blocked
