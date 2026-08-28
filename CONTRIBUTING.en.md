# Contributing to SMS Forwarder

<p align="center">
  <a href="CONTRIBUTING.md">简体中文</a> · <b>English</b>
</p>

Thank you for your interest in improving **SMS Forwarder**! This project is an independent GPL-3.0 open-source fork based on Fossify Messages, actively maintaining and advancing the Android modern SMS client and multi-channel SMS Gateway.

---

## 📋 Checklist Before Submitting an Issue

Before creating a bug report or feature request, please verify the following:

1. **Reproduce on Latest Official Release**: Please test against the latest build from [Releases](https://github.com/2756826865/android-sms-forwarder/releases/latest).
2. **Default SMS App Status**: If your issue involves SMS sending/receiving, filtering, or deletion, ensure the app is designated as the system default SMS client.
3. **Core Permissions Granted**: Verify that SMS (`RECEIVE_SMS`, `SEND_SMS`, `READ_SMS`), Contacts, Phone State, and Notification permissions are allowed.
4. **OEM Background & Battery Whitelist**:
   - On Xiaomi (MIUI/HyperOS), Huawei (EMUI/HarmonyOS), OPPO/vivo, ensure **「Auto-start」** is enabled, Battery is set to **「Unrestricted / Allow Background High Power Consumption」**, and the app is locked in the Recent Tasks multitasking view.
5. **Search Existing Issues**: Search the [Issue Tracker](https://github.com/2756826865/android-sms-forwarder/issues) and [Discussions](https://github.com/2756826865/android-sms-forwarder/discussions) to avoid duplicate reports.

---

## 🐛 Bug Report Guidelines

A good bug report includes clear reproduction steps and environment details:

- **Device Information**: Manufacturer and exact model (e.g., Xiaomi 13, Huawei nova 5z).
- **System Version**: Android OS version (e.g., Android 10, 11, 14) and OEM skin (HyperOS 1.0, HarmonyOS 4.2, OriginOS 4).
- **App Version**: SMS Forwarder version tag (e.g., `v1.1.2`) and installation source (GitHub Release / self-built).
- **SIM & Network Setup**: Single or dual SIM (SIM1 / SIM2 involved), network environment (WiFi / Cellular).
- **Steps to Reproduce**:
  1. Open app and navigate to...
  2. Configure... channel and tap...
  3. Notice unexpected behavior...
- **Expected vs Actual Behavior**: Clear description of what should happen versus what actually happened.
- **Logs & Screenshots**:
  - Diagnostic bundle exported from Developer Mode 【Operations & Diagnostics】, or system `logcat` stack traces.
  - ⚠️ **Privacy Warning**: **NEVER** include real SMS text, verification codes, real phone numbers, PushPlus Tokens, Bot Tokens, Webhook Secrets, or passwords in public issues or screenshots!

---

## 💡 Feature Requests & Code Contributions

### Feature Requests
When suggesting a new feature:
- Clearly state the problem, pain point, or use case.
- Outline the proposed UI flow (e.g., Classic Settings vs Developer Gateway Workbench).
- Consider potential impacts on third-party APIs, carrier charges, privacy, or battery life.

### Code Contributions (Pull Requests)
1. **Branching Strategy**: Branch from the latest `main` branch (`feat/...` or `fix/...`).
2. **Code Style & Guidelines**:
   - Adhere to official Kotlin coding conventions and project architecture;
   - For Compose UI, follow Material 3 and existing `Theme.kt` design guidelines;
   - For EventBus subscriptions, always use isolated subscriber objects with `Throwable` safety to prevent reflection crashes on older Android versions.
3. **Sensitive Information**:
   - Never commit signing keys, keystore files (`.jks`), `keystore.properties`, `local.properties`, or personal API tokens.
4. **Pre-Submission Verification**:
   ```bash
   # Check diff and whitespace
   git diff --check

   # Build Core Debug APK
   ./gradlew :app:assembleCoreDebug

   # Verify Release packaging & minification
   ./gradlew :app:assembleCoreRelease
   ```

---

## 📄 License & Upstream Attribution

By submitting contributions, you agree that your code will be licensed under the project's [GNU GPL-3.0](LICENSE) license.
This project is derived from [Fossify Messages](https://github.com/FossifyOrg/Messages) and maintains all applicable open-source obligations and upstream attributions.
