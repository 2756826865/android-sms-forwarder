# 短信转发成功但信息页延迟显示：专项检查

日期：2026-10-30（北京时间）。基线 main：`0d5f1af2e623187b03ac0e45801dca69cb1efc4d`。`git diff v1.2.7 main -- app/src/main` 为空，二者生产代码一致。

## 结论与边界

静态检查确认了会话刷新丢请求、最新本地列表等待 Provider、消息与会话同步判定不完整这几处缺陷。它们能解释“转发已完成而页面仍旧”的部分路径，但尚无此次真机的逐阶段日志，不能认定为该手机现象的唯一根因。没有发现 FULL 正常路径故意先转发再写系统短信库。

| 状态 | 源码依据与区别 | 本次处理 |
| --- | --- | --- |
| 系统短信入库 | IncomingSmsService.processIncoming 先 persistWithRetry，再获取 thread_id、markPersisted。persistWithRetry 最多重试 3 次；Context.insertNewSMS 检查返回 URI 和正数 ID，但不证明系统会话聚合或 UI 已完成 | 未改变写系统库和转发排序；真实 Provider 耗时仍需日志 |
| 本地消息与会话同步 | updateLocalDatabaseAndNotify 先查联系人，再 messagesDB.insertOrUpdate、syncThreadToLocal、发布两个事件。消息与会话不是同一事务；syncThreadToLocal 的 Provider 读取允许容错 | UI worker 成功条件同时检查消息和会话；恢复扫描补修已有消息缺少会话的状态 |
| 界面状态与渲染 | MainActivity 将事件交给 ConversationsViewModel.refresh；原先首次加载进行中直接 return，后续 Room 列表仅在当前页面为空时发布，非空旧列表需等待系统查询 | 合并保留首次加载期间的刷新请求；最新非空 Room 快照先显示，再执行 Provider 查询。StateFlow 赋值不等于屏幕已绘制 |

## 接收与补偿路径

1. SmsReceiver 对 SMS_DELIVER/SMS_RECEIVED 使用 goAsync，先尝试交给 IncomingSmsService；启动被拒时 processMinimal 兜底。FULL 服务由单线程 executor 处理，MINIMAL 有约 8 秒预算。
2. FULL：过滤只决定通知/转发，不阻止入库。系统写入成功后登记指纹和恢复水位；本地同步/通知抛错则安排 IncomingSmsUiSyncWorker 和 SmsRecoveryWorker，再继续规则与转发。这保证本地失败不会强制等待 UI 才转发。
3. MINIMAL：先尝试系统写入；非默认角色或写入失败时仍可能基于广播转发，并异步寻找系统稍后写入的短信。本地步骤被预算跳过、失败时排队 UI worker。因此此路径的“转发先显示”也可能是正常降级，而非 FULL 顺序错误。
4. UI worker：只读系统库，在收到时间前 60 秒至后 120 秒范围按发送者/正文哈希和可用订阅 ID 匹配；每次最多检查 256 行，4 次短轮询，相隔 250ms，之后 WorkManager 指数退避（基数 10 秒，最多 12 次重试）。调度、权限、OEM 后台限制都可能使修复延后；不是实时显示保证。
5. 原 UI worker 只验证 messages 行；而 ConversationsDao.getAllRegularWithLatestSnippet 以 conversations 为主表，只有 messages 行时不会显示任何会话。新增回归用例模拟此状态：基线返回 success，新增断言失败；修复后返回 retry。不能把消息已存在等同于整个本地同步完成。
6. 原 SmsRecoveryWorker 对“已在本地且已登记转发”的消息直接跳过同步；过滤分支也仅在消息缺失时同步。现在每个 thread 只查询一次会话存在性，缺失就加入去重同步集合；原转发判重、过滤、命令执行分支不变。
7. MainActivity 的 HomeRefreshGate 已将暂停期间的刷新合并到 resume。经典主页 getCachedConversations 先显示 Room 再查询系统，并会从孤立消息重建会话；本次 ViewModel 修改仅影响 useGatewayDeveloperUi 路径。ThreadActivity 则另走 getMessages 与消息页刷新，需单独真机观察。

## 已确认的刷新竞态

