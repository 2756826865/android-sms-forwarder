# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.7] - 2026-09-09

### 🌟 核心新特性与重大升级 (Major Features)

- **💬 企业微信智能机器人官方长连接支持 (WeCom WebSocket Bot Stream)**：
  - 基于腾讯企业微信官方 SDK (`aibot-node-sdk`) 规范，在 Kotlin 端原生移植 WebSocket 全双工长连接；
  - **免公网 IP / 免配置回调 URL**：通过官方长连接通道 `wss://openapis.work.weixin.qq.com/aibot-stream`，凭借 `bot_id` 与 `secret` 即可接入；
  - **双向发信与原路回执**：支持解析 `aibot_msg_callback` 指令并触发备用机发信；支持通过 `aibot_respond_msg`（带 `req_id`）快速应答与 `aibot_send_msg` 异步定向推送，实现原路精准回执；
  - **白名单与连接保活自愈**：内置 30 秒官方心跳保活机制与网络抖动断线自愈重连，支持 UserID / ChatID 权限校验，并由 `RemoteSourceRuntimeManager` 和常驻服务全生命周期托管；
  - **🔄 远程渠道与转发通道自动联动生成**：在「远程控制」中配置企业微信智能机器人后，系统自动在「转发通道」中同步生成「企业微信智能机器人 (长连接)」通道实例，自带独立启闭 Switch，无需推送时可自由关闭，且开关状态被持久化保护，不影响远程发信。

### 🌟 改进与问题修复 (Improvements & Fixes)

- **🔇 远程发信（企业微信/钉钉/飞书/TG/WS）双重回执智能去重与防重闭环 (`RemoteControlReceipt.kt`)**：
  - 彻底解决在机器人会话内发信时，由于同时配置了群 Webhook 机器人导致群内收到两条重复回执卡片的问题；
  - 架构级优先派发原路会话回执（Direct Session Reply），成功送达后自动屏蔽向同平台普通 Webhook 机器人的冗余派发，同时保留向邮箱、Bark、PushPlus 等异构备份通道的正常派发。
- **⚡ 飞书 Stream 长连接稳定性加固与凭证清洗**：
  - 强制对 `appId` 与 `appSecret` 进行首尾空白符过滤清洗，避免复制带入不可见空格导致飞书网关拒绝；
  - 增加对运行时配置热重载的主动识别，消除重载时偶现的 `websocket client closed` 误报为连接失败；
  - 补充关键鉴权错误码（如 `1000040346`）的清晰中文引导。
- **📖 钉钉与飞书机器人配置全指南与端差异指引**：
  - 在 `README.md` 及 App 内部设置界面（飞书/钉钉群机器人与远程控制页）新增显眼的配置指引卡片；
  - 重点提示：飞书与钉钉移动端默认隐藏了「群自定义机器人」入口，必须在电脑端（PC / Mac 桌面端）群设置中添加并获取 Webhook 地址；
  - 明确区分「群自定义机器人（单向短信推送）」与「开放平台自建应用（双向远程控制发信）」的使用场景与配置要点；
  - 明确强调飞书长连接必须在【事件配置】中订阅 `im.message.receive_v1` 事件，切勿选成【回调配置】。
- **🐞 飞书远程发信群聊 @机器人 指令解析修复**：
  - 修复飞书群聊中 `@机器人 /发信 ...` 时，飞书消息自带 `@_user_1` 占位符导致前缀匹配失败、指令被静默吞掉的缺陷；
  - 在 `FeishuStreamClient` 与 `RemoteCommandProcessor` 中增强前导 `@` 占位符的智能清洗与容错剥离，并在回复失败时输出详细错误日志。

## [1.1.6] - 2026-09-08

### 🌟 核心新特性与重大升级 (Major Features)

- **🔔 旁路通知栏消息监听与全渠道转发 (`NotificationForwardListenerService`)**：
  - **无侵入纯旁路监听**：基于 Android 原生 `NotificationListenerService` 架构开发，监听微信、QQ、支付宝、钉钉、飞书、云闪付及任意第三方 App 弹出的状态栏通知；
  - **白名单与预设常用 App**：预设 9 款高频常用 App（微信、QQ、支付宝等），支持用户在控制台中一键勾选常用应用，或直接输入自定义包名；
  - **多维防轰炸与高频防抖**：内置 5 秒时间窗口内容指纹防抖（包名 + 标题 + 正文），避免即时通讯软件高频刷屏造成通知轰炸；
  - **智能缓存与自身循环阻断**：严格过滤自身应用发出的通知，彻底杜绝转发自身提示造成的死循环；内置 500 条 LRU 缓存避免频繁 IPC 读取；
  - **独立控制面板与通道绑定**：在通道中心顶部新增「🔔 通知转发」标签页，提供权限状态检测、一键去系统授权、常驻通知智能过滤、目标通道绑定以及「🧪 发送模拟通知测试」。

- **🚀 经典版 / 开发版双 UI 引擎无缝切换 (Dual-UI Engine)**：
  - **经典极简原生版**：保留极致轻量流畅的经典短信列表和设置页，占用极低内存；
  - **开发版工作台 (SMS Gateway)**：基于 Jetpack Compose 构建的 5-Tab 运维级控制台（大盘监控、信息中心、通道枢纽、规则中心、设置）；
  - **首页显式快捷入口**：在经典版首页右上角常驻绿色胶囊按钮 **`🚀 开发版`**，在开发版工作台顶部常驻 **`📱 经典版`**，用户可随时秒级双向无缝切换，偏好配置持久化保存。

- **🎛️ 现代化 Compose 架构全面重构 (Modern Jetpack Compose UI)**：
  - 全面淘汰老旧繁琐的传统 XML 布局与分散 Activity，重构为高度组件化、响应式的 Jetpack Compose 现代化界面；
  - **全新通道管理枢纽 (`ChannelHubScreen`)**：支持全渠道卡片式管理、多类型实时过滤、健康状态呼吸灯与连接诊断；
  - **全新远程控制控制台 (`RemoteControlScreen`)**：支持国内推荐（钉钉/飞书）、专业接入（WebSocket）、应急备用（邮箱/短信）及海外渠道的分组流式交互。

