# 1.2.0 本地夜间修复记录

## 执行约束

- 基线 adcb03a7，保留已有全部修改；禁止 git push、云端发布或替换正式 APK。
- 2026-10-02 晚用户已授权自主修改、界面优化、运行测试与本地 APK 构建，无需等待回复。
- 用户实测正常：微信智能机器人、PushPlus、企业微信、钉钉。无证据不调整这些发送路径。
- 接续自动任务已建立（每小时，最多 8 次）；先检查当前进程和日志，避免并发改文件。

## 本轮状态

- 已安装 Java 25、Android SDK Platform 37.0、Build Tools 36.0.0；local.properties 指向 /tmp/sms-android-sdk（未提交）。
- 首次完整构建：:app:testCoreDebugUnitTest、:app:assembleCoreDebug、:app:assembleCoreRelease 全部成功，156 个测试零失败。
- 2026-10-03 当前源码最终重跑成功：`-Pksp.incremental=false :app:testCoreDebugUnitTest :app:assembleCoreDebug :app:assembleCoreRelease`，176 个测试、22 个套件，零失败/错误，`BUILD SUCCESSFUL in 8m 23s`。KSP 增量快照问题仅通过构建参数绕过，未改项目依赖。
- 本轮构建日志要点：首次因 Java 代理证书缺失无法解析 AGP 9.3.1；导入当前环境代理 CA 后进入配置；第二次因 Android 37.0 许可证未落盘停止；补齐许可证与 SDK 后第三次完整成功。两次环境失败均未当作源码测试结果。
- 接续任务先检查 Gradle 进程，避免同时构建或在编译过程中改源码。

## 已完成修改

- WxPusher：新增实例注册、配置、规则通道、测试和正式发送，共用发送器，按业务码与目标级回执判定任务创建。
- SMTP 编辑器：端口与 SSL/STARTTLS 选择、无效配置禁用测试、端口解析防崩溃。
- Qmsg：缺少成功字段不能当作成功；OneBot 校验 status/retcode。
- PushPlus：测试与正式发送共用正文生成和回执检查；正文 HTML 转义；测试提示接口已受理。
- Bark：测试改为与正式转发相同的 POST JSON；提示受理而非设备收到。
- ntfy：共用 Topic 格式校验和 RFC 2047 中文标题编码。
- WebSocket 测试补齐正式发送已有的 X-Token 请求头；提示仅加入发送队列。
- 发现 Server酱³ 配置、发送已存在但添加通道列表缺少定义，已补入口；标签说明由逗号纠正为官方竖线分隔。
- Server酱³：测试与正式发送统一要求明确的业务回执 `code == 0`，不再把 HTTP 风格 `200` 或含 success 的错误文本误判为服务端受理。
- 飞书长连接：按官方事件元数据只剥离命令开头的 mention token，保留短信正文中的普通 @ 内容；群聊“必须 @ 机器人”改为将事件 mention 的 `open_id` 与 Bot v3 信息接口返回的当前机器人 `open_id` 精确匹配。
- 飞书安全降级：无法取得机器人身份时不放宽群聊权限，只提示群聊 @ 校验暂不可用，单聊仍可处理；连接异常不再仅因错误文本含 `invalid` 就永久停止重试。
- 会话列表：连续刷新取消旧任务并做 300ms 合并，协程取消不再覆盖新结果，临时读取失败保留已有列表。
- 大盘与消息中心：刷新失败且没有旧成功数据时进入可重试错误态，避免页面一直停在 Loading。

- 已补 mutableIntStateOf 导入，解决编译错误。
- 移除列表隐藏旧通道的过滤器，保留所有旧实例和目录。
- 邮件教程明确通用 SMTP、多个收件人及 OAuth-only 不支持。
- 大盘/短信页正确传播协程取消，避免取消误报失败、旧加载覆盖刷新。

## 待处理

1. 飞书精确 @ 鉴权仍需真实飞书企业应用验证 Bot v3 权限、群聊 @ 当前机器人、@ 其他用户及断线重连场景；当前仅完成官方字段核对、编译与单元测试。
2. SMTP 已统一测试/正式发送：多行响应完整性、地址注入校验、RCPT 251、TLS 主机验证和 DATA 已受理后的 QUIT 失败处理。新增 5 个测试已通过。
3. 对未实测渠道继续比较 Apprise 插件参数、鉴权、格式、回执、限制。
4. 导航栏、规则预览、大盘刷新和历史加载必须把静态证据与真机通过分开。
5. 网络响应丢失后的投递不确定状态需谨慎设计，避免引入重复发送。

## 验证

- git diff --check 当前通过。
- 无真实短信、远程命令或对外消息发送。
- 最终 176 个单元测试通过，Debug 与 unsigned Release 均 BUILD SUCCESSFUL。Debug 已通过 apksigner 验证，package `com.helyu.smsforwarder.debug`，v1.2.0 / 1200，min26 target37；签名为 Android Debug，v2 签名有效。
- 当前 Debug APK：`app/build/outputs/apk/core/debug/SMS-Forwarder-1.2.0-core-debug.apk`（2026-10-03 本轮重建）。Release 为未签名产物，不能覆盖正式版。
- Debug APK SHA-256：`bab47914290216d32bb6c7985174d30db63cbeb4317299d6f9ff028b62356477`；unsigned Release SHA-256：`c12cd76a4319469c18114174391f832c5731a22a0f5ef9949710310b7d28bdc1`。
- 未配置原正式版签名。Debug 包可并行安装，不能冒充可覆盖正式版的更新包。
- 三键导航采用父容器统一 systemBars/IME inset，核查 commons 实现确认有底部 padding；仍无真机验证。
- 飞书 mentions.key 自带 @ 的问题已修复并覆盖 6 个解析测试；机器人身份解析与精确匹配另有 5 个测试。不得把任何提及误当作机器人授权依据。