首次加载取到旧 Room/Provider 快照后，新短信写入并发送事件。如果 initialLoaded 仍为 false，原 refresh 直接丢掉该事件；旧快照随后赋值，直到下次事件/回前台才刷新。现在只记录一次待刷新，并在当前加载正常结束后启动下一轮，不反复取消首次加载。

已有列表时，原逻辑即使读到更新的 Room 快照也不发布，而是等待 getConversations、联系人读取以及可能的同步完成。本次先发布非空快照，保留原有 300ms 合并窗口及最终 Provider 合并；空快照仍不会被当作删除清空屏幕。连续事件仍可能取消正在进行的刷新，本次不宣称完全消除所有调度延迟。

## 防重复、遗漏和回归范围

- 本次未增加任何系统短信 insert/update/delete，未更改指纹窗口、短信主键、订阅解析、验证码提取、发送逻辑或转发 workId；UI worker 仍不转发、不执行命令。
- Room 消息和会话均按既有主键 REPLACE，重复本地同步不会凭空增加相同 ID 的消息；SQL 回归覆盖重复同步及回收站已有会话接收新消息。
- 不能据此宣称系统库无重复：wasPersisted 与 markPersisted 分别持锁，并非跨路径的原子“检查+插入”；系统插入已提交但客户端收到异常时重试也存在重复风险。这些是既有风险，尚无本次日志证实发生，未在 UI 修复中改写接收去重语义。
- 系统库没有原始短信时，恢复扫描无法凭空重建；广播兜底转发也不证明系统短信已保存。UI worker 搜索窗口、重试上限和恢复扫描水位/历史限制仍可能导致补偿遗漏，不能作完整性保证。
- UI worker 的新增条件验证会话存在，不验证全部会话字段新鲜度；新旧快照并发覆盖、联系人查询卡顿、OEM 短信会话聚合延后仍需设备日志。

## 验证

- 生产 IncomingSmsUiSyncWorker 经 Kotlin 编译后在模拟 Android/WorkManager/Room 边界运行：17 个场景通过；新增“消息已存在但会话缺失”用例在基线失败、修复后通过。还覆盖权限撤回、空游标、Provider 延迟、双卡不匹配、取消传播、无效 ID、无操作同步和任务 KEEP。
- 六组既有 Python/SQLite 检查共 39 项通过，包括新增 3 项使用实际 DAO 查询的会话可见性/重复写入/回收站接收新短信测试。
- git diff --check 通过。
- 本地完整 Gradle 单测未启动：Gradle 9.7.0 下载报 Network is unreachable，且未发现 Android SDK。主机测试能获取 Kotlin 依赖并运行，不等于 Android 工程编译通过。
- 首次刷新竞态、Compose 状态发布以及恢复扫描的完整分支目前为静态验证；新增 SQL/worker 测试不等于端到端 UI 或真机验收。

## 真机验收与定位标准

使用脱敏日志记录同一 providerMessageId/threadId/subscriptionId，在默认短信角色稳定时分别测试经典/开发主页：冷启动同时收信、前台停留、打开会话、后台/锁屏、恢复前台、权限撤回与恢复、双卡、连续和长短信、联系人读取慢、Room 会话同步失败、转发成功后进程被杀。

按同一消息对齐以下时点：广播到达；Provider insert 返回；按 ID 实际读到系统行；Room 消息行/会话行均可查；refresh_cached_before_provider；viewmodel_final/status=state_assigned；Compose 实际绘制。现有 SMS persisted、ShadowRepository 的 providerInsertedAt/LOCAL_SYNC_OBSERVED 及 SmsSyncDiagnostics 能辅助，实际绘制须设备侧测量；现有日志不足以精确得到所有时差。

- 系统行出现晚：系统入库/接收处理阶段延迟。
- 系统行及时但 Room 消息或会话晚：本地同步延迟，检查联系人、异常、补偿排队/退避。
- Room 均及时但状态发布晚：事件、生命周期或列表查询阶段延迟。
- 状态及时但屏幕晚：主线程/Compose 渲染阶段延迟。

同时验收短信数量、正文、线程归属、未读状态、双卡标签、验证码提取/悬浮显示、普通回复、系统发送状态、过滤与断网补转发；确保不会因为本地修复重复转发或执行远程命令。本次没有以等待屏幕绘制作为转发条件。