- **🎨 可视化规则工作室 V2 (Rule Studio & Targeted Dispatch)**：
  - **条件与动作彻底解耦**：支持单条规则绑定任意多个目标通道实例，实现“一条短信同时抄送多端”或“根据发信人/卡槽定向精准分流”；
  - **多维组合过滤**：同时支持卡槽（SIM 1/2）、发信人号码前缀/通配符、关键词包含与高级正则表达式；
  - **动态正则替换 (`RegexReplacementList`)**：支持在转发前对短信文本进行局部正则清洗、敏感信息脱敏与格式重组；
  - **内置实时调试沙箱 (`RuleTestSection`)**：支持在界面直接输入模拟短信，毫秒级验证规则匹配度与最终渲染效果。

- **🛡️ 远程控制安全收敛与六大标准免公网协议 (Remote Control Matrix)**：
  - 彻底下线存在安全隐患的外部中继广播与旧版注入代码，收敛为 6 种标准安全的双向通信协议：
    1. **钉钉 Stream 长连接**：免公网 IP、免内网穿透，官方 Stream 协议全双工指令下发；
    2. **飞书长连接**：飞书企业自建应用官方 WebSocket 协议，原生安全长连；
    3. **双向 WebSocket 客户端**：支持连接用户自建服务，支持自定义 Token 认证与自签名证书信任；
    4. **邮箱 IMAP 轮询收信**：基于标准 IMAP 协议拉取指令，支持 SSL 993 及 STARTTLS，强制拒绝明文登录；
    5. **Telegram Bot 长轮询**：支持自定义反代 API 地址及 ChatID / UserID 白名单鉴权；
    6. **白名单应急短信指令**：无网环境或备用机断网时的本地自愈与指令控制。
  - **闭环原路回执**：远程发信完成后，严格通过 `sourceInstanceId` 靶向回传给原发起人，禁止跨实例回退。

- **🔐 硬件级敏感凭据加密存储 (AndroidKeyStore AES-GCM)**：
  - 所有通道与远程来源的敏感字段（如 AppSecret、Bot Token、邮箱密码等）全面接入 AndroidKeyStore 硬件加密，以 `ENC:` 前缀密文持久化存储，手机丢失或 root 环境下亦无法被逆向嗅探。

- **🔑 XXPermissions 现代化权限网关集成 (`XXPermissionGateway`)**：
  - 接入成熟的 XXPermissions 框架，统一接管 SMS 收发、通知、电池优化、无障碍与通话状态等全部危险权限申请，深度兼容 Android 12~15 及 HyperOS、ColorOS、OriginOS 等品牌系统。

---

### 🛡️ 底层架构加固与并发安全治理 (Architectural Hardening)

- **⚡ 远程仓库死锁彻底清零 (`RemoteSourceRepository.kt`)**：
  - 彻底解除 Repository 锁与 RuntimeManager 锁的反向依赖，将所有运行时通知移至数据库同步块外执行，完全粉碎 AB-BA 死锁闭环；
  - 数据读取-修改-持久化保持全流程 `@Synchronized` 原子性，避免多线程并发修改导致快照覆盖或漏同步。
- **🛑 句柄生命周期与迟到回调隔离 (`RemoteSourceRuntimeManager.kt`)**：
  - 引入 `isHandleActive` 双重检查（`!isStopped && runningHandles[id] === handle`），基于引用一致性拦截旧连接停止过程中迟到触发的异常或状态变更，杜绝热重启时旧连接把新实例的 `READY` 覆盖为 `ERROR`；
  - 钉钉、飞书与 WebSocket 客户端在停止后全面拦截 `onClosed`、`onFailure` 及消息队列消费，杜绝越权处理历史指令。
- **🧹 网络连接无条件断开保障 (`HttpConnectionScope.withDisconnect`)**：
  - 封装内联泛型扩展函数，确保底层 `HttpURLConnection` 无论在正常返回、I/O 超时还是断言抛错时，均在 `finally` 块中 100% 执行 `disconnect()`，彻底杜绝底层 Socket 泄漏与连接池枯竭。
- **🔒 Webhook 模板单次单遍渲染 (`WebhookTemplateRenderer.kt`)**：
  - 采用单遍正则扫描，短信正文中的 `[time]`、`[from]`、`[sim]` 等字符绝不会被二次递归展开，彻底封堵模板二次注入漏洞；
  - 优化 GET 请求 URL 拼接（`WebhookRequestUrl.kt`），确保参数精确追加在 `#` 锚点前，智能保留原有查询参数与编码格式。
- **🔋 低电量提醒状态机与容量计算加固 (`LowBatteryCheckWorker.kt`)**：
  - 即使所有通道均处于停用状态，只要电量回升至阈值以上即可强制复位已提醒状态，解决重新启用通道后不报警问题；
  - 增加对电池 `scale <= 0` 异常刻度的防御，防除零崩溃，计算过程全面转为 Long 防溢出；
  - 每次低电量事件生成独立 UUID 任务标识，避免历史任务去重误伤。
- **🚨 开机与系统广播安全性拦截 (`RescheduleAlarmsReceiver.kt`)**：
  - 导出接收器显式限制只处理系统注册的标准系统 Action（开机、应用覆盖升级、时间及时区变更），拦截外部恶意广播触发高负载调度；
  - 广播自愈中安全加入 `NotificationForwardListenerService.rebindService`，并在后台线程结合 `config.enabled` 与 `runCatching` 执行，零主线程卡死风险。
- **⏱️ 全通道底层网络显式超时加固 (`MultiChannelForwardWorker.kt` & `ChannelTestSender.kt`)**：
  - 为所有底层的 Raw Socket、SMTP 邮件协议及 HTTP 请求增加 8~12 秒显式连接超时与读超时（`connect(InetSocketAddress, timeout)`）；
  - 彻底杜绝弱网、飞行模式或路由黑洞下底层连接无限挂起导致后台线程池卡死耗尽的隐患。