## 1.2.1 后续修复（2026-10-03）

- VERSION_NAME=1.2.1，VERSION_CODE=1201；保留既有修改。
- 备份 schema v2：补实际规则、实例 ID 与配置（便于保持规则目标关联）、SMTP 密码/安全模式/启用状态；实例按 ID 合并并重新加密。兼容旧 JSON。拒绝空对象与未来 schema；凭据健康失败时阻止导出，避免产生缺失凭据的备份。导入后刷新规则与通道缓存。
- 备份仍未覆盖全部配置：远程来源、自动回复详细规则、界面偏好等待补齐；旧备份遗漏的数据无法恢复。当前导入并非跨配置事务，后续节失败可能已应用前面的配置。剪贴板导出明确包含凭据。
- Root/Shizuku 七项固定动作加入对应只读状态验证，成功需执行和回读都满足条件。厂商不支持或回读失败计失败；开机自检与自动重修、UserService 替代反射仍未实现。Shizuku 激活失效并不等于持久设置全部丢失。
- 当前 1.2.1 测试/构建尝试在 Gradle 下载阶段失败：Network is unreachable，未进入源码编译，没有新 APK。此前 176 测试和 1.2.0 APK 只代表前一批源码，不覆盖当前导航、Qmsg、备份与回读修改。日志 /tmp/sms-121-build.log。
- git diff --check 通过；未推送、未发布或替换正式 APK。

## 1.2.1 功能与大盘关联复查

- 大盘转发概览改用与流水相同的 ForwardingHistoryStore，排除测试。标题和提示明确：按短信接收日期统计，最多 200 条保留流水，不是全量日累计；请求受理不等于收件端送达。此前影子统计只查 DELIVERED/RUNNING，而业务侧记录 QUEUED/attempt SUCCESS，导致显示零或与流水不符。
- 修正 Worker 影子关联的实例键：接收侧使用实例 ID，Worker 原使用 instance_ID，现统一实例 ID。未改变已实测通道的 HTTP 请求。
- 新增大盘“已同步收到”短信数量：来自本地短信表，与短信列表同库，排除 MMS、定时记录和回收站；明确只代表已同步记录，不承诺能恢复系统未存储的通知验证码。显示发送未知数量。恢复事件总数改 COUNT(*)，不再最多 100 条。
- 消息中心已送达片段数按 deliveredState 判断，避免用裸 resultCode=0 覆盖明确失败状态。
- 通道组测试加入路径循环检测和 16 层限制，直接重复成员只测试一次；有成员失败时组测试与历史均失败，保留各成员结果。
- 备份继续补齐远程来源（凭据、实例 ID、白名单、限额、静默时段、自定义指令前缀）、自动回复完整规则。远程来源跨设备恢复重新加密，连接状态与历史时间不迁移，保存后由原运行时重新连接。ENC 未解密凭据拒绝导出/导入。新增三项远程备份测试，尚未运行。
- 导入已提前校验已知段的结构和数值/布尔字段，但仍不是跨多个 SharedPreferences 的原子事务。加密/存储中途失败仍可能局部应用；需要后续统一导入事务或回滚方案。界面偏好、短信正文、历史流水不属于当前配置备份。
- 通道目录/实例配置/测试/Worker 分支已静态交叉核对。通道组缺 hasDispatchConfiguration 判定，不能直接放开自动转发，否则与成员默认转发可能重复发送；需先设计组与成员路由去重，列为剩余项。
- 目前只有用户反馈的微信智能机器人、PushPlus、企业微信和钉钉具备真实通道反馈；其余通道仍需凭据及真实端验收。
- git diff --check 通过。Gradle 分发缓存缺失，外网下载不可达，未执行新增单元测试或编译。此前 APK 不包含本节修改；没有产生 1.2.1 APK，未推送。

### 通道静态完整性矩阵（22 个配置类型，含通道组）

代码分支存在不等于接口或真机测试通过；别名不重复计数。用户反馈的正常通道未改动其请求协议。

| 配置类型 | Worker/展开入口 | 实例测试入口 | 最低配置判定 |
|---|---|---|---|
| WxPusher 消息推送 | 有 | 有 | 有 |
| PushPlus 微信推送 | 有 | 有 | 有 |
| 微信公众平台测试号 | 有 | 有 | 有 |
| 企业微信群机器人 | 有 | 有 | 有 |
| 企业微信智能机器人 (长连接) | 有 | 有 | 有 |
| 企业微信自建应用 | 有 | 有 | 有 |
| 钉钉群机器人 | 有 | 有 | 有 |
| 飞书群机器人 | 有 | 有 | 有 |
| 飞书自建应用 | 有 | 有 | 有 |
| 邮件推送 (SMTP) | 有 | 有 | 有 |
| Telegram 机器人 | 有 | 有 | 有 |
| QQ 消息 (Qmsg/OneBot/NapCat) | 有 | 有 | 有 |
| Bark (iOS) | 有 | 有 | 有 |
| Discord 群机器人 | 有 | 有 | 有 |
| 自定义 Webhook | 有 | 有 | 有 |
| WebSocket 客户端 | 有 | 有 | 有 |
| Gotify 自建推送 | 有 | 有 | 有 |
| ntfy 推送 | 有 | 有 | 有 |
| Server酱³ | 有 | 有 | 有 |
| 腾讯云自定义告警 | 有 | 有 | 有 |
| 短信直发 (SIM) | 有 | 有 | 有 |
| 群组聚合消息 | 有 | 有 | 待补 |

