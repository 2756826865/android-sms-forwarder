# SMS Forwarder · Android 默认短信与多渠道转发网关

<p align="center">
  <img src="graphics/icon.webp" width="112" alt="SMS Forwarder 图标" />
</p>

<p align="center">
面向 Android 平台的开源现代化默认短信客户端与多渠道转发网关。
支持<b>「经典日常模式」</b>与<b>「现代化 SMS Gateway 开发版工作台」</b>双模式无缝切换。
</p>

<p align="center">
  <b>简体中文</b>
  ·
  <a href="README.en.md">English</a>
  ·
  <a href="https://github.com/2756826865/android-sms-forwarder">项目地址</a>
  ·
  <a href="https://github.com/2756826865/android-sms-forwarder/releases/latest">下载最新版</a>
  ·
  <a href="https://github.com/2756826865/android-sms-forwarder/issues">问题反馈</a>
  ·
  <a href="CUSTOM_BUILD.md">构建说明</a>
</p>

> 本项目是独立维护的 GPL-3.0 开源分支，不是 Fossify 官方版本，也不隶属于华为、小米、PushPlus 或任何运营商、消息平台。

---

## 🔄 双模式架构与自由切换

本项目采用 **「经典短信模式」** 与 **「开发模式（SMS Gateway 工作台）」** 双视图架构，底层数据 100% 双向实时互通：

| 模式 | 核心定位 | 适用场景 | 切换方式 |
| :--- | :--- | :--- | :--- |
| **📱 经典模式 (Classic)** | 完整的默认短信应用体验，支持气泡会话、双卡收发、批量群发与轻量级转发。 | 主力机日常发信收信、备用机日常短信管理。 | 主界面右上角菜单 → 点击 **「切换到开发版工作台」**。 |
| **🖥️ 开发模式 (SMS Gateway)** | Jetpack Compose 现代化工作台，提供流水监视、运行大盘看板、通道中枢、规则沙箱与自愈运维。 | 备用机做短信转发服务器、极客监控、系统集成。 | 开发版任意界面顶栏 → 点击 **「返回经典版」**。 |

---

## 🌟 核心功能特性

### 1. 现代化 SMS Gateway 5 大核心工作台 (Compose)
- 💬 **【信息中心】(Message Center)**：短信出入站全生命周期实时报文监控流水。
- 📊 **【大盘监控】(Dashboard)**：今日收发成功率看板、环形统计图表、Outbox 离线事务队列深度与卡槽吞吐率监控，通道在线测试发送自动归档入库。
- 🛠️ **【规则工坊】(Rule Studio)**：全新模板系统，支持 5 大预设模式（紧凑/标准/详细/Emoji/自定义）、12 大变量一键插值与智能提取验证码实时预览沙箱。
- 🔌 **【通道中枢】(Channel Hub)**：集中管理 11 大原生推送通道，状态指示灯与快捷测试一键直达。
- 🧰 **【运维诊断】(Operations)**：内置 500 条环形内存日志监视器，支持实时滚动与等级过滤（INFO/WARN/ERROR）、一键手动自愈补偿与诊断包导出。

### 2. 11 大主流推送通道矩阵 (All Supported Channels)
- 🟢 **微信测试号 (WeChat Test Account)**：个人无需企业资质，通过微信官方公众号模板消息直推个人微信，支持置顶且不折叠。
- ✈️ **Telegram Bot**：支持富文本 Markdown 排版、图片与极速推送，内置支持自定义 API 反代 Host 解决国内网络限制。
- 🌐 **通用自定义 Webhook**：支持 GET/POST/PUT/PATCH 请求方法、自定义请求头 (Headers) 与动态 Body 模板变量（`{{TITLE}}`、`{{CONTENT}}`、`{{FROM}}`、`{{SMS}}`、`{{TIME}}`），无缝对接 message-pusher、Server 酱、PushDeer 及用户自建后端。
- 🎮 **Discord Webhook**：频道群组即时推送。
- 💬 **企业微信应用/机器人 (WeCom)**：支持群机器人 Webhook 与企业应用消息直推。
- 📌 **钉钉群机器人 (DingTalk)**：支持签名密钥加签与自定义关键词过滤。
- 🕊️ **飞书群机器人 (Feishu)**：支持飞书 Webhook 与签名校验推送。
- 🍏 **Bark (iOS)**：支持 iPhone/iPad/Apple Watch 极速推送与分组管理。
- 📬 **PushPlus (微信推送)**：支持一对一微信公众号推送与群组订阅。
- 📧 **邮件 SMTP (Email)**：支持 SSL/TLS 加密与各大国内外邮箱服务器。
- 🔔 **Gotify 自建推送**：支持私有化部署通知服务器。