---

### 🐛 细节优化与 Bug 修复 (Bug Fixes & Polish)

- **SMTP 邮件 STARTTLS 自动适配**：修复多实例邮件通道中端口 587 错误硬编码 SSL 导致的握手中断问题，智能支持 STARTTLS / SSL 协议自适应；并在单通道测试器中对齐全套邮件鉴权逻辑；
- **通道群组实例测试支持**：单通道测试器深度支持 `CHANNEL_GROUP`，允许在多实例控制台中一键递归分发测试，并提供防循环调用与成员有效性校验；
- **通知消息格式防篡改**：在通知转发与模拟测试入队中传递 `bodyAlreadyRendered = true`，防止短信模板对通知内容进行二次包裹；
- **全通道单测逻辑对齐**：彻底清理旧版测试器中的冗余分支与未解析引用，全面对齐 19 大通道多实例调度架构；
- **CI/CD 自动化构建工作流增强 (`build-custom-apk.yml`)**：新增对 `master` 分支推送的自动触发支持，构建完成后自动签名并发布 GitHub Release APK 安装包；
- **Gotify 测试逻辑对齐**：测试发送与正式发送统一使用 `id > 0` 校验响应，并对 Token 执行空格修剪；
- **Bark & ntfy 凭据防呆**：对输入的服务器地址和 DeviceKey / Token 自动进行前后空格修剪，防止误输入空格导致推送失败；
- **HTTP 业务码防伪装**：JSON 转换失败时改用 `_httpStatus` 字段记录底层状态码，防止将非 JSON 的 HTTP 200 响应误判为业务层 `code: 200` 成功；
- **通道编辑数据防丢**：保存通道实例时自动保留 UI 未展示的高级字段；已有邮箱端口（如 587/25）不再被强制改成 465；QQ 切换模式时自动清理互斥的旧凭据；同类型重复点击下拉菜单不再清空输入；
- **测试配置快照联动**：通道测试点击时抓取参数快照，参数被再次修改后旧成功提示自动失效，避免误导用户；
- **提醒通道选择弹窗优化**：来电、电量、心跳通道选择弹窗中明确提示已失效的删除通道，仅在用户点击“确定”时清理，停用但存在的通道予以安全保留；
- **定时闹钟平滑降级**：当系统未授予精准闹钟权限时，自动平滑回退至非精准系统空闲闹钟，避免 Android 14+ 抛错崩溃。

---

## [1.1.5] - 2026-09-03

### 🌟 核心新特性与重大升级 (Major Features)

- **🔀 多渠道多实例池与靶向规则分流体系 (Multi-Instance Channel Hub & Targeted Routing)**：
  - 彻底打破单渠道仅能配置单个 Webhook/Bot 的限制，支持用户创建任意数量的独立渠道实例（如：研发企微群、运维钉钉群、告警飞书群等）；
  - **靶向规则定向分流**：转发规则升级支持 `targetInstanceIds` 属性，支持根据发信人、关键词将不同业务短信精准分流推送到指定群组/机器人，实现企业级多群多租户分流。

- **⚡ 免 Root 验证码自动填充引擎 (Accessibility Autofill Engine)**：
  - 基于 Android 官方无障碍辅助服务（AccessibilityService）架构，无需 Root / Xposed 即可实现前台登录或验证界面的全自动验证码模拟输入；
  - **智能特征引擎**：集成 `VerificationCodeExtractor`，精准匹配银行、运营商（包括特殊表达如 `(验证密码)951332`、`【123456】` 等）的 4~8 位数字/字母验证码；
  - **自动提交与剪贴板兜底**：支持配置 500ms 延时自动点击「登录/提交/确定」按钮；对金融加密软键盘等特殊场景自动复制到剪贴板。

- **📋 任务栏通知一键快捷「复制」验证码 (Notification Quick Action)**：
  - 收到验证码类短信时，系统通知栏卡片最左侧首选位置智能渲染 **`[ 复制 ]`** 快捷按钮；
  - 点击后秒级将验证码写入系统剪贴板、弹出 Toast 确认并优雅消除通知。

- **🏝️ 屏幕顶部 5 秒悬浮验证码胶囊 (Floating OTP Pill Overlay)**：
  - 基于 Android 原生 `WindowManager` 动态绘制深色毛玻璃轻量小胶囊（`[ 验证码 951332 · 点击复制 ]`）；
  - 收到短信后屏幕顶部即时弹出，支持单点一键秒级复制，无操作 5 秒后自动缩放淡出消失；
  - 在「验证码自动填充」设置中提供独立控制开关及悬浮窗权限一键引导。

- **💓 定时心跳与系统状态保活上报 (Scheduled Device Health Heartbeat)**：
  - 新增 `HeartbeatConfig` 与 `HeartbeatWorker`（支持 1~24 小时自定义周期）；
  - 定期自动抓取备用机电池电量/充电状态、Wi-Fi/移动数据网络连接、双卡卡槽状态及系统开机时长，秒级推送到所有启用的转发渠道，让用户随时掌握无人值守备用机存活状态。

- **📞 未接来电与通话状态实时转发 (Missed Call & Call State Forwarding)**：
  - 新增 `CallStateReceiver` 纯本地电话状态广播监听器与 `CallForwardingSettingsActivity` 设置界面；
  - 备用机产生未接来电（或通话结束）时，自动抓取来电人、通讯录姓名、卡槽（SIM1/SIM2）、响铃时长，秒级推送至已配好的全部转发渠道。

- **🎭 转发内容「隐私数据正则脱敏/掩码」 (Privacy Data Masking)**：
  - 新增 `PrivacyDataMasker` 纯本地脱敏引擎与全局/规则独立控制开关；
  - 自动对转发消息中的手机号 (`138****1234`)、身份证号 (`110101********1234`)、银行卡号 (`6222 **** **** 1234`) 进行精准正则掩码，杜绝群聊推送信安泄露。

