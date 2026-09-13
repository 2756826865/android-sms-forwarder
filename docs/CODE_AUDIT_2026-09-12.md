# 代码审计报告 — android-sms-forwarder

- **审计日期**：2026-09-12
- **代码版本**：1.1.7（目录名 1.0.4 与实际代码版本不一致，注意区分）
- **审计范围**：`app/src/main/kotlin` 326 个 Kotlin 文件 + `app/src/androidTest/` 30 个测试 + `AndroidManifest.xml` + `build.gradle.kts` + `databases/` + `app/schemas/`，共 367 个源文件
- **审计方式**：只读静态审计（未修改任何源码、未执行 gradle 构建）
- **审计团队**：QA 工程师（代码级缺陷）+ 架构师（架构/并发/安全）+ 主理人冲突仲裁与交叉验证

---

## 一、结论速览

| 等级 | 条数 | 定义 |
|---|---|---|
| **P0** | **6** | 崩溃 / 数据丢失 / 安全漏洞 |
| **P1** | **15** | 功能失效 / 逻辑错误 / 设计缺陷 |
| **P2** | **12** | 健壮性 / 边界 / 代码质量 |
| 合计 | **33** | |

> 统计口径：初轮 28 条（P0×5 / P1×13 / P2×10）+ 第二轮新增 6 条（P0×1 / P1×2 / P2×2），并合并 1 条重复项（`广播内启 FGS 无保护` 与 P0-2 为同一问题）。明细见第九、十章。

### 最需要优先处理的 5 件事

1. **远程发短信鉴权默认就是开放的** — 新建远程来源的 `whitelistEnabled` 默认 `false`，导致鉴权表达式恒为 `true`；**任何人发条短信就能用机主手机发信**。另有两条独立绕过路径
2. **短信接收链路无兜底，可能彻底丢短信** — `startForegroundService` 裸调用，已写好的安全路径是死代码
3. **通道凭据加解密失败静默降级** — 配置了企微长连接的用户，仅冷启动即自动明文落盘
4. **回前台默认不做补偿同步** — ISSUE-012「解锁后短信不同步」的代码层根因已确认
5. **Outbox 可靠投递子系统 653 行全是死代码** — 宣称的幂等重试、死信、指数退避在真机上不存在

**为什么第 1 条排在最前**：它是唯一一个"默认配置下就处在可被利用状态"的问题，不需要 Keystore 故障、不需要特定机型、不需要用户做任何操作——装好、配好远程控制，就已经开放了。

---

## 二、冲突仲裁说明

两位成员在**短信接收主链路**上给出了相反结论，主理人亲自读取源码裁决：

| 成员 | 结论 |
|---|---|
| 架构师 | 接收链路无兜底，短信会彻底丢失 |
| QA | 接收主链路异常处理完整，未见静默崩溃点 |

**裁决：架构师结论成立，QA 漏看了 receiver → service 的交接点。**

两者其实都不算错，但层级不同：QA 检查的是 `IncomingSmsService.processIncoming` 内部（确实有 `catch (Throwable)` 兜底，:87/:190/:446）；架构师指出的是**进入服务之前**的交接环节无保护。

关键证据（`receivers/SmsReceiver.kt:17-27`）：

```kotlin
25:        SmsKeepAliveService.ensureStarted(appContext)      // ← 内部有 runCatching 保护
26:        IncomingSmsService.enqueue(appContext, intent)     // ← 无任何 try/catch
```

而 `services/IncomingSmsService.kt:660-666`：

```kotlin
660:        fun enqueue(context: Context, source: Intent) {
661:            val serviceIntent = Intent(context, IncomingSmsService::class.java).apply {
662:                action = source.action
663:                replaceExtras(source)
664:            }
665:            ContextCompat.startForegroundService(context, serviceIntent)   // ← 裸调用
666:        }
```

**同一函数内第 25 行有保护、第 26 行裸奔 —— 可证明这是遗漏而非设计取舍。**

更讽刺的是，带完整兜底的安全路径 `processFromReceiver()`（:673-694）已写好（内部有 `catch (Throwable)`、失败会写入 `lastReceiverStatus`、注释明确说明是为 HyperOS 冻结场景设计），但全仓 grep 确认**调用点 = 0**，与 `processIncomingForReceiver()` 同为死代码。

---

## 三、P0 — 崩溃 / 数据丢失 / 安全漏洞（6 条）

### P0-1 远程发短信鉴权可绕过（已验证可利用，三条独立路径）

**验证结论：白名单外的发件人确实能发出短信。** 从短信到达至 `smsManager.sendTextMessage` 之间，`RemoteCommandProcessor.kt:117-138` 是全局唯一鉴权点，其后**无任何第二道身份校验**（`:299-316` 只检查 `SEND_SMS` 系统权限，不是身份鉴权）。

完整调用链：

```
SmsReceiver.kt:26 → IncomingSmsService.kt:285-293 → RemoteSmsCommand.kt:239-248（选源）
→ RemoteSmsCommand.kt:271 → RemoteCommandProcessor.kt:117-138（唯一鉴权点）
→ RemoteCommandProcessor.kt:342 → RemoteSmsCommand.kt:375-382
→ MessagingUtils.kt:168 → SmsSender.kt:44 → SmsSender.kt:117-124 smsManager.sendTextMessage
```

#### 路径 A：新建远程来源默认关闭白名单 —— 默认即开放（最危险）

```kotlin
RemoteSourceRepository.kt:68   val whitelistEnabled: Boolean = false
RemoteControlScreen.kt:789     initialSource?.whitelistEnabled ?: false
RemoteCommandProcessor.kt:125  isAuthorized = !whitelistEnabled || ...   // → 恒为 true
```

设置界面自述（`RemoteControlScreen.kt:1128`）："已关闭：接受所有用户的有效指令"；`:1322` 允许白名单为空时保存。

→ **用户按引导配好远程控制后，系统就处在"任何人可发指令"的状态**，无需任何攻击者侧技巧。

#### 路径 B：发件人无数字 —— 无条件通过

`RemoteCommandProcessor.kt:399-403`、`RemoteSmsCommand.kt:288-292`：

```kotlin
left == right || left.endsWith(right) || right.endsWith(left)
```

发件人为字母 sender ID / 邮件网关 / 部分 ROM 签名号时（如 `BANK`）`right == ""`，而 `left.endsWith("")` **恒为 true**，且只校验 `left.isNotEmpty()` → 任意发件人一律授权。

#### 路径 C：白名单条目短于 11 位 —— 后缀通配

`right.endsWith(left)`：白名单填 `10086`，发件人 `13912310086` 判定等价。

- **后果**：任何人可向该机发送 `/发信 号码 内容`，用机主手机发送任意短信（资费损失 / 诈骗跳板）
- **最小修复**：`whitelistEnabled` 默认值改为 `true`；`numbersEquivalent` 改为规范化后严格 `==` 且双方非空；新建来源 UI 强制填写白名单

### P0-2 短信接收链路无兜底，短信可能彻底丢失

- **位置**：`receivers/SmsReceiver.kt:26`、`receivers/SmsFallbackReceiver.kt:24`、`services/IncomingSmsService.kt:665`
- **触发**：Android 12+ 后台启动 FGS 受限、或 HyperOS/ColorOS 冻结后台时，`ContextCompat.startForegroundService` 抛 `ForegroundServiceStartNotAllowedException` → 异常穿透 `onReceive` → 广播崩溃
- **后果**：本应用作为默认短信应用，SMS_DELIVER 广播未被消费 → 短信既未入库也未转发，**用户完全无感知，无任何兜底方**
- **建议**：`enqueue` 内加 try/catch，失败时降级为 `goAsync()` 在广播窗口内同步入库；或直接将已写好的 `processFromReceiver()` 接线启用

### P0-3 通道凭据加解密失败静默降级 / 清空

- **位置**：`forwarding/MultiForwardConfig.kt:922-927`（encrypt）、:929-937（decrypt）、:744-750（saveSecret）、:752-756（getSecret）、:650-668（saveChannelInstances）
- **三条子路径**：
  1. **明文落盘**：`encrypt` 失败返回 `""` → `saveChannelInstances:661` 的 `if (encryptedConfig.isNotBlank())` 不成立 → `obj.put("configJson","{}")` 不执行 → `toJson()` 无条件写入的**完整明文凭据**（botToken / webhook / 邮箱密码 / corp secret）直接落盘
     - 精确触发条件：Keystore 不可用期间，**用户编辑/新建/迁移**任一通道配置（冷启动回写路径已被 `preservedEncrypted` 保护，不受影响）
  2. **凭据被清空**：`saveSecret:748` 在 `encrypt` 返回 `""` 时写入空串 → `getSecret:755` 中 `stored=""` 非空不早退、`decrypt("")` 返回 `""`、`isNotEmpty()` 为 false → `return stored` 即 `""`。**凭据静默清空，界面显示为空，用户以为自己没保存过**
  3. **密文当明文发送**：`getSecret:755` 的 `return if (decrypted.isNotEmpty()) decrypted else stored` —— 解密失败时把 **Base64 密文当作 token/secret 直接发出**
- **放大效应**：路径 3 发出的垃圾凭据会返回 401/404，被 `isPermanentError`（`MultiChannelForwardWorker.kt:584-593`，命中 "404"/"token" 等通用子串）判定为永久错误 → **直接 markFailed，不再重试**
- **叠加因素**：`AndroidManifest.xml:42 android:allowBackup="true"` 且无 `dataExtractionRules`/`fullBackupContent`；Android Keystore 密钥不随备份迁移（平台行为），换机恢复后全部密文解不开
- **建议**：加密失败必须抛错并保留旧值，绝不落明文/空串；区分"未加密的历史明文"与"解密失败的密文"两种回退语义；凭据 prefs 排除出备份；解密失败向 UI 明确提示"凭据需重新录入"

#### 补充验证（第二轮）：触发面比初判更大，无需用户操作

初判认为"只有用户手动编辑/新建/迁移配置才会落明文"，第二轮审计**推翻了这个范围限定**。

`ChannelRepository.init` 会在无任何用户交互的情况下自动重建企微长连接实例：