- 本轮另修短信 Provider/缓存合并：Provider 新值优先、按 (id,isMMS) 合并、缓存遵守分页与定时排除条件，排除回收站缓存；全量同步完成触发大盘刷新。新增 3 项合并测试。仅修读链路，未迁移本地 messages 表的单 id 主键；SMS/MMS 本地持久化身份冲突仍需单独迁移设计。
- Gradle 与 JDK25 已恢复，补齐官方 SDK 平台本地元数据后，当前验证日志为 /tmp/sms-121-verification.log；本节最终结果待构建结束补记。

### 本轮最终验证结果

- 1.2.1 / 1201 最新源码：`:app:testCoreDebugUnitTest :app:assembleCoreDebug` BUILD SUCCESSFUL（6m 5s）。25 个测试套件、193 项测试，失败/错误/跳过均为 0；包括新增 ThreadInsets、Qmsg、远程来源备份和短信来源合并测试。
- APK：app/build/outputs/apk/core/debug/SMS-Forwarder-1.2.1-core-debug.apk，45,569,228 bytes；SHA256 `67e1c958ecd2673b50bcf43e62a3cb6ad973669fa158b09f794581991d44831f`。包名 com.helyu.smsforwarder.debug，minSdk26，Debug 签名 v2 验证通过。测试包可与正式版并行安装，无法覆盖正式版；本轮未构建签名 Release。
- 签名证书 SHA256：fe86dde90e1c82a2d658d0800cb8159747c2ad5374dc23d6e19f77a497b1127b。正式签名不可用，不能承诺覆盖此前其他签名的测试包。
- 之前“依赖不可达/尚未编译”是本轮早期状态；已恢复 Gradle、JDK25、代理 CA 与官方 SDK 本地元数据，最终测试与 Debug 构建均通过。完整日志 docs/logs/1.2.1-core-debug.log。
- git diff --check 通过。未向真实外部通道发送验收消息，未运行真机/模拟器 UI 检查；三键与键盘适配仍需手机确认。未推送 git、未发布、未替换正式 APK。
- 剩余：配置导入跨存储事务/回滚、界面偏好备份、通道组自动路由去重、SMS/MMS 本地表复合身份迁移、Qmsg 消息 ID 持久化及最终回执、Shizuku 重启后自检与自动重修。

### Root 增强续修（2026-10-03）

- 修正 Root 管理器过期的只读注释，诊断补 SEND_SMS，角色诊断统一 current user。
- 新增 RootMaintenanceWorker：总开关开启后注册 15 分钟最佳努力诊断，关闭取消；应用启动和已有开机/升级广播重新注册。只执行固定只读 Root 命令，不自动修改默认短信角色。
- 有 READ_SMS 时补同步近一天短信涉及的会话，复用 Provider → Room 路径及列表刷新事件；不调用恢复 Worker 的历史补转发、通知、远程命令。诊断保存本地 root_maintenance，不保存短信内容。没有 READ_SMS 时记录受阻；尚未实现 Root 跨权限补读。
- 小米/Redmi、华为、荣耀、OPPO/OnePlus/realme、vivo/iQOO、魅族加入品牌诊断建议。这是建议识别，不是已验证的厂商设置自动修改。
- 尚未完成：系统外独立 Root 守护、用户主动停止后的守护暂停机制、厂商私有设置适配、发送确认处理、小米网络短信适配。没有官方 APK/真机证据，未写入猜测的私有命令。
- 本次 git diff --check 通过；环境原 /tmp/sms-android-sdk 已不存在、当前 Java17，未进行本次编译或测试。前文193测试与APK属于此前源码，不覆盖本次 Root 改动。

### Root 第二批实现与边界

- RootWatchdog 新增独立 detached shell 实验守护，双开关（Root 增强+后台保活）控制，每60秒只检查应用进程；不存在时尝试启动既有前台保活服务，不打开界面、不发送短信。检测当前用户 package stopped=true 后退出，不清除强行停止状态。锁目录与PID防止常规重复启动，关闭开关删除运行标志；开机依赖现有广播+维护任务重新安装守护，不写入 Magisk/KSU 全局启动目录。
- Root 已授权而 READ_SMS 缺失时，使用当前用户 pm grant 与 READ_SMS AppOps 尝试恢复，并检查运行时权限，随后仍走系统 Provider → Room 同步。这是权限恢复方案，不是无授权跨进程数据库读取；厂商拒绝时不会宣称补读成功。
- 开关文案明确权限恢复与守护条件；手动 Root 修复也尝试恢复读取权限，关闭后台保活停止守护。
- 尚未证实的厂商私有设置与免发送确认没有写入代码；现有标准 SEND_SMS 放行不能等同于厂商免确认。必须分析对应 ROM/官方APK并真机验证；不承诺所有品牌有效。
- 编译尝试：compileCoreDebugKotlin --offline --no-daemon 在 Gradle wrapper 下载阶段因 Network is unreachable 失败，尚未进入源码编译。当前APK不含本轮修改。守护脚本实际兼容性、nohup可用性、root服务启动权限、系统停止状态格式、SELinux与重启恢复均需真机验收。

### 会话输入栏手动位置调整

- 会话顶部姓名/手机号共用连续5次点击入口，相邻点击间隔不超过1.5秒，打开输入栏位置调整弹窗。
- 上移/下移每次4dp，范围0–160dp，恢复默认0dp。偏移叠加到既有自动导航栏/IME避让；下移不能低于自动安全位置。
- 实时请求insets重新布局，保存为全局thread_layout/composer_offset_dp；取消或点外部关闭回滚预览。所有会话后续打开时读取，重启保留。
- 保留原自动避让，无translation遮挡列表；列表与输入栏使用同一安全布局区域。未进行真机/模拟器验证，本次无新构建APK。静态差异检查通过。

### 功能解释第一批：短信链路与规则

