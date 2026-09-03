# 🔨 构建日志 (Build & Verification Log)

## 📌 构建环境基准 (Environment Baseline)
* **工程名称**：android-sms-forwarder
* **代码分支**：`main`
* **构建系统**：Gradle 8.x / Android Gradle Plugin (AGP) 9.3.1
* **Kotlin 编译器**：`2.4.10`
* **Java 目标版本**：JDK 17 (`VERSION_17`)
* **SDK 配置**：
  * `compileSdk` = **37**
  * `targetSdk` = **37**
  * `minSdk` = **26** (Android 8.0 Oreo+)
* **构建类型与变体**：`coreDebug`, `fossDebug`, `gplayDebug`

---

## 🚀 最新构建记录 (2026-09-03)

### 1. 全量工程清理与编译 (Clean Build)
* **执行命令**：`gradlew.bat clean assembleCoreDebug`
* **构建耗时**：1 分 55 秒
* **执行状态**：✅ **`BUILD SUCCESSFUL`** (36/36 Actionable Tasks Executed)
* **核心编译链路验证**：
  * `kspCoreDebugKotlin`：通过
  * `compileCoreDebugKotlin`：通过 (0 错误，0 致命告警)
  * `compileCoreDebugJavaWithJavac`：通过
  * `dataBindingGenBaseClassesCoreDebug`：通过 (XML ID 绑定完整)
  * `mergeCoreDebugResources`：通过 (资源引用无缺失)
  * `packageCoreDebug`：通过 (APK 打包生成成功)

### 2. 单元测试与代码质量 (Unit Tests)
* **执行命令**：`gradlew.bat testCoreDebugUnitTest`
* **执行状态**：✅ **`BUILD SUCCESSFUL`** (16/16 Tasks Passed)
* **测试用例覆盖范围**：
  * 验证码提取引擎（`VerificationCodeExtractorTest`）
  * 多实例配置持久化与序列化
  * 安全数据加解密与指纹哈希
  * 转发规则解析与决策路由

---

## 🔍 代码审计与防御性加固变更清单

| 变更组件 | 文件路径 | 变更类型 | 详情 |
|---|---|---|---|
| **无障碍设置** | `app/src/main/res/layout/activity_autofill_settings.xml` | 修复 | 清理 XML 冗余重复节点，确保 AAPT ViewBinding 正确生成 |
| **无障碍状态** | `app/src/main/kotlin/.../activities/AutofillSettingsActivity.kt` | 修复 | 修复颜色资源引用为 `miui_warning_text` |
| **系统清单** | `app/src/main/AndroidManifest.xml` | 合规 | 为 `TransactionService` 显式添加 `android:exported="false"` |
| **多实例转发** | `app/src/main/kotlin/.../forwarding/MultiChannelForwardWorker.kt` | 重构 | 优化实例路由过滤条件与 Telegram / Bark / Gotify 调用签名 |
| **会话存储** | `app/src/main/kotlin/.../extensions/Context.kt` | 加固 | 将 `getConversations()[0]` 升级为 `.firstOrNull() ?: continue` 防御越界 |
| **通知栏交互** | `app/src/main/kotlin/.../helpers/NotificationHelper.kt` | 新增 | 增加验证码一键「复制」Notification Action 快捷按钮 |
| **复制广播器** | `app/src/main/kotlin/.../receivers/CopyVerificationCodeReceiver.kt` | 新增 | 实现点击后秒级写入剪贴板并 Toast 提示与通知取消 |
| **隐私脱敏引擎** | `app/src/main/kotlin/.../forwarding/PrivacyDataMasker.kt` | 新增 | 纯本地手机号/身份证/银行卡号正则掩码脱敏 |
| **时间窗口规则** | `app/src/main/kotlin/.../forwarding/ForwardingRules.kt` | 升级 | 规则模型扩展时段 (timeStart/timeEnd) 与工作日匹配 |
| **电话状态广播** | `app/src/main/kotlin/.../receivers/CallStateReceiver.kt` | 加固 | 升级 `goAsync()` 异步派发与暂态持久化，彻底防御进程回收状态丢失 |
| **未接来电设置** | `app/src/main/kotlin/.../activities/CallForwardingSettingsActivity.kt` | 新增 | 未接来电转发独立设置与模拟测试入口 |
| **日志环形缓冲** | `app/src/main/kotlin/.../observability/log/RingBufferLogManager.kt` | 优化 | 使用 `AtomicInteger` 将高频容量检查从 O(N) 优化至 O(1)，大幅省电 |
| **硬件密钥自愈** | `app/src/main/kotlin/.../forwarding/MultiForwardConfig.kt` | 加固 | 增加 `KeyStore` 失效自动清理与自愈重建机制，防止硬件密钥损坏死锁 |
| **历史存储防卡死** | `app/src/main/kotlin/.../forwarding/ForwardingHistoryStore.kt` | 优化 | 将阻塞式 `.commit()` 升级为异步 `.apply()`，消除广播主线程 ANR 风险 |
| **低电量重置** | `app/src/main/kotlin/.../activities/LowBatterySettingsActivity.kt` | 修复 | 滑动条调整阈值时自动重置 `lastNotifiedLevel`，修复低阈值漏提醒 Bug |
| **定时心跳保活** | `app/src/main/kotlin/.../helpers/HeartbeatWorker.kt` | 新增 | 周期性上报电量、网络、卡槽、运行时间等系统健康报告 |
| **心跳保活设置** | `app/src/main/kotlin/.../activities/HeartbeatSettingsActivity.kt` | 新增 | 心跳周期调节（1~24h）与一键测试推送界面 |
| **邮箱指令编码** | `app/src/main/kotlin/.../remote/EmailRemoteCommandPoller.kt` | 修复 | 增加 RFC 2047 MIME 解码与发件人邮箱纯净正则提取，修复 QQ 邮箱未授权误报 |
| **厂商发信拦截** | `app/src/main/kotlin/.../helpers/DeviceCompatHelper.kt` | 完善 | 补充 ColorOS / HyperOS 后台发送短信免弹窗拦截与默认短信应用指引 |

---

## 📦 构建产物归档 (Artifacts)
* **Core Debug APK**：`app/build/outputs/apk/core/debug/SMS-Forwarder-core-debug.apk`
* **编译生成规则**：全量保留本地，未执行远程提交。