```kotlin
47:    fun refresh() {
49:        val linkedSources = detectConfiguredWeComStreams()
51:            linkedSources.forEach { syncDetectedWeComStream(it) }   // ← 无用户交互
```

→ `syncDetectedWeComStream`(:448-454) → `syncLinkedWeComStreamChannel`(:106-155)，构造真实 `configJson`（:125-129，含 `botId`/`chatId`）→ :147 的相等守卫在解密失败时失效（`existing.configJson == "{}"` ≠ `updated.configJson`）→ :153 执行 `saveChannelInstances` → 因 `configJson != "{}"` 不走 `preservedEncrypted`(:654) → 加密失败 → **明文写入**。

| 用户类型 | 仅冷启动是否触发 | 实际落盘内容 |
|---|---|---|
| 普通用户（未配企微长连接、无旧版扁平配置） | 否 ✅ | `preservedEncrypted` 保护生效，保留原密文 |
| **配置了企业微信长连接** | **是 ❌** | `chatId` **明文**（`RemoteSourceRepository:768-771` 的敏感字段列表未包含 chatId）；`botId` 仍为密文（`decryptSensitiveConfig:798-800` 失败时保留 `ENC:` 前缀原文） |
| 仍留有旧版扁平通道配置 | 是 ⚠️ | 密文被当作明文写入 `configJson` → 实例永久不可用（属数据损坏，因写入的仍是密文，**不构成真实凭据泄露**） |

**修正后表述**：Keystore 不可用时，无需用户做任何操作——只要配置过企业微信长连接（或留有旧版扁平配置），下一次冷启动即自动触发标识明文落盘。企微长连接是主推功能，命中面不小。

### P0-4 Outbox 可靠投递子系统无生产者、无调度者

> **第二轮修正**：初轮"完全是死代码"的表述不准确，工程师逐条核实后更正如下。

- **位置**：`outbox/` 模块（**4 个** executor，非 6 个）、`OutboxTaskDao`、迁移 `MIGRATION_14_15`（建表语句在 `MessagesDatabase.kt:277-287`）、`RecoveryEngine`、`rule/RuleEngine.toOutboxTaskContext()`
- **确认为零调用的部分**：
  - `OutboxDispatcher.dispatchOnce()` → 仅定义 + `androidTest/OutboxDispatcherTest.kt`
  - `OutboxExecutorRegistry.register(...)` → 仅定义 + 3 个 androidTest
  - `OutboxRepository.createTask(...)` → 仅定义（`helpers/OutboxRepository.kt:40`）
  - `rule/RuleEngine` + `rule/evaluator/ConditionEvaluator` → 生产零调用
  - `forwarding/plugin/`（ChannelPluginManager / HttpChannelPlugin / SmsDirectChannelPlugin）→ 仅被 `outbox/ForwardPluginOutboxExecutor.kt:33` 引用
- **需更正的部分**：
  - `OutboxTaskDao` **并非零调用**：有 3 处**只读**调用点——`DiagnosticBundleGenerator.kt:84-86`（诊断包）、`DashboardDataRepository.kt:43-45`（运行大盘）、`ForwardingCenterRepository.kt:42-44`（转发中心）
  - `RecoveryEngine` **并非永不执行**：被 `App.kt:58` 调用，段 1/2/5/6 **可达**，只是 `outbox_tasks` 表永远为空所以空跑
- **准确表述**：**无生产者、无调度者，`outbox_tasks` 表永远为空，但 UI 仍在读它的计数**
- **后果**：宣称的"Outbox 幂等重试 / 死信 / 指数退避"在真机上**根本不存在**；真实投递走 `MultiChannelForwardWorker`（WorkManager 一次性任务），**没有持久化重试队列**，进程被杀即丢转发
- **建议**：推荐**下线删除**（接线启用需补生产者/调度/幂等三处，成本高于重写）。删除时**必须**把上述 3 处 UI 计数改为读 `ShadowDaos()` 的 `forwarding_deliveries`，否则删完大盘与诊断包指标恒为 0。下线需新增 `MIGRATION_16_17` 删表以保持迁移链连续，而该迁移有破坏性，**须在 P0-5 完成后进行**

### P0-5 回溯窗口过大导致短信轰炸（两个独立触发场景）

**共同根因**：`SmsRecoveryWorker` 的判重基准是本地 `messages` 表的 `localIds`（:50），而回溯窗口缺省长达 7 天。

#### 场景 A：破坏性迁移后重推（原报告已记录）

- **触发链**：任一迁移抛异常 → `fallbackToDestructiveMigration`（`MessagesDatabase.kt:95`）静默清空整库 → `localIds` 为空 → 以 7 天窗口取全部收件短信 → `MultiChannelForwardWorker.enqueue(...)`（:169）→ 重复轰炸 + 重复触发远程指令（:140）

#### 场景 B：**首次安装即转发安装前 7 天历史短信**（第二轮新发现，与迁移无关）

```kotlin
// SmsRecoveryWorker.kt:30-37
val since = prefs.getLong(KEY_LAST_RECOVERY_SCAN_AT, 0L).takeIf { it > 0 }
    ?: (System.currentTimeMillis() - FIRST_LOOKBACK_MS)   // FIRST_LOOKBACK_MS(:27) = 7 天
```

- **触发条件**：`KEY_LAST_RECOVERY_SCAN_AT` 在首次安装时不存在 → 水位直接回拨 7 天
- **后果**：用户装完应用**第一次收到短信**，就会把过去 7 天的全部历史短信向所有通道转发一遍，并触发其中的远程指令
- **严重性**：**不需要任何故障即可 100% 复现**。这是场景 A 之外的独立轰炸源，初轮审计完全遗漏

#### 附：工程师核实的行号修正

| 项 | 初轮报告 | 实际 |
|---|---|---|
| `localIds` | :49 | :50（`messagesDB.getAll().map{it.id}.toHashSet()`，**全表加载**，大库有 OOM 风险） |
| 水位常量 | `MAX_LOOKBACK_MS:247` | 首次用 `FIRST_LOOKBACK_MS:27`（7 天），后续用 `LAST_RECOVERY_LOOKBACK_MS:29`，:62 取实际值 |
| 周期任务 | :250-255 | :249-256 |

- **建议**：① 建立**独立于本地短信库**的"已转发"持久化存储（如独立 Room 表 `ForwardedMessageStore`，只存短哈希 + 时间戳 + 保留期，不存正文）；② 收窄回溯窗口（瞬时抖动 ≤15 分钟，冷启动 ≤24 小时）；③ 移除 `fallbackToDestructiveMigration`，改为"迁移失败即崩溃 + 提示导出"的显式降级；④ **本条是 P0-4 与 P0-6 的前置依赖**，须最先修

---

### P0-6 回前台默认不做补偿同步（ISSUE-012 代码层根因）

- **位置**：`activities/MainActivity.kt:278-299`、`helpers/Config.kt:61-63`
- **问题**：`useGatewayDeveloperUi` 默认 `true`（`Config.kt:62`），而 `onResume` 在该开关为 true 时于 **`:285` 提前 `return`**，导致 **`:299` 的 `SmsRecoveryWorker.enqueueFullResync(this)` 永不执行**
- **早退分支只做本地刷新**：`dashboardViewModel.loadStats` / `messageCenterViewModel.loadMessageHistory` / `diagnosticsViewModel.loadDiagnostics` / `conversationsViewModel.refresh` —— **全部读本地 Room DB，不回源 Telephony Provider**
- **无解锁触发**：全局检索确认**没有** SCREEN_ON / USER_PRESENT / 解锁广播接收器
- **叠加效应**：与 P1-5（周期恢复任务用 `UPDATE` 策略，每次冷启动重置 15 分钟计时）叠加后，补偿同步实际只剩「下一次收信成功」与「BOOT_COMPLETED」两个触发点
- **后果**：后台漏收的短信**不会自动补回** —— 这正是 `KNOWN_ISSUES_CUSTOM.md` ISSUE-012 中「重新解锁后短信列表仍不自动补齐」的代码层根因，排查重点第 4 条「前台恢复时是否只读取本地缓存」已闭环确认为**是**
- **建议**：把 `enqueueFullResync` 移到早退分支之前；或注册 `USER_PRESENT` 广播触发轻量增量同步

---

## 四、P1 — 功能失效 / 逻辑错误 / 设计缺陷（15 条）