- ForwardingRuleDecision 新增 diagnostics 默认字段，实时测试显示 SIM/卡槽未知、时段跳过、逐项条件 ALL/ANY、号码/正文匹配结果、无效正则。解释不包含输入号码、正文、条件值。不改变发送匹配顺序和判定逻辑。
- 修正免打扰结果文案：时段检查在正文条件之前，不能声称规则已命中，只说明有规则被时段跳过。
- 运维页新增按需短信链路诊断，读取最近10个operation、每条最多100个实际步骤；关联缺失仅显示未观察，不判定业务失败。不显示号码、正文、凭据、原始detail。只读DAO查询，不迁移数据库。
- 新增2项规则解释单元测试（未执行）。静态 diff --check 通过；构建环境依赖下载仍受阻，前一APK不包含本轮内容。
- 尚未落地：完整通道回执详情页、备份恢复预览、长连接/Root实时健康页、短信同步进度。现阶段链路页展示实际步骤，不保证每条短信都已完整记录，也未展示渠道attempt详情。

### 诊断与备份第二批

- 备份导出不再直接复制：先显示实例、规则、远程来源和自动回复数量，用户点击复制后输出。恢复先解析结构并预览数量与覆盖语义，再明确确认写入；未包含字段提示保留。预览不输出凭据值，不取代完整语义校验，不宣称跨存储原子恢复。
- 运维链路卡增加最近10条通道attempt的状态、HTTP状态与有起止记录时的耗时；未记录不推断成功。尚无逐条业务回执/最终设备送达能力。
- 后台健康显示远程来源应用记录中的启停/连接状态、最近消息时间，订阅sourcesFlow更新；Root仅显示最近诊断时间，不把文件存在等同于守护正在运行。
- 尝试通过配置代理重新编译 compileCoreDebugKotlin，Gradle9.7分发下载仍 Network is unreachable，未进入编译，无新APK。diff --check通过。
- 未完成同步进度、连接重试次数、守护实时存活核验、逐通道业务回执详情；需后续落地与测试。

### 同步进度与守护心跳

- MainActivity 本地历史补同步发布按会话进度（完成/总数/失败），运维页订阅显示；单会话异常计失败继续处理，取消异常重抛，finally复位运行状态。仅全部成功时标记fullHistorySyncedV2，避免失败后永久跳过全量同步。该进度不是逐短信进度，尚未覆盖RecoveryWorker与Root补同步，进程退出后不保留。
- 守护脚本每轮写入秒级心跳，诊断刷新显示最近90秒心跳/过期/未启动；时间变化或无心跳均不判定存活。关闭删除心跳与运行标志。PID启动防重同时检查cmdline，避免PID复用误认为自身守护。
- 静态diff检查通过。本轮尝试curl获取Gradle分发已开始传输；尚未得到新测试或编译结果。
- 环境恢复进展：Gradle9.7分发已成功下载并解压，本地Gradle已能启动；编译新阻塞是JDK25工具链HEAD下载失败。尝试curl恢复JDK25。守护模板替换为测试占位参数后执行sh -n通过，这仅验证shell语法、不执行守护，不代表Android实际兼容。
- JDK25已恢复，Gradle进入项目配置；最新编译在解析com.android.application 9.3.1插件阶段失败（13秒），没有进入Kotlin编译。日志docs/logs/root-diagnostics-compile.log。不能把新功能称为构建通过。

### 优先三项：配置体检、队列、发送前兜底

- 配置体检只读检查启用实例最低配置、规则目标删除/停用、无目标规则、通道组缺成员/失效成员、循环或超16层；不输出凭据。运维诊断刷新执行，未覆盖每个渠道业务凭据有效性。
- 待发队列读取实际MultiChannelForwardWorker WorkInfo ENQUEUED/RUNNING计数，不用保留200条历史推断积压。已有联网约束调度继续保留；待调度不全部标记为断网。未新增验证码到期丢弃策略。
- 运维页为前20个实例提供备用通道选择/关闭。独立forwarding_fallback配置；仅主实例删除、停用、缺配置且未发请求时，创建KEEP唯一一次备用Worker。备用必须启用、配置完备，禁止SMS_DIRECT/通道组、自身；fallback_hop禁止连锁循环。备用继承原消息与operation、规则关联，不改正常成功路径。
- 未实现请求后的明确业务拒绝兜底；HTTP超时/响应丢失均不切换。备用设置尚未纳入导出备份；界面布局、Worker执行和编译尚待验证。diff --check通过。

### 最新构建验收结果

- 恢复Gradle9.7、JDK25、SDK37.0/BuildTools36及当前动态代理。首次编译发现SmsChainDiagnosticsCard在joinToString非挂起回调中读取DAO，已改为协程内for循环读取。随后增量产物产生重复声明报错，clean并禁用Kotlin增量后消失。
- 最新命令 :app:clean :app:assembleCoreDebug :app:testCoreDebugUnitTest -Pkotlin.incremental=false：BUILD SUCCESSFUL，2m3s，50项任务全部执行。
- 25套件195项测试全部通过，失败0、错误0、跳过0。APK签名验证通过；最新独立副本artifacts/SMS-Forwarder-1.2.1-core-debug-latest.apk，SHA256 a6ee664f2f4b20805fcaeec22915771c2976b0dcbdaa8ec6bb9ada5993c66e9d。日志docs/logs/latest-build-clean.log。
- 此结果覆盖本轮所有当前源码新增功能，替代此前未编译状态；仍不代表三键导航、Root守护、厂商适配或外部通道真机实测通过。Debug测试包，不替换正式APK，不推送云端。

### 运维布局、文件备份与两版入口对齐