- **⏰ 转发规则生效时段与时间窗口控制 (Time-Window & Active Days Rule Control)**：
  - `ForwardingRule` 实体升级支持 `timeStart`、`timeEnd` 与 `activeDays`（工作日/周末/自定义）；
  - 规则匹配引擎自动校验系统时间与星期，支持“工作日 09:00~18:00 转发至企业微信，其余时段静默或转推 Bark”等精细化分流控制。

- **📱 双卡（SIM 1 / SIM 2）自定义别名全链路覆盖 (Custom SIM Labels Full-Coverage)**：
  - 支持用户为卡 1 / 卡 2 配置个性化别名（如：`卡1-工作主卡`、`卡2-副卡流量`）；
  - 全链路覆盖：短信多渠道转发、消息模板变量 `{{SIM_SLOT}}`、未接来电推送均自动展示自定义卡槽名称。

- **📡 远程控制渠道矩阵重磅扩展至 8 大全渠道 (Remote Command Hub)**：
  - **✈️ Telegram Bot 远程控制**：基于官方 Bot API 的 `getUpdates` 长轮询架构，支持国内自定义 API 反代 Host，支持 ChatID / UserID 白名单与 `message_id` 去重，发信结果原路直接回复。
  - **🔌 WebSocket 全双工远程控制**：建立与用户自建服务端/网关的长连接，支持 Auth Token 鉴权，支持服务器主动下发 `send_sms` 指令载荷（指定卡槽、目标号码、内容），发信结果与回执通过同一连接实时主动上推。
  - **🐧 QQ 远程控制 (OneBot 11)**：支持对接 OneBot 11 / NapCat / LLOneBot 标准 WebSocket 协议，监听 QQ 私聊与群聊消息；支持 QQ 号/群号白名单与「群聊必须 @机器人」触发开关，发信结果原路回复。
  - **🔄 全渠道闭环回执直连上报**：远程指令下发发信完成后，除推送至全局已启用的转发渠道外，特设**原路直接回执响应**（Telegram / WebSocket / QQ），实现指令发起方秒级接收发送状态与送达结果。

---

### 🛡️ 底层架构与稳定性深度加固 (Deep Architectural Hardening)

- **⚡ 内存环形日志 O(N) 遍历降维至 O(1) (`RingBufferLogManager.kt`)**：
  - 原 `ConcurrentLinkedQueue.size` 每次打日志全量遍历 1000 个节点，产生不可忽视的 CPU/电池损耗；升级为 `AtomicInteger` 无锁原子计数器，将容量淘汰开销彻底降为 **$O(1)$**，大幅提升后台常驻省电性能。
- **🛡️ AndroidKeyStore 硬件加密密钥自动故障自愈 (`MultiForwardConfig.kt`)**：
  - 针对 Android 系统升级或修改锁屏密码可能导致 KeyStore 密钥永久失效的隐患，增加异常捕获与 `deleteEntry` 自动清理重建机制，杜绝凭据加解密模块陷入永久瘫痪。
- **📱 未接来电广播 `goAsync` 与进程被杀状态持久化 (`CallStateReceiver.kt`)**：
  - 采用 `goAsync()` 将多渠道入队从广播主线程剥离至 IO 协程并发执行；状态机改用 SharedPreferences 暂态持久化，彻底解决响铃期间备用机进程被杀导致状态丢失的漏洞。
- **🚀 转发历史存储全面异步化 (`ForwardingHistoryStore.kt`)**：
  - 将所有的同步阻塞式 `.commit()` 全量升级为异步 `.apply()`，彻底杜绝多通道并发转发时触发 BroadcastReceiver / UI 主线程 ANR 卡死的风险。
- **📦 WorkManager 10KB 载荷预算安全截断 (`MultiChannelForwardWorker.kt`)**：
  - 针对极端超长短信/彩信，在序列化入队前设置 4000 字符安全防御截断，彻底消除超过 Android WorkManager 10KB Data 限制引发崩溃的风险。
- **🔋 低电量阈值下调漏提醒修复 (`LowBatterySettingsActivity.kt`)**：
  - 用户滑动条调低阈值时自动重置 `lowBatteryLastNotifiedLevel = -1`，修复由此导致的低阈值不触发提醒 Bug。
- **🔍 异常与越界防御全面固化**：
  - 修复 `Context.kt` 会话索引 `.firstOrNull()` 安全防线，避免并发删除时的潜在越界；
  - 修复 `NumberFormatException` 解析风险，全量使用 `toLongOrNull() ?: 0L` 与 `toIntOrNull() ?: 0`；
  - 修复 `FloatingCodePillManager` 误调 `removeCallbacksAndMessages(null)` 隐患，改为只移除自身 Runnable。
- **📱 Android 12~15+ 系统合规加固**：
  - 为 `TransactionService` 显式标明 `android:exported="false"`，规范前台服务与广播权限声明。

---

### 🐛 社区真实反馈与特定机型专项排查修复 (Community Feedback Fixes)

- **📧 邮箱远程指令：RFC 2047 MIME 解码与 `<...>` 纯净邮箱正则提取 (`EmailRemoteCommandPoller.kt`)**：
  - 彻底解决 QQ 邮箱等邮件服务器返回带 MIME 编码的 `From` 头（形如 `["=?utf-8?B?...?=" <user@qq.com>]`）时被系统误判为“未授权发件人”的问题；
  - 增加主题与正文的 Base64 / Quoted-Printable 自动解码，让邮件发送的远程指令 100% 准确被识别。
- **🔌 钉钉 Stream 远程控制：增加 TCP/WebSocket Ping-Pong 心跳保活与智能退避重连 (`DingTalkStreamClient.kt`)**：
  - 注入 `20s` 原生心跳保活帧，防止被基站与路由器 NAT 超时掐断；
  - 针对 `Software caused connection abort` 增加智能排障指引日志，引导用户在钉钉开发者后台勾选 **【Stream 模式】** 并排查多端重复登录。