| # | 位置 | 问题 | 后果 |
|---|---|---|---|
| 1 | `forwarding/MultiChannelForwardWorker.kt:54` | `doWork()` 首行 `setForeground()` 无 try/catch | Android 12+ 后台受限时抛异常，整个 worker 失败，转发丢失 |
| 2 | `forwarding/MultiChannelForwardWorker.kt:53-69` | `markRunning`（:69）之后无 try/catch；`enqueueSingle` 先 `registerQueued` 再用 `enqueueUniqueWork(KEEP)` | 异常时记录永久卡 `running`；同名任务在队列中时新任务被静默丢弃但历史已重置为 `queued`，永不推进 |
| 3 | `helpers/MessagingCache.kt:13-14` | `namePhoto` 是无锁 `LruCache`，主线程 `evictAll()` 与收信后台线程 `get/put` 竞争 | `IllegalStateException: LruCache.sizeOf() is reporting inconsistent results`，崩溃点在收信关键路径 |
| 4 | `databases/MessagesDatabase.kt:84-95` | `private var db` 无 `@Volatile`，双重检查锁定失效（ARM 可见未完全构造实例）；`fallbackToDestructiveMigration` | ARM 上潜在的未初始化实例；迁移异常即静默清库 |
| 5 | `messaging/SmsRecoveryWorker.kt:250-255` | 周期任务用 `ExistingPeriodicWorkPolicy.UPDATE`，而 `App.kt:62`/`MainActivity.kt:156` 每次冷启动都 `schedule()` | 15 分钟计时被反复重置，在正是它要救场的 OEM 强杀场景下几乎永不执行（同文件 `RecoveryWorker.schedule` 用 `KEEP`，是正确的） |
| 6 | `forwarding/ForwardingRules.kt:552-706` | `decodeRules` 用 `runCatching{}.getOrDefault(emptyList())` 包裹整段，任一条坏数据抛 `JSONException` → 返回空列表 | 若 UI 触发一次 `rules` setter（:490-492），空列表被持久化 → **规则永久丢失**，`IncomingSmsService:250` 静默不发 |
| 7 | `forwarding/ForwardingRules.kt:238,468-472` + `services/IncomingSmsService.kt:237-243` + `activities/MainActivity.kt:579-594` | **决定性发现：`READ_PHONE_STATE` 权限从未在主界面申请**。`MainActivity.askPermissions()`（:579-594）只申请 `READ_SMS` + `RECEIVE_SMS`；`PERMISSION_READ_PHONE_STATE` 仅在 `ThreadActivity.loadConversation()`（:899）申请，`MainActivity.kt:54` 只是 import 未使用 → **用户若从未打开过任意会话详情页，该权限永不授予**。叠加 `subscriptionId == -1`（:127-135，大量 ROM 的 SMS_DELIVER 不带 subscription extra）、副卡离网、eSIM | **默认状态下 SIM 作用域规则 100% 不可用**：`simMatches` 要求 `slot == 0/1`，null 时两者均 false → :238-239 必然 `continue` → 该规则永不命中 → `matchedRules` 为空 → 短信被判"规则未允许"而**静默丢弃**（:301-315）。补偿路径 `SmsRecoveryWorker.kt:217-225` 同名逻辑同样失效 |
| 8 | `remote/RemoteSmsCommand.kt:245-248` | 白名单未命中时第三级回退 `?: matchingSources.firstOrNull()?.first` 无条件选中第一个实例 | 被 `NOT_AUTHORIZED_USER` 拒绝后，`tryConsume` 对 `Rejected` 返回 **true**（:280-283）→ 短信被判"指令已消费"，**不再走普通转发**，用户无感知 |
| 9 | `forwarding/MultiChannelForwardWorker.kt:595-620` | 判定为 `failures.isEmpty() && successes.isNotEmpty() → success`，只要一个渠道失败就整体 `Result.retry()` | 整个 Worker 重跑，**已成功的渠道被重发**；多实例广播场景 1 个失败 = N-1 个重复 |
| 10 | `helpers/OutboxRepository.kt:62-64` | `createTask` 在 `insert` 抛异常时只 `Log.w`，随后 `return taskId` | 调用方无法区分成功/失败，无重试无提示（当前无生产调用点，一旦接上即真实丢消息路径） |
| 11 | `receivers/SmsStatusSentReceiver.kt:152-165` | 被 `ensureBackgroundThread`（:77）调用却 `Handler(mainLooper).post{}` 在主线程查联系人；cursor 交后台线程跨线程使用且**从不 close**；:136-137 空 `catch` 吞异常 | ANR 风险 + Cursor 泄漏 + 同步异常静默 |
| 12 | `forwarding/MultiChannelForwardWorker.kt:584-593` | `isPermanentError` 命中 "Token/token/400/404/invalid/签名" 等通用子串 | 一次含 "404" 的网关抖动即判永久失败 → `canRetry=false` → 静默放弃转发（叠加仅 2 次重试） |
| 13 | `AndroidManifest.xml:25`、:23 | `READ_CALL_LOG` 全仓零使用；`READ_SYNC_SETTINGS`（:23）同样未 grep 到调用点 | 过度授权，合规风险（注：`CALL_PHONE` **有使用**，`Activity.kt:59-60` 有权限时 `ACTION_CALL` 直拨；已核实无间接触达面，见 9.2） |
| 14 | `receivers/ScheduledMessageReceiver.kt:61-69` | `Handler(mainLooper).post { sendMessageCompat(...) }` 立即返回，随后 finally 里 `wakelock.release()`(:38) + `pendingResult.finish()`(:42) → **在短信真正发出前就释放了广播令牌与 WakeLock**；且 `sendMessageCompat` 在**主线程**做 ContentResolver insert + Room 读写（`MessagingUtils.kt:100-181`），落在 10 秒广播 ANR 窗口内 | 定时短信可能不发送或发一半被杀；主线程 I/O 触发 ANR |
| 15 | `receivers/DirectReplyReceiver.kt:24-80` | `onReceive` **未调用 `goAsync()`**，整个发送流程丢进 `ensureBackgroundThread`(:41)，`onReceive` 返回后系统随时可回收进程；另 `:32` `SubscriptionResolver.resolve(...)` 在**主线程**执行 Binder 调用 | 用户在通知栏输入的回复可能在 `sendTextMessage` 前丢失；主线程 Binder 调用 |

### 附带：测试覆盖错位（值得单独关注）

`rule/RuleEngine.kt` + `rule/evaluator/ConditionEvaluator.kt` 在生产代码中**零调用**（仅 `TemplateRenderer.extractVerificationCode` 被用到），却被 `androidTest/rule/RuleEngineTest.kt` 的 9 个用例完整覆盖；而**真正生效的** `forwarding/ForwardingRules.kt: ForwardingRuleEngine`（优先级、免打扰、SIM 作用域、目标去重、正则替换）**无任何单测**。

即：测试全部通过，但没测到真正跑在生产链路上的代码。

---

## 五、P2 — 健壮性 / 边界 / 代码质量（12 条）

| # | 位置 | 问题 |
|---|---|---|
| 1 | `MultiChannelForwardWorker.kt:548-557` | 多实例派发分支 Email 硬编码 `security = 0`（SSL），忽略实例配置；对比同文件 :298-301 与 `ChannelTestSender.kt:585` 都按 `port==587 → STARTTLS` 解析 → 配置 587+STARTTLS 的实例在广播/群发必失败 |
| 2 | `services/IncomingSmsService.kt:547,560,652` | `KEY_LAST_FINGERPRINT` 从未被写入（只有 `remove`），该判重分支恒为 false，死代码 |
| 3 | `forwarding/ForwardingRules.kt:408-413`、`rule/evaluator/ConditionEvaluator.kt:39-41` | 用户可控正则直接 `Regex(value).containsMatchIn(短信正文)`，Java 回溯引擎无超时 → 病态正则（`(a+)+$` 类）可卡死转发线程（ReDoS） |
| 4 | `forwarding/ForwardingHistoryStore.kt:124-128,183-185` | 200 条 × 每条最多 2000 字符的**短信正文明文**写入 `forwarding_history.xml`，叠加 `allowBackup=true` 进入备份；项目已有 `PrivacyDataMasker` 却未用于历史记录 |
| 5 | `AndroidManifest.xml:42` | `allowBackup="true"` 无排除规则，含短信正文的 SharedPreferences 上云 |
| 6 | `AndroidManifest.xml:47` | `usesCleartextTraffic="true"` 全局开启明文 HTTP；已有 `ForwardingUrlPolicy`/`barkAllowHttp` 单通道开关，建议改按域名白名单（`networkSecurityConfig`） |
| 7 | `recovery/RecoveryEngine.kt:63-68` | 解锁死锁任务时只 `updateState(PENDING)` 不递增 `attemptCount`，若任务每次执行都令进程崩溃则无限复活（无 poison-pill 计数） |
| 8 | `interfaces/OutboxTaskDao.kt:25` | `findPendingTasks` 不覆盖 `RUNNING 且 lock_expires_at < now` 的任务，只能等 15 分钟一次的 `RecoveryWorker`；叠加 P1-5 后可能永久卡死 |
| 9 | `messaging/SmsRecoveryWorker.kt:99` | 仅以 `id in localIds` 判重，与 `IncomingSmsService` 的 `sms_receiver_state` 指纹表（:539-561）不共享；本地插入失败时同一条短信会被再次转发 |
| 10 | `remote/WeComStreamClient.kt:423,473,479` | `ack.responseJson!!` / `body!!` 在异常帧时 NPE 打挂长连接线程；同类 `!!` 见 `ThreadActivity.kt:1042,1432,1685,1702`、`ManageBlockedKeywordsActivity.kt:127` |
| 11 | `services/SmsKeepAliveService.kt:47,58,84-85` | 常驻**空**前台服务（`onCreate` 起 FGS，:58 返回 `START_STICKY`，但注释自述 "never polls or holds a permanent wake lock"，不承载任何实际工作）；`ensureStarted` 仅在 `isDefaultSmsApp` 为 true 时启动，且 `runCatching{}`(:85) **吞掉全部启动异常**（含 Android 12+ FGS 限制异常） | 保活静默失效且无任何告警；`stopWithTask=false` + `START_STICKY` 在 Android 14 上受后台限制约束，不宜作为主要可靠性手段 |
| 12 | `receivers/CallStateReceiver.kt:63,66,97,113` | `goAsync()`(:66) + `finally { pendingResult.finish() }`(:97) 配对**正确**，但协程内 `dispatchCallNotification` 读联系人(:113)并入队 WorkManager，**无 WakeLock**；另 `:63` `prefs.edit().clear().apply()` 为异步提交 | 设备休眠时来电转发可能被挂起；进程被杀则暂态未清，下次来电可能误判"已接听" |

### 其他 P2（架构层面）

- `App.kt:55-70`：冷启动主线程同步构造 `ChannelRepository`（含 Keystore 解密）+ `importLegacySources` + `sync()`，低端机 ANR 风险；`:57` 在共享单线程里 `runBlocking { RecoveryEngine.runRecoveryScan(...) }` 长时间占用公共线程池
- `services/IncomingSmsService.kt:673-690`：静态单线程 executor 上 `Thread.sleep(20_000)`，若启用则连续两条短信第二条排队 20 秒
- `helpers/NotificationHelper.kt:63/75/87/108`：多处 `PendingIntent` 用 `FLAG_MUTABLE`，建议除 `RemoteInput` 回复外一律改 `FLAG_IMMUTABLE`
- `helpers/FloatingCodePillManager.kt:30`：`object` 中静态持有 `View`（持有 Context）；`SmsAutofillAccessibilityService:142-145` 把验证码写入系统剪贴板且无自动清除
- `observability/bundle/DiagnosticBundleGenerator.kt:104-113`：导出包可明文导出且含 `exceptionMessage`/`stackTraceSummary`，可能夹带号码或短信片段
- `forwarding/repository/ChannelRepository.kt:25`：`MultiForwardConfig(context!!.applicationContext)` 默认参数强解包；`lock` 只保护本类入口，而 Worker 等组件直接实例化 `MultiForwardConfig` 读写同一份 prefs，锁边界不一致
- `remote/RemoteCommandProcessor.kt:213,283`：普通函数中用 `runBlocking` 调 DAO，缺少约束