### 3. 📡 8 大远程控制发信渠道矩阵 (Remote SMS Command Hub · v1.1.5)
支持通过远程接收指令（如 `/发信 [SIM1|SIM2|默认] 13800138000 短信内容`）驱动备用机本地 SIM 卡发送短信：
- ✈️ **Telegram Bot 模式**：基于 `getUpdates` 长轮询与自定义反代 Host 支持，支持 ChatID/UserID 白名单，回执原路直接响应；
- 🔌 **WebSocket 全双工模式**：支持与服务端全双工长连接，服务端主动下发 `send_sms` JSON 载荷，发信及送达状态通过同一连接实时上推；
- 🐧 **QQ (OneBot 11) 模式**：支持 OneBot 11 WebSocket 协议，支持 QQ 号/群号白名单与「群聊必须 @机器人」开关，回执原路回复；
- 📌 **钉钉 Stream 模式**：无需公网 IP，通过钉钉官方长连接协议接收机器人消息指令；
- 🕊️ **飞书 Stream 模式**：通过飞书 OpenAPI WebSocket 长连接订阅机器人事件；
- 💬 **企业微信自建应用**：支持 CorpID/AgentID/Secret 鉴权与成员 UserID 白名单安全过滤；
- 📧 **邮箱 IMAP/SSL 轮询**：支持 QQ/163/Gmail 等邮箱轮询与发件人白名单安全校验；
- 📱 **短信远程指令**：支持授权号码通过 SMS 发送暗号指令；
- 🧾 **回执闭环与去重防重**：全渠道支持 SHA-256 指纹防重、规则引擎安全拦截、原路直连回复与发信状态回执统一上报。

### 4. 🤖 智能防对轰「短信自动回复」引擎 (v1.1.3 新增)
- **精准触发**：支持指定发件人号码过滤、关键词/排除词过滤、指定发信卡槽。
- **4 重防对轰保护**：
  - **自由冷却周期**：支持 **1 分钟到任意天数**（如 1 分钟、10 分钟、24 小时或不限制）自定义设置；
  - **发信延迟**：0~60 秒模拟真人发信；
  - **日上限熔断**：防止短时间内大量回复消耗话费；
  - **回执上报**：自动记录并推送执行回执。

### 4. 消息模板与智能变量插值
- **两版无缝共享**：经典版「设置 -> 功能 -> 消息模板」直接直通开发版「规则工坊」，双端配置实时同步。
- **支持 18 大动态模板变量**：
  - `{{CODE}}` 智能提取验证码、`{{FROM}}` 发信号码、`{{SMS}}` 短信正文、`{{RECEIVE_TIME}}` 接收时间、`{{DATE_YMD}}` 仅日期、`{{DATE_HMS}}` 仅时间、`{{CONTACT_NAME}}` 联系人姓名
  - `{{SIM_SLOT}}` 卡槽标识、`{{SIM_INDEX}}` 卡槽序号、`{{RECEIVER_NUMBER}}` 接收卡号
  - `{{DEVICE_NAME}}` 设备型号、`{{DEVICE_BRAND}}` 设备品牌、`{{BATTERY_INFO}}` 电池电量状态、`{{BATTERY_PCT}}` 电量百分比、`{{IP_LIST}}` 当前 IP 地址、`{{NET_TYPE}}` 网络类型、`{{APP_VERSION}}` 应用版本、`{{CURRENT_TIME}}` 当前时间