- **💬 企业微信远程控制：去重降噪与提示完善 (`WeComRemoteControlService.kt`)**：
  - 消除每分钟重复打印守护日志刷屏现象，明确提示企业微信接收指令需配置回调服务器，推荐免公网全双工场景优先使用钉钉/飞书 Stream 或 Telegram。
- **🚨 ColorOS / HyperOS / OriginOS 后台发信 5 秒倒计时弹窗拦截排查指引 (`DeviceCompatHelper.kt`)**：
  - 补充针对 OPPO / 小米 / vivo 系统自带「恶意扣费保护 / 后台发送短信拦截」的自检方案（建议设为默认短信应用，或在系统权限中将「发送短信」设为「始终允许」）。

---

## [1.1.4] - 2026-09-02

### 🌟 核心新特性与重大升级 (Major Features)
- **📡 远程控制发信渠道矩阵全面扩充 (Remote SMS Command Hub)**：
  - **🕊️ 飞书 Stream 远程控制**：基于飞书官方 OpenAPI WebSocket 长连接模式（无需公网 IP），订阅机器人消息事件，实时接收 `/发信 [SIM1|SIM2|默认] 目标号码 内容` 指令并调度发信。
  - **💬 企业微信自建应用远程控制**：支持企业微信应用凭证（CorpID / AgentID / Secret）与指定成员 UserID 白名单安全鉴权，安全管控远程发信权限。
  - **📧 邮箱 IMAP/SSL 远程控制**：支持通过 IMAP4/SSL 协议监听收件箱，支持发件人邮箱白名单校验，从主题或正文提取指令后调用本地 SIM 卡发信，发信后自动标记已读防止重复执行。
  - **🎛️ 远程控制中枢升级**：在「设置 → 远程控制」中集中提供短信、钉钉 Stream、飞书 Stream、企业微信应用、邮箱 5 大渠道的独立配置入口、状态指示灯、实时测试与执行日志查看。
- **🛡️ 全链路 4 大长连接服务保活与自愈守护**：
  - 在应用冷启动 (`MainActivity`) 与系统重启广播 (`RescheduleAlarmsReceiver`) 中注册全套远程长连接与轮询前台服务自愈链路。

### 🐛 问题修复与视觉调优 (Fixed & Improved)
- **🎨 彻底消除 Android 10+ 底部手势区域白色蒙层 (System Scrim Fix)**：
  - 彻底解决 Android 10（API 29+）启用了深色手势条后由系统底层 SurfaceFlinger 强行盖上的半透明白色保护蒙层（Scrim）；
  - 针对对话详情页设置 `enforceNavigationBarContrast = false`，窗口背景与根布局统一采用 `#F8FAFC`，实现纯净通透背景。
- **📐 会话底部输入栏悬浮间距微调**：
  - 优化输入框底栏内边距（`paddingBottom="10dp"`）与会话列表底部垫高高度（`56dp`），彻底解决多余空白与遮挡问题。

---

## [1.1.3] - 2026-08-31

### 🌟 核心新特性与重大升级 (Major Features)
- **🤖 智能防对轰「短信自动回复」引擎 (Smart Anti-Loop Auto-Reply)**：
  - 支持按发件人号码过滤、关键词/排除词过滤、指定卡槽发信；
  - **自由冷却周期**：支持 **1 分钟到任意天数**（如 1 分钟、10 分钟、24 小时、不限制）自由自定义，每条规则独立配置；
  - **4 重防对轰熔断保护**：同号独立冷却 + 0~60 秒模拟真人发信延迟 + 单日上限全局熔断 + 回执实时上报，彻底告别机器人对轰死循环扣话费！
  - 经典版（设置 → 功能 → 短信自动回复）与开发版（规则工坊卡片）双入口支持。
- **🛡️ 接收链路双广播容灾降级 (Dual Broadcast Fallback)**：
  - 接入 `SMS_DELIVER`（默认应用模式）与 `SMS_RECEIVED`（非默认模式）双广播链路，配合全局 SHA-256 指纹防重环，非默认短信应用状态下亦能 100% 兜底接收转发且零重复。
- **🔑 自定义模板 `{{CODE}}` 修复与 18 大全能变量 (Message Template)**：
  - 彻底修复自定义模板中 `{{CODE}}` 验证码未在发信时被调用的问题；
  - 全量扩展支持 18 大变量（`{{CODE}}`、`{{FROM}}`、`{{CONTACT_NAME}}`、`{{SMS}}`、`{{RECEIVE_TIME}}`、`{{DATE_YMD}}`、`{{SIM_SLOT}}`、`{{DEVICE_BRAND}}`、`{{BATTERY_INFO}}`、`{{NET_TYPE}}`、`{{IP_LIST}}` 等）。
- **📦 配置与规则一键备份 / 迁移 (`ConfigBackupHelper`)**：
  - 支持将 12 大通道凭据、智能规则、消息模板一键导出为结构化 JSON 文件并支持换机快速导入。
- **📡 远程发信口令自定义**：
  - 支持用户在设置中自定义远程发信暗号前缀，原生支持 `/短信发送`、`/发信`、`/发短信`、`#发信` 等多重别名。

### 🐛 问题修复与体验优化 (Fixed & Improved)
- **🎨 深色与暗黑模式深度固化**：
  - 新增全套 `res/values-night/colors.xml` 主题规范，彻底解决对话气泡、输入框与全局 Dialog 弹窗在深色模式下的白底白字与对比度问题。
- **✨ 权限引导全面温和化**：
  - 取消冷启动强行弹出系统默认短信拦截框的逻辑，经典版与开发版体验统一为轻量级无感启动，首页新增温和状态提示条。
- **📊 设备自检报告扩展**：
  - 权限自检报告现已覆盖自动回复与双广播容灾链路健康状态。