---

## 六、未发现显著问题的模块（明确说明，未编造）

- **Room 迁移链完整性**：`version=16`，`MIGRATION_1_2 … 15_16` 的 DDL 与各 Entity 逐字段比对，**列名、空性、主键、索引全部一致**，未发现字段缺失或版本错配（风险在 `fallbackToDestructiveMigration` 兜底策略，见 P0-5）
- **HTTP 超时配置**：`MultiChannelForwardWorker` 的 `HttpURLConnection` 统一 10s/12s；`remote/` 各 OkHttp client 均设 `connectTimeout`（`DingTalkStreamClient`/`WebSocketRemoteClient` 的 `readTimeout(0)` 是长连接正确用法，配 `pingInterval(20s)`）。未发现裸奔无超时的网络调用
- **GlobalScope 滥用**：全仓 0 处，协程均使用显式 `Dispatchers.IO` + `SupervisorJob` 的仓储级作用域，未发现作用域未取消导致的泄漏
- **主线程数据库访问**：未使用 `allowMainThreadQueries`；DAO 调用均在 `Dispatchers.IO` 或后台 executor 内；`@Transaction` 使用点合理
- **远程控制鉴权主干**：`RemoteCommandProcessor` 12 步校验完整（来源实例校验、10 分钟时效窗、白名单默认拒绝、前缀从头匹配、目标号码格式校验、免打扰、限频、幂等 claim、规则联动、SIM 可用性、权限前置）。（注：P0-1 是**发件人号码比对**这一环的缺陷，非整体鉴权缺失）
- **androidTest 假通过**：未发现 `@Ignore`、`assertTrue(true)`、恒真断言；30 个测试文件均有实质断言。主要问题是**覆盖对象错位**（见 P1 附带说明）
- **飞书 Webhook 签名**：`MultiChannelForwardWorker:652-654` 的 `Mac.init(secret=timestamp\nsecret)` + `doFinal(空字节)` **符合飞书官方算法**，不是 bug
- **密钥入库**：`.jks` / `keystore.properties` / `local.properties` 均未被 git 追踪，仓库无凭据泄露
- **可观测性内存占用**：`RingBufferLogManager`、`PerformanceTracker.metricStore` 均有界，无无界增长
- **无障碍服务静态引用**：`SmsAutofillAccessibilityService.instanceRef` 在 `onDestroy` 用 `compareAndSet` 正确清理，无泄漏

---

## 七、修复优先级建议

> **重要**：修复顺序不是按严重度，而是按**依赖关系**。第二轮审计发现 P0-4 与 P0-6 都依赖 P0-5 的判重改造，若按原顺序先修它们会引入新的短信轰炸路径。

### 第一批（独立、无前置依赖，可立即修）

1. **P0-1 远程发短信鉴权绕过** —— 改动点：① `RemoteSourceRepository.kt:68` 默认值改 `true`；② `:419 / :448 / :515 / :543` 四处构造实例时未设 `whitelistEnabled`（继承 false）需补齐；③ `numbersEquivalent` 两份重复实现合并为统一的号码匹配器，改为规范化后严格 `==`（含分机号截断、`86/0086` 前缀剥离、短号与手机号不可比、仅认 ASCII 数字）；④ `RemoteControlScreen.kt:1322` 名单必填、`:789` 默认改 `true`、`:1128` 文案改为风险提示。
   **必须配套存量迁移**：改默认值只影响新建来源，存量已持久化的 `false` 不会变。推荐「仅对 `whitelistEnabled == false && authorizedUsers.isEmpty()` 的来源置 true」，已填名单的用户零感知
2. **P0-2 短信接收链路兜底** —— 在 `IncomingSmsService.enqueue()` 加 try/catch，失败时降级为 `goAsync()` 在广播窗口内同步完成入库。
   **注意**：不可直接"接线启用" `processFromReceiver()`——它内部有 `Thread.sleep(20_000)`，超过 `goAsync` 的 10 秒窗口必然 ANR，且跑在静态单线程 executor 上会让连续短信排队 20 秒。建议改用 `CountDownLatch` 或 Job 串行化，或直接删掉这段零调用的死代码

### 第二批（关键前置，必须先于 P0-4 / P0-6）

3. **P0-5 回溯窗口与判重改造** —— 这是 **P0-4 与 P0-6 的共同前置**：
   - 新建**独立于本地 `messages` 表**的"已转发"持久化存储（只存短哈希 + 时间戳 + 保留期，不存短信正文）
   - 收窄回溯窗口（瞬时抖动 ≤15 分钟、冷启动 ≤24 小时），消除**首次安装即回拨 7 天**的轰炸
   - 移除 `fallbackToDestructiveMigration`，改为"迁移失败即崩溃 + 提示导出"的显式降级

   **为什么必须先做**：P0-4 下线需要 16→17 删表迁移（有破坏性，出错即真清空）；P0-6 会让回前台补偿频繁执行——若判重基准仍是那个可被清空的本地表，**补偿同步本身会变成新的轰炸源**。此外 `SmsRecoveryWorker.kt:50` 的 `getAll().toHashSet()` 是全表加载，频繁触发有 OOM 风险

### 第三批（依赖 P0-5）

4. **P0-4 Outbox 下线** —— 建议下线删除（接线需补生产者/调度/幂等三处，成本高于重写）。**删除时必须**把 3 处 UI 计数（`DiagnosticBundleGenerator.kt:84-86`、`DashboardDataRepository.kt:43-45`、`ForwardingCenterRepository.kt:42-44`）改为读 `ShadowDaos()` 的 `forwarding_deliveries`，否则指标恒为 0
5. **P0-6 回前台补偿同步** —— 改 `MainActivity.onResume` 使两种 UI 模式都执行补偿 + 注册 `USER_PRESENT` 广播，判重基于 P0-5 的新存储。
   **可提前做的独立部分**：`SmsRecoveryWorker.kt:249-256` 的 `UPDATE` → `KEEP`（建议配套 `PERIOD_VERSION` 机制，避免 `KEEP` 导致后续调周期静默失效）

### 第四批（安全类，但改动面大）

6. **P0-3 凭据加解密失败显式化** —— 核心原则：**禁止用 `runCatching{}.getOrDefault(默认值)` 处理加密失败**。涉及 `MultiForwardConfig` 多处与 40+ 处 `getSecret` 调用点，建议用**可空返回**（`encryptOrNull` / `decryptOrNull`）而非抛异常来收敛，SDK 故障期应返回"凭据暂不可用"而非用空串覆盖旧值。另需修 `syncLinkedWeComStreamChannel`（:106-155）在解密失败时的无交互重建

### 第三批（稳定性与正确性）

6. P1-5 恢复 Worker 改 `KEEP`
7. P1-6 规则解析改为逐条容错
8. P1-7 SIM slot 为 null 时的降级策略
9. P1-9 按 channel/instance 记录成功集合，避免重复推送
10. P1-3 `MessagingCache` 加同步封装

### 建议补充的测试

真正生效的 `ForwardingRules.kt: ForwardingRuleEngine` 目前**零单测**，而零调用的 `RuleEngine` 有 9 个用例。建议把测试投入转移到前者。

---

## 八、总体评价

- **功能完整度与可观测性**：较高水平。模块划分清晰（`forwarding`/`remote`/`outbox`/`recovery`/`observability`/`security` 边界明确），`outbox` + `RecoveryEngine` + `ShadowRepository` 三层"软锁 / 幂等 claim / 审计留痕"的设计质量明显高于同类项目
- **核心短板**：**关键失败路径的确定性不足**。项目有安全设计意识（KeyStore AES-GCM、HMAC 脱敏、审计事件），但失败路径一律 `runCatching{}.getOrDefault(默认值)`，把"异常"变成了"静默降级"，反而制造了明文落盘、密文当明文用、凭据被清空等多个 P0 —— **失败必须显式化**
- **耦合痛点**：`MultiForwardConfig` 同时是配置模型、持久化层、加解密层和旧版兼容层（48KB、单文件 100+ 个 KEY），被 Worker/Repository/UI/Receiver 四处直接实例化，导致锁边界与加密语义无法统一收敛
- **状态管理**：状态分散在 Room（权威）+ SharedPreferences（历史/去重/配置）+ 内存三处，缺少统一状态机与超时收敛机制，这是"状态卡死"类问题的共同根因
- **可靠性策略**：偏重"前台服务保活 + 20 秒 sleep 等待"这类 OEM 经验式手段，缺少可降级的分层（FGS → goAsync → WorkManager），在 Android 12+ 后台限制趋严的背景下脆弱性会持续放大

---

---

## 九、第二轮补充审计（架构卷）

### 9.1 权限清单复核

完整 `uses-permission` 共 23 条，逐条核对使用情况：

| 行 | 权限 | 级别 | 使用 |
|---|---|---|---|
| 6-11 | READ_SMS / WRITE_SMS / SEND_SMS / RECEIVE_SMS / RECEIVE_MMS / RECEIVE_WAP_PUSH | 危险 | 均有使用 |
| 12 | WAKE_LOCK | 普通 | 有使用 |
| 13 | REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 特殊 | 有使用（`XXPermissionGateway.kt:111` 等 3 处） |
| 14 | SCHEDULE_EXACT_ALARM | 特殊 | 有使用（`ScheduledMessage.kt:32` 等 4 处） |
| 15 | RECEIVE_BOOT_COMPLETED | 普通 | 有使用（`RescheduleAlarmsReceiver`） |
| 16 | READ_PHONE_STATE | 危险 | 有使用（双卡识别） |
| 17 | POST_NOTIFICATIONS | 危险 | 有使用 |
| 18 | POST_PROMOTED_NOTIFICATIONS | 系统级 | **无效声明**（无对应 API 调用，普通应用拿不到） → P2 |
| 19-22 | FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / INTERNET / ACCESS_NETWORK_STATE | 普通 | 均有使用 |
| 23 | **READ_SYNC_SETTINGS** | 普通 | **零使用** → P2 |
| 24 | CALL_PHONE | 危险 | 有使用（`Activity.kt:60` ACTION_CALL） |
| 25 | **READ_CALL_LOG** | 危险 | **零使用** → P2 |
| 26 | SYSTEM_ALERT_WINDOW | 特殊 | 有使用（`FloatingCodePillManager.kt:37` 等 3 处） |
| 28 | READ_CONTACTS | 危险 | 有使用 |

