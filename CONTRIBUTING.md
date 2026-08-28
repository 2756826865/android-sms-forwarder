# 参与贡献

感谢你愿意帮助改进 **SMS Forwarder**！本项目是基于 Fossify Messages 的独立 GPL-3.0 开源分支，重点维护与演进 Android 现代短信客户端与 SMS Gateway 多渠道转发网关。

---

## 📋 提交 Issue 前的检查清单

在提交 Bug 报告或功能建议前，请先完成以下检查：

1. **使用最新正式版复现**：请先升级至 [Releases](https://github.com/2756826865/android-sms-forwarder/releases/latest) 中的最新正式版本尝试复现。
2. **确认默认短信权限**：若涉及短信收发、过滤或删除，请确认应用已设为系统默认短信 App。
3. **确认系统基础权限**：确认已授予短信（`RECEIVE_SMS`, `SEND_SMS`, `READ_SMS`）、联系人、电话状态及通知权限。
4. **后台保活与电池优化**：
   - 小米 (MIUI/HyperOS)、华为 (EMUI/HarmonyOS)、OPPO/vivo 等设备请确认已开启 **「允许自启动」** 与电池 **「无限制 / 允许后台高耗电」**，并在多任务后台给应用加锁。
5. **搜索已有 Issues**：先搜索现有的 [Issues 列表](https://github.com/2756826865/android-sms-forwarder/issues)，避免重复提交。

---

## 🐛 Bug 报告格式

一个清晰详尽的 Bug 报告有助于我们快速定位并修复问题，请在报告中尽量包含：

- **设备信息**：手机品牌、具体机型型号（如：小米 13、华为 nova 5z）。
- **系统版本**：Android 版本（如 Android 10、11、14）以及定制 ROM 版本（如 HyperOS 1.0、HarmonyOS 4.2、OriginOS 4）。
- **应用版本**：SMS Forwarder 版本号（如 `v1.1.2`）与安装来源（GitHub Release / 自行编译）。
- **卡槽与网络**：单卡或双卡（涉及 SIM1 还是 SIM2）、运营商网络环境（WiFi / 移动数据）。
- **复现步骤**：
  1. 打开应用并进入...
  2. 配置...通道并点击...
  3. 观察到...异常
- **预期行为 vs 实际结果**：清晰描述期望的结果与实际发生的结果。
- **日志与截图**：
  - 开发版【运维诊断】导出的诊断日志包，或 `logcat` 异常崩溃堆栈。
  - ⚠️ **安全与隐私警告**：**严禁**在 Issue 或公开截图中上传真实短信正文、验证码、真实手机号、PushPlus Token、Telegram Bot Token、Webhook Secret、邮件授权码、企业微信密钥或任何私密数据！

---

## 💡 功能建议规范

提交新功能建议时，请说明：

- 要解决的核心痛点或具体业务场景。
- 建议的操作流程与界面位置（如：是在【经典版】设置页还是【开发版】工作台）。
- 是否影响默认短信角色、双卡规则、后台任务、通知或第三方推送通道。
- 是否存在运营商资费、隐私滥发或系统权限风险。

涉及批量短信、自动转发和后台常驻的功能，必须保留明确确认、频率限制和隐私提示，严禁加入隐藏发送、绕过系统权限或规避运营商限制的违规实现。

---

## 🛠️ 代码贡献指南

1. **分支管理**：从最新的 `main` 分支创建功能分支（`feat/...`）或修复分支（`fix/...`）。
2. **规范与代码风格**：
   - 保持 Kotlin 官方编码规范与现有项目架构风格；
   - 涉及 Compose UI 时请遵循 Material 3 与现有 `Theme.kt` 品牌设计系统；
   - 涉及事件订阅必须使用独立的 Subscriber 静态对象并做好 `Throwable` 级兼容保护，严禁直接在 Activity 上注册导致低版本 Android 10/11 反射崩溃。
3. **敏感信息隔离**：
   - 严禁提交任何个人 Keystore 签名文件、`keystore.properties`、`local.properties`、测试 Token、私钥或编译产物。
4. **提交前本地自检**：
   ```bash
   # 检查代码格式与未暂存差异
   git diff --check

   # 编译 Debug APK
   ./gradlew :app:assembleCoreDebug

   # 验证 Release 混淆与编译打包
   ./gradlew :app:assembleCoreRelease