---

## [1.1.2] - 2026-08-28

### 🌟 核心与视觉重大重构 (Major Features & Visual Redesign)
- **全新悬浮式毛玻璃胶囊底部导航栏 (Floating Capsule Dock)**：
  - 废弃贴底式导航栏，升级为悬浮于屏幕下方的独立圆角胶囊 Dock（`RoundedCornerShape(36.dp)` + 柔和阴影 + 细微边框 + 高刷动态光斑）。
  - 列表底部统一垫高安全距离（`100.dp`），彻底解决滑动遮挡问题。
- **经典版与开发版全态数据双向互通 (Two-Way Real-time Sync)**：
  - 统一 PushPlus、微信测试号、钉钉、飞书、企业微信、QQ、邮件、Bark、Telegram、Gotify 等 15 大通道底层加密 Token 仓库与开关状态，两版任意切换数据 100% 实时同步。
- **消息模板全面升级与两版共享 (Unified Message Template & Sandbox)**：
  - 经典版「设置 -> 功能」正式加入「消息模板」独立入口，与开发版【规则】共用强大的模板工作台 `MessageTemplateActivity`。
  - 支持 5 大预设模式（紧凑/标准/详细/Emoji/自定义）、12 大变量一键插值与智能提取验证码实时预览沙箱。
- **对话时间戳与卡槽智能对齐**：
  - 重构 `item_thread_date_time` 与 `ThreadAdapter`，接收短信靠左对齐、发出短信靠右对齐，卡槽标识 `[1] / [2]` 始终固定在时间戳最前。
- **顶栏底色与沉浸融合**：
  - 开发版 5 大页面顶栏底色全面还原品牌深色背景，沉浸式状态栏与内容流无缝衔接。
- **通道测试数据实时入库大盘**：
  - `ChannelTestSender` 在线测试结果自动归档至 `ForwardingHistoryStore`，大盘页面切换前台自动触发实时刷新，测试记录立即可见。
- **通道卡片防挤压重构**：
  - 按钮精简为「测试」两字，配置固定安全宽度，长状态提示自动折行，彻底解决文字竖排挤压问题。

### 🐛 问题修复 (Fixed)
- **全工程反射与生命周期安全加固**：
  - 排查并重构回收站 (`RecycleBinConversationsActivity`)、主界面 (`MainActivity`)、对话页 (`ThreadActivity`) 的 EventBus 订阅结构，彻底杜绝低版本 Android 因反射 `PictureInPictureUiState` 导致的闪退。

---

## [1.1.1] - 2026-08-28

### 🏗️ 现代化网关底层架构与 Compose 基础 (Architecture & Clean Core)
- **Android 10+ 与华为/荣耀双卡订阅解析彻底重构**：
  - 重构底层 `SubscriptionResolver`，彻底解决各厂商在 Android 10 (Q) ~ Android 14 下的 SIM1/SIM2 绑定与发信权限兼容问题。
- **Outbox 离线事务队列系统 (`OutboxDispatcher`)**：
  - 构建基于 Room 数据库的事务型 Outbox 任务队列（PENDING / RETRY / FAILED），网络断开自动暂存，恢复后按指数退避策略自动恢复发送。
- **Recovery 自动补偿与状态自愈引擎 (`RecoveryEngine`)**：
  - 引入后台周期性巡检与自愈补偿机制，杜绝偶发性漏发与状态丢失。
- **开发版 5 大核心工作台架构奠定**：
  - 全面引入 Jetpack Compose，搭建【信息】、【大盘】、【规则】、【通道】、【运维】5 大业务模块架构与内存环形日志缓冲区（`RingBufferLogManager`）。

---

## [1.1.0] - 2026-08-27

### 🌟 新增功能 (Added)
- **新增 4 大高价值原生推送通道（借鉴 message-pusher 优秀方案）**：
  - 🟢 **微信测试号 (WeChat Test Account)**：个人无需企业资质，通过微信官方公众号模板消息直推个人微信，支持置顶且不折叠。
  - ✈️ **Telegram Bot**：支持富文本 Markdown 排版、图片与极速推送，内置支持自定义 API 反代 Host 解决国内网络限制。
  - 🌐 **自定义通用 Webhook**：支持 GET/POST/PUT/PATCH 请求方法、自定义请求头 (Headers) 与动态 Body 模板变量（`{{TITLE}}`、`{{CONTENT}}`、`{{FROM}}`、`{{SMS}}`、`{{TIME}}`），无缝对接 message-pusher、Server 酱、PushDeer 及用户自建后端。
  - 🎮 **Discord Webhook**：在 Discord 频道内生成 Webhook 即可即时接收短信转发。
- **安全存储与多卡分流**：
  - 所有新通道密钥（AppSecret、Bot Token、Headers 等）均采用 Android Keystore AES-GCM 安全硬件加密存储。
  - 全量适配 SIM1/SIM2 卡槽规则分流引擎与设备低电量告警通知。