**注意**：`:23` 与 `:25` 两条零使用权限很可能继承自 `org.fossify:commons` 上游模板（第三方 AAR 的 manifest 也会合并）。**建议删除后在真机跑一遍完整流程验证，而非直接删。**

### 9.2 ACTION_CALL 定级下调（P1 → P2）

`Activity.kt:57-74` 的 `dialNumber` 全部调用点：`ConversationsAdapter.kt:109`（会话列表长按菜单）、`ThreadActivity.kt:459`（会话详情菜单）、`VCardViewerActivity.kt:80`（点击 vCard 号码）。

已排查所有间接触达面：`NotificationHelper.kt` 的全部 PendingIntent（contentIntent / markAsRead / deleteSms / reply）无一指向拨号；`RemoteCommandProcessor` 无拨号分支；`res/xml/` 无 shortcut/deeplink。

→ **不存在被远程指令或通知点击间接触达的路径**，属权限卫生问题而非漏洞。仍建议改为 `ACTION_DIAL`（免权限、多一步确认）。

### 9.3 构建与资源层

- **ProGuard（`proguard-rules.pro`，86 行）未发现关键缺失** ✅：Room 实体由 room-runtime consumer rules 覆盖；Gson 反射的三处类型（`Attachment` / `SimpleContact` / `MessageAttachment`，`Converters.kt:12-14`）在 `:50-52` 均已 keep；`forwarding/plugin/` 是显式 `registerPlugin(...)` 构造而非反射，无需 keep；lark-oapi 模型 `:83-85` 已 keep。→ **Release 包不会因混淆多出崩溃**。
- **[P2] `proguard-rules.pro:63-80`** 用 `-assumenosideeffects` 剥离 Fossify 上游的"修改版/侧载检测"弹窗（`showModdedAppWarning` / `showSideloadingDialog` / `fakeVersionCheck`，对应 `KNOWN_ISSUES_CUSTOM.md` 的 ISSUE-001）。功能上安全（纯弹窗无副作用），但属 **GPL 合规敏感改动，交付/分发时应向使用者明示**。
- **[P2] `app/build.gradle.kts`**：`packaging.resources.excludes += "/META-INF/DEPENDENCIES"` 为绕开 lark-oapi 传递依赖冲突做的全局排除，会连带排除其它 AAR 的同名元数据。建议改 `pickFirst` 或 `merge`。
- **[P2] `res/xml/` 确认缺失**：目录下只有 `provider_paths.xml` / `searchable.xml` / `accessibility_service_config.xml`，**无 `backup_rules`、无 `data_extraction_rules`、无 `network_security_config`**。这直接坐实了备份泄露（`allowBackup=true` 无排除规则）与全局明文 HTTP 无域名白名单两项问题。
- **依赖版本无冲突**：Room 2.8.4 / okhttp 4.12.0 / work 2.11.0 / compose 1.12.0 / kotlin 2.4.10 / AGP 9.3.1 / lark-oapi 2.8.5。
- **密钥无入库泄露** ✅：`.gitignore` 已含 `keystore.properties` 与 `sms-forwarder-release.jks`，`git ls-files` 无命中。

### 9.4 撤销一项担忧

全仓 grep `android:process` **零命中**，即**单进程模型**。因此 `ForwardingHistoryStore`（companion `lock`）、`IncomingSmsService.duplicateLock`、`ChannelRepository.lock` 的进程内同步完全有效，初轮隐含的"跨进程 SharedPreferences 竞争"担忧**不成立，予以撤销**。

### 9.5 本轮定级调整

| 条目 | 初轮 | 本轮 | 理由 |
|---|---|---|---|
| P0-2 短信接收无兜底 | P0 | P0（不变） | 证据已闭合 |
| P0-3 凭据明文落盘 | P0 | P0（不变，**触发面扩大**） | 企微长连接用户仅冷启动即触发，无需用户操作 |
| 备份泄露 PII | P0 | P1（降级） | 已确认为配置问题，且依赖用户是否开启云备份 |
| ACTION_CALL 直拨 | P1 | P2（降级） | 无间接触达面 |
| 跨进程 SP 竞争 | 隐含担忧 | **撤销** | 单进程模型 |
| ProGuard 缺失 | 未评估 | 未发现显著缺失 | 均已覆盖 |
| READ_SYNC_SETTINGS | 待核 | P2（确认零使用） | 与 READ_CALL_LOG 同类 |

**新增 P2 × 3**：`POST_PROMOTED_NOTIFICATIONS` 无效声明、`packaging.excludes` 全局排除、`-assumenosideeffects` 剥离上游检测（合规明示）。

**本轮无新增 P0 / P1。**

---

---

## 十、第二轮补充审计（QA 卷）

### 10.1 P1-8 白名单回退的准确表述

初轮表述为"短信被静默吞掉"，经验证需精确化：

链路：`RemoteSmsCommand.kt:248` 第三级回退选中实例 → `:253` 的 null 检查因此不触发 → `RemoteCommandProcessor.kt:132-138` 返回 `Rejected(NOT_AUTHORIZED_USER)` → `RemoteSmsCommand.kt:280-283` 对 `Rejected` **返回 true**（表示"已消费"）→ `IncomingSmsService.kt:299` 的 `if (!remoteCommandConsumed)` 整段跳过 → `:387` 自动回复同样跳过。补偿路径 `SmsRecoveryWorker.kt:168` 也跳过。

**准确结论**：短信**已**写入 Provider 与本地库并弹出通知（`IncomingSmsService.kt:179`、`:216-231`），用户在 App 内看得到；**但永远不转发**，且转发历史记录的拒绝原因是"转发规则未允许"——**归因误导**，真实原因是鉴权拒绝。自动回复也一并失效。**无任何补救措施**，只有 `config.appendLog` 写入 `remote_sms_command` prefs。

### 10.2 接收与保活链路 —— 未发现问题的部分