- 开发版运维增加“备份与恢复”，通过系统文件选择器导出JSON/从文件恢复；导出和恢复先预览，确认后执行。读写在IO线程，导入文件上限2MiB，取消不写配置；沿用ConfigBackupHelper，不声称完整应用备份或跨存储原子事务。
- 运维链路区域改为独立主题圆角卡片（同步、配置、队列、影子链路、请求、Root、备用、连接），长内容可展开；滚动底部额外24dp留白。未做设备截图/交互验收。
- 运维补“打开完整设置”，让开发版能访问经典版拦截、回收站、显示密度、灵动岛、全量重新同步和关于。未强行重写原有设置行为。
- 已核实规则模拟输入updateTestInputs立即runLiveTest，计算当前草稿并渲染，不实际发送。
- 完整入口对照及剩余差异：docs/classic-developer-parity.md。两版共用业务不等于所有控件完全一致；备份仍不包含全部设置和备用通道独立配置。
- 最终本地 :app:assembleCoreDebug :app:testCoreDebugUnitTest -Pkotlin.incremental=false BUILD SUCCESSFUL（1m4s），195项测试失败0/错误0/跳过0。日志docs/logs/operations-parity-final-build.log。未推送，本轮未构建正式签名包。

### 1.2.1 配置恢复与开发版功能补齐（本次提交）

- 经典设置与开发版运维共用文件备份入口，可导出/导入 v3 JSON；保留剪贴板兼容。新增备用通道、SIM 名称与号码、常用转发及界面设置；v1/v2 导入仍兼容。JSON 含凭据，导出前提示用户保管。备份不包含短信内容、系统授权及全部设置项。
- 恢复前创建 Android Keystore 加密本机快照和待恢复日志；写入失败或启动发现未完成日志时回滚配置。对远程命令及远程来源只回滚配置键，保留同时发生的执行防重和限流事实；快照损坏或密钥不可用时保留日志并暂停后台初始化。多存储恢复仍非跨库原子事务。
- 开发版运维增加原生拦截名单、回收站会话、显示密度及灵动岛设置；信息页支持已同步短信正文的本地会话搜索，区分普通刷新和前台全量同步。全量同步与旧入口共用防并发状态，不重复转发历史短信。
- 运维诊断以独立状态卡片显示最近检查时间、有限长度日志和相关设置快捷入口；实际通道/设备送达仍需外部实测。
- `:app:assembleCoreDebug :app:testCoreDebugUnitTest --offline --no-daemon -Pkotlin.incremental=false`：BUILD SUCCESSFUL，208 项测试通过，失败/错误/跳过均为 0。日志 `docs/logs/backup-parity-complete.log`；`git diff --check` 通过。构建环境只读主目录时使用 `/tmp/sms-gradle-home` 缓存；Kotlin daemon 无法写主目录后回退进程内编译，构建结果仍成功。按用户要求跳过真机验收。

### 同步提示和输入栏位置弹窗复查

- 对照23:15:42与23:15:53截图，同步218/319属于运行中快照，11秒后的页面已回到空闲；常驻的是搜索范围说明与全量同步入口。空闲时收起这两行，搜索词非空才显示搜索结果说明，同步运行中才显示进度；重新同步入口移到信息页右上角更多操作，仍保留确认弹窗。
- 会话顶部五次点击位置调整在没有可见输入栏的短码/回收站会话不再弹出。独立浅色弹窗主题与显式深色文字修正截图中白底白字，按钮也采用深色文字和浅色底。
- `:app:assembleCoreDebug :app:testCoreDebugUnitTest --offline --no-daemon -Pkotlin.incremental=false` BUILD SUCCESSFUL（1m11s），208项测试失败0、错误0、跳过0。日志 `docs/logs/sync-dialog-visual-fix.log`。未做真机视觉验收。
# 2026-10-09 自定义 SIM 名称在部分通道缺失

- 定位：`ForwardingMessageFormatter` 将自定义卡名写入标题；企业微信应用/群机器人、钉钉、飞书群机器人、腾讯云告警和短信直发的实际发送接口只接收正文，因此这些通道不会看到卡名。多实例定向、普通分发及通道组展开均存在此路径。
- 修改：`MultiChannelForwardWorker` 对纯正文通道补入由真实接收 subscriptionId 解析出的自定义卡名。已有详细/Emoji 模板包含卡名时不重复添加；显式自定义模板及规则正文保持原样。无自定义卡名、无权限或无法确定接收 SIM 时保持原正文，不猜卡槽。自定义 Webhook 测试模板的模拟 SIM1 名称/号码改用当前配置。
- 验证：`git diff --check` 通过，逐一核对三个调度分支的纯正文调用。当前环境 Gradle Wrapper 9.7 分发包缺失且网络不可用，编译停在下载前，没有生成新 APK，也没有真机发信验收。


### 2026-10-09 卡槽误显示 SIM3 与会话选卡入口

- 确认源码缺陷：接收 subscriptionId 查询失败时将其当卡槽、取第一张活跃卡；模板 SIM_INDEX 同样用订阅 ID 推算，可能把卡一显示为 SIM3。改为仅按真实 subscriptionId 匹配系统 SubscriptionInfo，以真实 simSlotIndex 读取名称/号码；无法确定显示“未知接收卡”，数字模板字段留空，不猜卡。
- 会话选卡读取异常/空列表不再隐藏，显示“?”并提供权限申请/重新读取入口及状态说明；已识别卡按物理卡槽排序，单卡保留入口。发送前重新检查选中订阅是否活跃，失败保留草稿、不提交、不换另一张卡。
- 新建会话、统一订阅快照去掉列表下标当卡槽；群发选项复用真实卡槽名称，卡二单卡不再显示卡一。会话 onResume 原有刷新机制保留，自定义卡名同步至控件无障碍说明。
- 增加两个 SubscriptionResolver 仪器回归测试：订阅 ID 3/卡槽0显示 SIM1、未知物理卡槽不推算 SIM3。这些测试未执行，没有设备环境。
- git diff --check 通过，全文检索未再发现 subscriptionId - 1、订阅 ID 拼 SIM 或 simSlotIndex 回退列表下标的旧写法。
- 尝试 :app:testCoreDebugUnitTest :app:assembleCoreDebug --offline --no-daemon -Pkotlin.incremental=false；Wrapper 在下载 Gradle 9.7.0 时因 Network is unreachable 失败，未进入编译/测试，未产出新 APK。完整日志 docs/logs/sim-identity-fix-build.log。现有 APK 未替换、未推送。
- 待验收：红米 K50 电话权限/订阅列表返回情况、卡一订阅 ID 3 通知、自定义卡名各通道、换卡后发送拦截。当前没有手机日志，不能宣称截图中的实际发送失败已消除。


