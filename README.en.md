# SMS Forwarder · Default Android SMS Client & Multi-Channel Forwarding Gateway

<p align="center">
  <img src="graphics/icon.png" width="112" alt="SMS Forwarder Icon" />
</p>

<p align="center">
  A modern, open-source default SMS client and multi-channel forwarding gateway for Android, deeply developed based on Fossify Messages.<br>
  Seamlessly supports switching between <b>「Classic Everyday Mode」</b> and <b>「Modern SMS Gateway Developer Workbench」</b>.
</p>

<p align="center">
  <a href="README.md">简体中文</a>
  ·
  <b>English</b>
  ·
  <a href="https://github.com/2756826865/android-sms-forwarder">GitHub</a>
  ·
  <a href="https://github.com/2756826865/android-sms-forwarder/releases/latest">Download Latest APK</a>
  ·
  <a href="https://github.com/2756826865/android-sms-forwarder/issues">Issues</a>
  ·
  <a href="CUSTOM_BUILD.md">Build Guide</a>
</p>

> This project is an independently maintained GPL-3.0 open-source fork, not an official Fossify release, nor affiliated with Huawei, Xiaomi, PushPlus, or any carrier/messaging platform.

---

## 🔄 Dual-Mode Architecture & Seamless Switching

This project features a dual-view architecture — **「Classic Mode」** and **「Developer Mode (SMS Gateway Workbench)」** — with 100% two-way real-time data synchronization at the database layer:

| Mode | Core Purpose | Typical Use Case | How to Switch |
| :--- | :--- | :--- | :--- |
| **📱 Classic Mode** | Complete default SMS app experience with bubble conversations, dual SIM support, bulk send, and lightweight forwarding. | Primary phone daily messaging, daily backup phone SMS manager. | Top-right menu on Home → Tap **「Switch to Developer Workbench」**. |
| **🖥️ Developer Mode (SMS Gateway)** | Jetpack Compose modern workbench with real-time stream monitor, dashboard analytics, channel hub, template sandbox, and self-healing engine. | Spare phone acting as a dedicated SMS forwarding server, geek monitor, enterprise integration. | Top bar on any developer screen → Tap **「Back to Classic Mode」**. |

---

## 🌟 Key Features

### 1. Modern SMS Gateway 5-Tab Workbench (Compose)
- 💬 **【Message Center】**: Real-time lifecycle streaming monitor for inbound & outbound SMS packets.
- 📊 **【Dashboard】**: Daily delivery success rate, circular charts, Outbox offline transaction queue depth, and SIM throughput metrics. Test sends are automatically recorded to the database.
- 🛠️ **【Rule Studio】**: Advanced template engine supporting 5 preset modes (Compact, Standard, Detailed, Emoji, Custom), 12 dynamic placeholders, and a live verification code extraction sandbox.
- 🔌 **【Channel Hub】**: Unified management for 11 native push channels with real-time health indicator lights and instant testing buttons.
- 🧰 **【Operations & Diagnostics】**: Built-in 500-entry ring buffer memory log viewer with level filtering (INFO/WARN/ERROR), one-click manual self-healing, and diagnostic bundle export.

### 2. 11 Native Push Channels (Matrix)
- 🟢 **WeChat Test Account (微信测试号)**: Direct official WeChat template message push to personal WeChat without enterprise verification.
- ✈️ **Telegram Bot**: Rich Markdown typography, images, ultra-fast push, and built-in custom API reverse proxy host support.
- 🌐 **Custom Generic Webhook**: Supports GET/POST/PUT/PATCH methods, custom headers, and dynamic body placeholders (`{{TITLE}}`, `{{CONTENT}}`, `{{FROM}}`, `{{SMS}}`, `{{TIME}}`). Compatible with message-pusher, ServerChan, PushDeer, and self-hosted backends.
- 🎮 **Discord Webhook**: Instant notification in Discord channels.
- 💬 **WeCom (企业微信应用/机器人)**: Supports Group Robot Webhooks and WeCom Application direct messaging.
- 📌 **DingTalk (钉钉机器人)**: Supports secret signing and custom keyword filters.
- 🕊️ **Feishu (飞书机器人)**: Supports Feishu Webhook and signature verification.
- 🍏 **Bark (iOS)**: Ultra-fast push notifications and group management on iPhone/iPad/Apple Watch.
- 📬 **PushPlus (微信推送)**: 1-on-1 WeChat Official Account push and group subscriptions.
- 📧 **Email SMTP**: SSL/TLS encryption and multi-server email dispatch.
- 🔔 **Gotify**: Self-hosted notification server support.