- **`RescheduleAlarmsReceiver.kt:26-40`**：`goAsync()` + `try/finally` 配对正确，完整覆盖 BOOT_COMPLETED / MY_PACKAGE_REPLACED / TIME_CHANGED / TIMEZONE_CHANGED，含 `SmsRecoveryWorker.schedule` + `enqueueFullResync` + 保活重启 + 远程源同步。是本项目中可靠性设计最完整的一环。
- **Manifest 前台服务声明齐全** ✅：`SmsKeepAliveService`(:378)、`IncomingSmsService`(:448) 及 6 个远程控制服务均声明 `foregroundServiceType="specialUse"` 并带 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`（Android 14 强制要求），**无遗漏**。
- **WakeLock 配对正确** ✅：全项目仅 2 处（`IncomingSmsService.kt:83-92`、`ScheduledMessageReceiver.kt:26-38`），均 acquire/release 成对；`IncomingSmsService` 还用 `acquire(60_000)` 带超时兜底，无泄漏。
- **远程控制服务自愈机制存在**：6 个 `RemoteControlService` 均 `START_STICKY` + manifest `stopWithTask=false`。
- **15 个 receiver 的 `goAsync()` 普查**：`CallStateReceiver` / `RescheduleAlarmsReceiver` / `ScheduledMessageReceiver` 三处 `finish()` 配对完整；其余 12 个为同步短路径或未做异步派发（其中 `DirectReplyReceiver` 属漏配，已列 P1-15）。

### 10.3 本轮新增汇总

| 等级 | 条目 |
|---|---|
| **P0** | P0-6 回前台默认不做补偿同步（ISSUE-012 根因） |
| **P1** | P1-14 定时短信主线程投递 + 令牌早释放；P1-15 直接回复缺 `goAsync()` |
| **P2** | P2-11 保活服务常驻空 FGS 且静默失败；P2-12 来电转发协程无 WakeLock |

另：P1-7（SIM 规则）严重性上调——决定性原因是 `READ_PHONE_STATE` 从未在主界面申请，导致**默认状态下 SIM 作用域规则 100% 不可用**。

### 10.4 ISSUE-012 代码层结论

`KNOWN_ISSUES_CUSTOM.md` 中 ISSUE-012（息屏后离线、解锁后不同步、通知缺失）原本标注"仍需真机回归"。本轮审计在代码层闭环了排查重点中的两条：

| 排查重点 | 代码层结论 |
|---|---|
| 第 2 条：`goAsync()` 后异步线程是否被终止 | **未采用 `goAsync()`**，走的是 FGS 路径且无异常保护 → 见 P0-2，短信可能永久丢失 |
| 第 4 条：前台恢复时是否只读本地缓存 | **确认为只读本地缓存** → 见 P0-6，`enqueueFullResync` 因早退 return 永不执行，且无解锁广播触发 |

即：ISSUE-012 中「解锁后短信不同步」这一项，**不必等真机回归即可确认是代码缺陷**，可直接进入修复阶段。

---

---

## 十一、修复进度追踪

> 第一至十章为只读审计阶段的产出；本章记录经用户授权后的实际修复情况。

### 11.1 已完成并通过验证

#### P0-1 远程发短信鉴权绕过 — ✅ 已修复，26/26 验证通过

| 改动 | 文件 |
|---|---|
| 新建统一号码匹配器（全项目唯一实现） | `remote/NumberMatcher.kt`（新增） |
| 删除**三份**重复实现（含 `RemoteSmsCommand.kt` 文件级第三份，初轮只识别出两份） | `RemoteCommandProcessor.kt`、`RemoteSmsCommand.kt` |
| `whitelistEnabled` 默认值 `false` → `true` | `RemoteSourceRepository.kt:68/70`、UI `RemoteControlScreen.kt:869` |
| 四处 legacy 构造补 `whitelistEnabled = true`（钉钉/飞书/企微/WebSocket） | `RemoteSourceRepository.kt` |
| 保存按钮改为名单必填；风险文案；三态告警标签 | `RemoteControlScreen.kt` |
| 存量迁移：`whitelistEnabled=false && authorizedUsers.isEmpty()` → 置 true | `RemoteSourceRepository`（`loadFromPrefs` + `persist` **双路径**加固，比原规格更严） |

**验证**：新增 `app/src/test/.../NumberMatcherTest.kt`（34 个用例全部通过）；**26 条回归矩阵 26/26 符合期望**，10 条绕过用例（R-01/02/03/04/05/06/09/13/24/25）全部翻转为"拒绝"，17 条应保持拒绝的未被误放宽。全仓 `numbersEquivalent` 残留 0 处。编译通过。

**关键设计**：非短信来源（Telegram/飞书/企微）走 `equals(ignoreCase = true)` 而非号码归一化（`RemoteCommandProcessor.kt:130`），避免了把字符串型 UserID 滤成空串导致非短信渠道整体失效。

#### P0-6 回前台默认不做补偿同步 — ✅ 已修复

- `MainActivity.onResume` 把补偿同步提到 UI 模式分流之前，原 `:299` 调用删除（避免重复执行）
- `SmsRecoveryWorker` 新增 `enqueueForegroundResync`（60 秒节流，首次必执行）；周期任务 `UPDATE` → `KEEP`
- **设计细节**：新增带节流的函数而非改 `enqueueFullResync` 本身——后者被 `RescheduleAlarmsReceiver` 在开机/包替换时调用，该场景**必须**无节流执行
- **验证**：隔离 worktree（干净基线 `7bcd289c` + 仅应用自身 diff）`BUILD SUCCESSFUL in 3m 43s`
- **附带确认**：Compose 开发版界面**另有**「未设为默认短信应用」提示（会话页可点击跳转的提醒条 + 仪表盘状态卡扣 30 分健康分），因此 `onResume` 早退跳过经典版横幅不构成缺陷

### 11.2 其余 P0 的修复进展

#### P0-2 短信接收链路兜底 — ✅ 已完成（编译通过 2m57s，零 error）

- `IncomingSmsService.enqueue()` 改返回 `Boolean`，**仅捕获 `IllegalStateException` / `SecurityException`**（其余继续抛出，不掩盖真实 bug）
- 两个 Receiver 加 `goAsync()` + 降级路径 `processMinimal()`
- `ensureStarted` 用 `runCatching` **单独兜底**：仅挪进 `try/finally` 只能保证 `pending.finish()` 执行，不能保证降级路径执行——它抛异常会跳到 `finally` 再穿出 `onReceive`，`enqueue` / `processMinimal` 照样被跳过
- 降级路径做**廉价过滤**（白名单短路 / 应用黑名单 / 关键词 / 系统屏蔽号），**跳过 `blockUnknownNumbers` 的联系人查询**（唯一昂贵操作，会触碰 8 秒预算）
- 过滤命中时**仍写 Provider 与本地库，但不入队转发**（保短信不丢 + 尊重用户拦截意图）
- 删除死代码 `processFromReceiver`（内含 `Thread.sleep(20_000)`，超 `goAsync` 10 秒窗口必然 ANR）与 `processIncomingForReceiver`
- **额外修复**：`startForeground` 提前到降级路径之前——否则常驻通知会在"短信已写入系统库"之后、"FGS 真正起来"之前插入，被 `SmsReceiver` 当成一条新短信收下 → 重复入库甚至递归
- **验证方法值得记录**：`isNumberBlocked` 是否读联系人库，不是靠猜，而是解出 `org.fossify:commons:6.1.6` 的 AAR 用 `javap` 反汇编确认——方法体为纯内存比对 + 正则，默认参数在"非默认拨号器"时直接返回空表、一次查询都不做

#### P0-3 凭据降级（第 1 批）— ✅ 已完成

- 抽出 `CredentialCipher` 接口 + `AndroidKeystoreCipher` / `PlaintextCipher`，使 JVM 单测可隔离 Keystore（此前 `AndroidKeyStore` 在纯 JVM 下抛异常，任何涉凭据用例都无法编写）
- `getSecret` 解密失败**不再回退 `stored`**；`saveSecret` 加密失败**保留旧值不写盘**
- 新增 `security/crypto/CredentialHealth.kt` + `SecurityAuditManager` 审计事件
- 修正 2 条既存坏断言；主动排查并修复 3 个清单外调用点（其中 `RemoteSourceRepositoryTest:135` 若不修，会把"修 2 条"变成"修 2 条挂 1 条"）
- 全量单测 79/79 通过

#### P0-5 短信轰炸 · 窗口收窄 — ✅ 已完成

| 常量 | 原值 | 新值 |
|---|---|---|
| 首次回溯窗口 | 7 天 | **0**（常量已删除，等价 `if (firstRun) now`） |
| `MAX_LOOKBACK_MS` | 7 天 | **6 小时** |
| `FULL_RESYNC_LOOKBACK_MS` | 24 小时 | **1 小时**，且不再回拨水位 |

- 首次运行**忽略 `KEY_FORCED_SINCE` 入参**——否则开机广播 / 回前台都会触发 `enqueueFullResync`，新装用户仍会回放 1 小时历史短信（场景 B 的残留路径）
- 深回溯改为一次性 WorkManager 入参，不再持久回拨水位
- `getAll()` → `getAllIds()`（Room 单列投影，消除大库 OOM）
- 水位登记已确认：`markScanComplete()` 无条件写入，即使扫描结果为空
- 新增 `SmsRecoveryWaterMarkTest`（12 条，含源码守卫：断言 `FIRST_LOOKBACK_MS` 不得重现）

#### P0-5 场景 A（破坏性迁移）— ✅ 已完成

- 删除 `MessagesDatabase.kt:95` 的 `.fallbackToDestructiveMigration()`
- 新增 `openOrRecover()`：捕获 `IllegalStateException` / `SQLiteException` → `quarantineDatabaseFiles()` → `DatabaseHealth.markRecoveredFromFailure()` → 空库重启
- **保留现场**：`conversations.db` 连同 `-wal` / `-shm` 一起 rename 为 `.corrupt-<时间戳>`，可事后导出排查；并处理了 rename 失败的情况（不假设一定成功）
- **关键设计**：`buildDatabase()` 里主动调 `database.openHelper.writableDatabase`，把 Room 的迁移/schema 校验异常从"首次 DAO 调用时"提前收敛进 try/catch——**不做这步，异常会在捕获不到的地方抛出，整个降级形同虚设**
- 新增 `helpers/DatabaseHealth.kt`；`private var db` 已补 `@Volatile`（双重检查锁定下缺失是隐患）
- 系统短信库不受影响，会话会从 Provider 重新同步回来

#### legacy 白名单自动回填 — ✅ 已完成（82 全绿）

- `RemoteSmsCommandConfig.knownRequesters()`：从限流记录（`rate_` 前缀）提取历史发件人，复用已有常量、不硬编码
- `enforceWhitelistSecurityDefault` 重写为两分支：取到历史发件人 → 填充并开启；取不到 → 保持关闭（接受全部）
- **作用域限制（工程师补充，关键）**：只对 `RemoteSourceType.SMS` 回填。否则把手机号塞进钉钉/企微的名单（条目是用户 ID / 邮箱）会导致永远匹配不上，**反而制造出正要避免的失效态**
- 惰性读取（无待回填来源时不读 prefs）+ 迁移只做一次
- 新增 3 条单测，含反向用例（无 `rate_` 记录时不得被强行开启）

> **安全副作用（已知并接受）**：`rate_` 记录的是"成功执行过指令的号码"，而 P0-1 修复前任何人发的指令都会执行并写入 → 自动回填的名单**可能包含陌生号码**。仍按用户要求做，因为从"任何人可发"收敛到"少数有记录号码"是数量级收敛，且名单可见可编辑。**但必须让用户知情**（已要求加 UI 提示"名单由历史发信记录自动生成，请核对"），并列入真机回归清单。

#### 仅剩

- **两处 UI 提示**（进行中）：legacy 名单自动生成提示、数据库恢复模式通知
- **P0-4**（待用户决策方向）

> **过程教训**：P0-3 第 2 批曾出现"工程师报告完成、但磁盘代码未改动"的情况（完成报告与派单消息交叉）。此后一律以 `grep` 核对实际代码为准，不直接采信状态报告。

### 11.3 已知遗留（不阻断）

| 项 | 说明 |
|---|---|
| `ChannelRepositoryTest` 2 条 WeCom 用例失败 | ✅ **已修复**（P0-3 第 1 批）。根因有二：① 单测跑在纯 JVM 无 `AndroidKeyStore`，`ForwardingCipher` 加解密返回空串 → chatId 为空 → 不建实例；② 断言自 `3b0f3bd2` 起与实现从未对齐（`init` 无条件 `importLegacyChannels()` 使 `hasLegacyConfigToMigrate()` 恒 false）。修复：注入 `PlaintextCipher` 隔离 Keystore + 修正断言 |
| `PERIOD_VERSION` 机制未做 | `KEEP` 之后若将来调整周期会静默失效，已记入技术债 |
| 白名单开关关闭时名单仍无效 | `isAuthorized = !whitelistEnabled \|\| ...`，关闭即接受所有人。属产品决策，见 11.4 |
| `isCnMobile("18605551234") == true` | 美国号 `+1 860 555 1234` 归一化后为 11 位且以 1 开头，会被判为"中国大陆手机号"。当前无害（`equivalent` 要求严格相等），建议加注释说明仅用于 86 前缀剥离 |
| 分机号分隔符仅覆盖 `,` 与 `;` | `#`、`x`/`ext`、项目自身的 `\|`（`MessagingUtils.kt:279`）不截断，属可用性缺口，非安全绕过 |
| 全局横幅未做 | 存量迁移提示仅在「远程发送」页常驻，未做跨页面全局横幅（需动 `MainActivity`，当时被 P0-6 改动占用） |