### 2026-10-09 SIM 映射与转发快照优化

- 统一按真实 subscriptionId 查询 SubscriptionInfo，供转发标题、正文、模板及自定义 Webhook 使用；查询异常返回未知，不推算卡位。运维诊断增加“SIM 卡位映射”，显示真实卡位、订阅 ID、自定义名称，不显示本机号码。
- 会话和新建会话页面在前台监听订阅列表变化，暂停时解除监听；返回页面重新读取。新建会话也保留未知状态的权限/重试入口。卡被拔出/停用后记录需要重新选卡，显示 ?，直到用户明确选择才恢复发送，避免监听刷新后偷偷切到另一张卡。
- 新转发任务入队时记录真实卡位、当时名称和接收号码至 WorkManager Data；延迟、重试、普通格式、自定义模板和 Webhook 使用同一快照；旧队列没有快照时保留实时查询兼容。这里是转发入队快照，未迁移短信 Room 历史记录，也不宣称历史会话永久保存旧名称。
- git diff --check 通过。构建重试仍在 Wrapper 下载阶段因 Network is unreachable 失败（docs/logs/sim-mapping-optimization-build.log），未进入 Kotlin 编译或测试，未生成新 APK；新增订阅回归测试未执行。未推送/替换发布资产。


### 2026-10-09 扩大源码巡检

- 358 个生产 Kotlin 文件路径/风险模式扫描，并人工复查主要短信、远程命令、同步、转发、备份、规则、SMTP 和生命周期路径。新增 11 组确认缺陷修正，详见 docs/full-code-audit-2026-10-09.md；并非全文件逐行审核或全功能验收。
- Room 状态更新实际 SQL 在本机 SQLite 的 8 个场景通过，git diff --check 通过；新增长短信回执聚合三项 Kotlin 单测尚未执行。
- 最终构建仍因 Gradle 9.7.0 缺失且网络不可达停在 Wrapper 下载阶段，没有 APK。日志 docs/logs/full-audit-build-attempt.log；未推送。保留超长 WorkData、定时闹钟竞争、历史卡名快照迁移等待处理边界，不宣称完全修复。


## 2026-10-09 继续审查：QQ群、教程及两版入口

- QQ 交流群统一为 569321348：中英文 README、共用教程、关于页点击复制。关于页改可滚动，避免新增入口在小屏/大字体下被截断。
- 新增 UserGuideActivity（非导出），复用 ChannelFullTutorialDialog；经典远程页、两版关于页及开发版运维提供入口。开发版运维同时提供关于/群号直达。
- 核查实际 RemoteSourceType 枚举：7 类。纠正 README 宣称 QQ/OneBot 远程发送与企业微信应用远程来源的错误；这些并未在运行时实现，不新增功能冒充修复。
- 补充钉钉、飞书、企业微信、Telegram、WebSocket、IMAP步骤；区分 SMTP/IMAP、真实 ID 白名单、物理卡槽/订阅 ID、提交/发送/送达状态。完整版写入 docs/user-guide.md；同步 docs/classic-developer-parity.md 中全量同步入口说明。
- 新确认问题：4000字符不等于 WorkManager 10KB，中文正文和元数据可能导致构建 Data 失败。改按 UTF-8 6000字节预算裁剪（保留明确后缀、不拆代理对），限制发送者显示长度，构建 Data 异常记历史失败后停止入队。6000只是正文预算；元数据异常仍由实际 Data 构建校验，不能声称任何配置均保证入队。
- 添加 WorkPayloadTextTest 三例：短文保留、中文字节限额、补充 Unicode 不拆分。JUnit 尚未执行。
- 已执行：git diff --check、三个 XML 解析、strings资源重名检查、实际 Room SQL 提取后的8条状态迁移检查通过。
- 构建尝试 :app:testCoreDebugUnitTest :app:assembleCoreDebug --offline --no-daemon：Wrapper缺失Gradle9.7.0，下载 Network is unreachable，未进入 Kotlin 编译；日志 docs/logs/tutorial-review-build.log。没有新的APK、没有push、没有发布。
- 尚需：编译与新增JUnit；两版页面设备验收；定时闹钟与手动立即发送的并发认领、远程超长指令WorkData、旧短信卡名称等未解决边界保留，不算已修复。

## 2026-10-25 未提交改动复查（基线 708c5117）

本节仅描述本次本地改动；上文 10 月 3 日的测试和构建结果不适用于当前源码。保留所有既有修改、黄页替换和未跟踪的 AutoReplyWorker。未提交、未推送、未发布。

### 本次确认并修改的问题