### 🐛 问题修复 (Fixed)
- **彻底解决 Android 10/11 闪退顽疾**：
  - 修复了华为 nova 5z、红米 9a 等 Android 10/11 机型上因 EventBus 反射扫描 Activity 继承树缺失 `PictureInPictureUiState` 类而引发 `NoClassDefFoundError` 崩溃的问题（关联 GitHub Issue [#20](https://github.com/2756826865/android-sms-forwarder/issues/20)）。
  - 重构为独立轻量监听器与全局 Throwable 双重兜底架构。
- **网络与接口容错增强**：
  - 修复了部分 Webhook 服务返回 HTTP 204 No Content 空响应时误判失败的问题。
  - 优化了网络异常与鉴权失败时的提示信息解析。

---


## [1.0.9] - 2026-08-26
### Added
- 全新的自定义转发模板系统，支持 {{FROM}}, {{SMS}}, {{RECEIVER_NUMBER}} 等 12 种动态标签。
- 自定义模板设置中增加快捷插入面板，提升配置效率。
- 首页及对话详情页增加下拉刷新（Swipe to Refresh）功能。

### Fixed
- **小米/HyperOS 深度兼容**：解决 10086 等服务号发送短信后气泡消失的顽疾。
- **MMS SMIL 容错**：修复部分彩信解析 XML 时导致的崩溃及日志堆栈堆积问题。
- **性能优化**：优化 getMessages 查询逻辑，大幅减少系统 Provider 的冗余访问。
- **资源泄露**：修复多处 Cursor 未关闭导致的内存与系统资源泄露。

### Changed
- 移除了冗余的黄页识别功能及数据库，应用体积减小约 0.5MB。
- 将所有数据库敏感操作迁移至后台线程，提升 UI 流畅度。


## [1.9.0] - 2026-07-12
### Added
- Added a group message format choice on the first group send ([#52])

### Changed
- Updated translations

### Fixed
- Partially fixed issue with sending MMS images ([#45])
- Fixed slow loading of the conversation list ([#234])

## [1.8.1] - 2026-07-09
### Changed
- Updated translations

### Fixed
- Fixed messages being sent to the wrong contact ([#615])
- Fixed incomplete message exports ([#713])
- Fixed crash when viewing older messages
- Other stability improvements

## [1.8.0] - 2026-01-30
### Added
- Added support for custom fonts
- Added "Copy number to clipboard" option inside chat overflow menu ([#651])

### Changed
- Improved multi-message copy formatting with timestamps and sender names
- Updated translations

### Fixed
- Fixed missing notifications in some cases ([#159])
- Fixed incorrect blocking of MMS messages in some rare cases ([#644])
- Fixed issue with importing alphanumeric blocked numbers ([#282])
- Fixed issue where scheduled messages were not sent after a reboot or app updates ([#641])

## [1.7.0] - 2025-12-16
### Added
- Ability to select and copy multiple text messages at once ([#600])

### Changed
- Updated translations

### Deprecated
- Deprecated the recycle bin feature ([#290])

### Fixed
- Fixed new conversation shortcut ([#416])
- Fixed blocking MMS messages from unknown numbers ([#610])

## [1.6.0] - 2025-10-29
### Changed
- Compatibility updates for Android 15 & 16
- Calling now works directly without launching dialpad ([#562])
- Search bar is now pinned to the top when scrolling
- Updated translations

### Fixed
- Fixed freezing when sending messages ([#574])

## [1.5.0] - 2025-10-18
### Added
- Unread badge count for conversations ([#177])

### Changed
- Optimized loading messages in conversations ([#234])
- Updated conversation item design to be more compact ([#376])
- Pin/unpin actions now always show as action buttons in the menu ([#561])
- Updated translations

### Fixed
- Fixed position reset when opening attachments in conversations ([#82])
- Fixed automatic scroll to searched message in conversations ([#350])
- Fixed non-standard text and avatar sizes in list items
- Fixed "Mark as read" not working in some cases ([#264])

## [1.4.0] - 2025-10-12
### Added
- Ability to save multiple attachments ([#75])
- Ability to select numbers that aren't starred when starting a new conversation ([#153])

### Changed
- Reordered menu options throughout the app
- Updated translations

### Fixed
- Fixed keyword blocking for MMS messages ([#99])
- Fixed contact number selection when adding members to a group ([#456])
- Fixed a glitch in pattern lock after incorrect attempts
- Fixed disabled send button when sending images without text ([#165])

## [1.3.0] - 2025-09-09
### Added
- Option to keep conversations archived ([#334])

### Changed
- Updated translations

## [1.2.3] - 2025-08-21
### Changed
- Updated translations

### Fixed
- Fixed stale/missing notification badge on some devices

## [1.2.2] - 2025-08-01
### Changed
- Updated translations

### Fixed
- Fixed inability to view messages when there is no SIM card ([#461])

## [1.2.1] - 2025-06-17
### Changed
- Preference category labels now use sentence case
- Updated translations

## [1.2.0] - 2025-06-04
### Added
- Conversation shortcuts ([#209])

### Changed
- Updated translations

## [1.1.7] - 2025-04-01
### Changed
- Added more translations

### Fixed
- Fixed incorrect cursor position when reopening the app ([#349])
- Fixed scrolling issue on conversation details screen ([#359])

## [1.1.6] - 2025-03-24
### Changed
- Other minor fixes and improvements
- Added more translations

### Removed
- Removed storage permission requirement ([#309])

### Fixed
- Fixed crash when viewing messages
- Fixes incorrect author name in group messages ([#180])

## [1.1.5] - 2025-02-02
### Changed
- Added more translations

### Fixed
- Fixed issue with third party intents ([#294])
- Fixed toast error when receiving MMS messages ([#287])
- Fixed RTL layout issue in threads ([#279])

## [1.1.4] - 2025-01-23
### Changed
- Added more translations

### Fixed
- Fixed issue with forwarding messages ([#288])

## [1.1.3] - 2025-01-05
### Changed
- Added more translations

### Fixed
- Fixed issues with conversation date update ([#225], [#274])

## [1.1.2] - 2025-01-05
### Changed
- Added more translations

### Fixed
- Fixed issues with conversation date update ([#225], [#274])

## [1.1.1] - 2025-01-04
### Changed
- Improved third party SMS/MMS intent parsing ([#217], [#243])
- Modified short code check to exclude emails ([#115])
- Other minor bug fixes and improvements
- Added more translations

### Fixed
- Fixed issue with messages draft deletion ([#13])
- Fixed multiple toast errors for MMS messages ([#70], [#262])
- Fixed some layout issues in message thread ([#135])

## [1.1.0] - 2024-12-27
### Changed
- Replaced checkboxes with switches
- Improved app lock logic and interface
- Other minor bug fixes and improvements
- Added more translations

### Removed
- Removed support for Android 7 and older versions

### Fixed
- Fixed various issues related to importing/exporting messages
- Fixed keyword blocking for MMS messages
- Fixed issue with messages draft deletion

## [1.0.1] - 2024-02-09
### Changed
- Minor bug fixes and improvements
- Added some translations

## [1.0.0] - 2024-01-24
### Added
- Initial release

[#13]: https://github.com/FossifyOrg/Messages/issues/13
[#45]: https://github.com/FossifyOrg/Messages/issues/45
[#52]: https://github.com/FossifyOrg/Messages/issues/52
[#70]: https://github.com/FossifyOrg/Messages/issues/70
[#75]: https://github.com/FossifyOrg/Messages/issues/75
[#82]: https://github.com/FossifyOrg/Messages/issues/82
[#99]: https://github.com/FossifyOrg/Messages/issues/99
[#115]: https://github.com/FossifyOrg/Messages/issues/115
[#135]: https://github.com/FossifyOrg/Messages/issues/135
[#153]: https://github.com/FossifyOrg/Messages/issues/153
[#159]: https://github.com/FossifyOrg/Messages/issues/159
[#165]: https://github.com/FossifyOrg/Messages/issues/165
[#177]: https://github.com/FossifyOrg/Messages/issues/177
[#180]: https://github.com/FossifyOrg/Messages/issues/180
[#209]: https://github.com/FossifyOrg/Messages/issues/209
[#217]: https://github.com/FossifyOrg/Messages/issues/217
[#225]: https://github.com/FossifyOrg/Messages/issues/225
[#234]: https://github.com/FossifyOrg/Messages/issues/234
[#243]: https://github.com/FossifyOrg/Messages/issues/243
[#262]: https://github.com/FossifyOrg/Messages/issues/262
[#264]: https://github.com/FossifyOrg/Messages/issues/264
[#274]: https://github.com/FossifyOrg/Messages/issues/274
[#279]: https://github.com/FossifyOrg/Messages/issues/279
[#282]: https://github.com/FossifyOrg/Messages/issues/282
[#287]: https://github.com/FossifyOrg/Messages/issues/287
[#288]: https://github.com/FossifyOrg/Messages/issues/288
[#290]: https://github.com/FossifyOrg/Messages/issues/290
[#294]: https://github.com/FossifyOrg/Messages/issues/294
[#309]: https://github.com/FossifyOrg/Messages/issues/309
[#334]: https://github.com/FossifyOrg/Messages/issues/334
[#349]: https://github.com/FossifyOrg/Messages/issues/349
[#350]: https://github.com/FossifyOrg/Messages/issues/350
[#359]: https://github.com/FossifyOrg/Messages/issues/359
[#376]: https://github.com/FossifyOrg/Messages/issues/376
[#416]: https://github.com/FossifyOrg/Messages/issues/416
[#456]: https://github.com/FossifyOrg/Messages/issues/456
[#461]: https://github.com/FossifyOrg/Messages/issues/461
[#561]: https://github.com/FossifyOrg/Messages/issues/561
[#562]: https://github.com/FossifyOrg/Messages/issues/562
[#574]: https://github.com/FossifyOrg/Messages/issues/574
[#600]: https://github.com/FossifyOrg/Messages/issues/600
[#610]: https://github.com/FossifyOrg/Messages/issues/610
[#615]: https://github.com/FossifyOrg/Messages/issues/615
[#641]: https://github.com/FossifyOrg/Messages/issues/641
[#644]: https://github.com/FossifyOrg/Messages/issues/644
[#651]: https://github.com/FossifyOrg/Messages/issues/651
[#713]: https://github.com/FossifyOrg/Messages/issues/713
[#829]: https://github.com/FossifyOrg/Messages/issues/829

[Unreleased]: https://github.com/FossifyOrg/Messages/compare/1.9.1...HEAD
[1.9.1]: https://github.com/FossifyOrg/Messages/compare/1.9.0...1.9.1
[1.9.0]: https://github.com/FossifyOrg/Messages/compare/1.8.1...1.9.0
[1.8.1]: https://github.com/FossifyOrg/Messages/compare/1.8.0...1.8.1
[1.8.0]: https://github.com/FossifyOrg/Messages/compare/1.7.0...1.8.0
[1.7.0]: https://github.com/FossifyOrg/Messages/compare/1.6.0...1.7.0
[1.6.0]: https://github.com/FossifyOrg/Messages/compare/1.5.0...1.6.0
[1.5.0]: https://github.com/FossifyOrg/Messages/compare/1.4.0...1.5.0
[1.4.0]: https://github.com/FossifyOrg/Messages/compare/1.3.0...1.4.0
[1.3.0]: https://github.com/FossifyOrg/Messages/compare/1.2.3...1.3.0
[1.2.3]: https://github.com/FossifyOrg/Messages/compare/1.2.2...1.2.3
[1.2.2]: https://github.com/FossifyOrg/Messages/compare/1.2.1...1.2.2
[1.2.1]: https://github.com/FossifyOrg/Messages/compare/1.2.0...1.2.1
[1.2.0]: https://github.com/FossifyOrg/Messages/compare/1.1.7...1.2.0
[1.1.7]: https://github.com/FossifyOrg/Messages/compare/1.1.6...1.1.7
[1.1.6]: https://github.com/FossifyOrg/Messages/compare/1.1.5...1.1.6
[1.1.5]: https://github.com/FossifyOrg/Messages/compare/1.1.4...1.1.5
[1.1.4]: https://github.com/FossifyOrg/Messages/compare/1.1.3...1.1.4
[1.1.3]: https://github.com/FossifyOrg/Messages/compare/1.1.2...1.1.3
[1.1.2]: https://github.com/FossifyOrg/Messages/compare/1.1.1...1.1.2
[1.1.1]: https://github.com/FossifyOrg/Messages/compare/1.1.0...1.1.1
[1.1.0]: https://github.com/FossifyOrg/Messages/compare/1.0.1...1.1.0
[1.0.1]: https://github.com/FossifyOrg/Messages/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/FossifyOrg/Messages/releases/tag/1.0.0