### 11.4 产品决策（用户已确认）

1. **白名单开关保留**：开启 → 走白名单；关闭 → 接受全部。**这是要保留的功能，不是漏洞**。关闭时指令本身仍走完整命令校验（格式解析、目标号码、免打扰、限频、幂等 claim），仅跳过发件人身份鉴权这一步。
2. **legacy 导入自动迁移**（不要求用户手动补名单）：对「关闭且名单空」的来源
   - 能取到历史发件人 → 自动填充 `authorizedUsers` 并开启白名单（安全 + 功能不中断）
   - 取不到 → **保持关闭**（接受全部，与旧版一致），由 UI 橙色标签警告
   - **禁止出现"名单空 + 白名单开"的失效态**——那会让来源被 `AUTHORIZED_USERS_REQUIRED` 拒绝、远程发短信完全停摆
3. **默认短信应用提示是否全局化**：尚未决定。目前只在会话页，仪表盘/诊断页仅有标签与健康分扣分。

> **历史发件人数据源**：`RemoteSmsCommandConfig` 的限流记录。prefs 名 `remote_sms_command`，key 为 `rate_` + **已规范化号码**（由 `RemoteCommandProcessor.kt:338` 的 `markExecution` 写入）。遍历该前缀即可取出历史发件人，无需解析日志文本。
> **已知限制**：该记录目前仅覆盖短信来源，钉钉/飞书/企微/WebSocket 来源无历史数据，会落到"保持关闭"分支——这与它们在旧版本的"任何人可发"行为一致，不会中断功能。

### 11.5 待修复 / 进行中

- **P0-3 第 2 批**（进行中）：`saveChannelInstances` 加密失败整批中止；`getSecret` 旧版明文兼容迁移；`PlaintextCipher` 挪到 test 源集
- **P0-5 场景 A**（进行中）：移除 `fallbackToDestructiveMigration`，改为"保留现场 + 只读安全模式"
- **P0-4**（Outbox 653 行死代码）：**待用户决策方向**（下线删除 vs 接线启用）。两套方案的完整清单已备妥，见 11.8

### 11.6 最终验证（已完成）

多位工程师并发编辑过同一批文件（尤其 `SmsRecoveryWorker.kt`），各自报的"编译通过"仅代表执行那一刻，故统一复核。

**结果：全量单测 83 个用例，0 failure / 0 error / 0 skipped**

- **二次确认（2026-09-13 11:36，主理人独立执行，构建环境无并发）**：`EXIT=0`，`BUILD SUCCESSFUL in 5m 27s`，XML 时间戳 Sep 13 11:36
- **首次确认（2026-09-12 22:31）**：同为 83 全绿
- 两次均读取 `app/build/test-results/testCoreDebugUnitTest/*.xml` 实测，非转述

| 测试类 | 用例 | 失败 |
|---|---:|---:|
| remote.NumberMatcherMatrixTest | 23 | 0 |
| messaging.SmsRecoveryWaterMarkTest | 12 | 0 |
| ForwardingRulesV2Test | 9 | 0 |
| ChannelRepositoryTest | 8 | 0 |
| RemoteSourceRepositoryTest | 6 | 0 |
| remote.NumberMatcherNormalizeTest | 6 | 0 |
| remote.NumberMatcherWhitelistTest | 5 | 0 |
| RemoteCommandProcessorTest | 4 | 0 |
| WebhookRequestUrlTest / WebhookTemplateRendererTest | 3 / 3 | 0 |
| HttpConnectionScopeTest / PrivacyAndRulesTest | 2 / 2 | 0 |

`testCoreDebugUnitTest` 可跑通即证明 `compileCoreDebugKotlin` 已通过（前者依赖后者）。

**临时文件已清理**：`p06_final.diff`、`p03_diff.txt`、`p03_ls.txt`、`p05c.txt`、`p05d-test.txt`、`p05probe.txt`、`lead_verify.log` 全部删除，`git status` 仅剩预期改动。

**并发环境说明（重要）**：本次共出现 5 次"构建失败"，**全部为多 agent 争用 `app/build` 与 Gradle 用户目录导致的假失败**（`journal-1.lock` / `fileHashes.lock` / Kotlin daemon `lookups.tab`），无一次是代码错误。判别方法：失败日志中均无 `e: ` 开头的编译错误行。若不加分辨即去"修复"，反而会把正确的代码改坏。

**仍建议人工复核**：`SmsRecoveryWorker.kt` 曾被两个 agent 并发编辑。本次已确认编译与测试通过，但**未逐行通读合并结果**（构建环境并发不稳定，优先保证可构建可测试）。合入前建议由 owner 通读 `:43-70` 与 `:277-302`。

### 11.7 遗留技术债（未纳入本轮）

| 项 | 说明 |
|---|---|
| SMS_DELIVER / SMS_RECEIVED 双发重复入库 | **既有全量路径同样存在的时序问题**，非本次引入。修复需跨广播的进程内锁，超出最小变更范围 |
| `PERIOD_VERSION` 机制 | `KEEP` 之后若调整周期会静默失效 |
| `buildMultiChannelAllowedChannels()` 死代码 | 删除收益低且有风险，仅标记 |
| 降级路径无通知 / 不做转发规则求值 | 已确认取舍：优先保证短信不丢 |
| 长时冻结（>6h）短信不自动转发 | 窗口收窄的副作用。短信仍在系统库、App 内可见，仅不转发。可选方案：开机（`BOOT_COMPLETED`，一次性事件）用更长窗口 |

### 11.8 复审发现（架构师独立复审，2026-09-13）

复审范围：5 个已修复 P0 的实际落地代码。**确认 6 项无问题**，发现 7 个缺陷。

#### 确认无问题

| 项 | 结论 |
|---|---|
| `SmsRecoveryWorker` 水位逻辑（:52-65） | 两个 agent 的改动**未互相覆盖**，`firstRun` / 首装忽略 `forcedSince` / `getAllIds()` 三者自洽 |
| `schedule()` 与 `enqueueForegroundResync` 配合 | `UNIQUE_PERIODIC`(KEEP) 与 `UNIQUE_NOW`(REPLACE) 是不同 unique name，互不干扰；首次调用必定放行，不会被节流误挡 |
| `processIncoming` 全量路径 | 重构后过滤顺序与语义逐条一致，未改坏 |
| `isFilteredCheap`（:719-724） | 与全量路径语义一致（白名单 → 黑名单 → 关键词 → 系统屏蔽号） |
| `saveChannelInstances` 整批中止 | 两阶段确实做到"任一失败不写盘"；`ChannelRepository` **6 处**调用点已正确门控内存态（第 7 处 `:45` 经判断无需条件化） |
| `MessagesDatabase` `@Volatile` + DCL、`RemoteSourceRepository` fail-closed | 均正确，不会产生"白名单开 + 名单空"失效态 |

#### 发现的缺陷

| # | 级别 | 位置 | 问题 | 状态 |
|---|---|---|---|---|
| 1 | **P1** | `IncomingSmsService.kt:73` | `startInForeground()` 无 try/catch。存在"系统接受 FGS 启动、但 `startForeground()` 随后抛异常"的中间态（Android 14+ `specialUse` 未获批、OEM 二次校验）→ `enqueue()` 返回 true 不降级 → 服务崩溃 → **短信彻底丢失**。与 P0-2 是同一个失效模式的另一触发点 | ✅ 已修复（87 全绿） |
| 2 | P1 | `IncomingSmsService.kt:680` | 降级第 4 步无预算门控，且首次构造 `ChannelRepository` 开销是百毫秒到秒级（含 Keystore 逐字段解密），非注释所称"毫秒级"。可能触碰 goAsync 10s 窗口导致广播 ANR | ✅ 已修复 |
| 3 | **P1** | `IncomingSmsService.kt:606-629` + `SmsRecoveryWorker.kt:102-212` | 三处过滤不一致：全量路径命中过滤在写库前 return；降级路径仍写 Provider；**`SmsRecoveryWorker` 完全没有过滤判定**。叠加后用户拉黑的号码会被转发到外部渠道 | ✅ 已修复 |
| 4 | P1 | `MultiForwardConfig.kt:1064-1068` | `looksLikeCiphertext` 的 Base64 启发式误伤遗留明文凭据：**PushPlus token（32 位十六进制）、Bark deviceKey（22 位）、Gotify token（16 位）**均可被 Base64 解码且 >12 字节 → 误判为密文 → 通道静默消失且无提示 | ✅ 已修复 |

#### 修复要点（复审问题 1-4）

**问题 1**：`onCreate` 用 `runCatching` 包住 `startInForeground()`，失败**不中断流程**，继续走 `processIncoming` + 入队补偿扫描。catch 用 `Throwable` 而非具体异常类型（Android 14 `specialUse` 未获批可能抛 `SecurityException` / `InvalidForegroundServiceTypeException`，OEM 还可能自定义）。
- **关键补充**：`onStartCommand` 的 `finally` 里当 `!foregroundStarted` 时**再入队一次**——`onCreate` 那次入队时短信还没写进 Provider，Recovery 扫的是 Provider，那次救不回来；处理完之后那次才真正有效。

**问题 3**：恢复路径补 `isFilteredCheap()` 判定，复用 `IncomingSmsService.isFilteredCheap()`（不新增第三份实现）。命中过滤时**仍 `threadIdsToSync.add(threadId)`**——`syncThreadToLocal` 内部才 `insertMessages`，不加入同步的话 id 不会进 Room，该短信会持续"Provider 有、Room 没有"被后续每轮扫描重复检出。