- **智能验证码提取沙箱**：内置正则与前后瞻匹配引擎，精准提取 4~8 位数字验证码。

### 5. 🛡️ 双广播容灾降级与数据备份 (v1.1.3 新增)
- **双广播容灾**：`SMS_DELIVER` + `SMS_RECEIVED` 双通道监听，结合 SHA-256 全局指纹去重，非默认短信应用亦能 100% 稳定接收转发。
- **一键配置备份与还原 (`ConfigBackupHelper`)**：支持将 12 大通道凭据、智能规则、消息模板一键导出为结构化 JSON 文件并支持快速导入。

### 4. 离线事务队列与自愈恢复引擎
- **Outbox 事务队列 (`OutboxDispatcher`)**：断网或息屏休眠时自动暂存（`PENDING / RETRY / FAILED`），网络恢复后按指数退避策略自动重试。
- **Recovery 自动补偿 (`RecoveryEngine`)**：后台周期性巡检与自愈补偿机制，杜绝偶发性漏发与状态丢失。

### 5. 极致视觉与细节打磨
- 🛸 **全新悬浮式毛玻璃胶囊 Dock 导航**：采用 `RoundedCornerShape(36.dp)` 悬浮圆角胶囊与动态高刷光斑，列表底部统一垫高 `100.dp` 彻底解决遮挡。
- 📱 **通道卡片防挤压重构**：按钮精简为「测试」两字并锁定最小安全宽度，彻底解决窄屏文字竖排挤压问题。
- ⏱️ **会话时间戳与卡槽智能对齐**：接收短信靠左、发出短信靠右，卡槽标识 `[1] / [2]` 始终固定在时间戳最前部。

---

## 🛡️ 隐私、安全与兼容性

### 1. 硬件级安全加密 (Keystore AES-GCM)
- 所有通道密钥（AppSecret、Bot Token、Headers 等）均采用 **Android Keystore AES-GCM 硬件级加密** 存储；
- 具备旧版本无缝平滑迁移与硬件不可用时的内存加密自动回退机制。

### 2. Android 10~14 全平台防闪退加固
- 全面重构主界面 (`MainActivity`)、回收站 (`RecycleBinConversationsActivity`) 与对话页 (`ThreadActivity`) 的事件订阅架构，彻底杜绝华为 nova 5z、红米 9a 等低版本 Android 10/11 因 EventBus 反射扫描 `PictureInPictureUiState` 导致的启动闪退。

### 3. 多卡分流与低电量告警
- 重构底层 `SubscriptionResolver`，完美兼容小米 HyperOS、华为/荣耀 EMUI/MagicOS、OPPO/vivo 的 SIM1/SIM2 双卡规则分流与发信权限。
- 支持低电量智能检测与告警分流推送。

---

## ⚙️ 息屏保活与后台设置指南

为了确保备用机或主力机在长时间息屏休眠时稳定转发，请确保开启以下系统权限（可在应用内「设置 -> 后台运行/设备兼容」中一键跳转设置）：

1. 🔋 **电池优化**：设置为 **「无限制」 / 「允许后台高耗电」**；
2. 🚀 **自启动权限**：设置为 **「允许自启动」 / 「允许关联启动」**；
3. 🔒 **后台多任务加锁**：在多任务界面给应用卡片加上 **「锁头 🔒」**；
4. 📱 **设为默认短信应用**：推荐设置为系统默认短信 App，以获得最高系统保活优先级。

---

## 🛠️ 本地构建

```bash
# 克隆仓库
git clone https://github.com/2756826865/android-sms-forwarder.git
cd android-sms-forwarder

# 编译 Core Debug APK
./gradlew :app:assembleCoreDebug

# 编译 Release APK
./gradlew :app:assembleCoreRelease
```

编译输出目录：`app/build/outputs/apk/core/`

---

## 📄 开源许可与致谢

- 本项目基于 [Fossify Messages](https://github.com/FossifyOrg/Messages) 衍生开发，遵循 **GPL-3.0** 开源协议；
- 部分 Webhook 及推送通道实现借鉴并优化自 [message-pusher](https://github.com/songquanpeng/message-pusher) 优秀方案。