- 自动回复原本在 WorkManager 入队前保存任务去重标记、扣除每日限额并开始冷却；Data 过大或入队失败仍会留下标记。改为 WorkManager 唯一任务完成持久化入队；限额、冷却与提交去重在 Worker 实际提交前用同一 SharedPreferences commit 原子保存。提交结果不确定时保留标记，不自动重发。读取旧提交标记以避免已有任务重复提交。
- 规则快照原本直接塞入 WorkManager Data，与回复正文重复占用其 10 KiB 上限。现在传递规则 SHA-256 摘要；回复正文过大仍会在构建 Data 时失败，但不占用发送限额或写入提交标记。
- 自动回复的影子步骤从 SUBMITTED 改为 QUEUED；延迟任务执行前仍回读启用状态、规则快照和指定订阅有效性。排队状态与真正提交状态分开。
- 大盘接收统计、转发统计读取失败此前仍可能导致整页失败或被当作零。现在记录失败项、显示“未知”；部分数据缺失时不显示健康分。健康描述改为配置参考，不保证息屏不被杀。
- RecoveryEngine 与诊断 ViewModel 重新抛出协程取消，避免取消扫描被记录成检查失败或错误页面。
- 心跳 lastReportTime 移到全部目标完成入队调用之后；该字段只表示入队时间，不能作为送达时间。
- 删除本轮新增的短信直发测试文案中的收费提示，遵照用户要求不增加收费确认弹窗。

### 验证与边界

- `git diff --check` 通过（仅空白/补丁格式检查，不代表编译、测试或功能正常）。
- 尝试 `./gradlew :app:testCoreDebugUnitTest :app:assembleCoreDebug --no-daemon`，在下载 Gradle 9.7.0 时失败：`Network is unreachable`。尚未进入 Kotlin 编译和单元测试，也没有产生新 APK。
- 完整日志：`docs/review-logs/2026-10-25-gradle.log`。
- 仍待验证：自动回复并发入队/重启/跨日/规则变更取消/限额与冷却；指定卡移除时停止发送；大盘数据库读取失败；恢复扫描取消；经典版与开发版功能一致性。未进行真实短信发送或真机验收。
- 提交去重采用保守策略：提交标记持久化后若进程在调用发送前退出，可能漏掉一次回复；不能在没有幂等发送接口的情况下同时保证绝不漏发与绝不重复发送。此处选择不自动重复发送。

## 2026-10-26 接收短信列表缺失：局部维修
- IncomingSmsService：联系人姓名/头像查询失败时回退号码与空头像，避免联系人补充信息异常阻断原短信本地入库。
- IncomingSmsUiSyncWorker：READ_SMS 缺失及 SecurityException 不再误报成功；保留 WorkManager 指数退避重试。Provider 未匹配的重试上限从 3 调整到 12，耗尽后输出 reason。只读补同步，未加入发送、转发或自动回复调用。
- 保留全部既有修改；未提交、未推送、未发布。
- git diff --check 通过。单元测试及 Debug 构建已尝试，但 Gradle 下载阶段网络失败，未进入编译，详见 review-logs/2026-10-26-gradle.log。不得据此宣称修复验证通过。
- 待验收：权限短暂拒绝后恢复、系统短信延迟入库、联系人查询失败、重复广播、重启恢复及经典/开发版列表刷新。重试耗尽后的手动恢复入口和短信直发回调链路尚未完成，本次不是完整修复。

## 2026-10-26 外观设置接入（进行中）
- 新增运维“外观与主题”入口：跟随系统/浅色/深色、纯黑背景、Android 12+ 系统动态配色、绿/蓝/紫主题色；SharedPreferences 保存并即时重组。
- 开发版导航胶囊调整为 64dp 外壳/56dp 导航项、等宽四项、主题色选中背景、按压弹簧缩放。当前为项目自身实现，不是 KernelSU 原组件完整移植。
- 尚未接入 Miuix、拖动吸附/滑动选中指示器、液态玻璃、调色板算法、经典版主题；大量固定颜色页面仍需适配。不能宣称 KernelSU 主题整套移植完成。
- diff --check 通过；Debug 构建尝试在 Gradle 下载阶段失败，未编译验证。未提交或发布。

## 2026-10-26 KernelSU 导航源码移植与设置入口
- 底部“运维”和页面“运维中心”改为“设置”，保留原诊断、后台运行及备份功能。
- 移植 tiann/KernelSU v3.3.1 的 FloatingBottomBar、DampedDragAnimation、InteractiveHighlight、DragGestureInspector、CombinedBackdrop、InnerShadow、Lens、Vibrancy；包名改为项目 ui.compose.ksu。原始 Apache 来源注释保留；GPL 文本见 docs/licenses/KernelSU-GPL-3.0.txt。
- 固定依赖 Miuix UI/Blur 0.9.4；接入背景捕获，支持拖动吸附、弹簧位移/形变、按压高光、折射/色散/内阴影。Android 13 以下禁用 shader 创建；关闭玻璃时不监听倾斜。
- 外观设置新增自定义十六进制颜色、Miuix 调色板风格、2021/2025 色彩规范、玻璃开关，保存到独立 appearance 偏好；Miuix 主题与 Material 主色/背景桥接。
- 边界：并非整个 KernelSU 管理器 UI 搬入。经典 XML 页面和开发版硬编码颜色还未全部改造；Miuix/Material 整套界面切换尚未接入；导航仍用原 emoji，图标待统一。已有备份是否包含 appearance 偏好尚需核对。
- diff --check 通过；单元测试/Debug 构建在 Gradle 下载阶段 Network is unreachable，未进入编译。源码兼容性、渲染、手势/三键/键盘避让均未验证，不能认定整套移植完成。保留所有已有改动，未提交/推送/发布。

## 2026-10-26 设置模块可用性优化
- 外观入口移至页面固定区域，展示当前明暗模式/配色来源/胶囊效果；不再依赖诊断加载成功，主题弹窗开关跨配置重建保存。
- 诊断失败提供重新加载、显示/拦截设置和文件备份入口，内容可滚动；Idle 加载调用移入 LaunchedEffect，避免重组重复触发。
- 设置页背景、正文、卡片和分组编号使用应用主题色，不再仅按系统深色模式判断。外观增加恢复默认按钮。
- diff --check 通过；本轮未新增构建结果，沿用已确认 Gradle 下载不可达阻塞，不声称编译或设备验证通过。尚未完成整个设置体系的主题覆盖与 appearance 备份集成。