**问题 4**：加密输出加 `v1:` 版本前缀。**但最终未采用"一刀切前缀判定"**——那会让无前缀老密文在密钥丢失时被当成明文原样返回，等于重新打开 P0-3 的洞。改为：前缀优先 → 回退 GCM 结构判定 → 阈值从 `size > 12` 收紧为 `size >= 28`（IV 12 + Tag 16，AES-GCM 理论最短长度），三类误伤凭据（解码后 12/16/24 字节）全部排除。
- 附带修正：`android.util.Base64` → `java.util.Base64`。前者在 JVM 单测是 Stub 会抛异常被 `runCatching` 吞掉，导致 `looksLikeCiphertext` 恒为 false——**边界用例会变成空跑（恒绿但什么都没验证）**。
- 老密文一次性迁移用独立标记位 `legacyCipherMigrated`，不复用 `CredentialHealth.failedKeys()`（"加解密失败"与"已尝试迁移"是两件事，共用会导致 KeyStore 恢复后拿不到迁移机会）。
| 5 | P2 | `MessagesDatabase.kt:105-118` | quarantine rename 失败后仍对同一损坏库二次建库 → 异常穿透 → 启动崩溃循环 | 下轮 |
| 6 | P2 | `MessagesDatabase.kt:146` | 主动开库把迁移提前到 `getInstance()`。评估：迁移链中仅 `MIGRATION_2_3` 涉及数据拷贝，其余 14 条为建表/加列，毫秒级。**影响面有限，建议补后台预热但不阻断发布** | 下轮 |
| 7 | P2 | `SmsRecoveryWorker.kt:222-224` | `runCatching` 吞掉 `CancellationException`（`REPLACE` 策略会取消同名任务），被取消的扫描被转成 retry | 下轮 |

> **问题 1 的定级取决于分发渠道**：若目标是 Play 上架且 `FOREGROUND_SERVICE_SPECIAL_USE` 未获审批，应升为 **P0**；若仅非 Play 分发，P1 合适。需向用户确认分发渠道后最终定级。

### 11.8b 第二轮全面复查（2026-09-13，所有修复完成后）

复查范围：本轮全部修复（19 文件 +1439/−323）。**确认 6 项逻辑正确**，**1 项未修**，新发现 **2 个 P1 + 5 个 P2**。

#### 确认逻辑正确（架构师逐条验算，非仅核对存在）

FGS 半程失败兜底、降级第 4 步预算闸门、恢复路径过滤、密文 `v1:` 前缀判定、DB 四级降级链、启动预热移出主线程。

密文判定实际代入验算：PushPlus 32 位十六进制 → 24 字节 < 28 ✔、Bark 22 位 → 非 4 倍数解码抛异常 ✔、Gotify 16 位 → 12 字节 < 28 ✔，三者均不再误判为密文。

#### 未修

| # | 位置 | 问题 |
|---|---|---|
| 7 | `SmsRecoveryWorker.kt:237-239` | `runCatching{}.onFailure { return Result.retry() }` 吞掉 `CancellationException`（全仓该文件 0 命中）。实际影响低（block 内无挂起点），但语义错 |

#### 发现的问题

| # | 级别 | 位置 | 问题 |
|---|---|---|---|
| **A** | **P1（本轮新引入）** | `IncomingSmsService.kt:693-712,724-732` + `SmsRecoveryWorker.kt:117` | **预算闸门 × 恢复去重交互洞**：降级路径第 3 步成功写 Room → 第 3 步耗时使第 4 步判定预算耗尽 → 入队恢复扫描 → 恢复发现该消息已在 Room（同 id 空间）→ `id in localIds` → 跳过 → **短信入库但永不转发**。两个机制各自正确，合起来产生新失败路径 |
| **B** | **P1（既有）** | `IncomingSmsService.kt:615-763,788-843` | **MINIMAL 完全不做转发规则求值**（未引用 `ForwardingRuleEngine`）→ 降级期间规则被整体绕过，消息投递到**所有**启用渠道（含被规则明确屏蔽者）。隐私/合规偏差 |
| C | P2 | `:430-442` vs `:777-782` | `blockUnknownNumbers` 仅 FULL 生效（廉价路径不含联系人查询）。**已接受现状**，但需在注释写明已知差异 |
| D | P2 | FULL `:206-210` vs MINIMAL `:644-647` | 过滤短信持久化语义分歧：正常模式完全不落库，降级模式落库。同一短信在两种模式下行为相反。**✅ 已按用户决策统一为 B 方案（都落库但不转发）** |
| E | P2 | `SmsRecoveryWorker.kt:150-156,203-209` | RECOVERY 转发正文未走规则自定义模板（未传 `resolveContent`），与 FULL 不一致 |
| F | P2（P0-3 同类残留） | `RemoteSourceRepository.kt:866-885,269` | `encryptSensitiveConfig` 加密失败时**原样写回明文**且 `persist` 落盘 → Keystore 故障期编辑远程来源会明文入库。**与 `ChannelRepository` 的"整批中止"语义完全相反**——同一应用两套凭据策略，等于 P0-3 只堵了一半 |
| G | P3 | `App.kt:88-90` | 仍主线程构造 `ChannelRepository.getInstance`（含 Keystore 逐字段解密）与 `RemoteSourceRuntimeManager.sync()`。预热只覆盖 Room。第一轮已报，本轮 `App.kt` 被改但未处理此点 |

#### 三路径语义一致性（架构师实测结论）

| 维度 | FULL（正常） | MINIMAL（降级） | RECOVERY（恢复） |
|---|---|---|---|
| 过滤规则 | `isFiltered`（含联系人查询） | `isFilteredCheap` | `isFilteredCheap` |
| 命中过滤后持久化 | **完全不落库** | 落库（Provider + Room） | 补 Room 同步 |
| 命中过滤后转发 | 不转发 | 不转发 | 不转发 |
| 转发规则求值 | ✅ 有 | ❌ **无** | ✅ 有 |
| 转发正文 | 规则自定义模板 | 默认格式 | 默认格式 |

**同一场景三种行为**：取"命中规则 R（只允许渠道 Y）且命中过滤"的场景——FULL 只发 Y；MINIMAL **发全部启用渠道（规则被绕过）**；RECOVERY 只发 Y 但正文用默认模板。

#### 修复安排

| 问题 | 负责人 | 状态 |
|---|---|---|
| P1-A + P1-B + C/D/E + #7 | kou-fix-p0-1 | 修复中 |
| F | kou-engineer | 修复中 |
| G | — | 记入下一迭代 |

> **本轮最重要的一条经验**：P1-A 不是原有 bug，而是**修 P0-2 时新引入的**。"加预算闸门"与"恢复路径补过滤"两个改动单看都正确，但闸门把消息推给恢复路径、恢复路径又因消息已入库而跳过它——**两个正确的修复合起来开出一条新的失败路径**。单元测试全绿恰恰因为没有任何用例会同时走「降级路径 + 预算耗尽 + 恢复扫描」这条组合路径。这是"修完必须交叉复查"的最直接证据。

### 11.9 P0-4 两套方案（待用户选定后执行）

#### 方案 A：下线删除（推荐）

| 类别 | 内容 |
|---|---|
| 删除 | `outbox/` 全目录（6 文件）、`forwarding/plugin/` 全目录、`helpers/OutboxRepository.kt`、`interfaces/OutboxTaskDao.kt`、`models/OutboxTaskEntity.kt` 及 `OutboxTaskType/State/SourceType/Context` |
| `RecoveryEngine.kt` | 删段 1/2/5/6 与 `:40` 的 `outboxDao`；**段 3（SmsSend 超时）与段 4（RemoteCommand 对账）保留——这两段是在用的** |
| `MessagesDatabase.kt` | entities 去 `OutboxTaskEntity`(:55)、去 `abstract fun OutboxTaskDao()`(:79) 与 import(:20)、version 16→17(:58) |
| 迁移链 | **必须保留 `MIGRATION_14_15`**（v14 老用户依赖它建表），新增 `MIGRATION_16_17` 执行 `DROP TABLE IF EXISTS outbox_tasks` 并注册。1→…→15→16→17 全链完整 |
| UI 指标 | 3 处计数（`DiagnosticBundleGenerator.kt:84-86`、`DashboardDataRepository.kt:43-45`、`ForwardingCenterRepository.kt:42-44`）改为读 `ShadowDaos()` 的 `forwarding_deliveries`，文案"Outbox 队列"→"转发队列"。**不改则删完指标恒为 0** |
| androidTest | 删 `OutboxDispatcherTest` / `OutboxMigrationTest` / `SendSmsOutboxExecutorTest` / `ForwardOutboxExecutorTest` / `ForwardChannelPluginTest`；改 `RecoveryEngineTest:111,143`、`RecoveryStateAlignmentTest:225,265`、`DashboardUiTest:84`、`ForwardingCenterUiTest:102`、`UIArchitectureTest:105` |

**风险**：删除后 androidTest 数量下降，但删的是死代码测试，覆盖反而更真实。需 `assembleDebug + lintDebug` 全绿。

#### 方案 B：接线启用

- `App.kt:28-71` 注册 4 个 executor（ForwardHttp / ForwardSms / ForwardPlugin / SendSms）
- 新增 `OutboxDispatchWorker`（PeriodicWork 15min + `KEEP`，对齐 `RecoveryWorker.schedule:55`）
- **唯一防双发送法**：outbox 只做持久化账本，实际发送仍委托 `MultiChannelForwardWorker`（包装型 executor），禁止 `ForwardHttpOutboxExecutor` 与它同时直接发同一条
- 前置：须先补 `MultiChannelForwardWorker` 对 `ForwardingShadowDelivery` 终态的写入，否则 `RecoveryEngine:331-372` 段 6 会把大量 RUNNING delivery 误判 FAILED

**成本提示（重要）**：`MultiChannelForwardWorker` 覆盖 20+ 通道（含企微长连接 / ntfy / Discord / 通道组递归展开），而 `ForwardHttpOutboxExecutor` 只有裸 HTTP POST，`ChannelPluginManager` 只注册 7 个旧 plugin。**接线不等于"接上就能用"**——要么把 20+ 通道再实现一遍，要么做双路互斥，而后者是双发高危区。这也是推荐方案 A 的主要理由。

---

*第一至十章为只读审计产出，所有行号为实际读取确认；第十一章为授权后的修复记录。真机回归仍建议覆盖华为 EMUI/HarmonyOS 与小米 HyperOS 双卡设备。*
