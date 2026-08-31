# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