### 3. Message Template & Smart Variable Interpolation
- **Shared Across Both Modes**: Access via `Settings → Features → Message Template` in Classic Mode or `Rule Studio` in Developer Mode.
- **12 Dynamic Placeholders Supported**:
  - `{{FROM}}` Sender Number, `{{SMS}}` Message Body, `{{RECEIVE_TIME}}` Received Time, `{{CONTACT_NAME}}` Contact Name
  - `{{SIM_SLOT}}` SIM Card Slot, `{{RECEIVER_NUMBER}}` Receiving Phone Number
  - `{{DEVICE_NAME}}` Device Model, `{{BATTERY_INFO}}` Battery Level & Status, `{{IP_LIST}}` IP Address, `{{NET_TYPE}}` Network Type, `{{APP_VERSION}}` Version Name, `{{CURRENT_TIME}}` Current Time
- **Smart Verification Code Sandbox**: Built-in regex engine with lookaround matching to accurately extract 4–8 digit verification codes.

### 4. Offline Transaction Queue & Self-Healing Engine
- **Outbox Dispatcher**: Automatically buffers tasks (`PENDING / RETRY / FAILED`) when disconnected or screen-off, and retries with exponential backoff upon reconnection.
- **Recovery Engine**: Background periodic audit and self-healing engine to prevent message loss.

### 5. Refined Visuals & UI Polish
- 🛸 **Floating Frosted Glass Capsule Dock**: Modern `RoundedCornerShape(36.dp)` floating pill Dock with dynamic specular glow and `100.dp` bottom safety padding.
- 📱 **Anti-Squeeze Channel Cards**: Redesigned test buttons with fixed safety width to eliminate vertical text wrapping on narrow screens.
- ⏱️ **Intelligent Timestamp & SIM Slot Alignment**: Received messages left-aligned, sent messages right-aligned, with `[1] / [2]` SIM tags pinned at the start of timestamps.

---

## 🛡️ Privacy, Security & OEM Compatibility

### 1. Hardware Keystore Encryption (AES-GCM)
- All channel secrets (AppSecret, Bot Token, Headers, etc.) are encrypted via **Android Keystore AES-GCM** hardware-backed storage with automatic graceful memory fallback.

### 2. Android 10–14 Crash Immunity
- Refactored EventBus subscriptions in `MainActivity`, `RecycleBinConversationsActivity`, and `ThreadActivity` into isolated subscriber objects, eliminating `PictureInPictureUiState` reflection crashes on Huawei nova 5z, Redmi 9a, and other Android 10/11 devices.

### 3. Dual SIM Routing & Low Battery Alert
- `SubscriptionResolver` fully adapted for Xiaomi HyperOS, Huawei EMUI/MagicOS, OPPO/vivo dual SIM card slot routing.
- Configurable low battery alerts with dedicated channel routing.

---

## ⚙️ Background Keep-Alive & Screen-Off Guide

To ensure 100% reliable forwarding during deep sleep, please configure the following system settings (accessible via `Settings → Background & Device Compatibility`):

1. 🔋 **Battery Optimization**: Set to **「Unrestricted」 / 「Allow background high power consumption」**;
2. 🚀 **Auto-start**: Set to **「Allow Auto-start」 / 「Allow Secondary Launch」**;
3. 🔒 **Recent Apps Lock**: Lock the app in the Recent Tasks multitasking screen;
4. 📱 **Default SMS App**: Recommended to set as the system default SMS application for maximum background priority.

---

## 🛠️ Local Build

```bash
# Clone the repository
git clone https://github.com/2756826865/android-sms-forwarder.git
cd android-sms-forwarder

# Build Core Debug APK
./gradlew :app:assembleCoreDebug

# Build Release APK
./gradlew :app:assembleCoreRelease
```

Build outputs: `app/build/outputs/apk/core/`

---

## 📄 License & Credits

- Licensed under **GPL-3.0**, derived from [Fossify Messages](https://github.com/FossifyOrg/Messages);
- Select Webhook and push channel architectures inspired by and optimized from [message-pusher](https://github.com/songquanpeng/message-pusher).
