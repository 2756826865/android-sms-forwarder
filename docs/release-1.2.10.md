# 短信转发器 1.2.10

发布日期：2026 年 11 月 13 日 · 版本代码：1220

针对 ColorOS 17、MIUI/HyperOS、OriginOS、Flyme、HarmonyOS NEXT 等国内主流定制系统，进行无死角深度代码审查与并发链路加固，全面提升复杂厂商环境下的通信韧性、保底留存率与长时运行稳定性。

## 主要更新

- **系统短信通知本地入库保底**：
  - 彻底根治 ColorOS 17 / vivo / 小米 等强制收回默认短信角色或屏蔽验证码广播时，系统通知栏弹了短信但 App 对话列表没有的顽疾。
  - 在 `NotificationForwardListenerService` 中，捕获系统短信通知后独立且异步地持久化写入本地 Room 数据库，无论用户是否开启外部通道通知转发，均确保本地短信永不丢失。
  - 双分支平滑兼容：若系统 SMS Provider 允许读取，直接对齐导入真实记录；若系统 Provider 拒绝/隐藏，则采用高位合成 ID 策略（`8_000_000_000_000L` / `9_000_000_000_000L`），绝对避开真实主键范围，绝无主键冲突或溢出。
- **发件人解析与双向号码归一化**：
  - 针对小米、OPPO、vivo 将通知标题设为「信息/短信/通知类短信/验证码」等通用词的场景，深度解包 AndroidX `MessagingStyle`，优先提取 `person.uri`（`tel:` 发件人号码）及 `conversationTitle`，精准过滤系统通用占位词。
  - 升级 `ConversationsDao` 会话查询为「双向智能归一化」，支持带与不带 `+86`、去除首尾空格的智能双向匹配，确保通知保底会话与历史对话 100% 聚合在同一会话流。
- **通知保底双卡识别与单卡自动推断**：
  - 引入 `ReceivingSubscriptionReader.resolveFromBundle`，全面提取 `notification.extras` 中的私有卡槽标识（`subId`、`subscription_id`、`simSlot`、`slot_id` 等）并校验活跃 SIM 卡。
  - 单卡设备自动推断回填唯一有效 SIM 卡槽，消除保底转发时的「未知接收卡」或 `[SIM?]` 标识；双卡设备按私有键精准匹配，无盲猜错乱。
- **并发防重与短时验证码容错**：
  - 广播与通知互通防重窗口精准对齐为 20 秒，本地保底优先以 Provider 真实 ID 判重。
  - 杜绝 30 秒内合法连续请求重发的同正文验证码被误杀，保证高频验证码顺利流转。
- **空白正文与不可见字符严格过滤**：
  - 严密过滤系统通知 `FLAG_GROUP_SUMMARY`（组汇总摘要）。
  - 过滤覆盖全部 Unicode 零宽控制字符（`\u200B-\u200F`、`\u2060-\u206F`、`\uFEFF`）及全角空格（`\u3000`）、窄不换行空格（`\u202F`）、数字空格（`\u2007`）。
  - 模板渲染结果为空时前置熔断拦截，杜绝飞书/钉钉等外部通道出现空白暗色气泡。
- **内存健康、线程守护与大数据量长时保活**：
  - 静态缓存（`recentDeduplicationMap`、`recentBroadcastBodies`、`appNameCache`）设置严格上限与定期淘汰清理机制，防止连续运行 30 天以上内存泄漏。
  - 后台执行线程池（`fallbackExecutor`、`fallbackScheduler`）装配带有未捕获异常处理器（`UncaughtExceptionHandler`）的守护线程工厂，保障工作线程生命周期健康。
  - 会话详情页在大数据量（3 万条以上短信）下严格采用分页上限（`MESSAGES_LIMIT`）与 SQLite 差量写入策略，彻底避免打开超长会话出现卡死或 ANR。
- **超大数据量（3 万条以上）短信搜索性能重构**：
  - 重构 SQLite 短信搜索查询：移除全量 `GROUP BY MAX(date)` 与逐行 `lower(body)` 函数转换，改用索引直出的正序倒序截断，避免 3 万条历史短信每次搜索全表暴力聚合。
  - 增加 300ms 智能搜索输入防抖与分页上限，防止打字过程中连续触发数十次无意义底层全库扫描。
  - 彻底移除 Compose UI 会话搜索过滤中的内联黄页高频计算与 $O(N^2)$ 嵌套诊断循环，彻底消除全量同步 3 万条短信后搜索框输入卡顿、掉帧与无响应。

完整累计变更见 [CHANGELOG](https://github.com/2756826865/android-sms-forwarder/blob/v1.2.10/CHANGELOG.md)。