## 2026-10-26 设置：日志与关于入口
- 新增 SupportSettings，日志底部弹层含生成进度、失败提示、明文预览、SAF 保存 JSON、FileProvider 文件分享及取消；每次打开重新生成快照，不自动上传。分享前明确让用户检查内容，不承诺所有日志已脱敏。
- 关于采用独立全屏弹窗，读取当前安装包名称和 versionName；提供源码、Releases、README 教程链接、QQ群 569321348 和移植组件说明。未虚构 Telegram/QQ群邀请链接。
- 两入口不依赖诊断状态，原报告/日志区暂保留，后续可合并以消除重复。
- diff --check 通过。编译/渲染/SAF/分享面板仍待验证；Gradle 下载阻塞未解除。未提交发布。

## 2026-10-26 设置首页分类改造
- 设置首页改为外观与主题、短信与显示、后台运行、备份与恢复、诊断与报告五类圆角入口，下方保留日志与关于。首页不再直接展示运维长列表。
- 二级页支持标题返回和系统返回，分类状态 rememberSaveable；原后台运行块仅在后台运行页显示，短信链路/报告/日志集中到诊断页。短信设置与经典设置入口保留，备份独立。
- 外观编辑器直接嵌入二级页，避免“点入口后再点按钮”；避免嵌套纵向滚动无限高度。
- diff --check 通过。Debug 构建再次在 Gradle 分发包下载阶段 Network is unreachable，未编译。二级页返回、旋转、长内容滚动和主题切换需实际验证；未提交推送发布。

## 2026-10-26 1.2.7 分支云端构建结果
- 分支 build/1.2.7；成功构建源码提交 7e6aa0ca990ed352c5cd253e73fc0b11a2ddc1b2；main 保持 708c5117eece30336f752476ae58cdd4df310666。
- Actions https://github.com/2756826865/android-sms-forwarder/actions/runs/40326759316 全部成功；单元测试/Android 测试源码编译、签名 Release、签名验证、artifact 上传通过。未运行设备测试；没有创建 Release。
- 构建中修复：host WorkManager 桩 failure(Data) 与权限重试断言；Miuix Blur minSdk33 与现有 minSdk26 冲突（仅 API33+ 加载，低版本普通导航）；CompatibilityManager 参数逗号；电量设备名保存按钮作用域；RowScope 导航项扩展导入。
- 本地 SQL/查询回归共 36 个测试及补同步 worker 16 个模拟场景通过。
- 下载 artifact 12651687550 并校验 APK：com.helyu.smsforwarder，versionName 1.2.7，versionCode 1216。
- APK SHA-256：2c2d9af335f76a43cd58ff8ce0e19112cc0ea7800aa948d89ae361380cb7e2b2，与 artifact 内校验文件一致。
- 云端证书 SHA-256：227d5ed7b74462e37d6e88c424eb0aad58a33acb1b6e193f2e2d08a650107c96，与既有发布证书一致。
- 下载 https://github.com/2756826865/android-sms-forwarder/actions/runs/40326759316/artifacts/12651687550 。安装包仅用于分支验收，不代表真机短信/旧系统降级/主题手势验证通过。

## 2026-10-26 真机截图：导航胶囊收缩
- 用户 1.2.7 截图确认四项导航被挤在底部中央，属于实际布局缺陷。
- 根因：FloatingBottomBar 使用 IntrinsicSize.Min；移植调用未携带上游导航项 defaultMinSize，且未指定外层宽度，emoji/文字固有宽度使胶囊收缩。
- 修正：API33+ 胶囊外层 fillMaxWidth（保留左右28dp），四项权重均分，补 minWidth64dp；旧版普通胶囊不变。
- diff --check 通过；修正尚未重新编译及设备验证，不宣称已验收。未推送此修正。

## 2026-10-26 按原版 KSU 设置页改造
- 用户明确要求直接移植页面，撤掉之前自行拼接的首页卡片/文字箭头，新增 KsuSettingsHome，来源 tiann/KernelSU v3.3.1 SettingsMiuix.kt，保留原作者与 GPL 声明。
- 使用同版 Miuix Scaffold/TopAppBar/MiuixScrollBehavior、Card、ArrowPreference、scrollEndHaptic/overScrollVertical、12dp分组间距和边距；用我们的设置标题、描述和导航回调替换内核授权业务。
- 日志/关于改成同款 ArrowPreference 和分组卡片；导航图标改为单色矢量 Material Rounded，去掉 emoji。补 Miuix preference0.9.4 和 material-icons-extended1.7.8 依赖。
- 胶囊宽度修正一并保留。diff --check 通过，尚未重新构建/设备验证。本次未推送；已成功的 1.2.7 APK 不包含这些后续修改。

## 2026-10-26 设置大改：统一原版组件
- 二级页面新增 KsuSettingsPage，使用 Miuix Scaffold/SmallTopAppBar 与真实返回图标，替换拼接标题。
- 外观页采用 Miuix Card、OverlayDropdownPreference、SwitchPreference，低版本禁用动态色/玻璃并显示系统要求；保留自定义颜色输入和明确应用按钮。
- 日志弹层使用与 KSU 同款 OverlayDialog；沿用我们自己的报告生成/预览、SAF 和 FileProvider，不搬入 KernelSU root 日志命令。
- 关于页增加实际应用图标、名称、版本及 Miuix ArrowPreference 链接组。
- 外观偏好加入 BackupPreferences 可迁移白名单及导入类型/范围验证；恢复后主题内存刷新仍需验证。
- 新组件 API 已对照 Miuix v0.9.4 原始签名；diff --check 通过。compileCoreDebugKotlin 仍停在 Gradle 下载 Network is unreachable；本轮没有新的编译通过/截图验证结果。后续修改未推送，旧分支 APK 不包含这些变更。
