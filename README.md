# SMS Forwarder · Android 短信转发客户端

<p align="center">
  <img src="graphics/icon.webp" width="112" alt="SMS Forwarder 图标" />
</p>

<p align="center">
  基于 Fossify Messages 二次开发的 Android 默认短信应用，集短信收发、双卡管理、批量与定时发送、多渠道转发于一体。
</p>

<p align="center">
  <a href="https://github.com/2756826865/sms-forwarder-huawei/releases/latest">下载最新版</a>
  ·
  <a href="https://github.com/2756826865/sms-forwarder-huawei/issues">反馈问题</a>
  ·
  <a href="CUSTOM_BUILD.md">使用与构建说明</a>
</p>

> 本项目是独立维护的 GPL-3.0 开源分支，不是 Fossify 官方版本，也不隶属于华为、小米、PushPlus 或任何短信运营商。

## 项目简介

SMS Forwarder 可以替代系统短信应用，直接接管 Android 标准短信收发流程。它特别针对仍兼容 Android APK 的华为 EMUI / HarmonyOS 4.x 设备进行开发，同时使用标准 Android 接口兼容小米 MIUI / HyperOS 等系统。

收到短信后，应用可以按用户配置转发到 PushPlus、钉钉、飞书、企业微信应用消息或电子邮箱。网络暂时不可用时，后台任务会保留并自动重试。

<p align="center">
  <img src="design/reference-main-screen.png" width="360" alt="短信主界面" />
</p>

## 主要功能

- **完整短信客户端**：短信会话、搜索、已读、置顶、删除、最近删除、短信通知。
- **双卡收发**：识别 SIM1/SIM2，支持手动设置卡名和本机号码。
- **多种发送方式**：联系人或手动号码、新建短信、批量发送、1–5 秒发送间隔、定时发送。
- **多渠道转发**：PushPlus、钉钉群机器人、飞书群机器人、企业微信应用消息、电子邮箱。
- **转发模板**：简洁、标准、详细三种模板，可配置发送方、SIM 名称和接收时间。
- **后台可靠性**：短信广播、WorkManager 重试、补偿同步、开机/解锁/亮屏恢复和前台保活服务。
- **骚扰拦截**：黑名单、白名单和关键词拦截。
- **通讯录支持**：读取联系人名称与头像，同时允许直接输入 `10086` 等服务号码。
- **厂商兼容引导**：华为/Honor、小米/Redmi/POCO 的自启动和电池策略设置入口。
- **隐私与安全**：转发凭据保存在本机；Release 构建使用固定正式证书签名。

## 转发渠道

| 渠道 | 配置方式 | 适用场景 |
|---|---|---|
| PushPlus | Token | 微信公众号接收个人通知 |
| 钉钉 | 群机器人 Webhook | 钉钉群通知 |
| 飞书 | 群机器人 Webhook | 飞书群通知 |
| 企业微信 | Corp ID、Agent ID、Secret、接收对象 | 企业微信应用消息 |
| 电子邮箱 | SMTP 服务器、账号与授权码 | 邮件归档或跨设备接收 |

## 安装要求

- Android 8.0（API 26）及以上。
- 设备允许安装普通 Android APK。
- 必须将本应用设置为系统默认短信应用，并授予短信、联系人、电话状态和通知权限。
- 为保证息屏后接收与转发，请允许自启动、后台活动，并把电池策略设为不限制。

在华为设备上通常需要进入“应用启动管理”关闭自动管理，并允许自启动、关联启动和后台活动。小米设备需要在应用信息中开启自启动，并将省电策略调整为无限制。

## 下载与升级

正式 APK 请从 [GitHub Releases](https://github.com/2756826865/sms-forwarder-huawei/releases) 下载。

- 当前正式版：`1.0.1`（versionCode `1008`）
- 正式包名：`com.helyu.smsforwarder`
- 正式签名证书 SHA-256：

```text
227d5ed7b74462e37d6e88c424eb0aad58a33acb1b6e193f2e2d08a650107c96
```

Debug 包使用不同包名和签名，首次迁移到正式版时需要卸载 Debug 包。以后只要正式包名与证书保持一致，即可直接覆盖升级。

## 使用提示

1. 安装后设置为默认短信应用，并完成权限授权。
2. 打开“设置 → 后台运行与系统兼容”，按设备品牌完成后台权限设置。
3. 打开“设置 → 短信转发功能”，阅读并同意免责声明。
4. 选择并配置需要的转发渠道，先发送测试消息。
5. 再用另一台手机向 SIM1/SIM2 分别发送普通短信，验证短信列表、任务栏通知和真实转发。

## 隐私与风险说明

- 启用转发后，短信内容会发送到用户主动配置的第三方服务。请自行确认相应服务的隐私政策和账号安全。
- 短信可能包含验证码、账单、身份信息等敏感内容，不建议转发到多人群聊或不受信任的服务器。
- 批量发送可能产生运营商短信费用，并可能触发短时间发送限制。请遵守当地法律、运营商规则和接收方意愿。
- 本项目不提供云端服务，不收集用户短信；开发者不对用户配置的第三方渠道、网络服务或误操作造成的损失负责。

## 构建

需要 JDK 17、Android SDK 36，以及可访问 Google Maven 和 Maven Central 的网络环境。

```bash
git clone https://github.com/2756826865/sms-forwarder-huawei.git
cd sms-forwarder-huawei
./gradlew :app:assembleCoreDebug
```

Debug 输出目录：

```text
app/build/outputs/apk/core/debug/
```

正式版由 `.github/workflows/build-custom-apk.yml` 构建。签名材料只通过 GitHub Actions Secrets 注入，不提交到仓库。流水线会完成 R8/资源裁剪、签名校验，并阻止包含 Fossify 假版本警告的 APK 上传。

详细接手、签名验证和真机回归步骤见 [CODEX-RUN.md](CODEX-RUN.md)。

## 兼容与测试状态

- 华为 EMUI / HarmonyOS 4.x：主要开发与验证目标。
- 小米 MIUI / HyperOS：已加入系统设置引导，仍欢迎不同机型的真机反馈。
- 其他 Android 系统：使用标准短信、默认应用、通知和 WorkManager 接口，实际行为可能受厂商后台策略影响。

如果发现息屏漏收、通知缺失或双卡识别错误，请在 Issue 中附上设备型号、系统版本、是否为默认短信应用、权限状态和复现步骤，不要上传真实短信内容、Token 或账号密码。

## 开源许可与致谢

本项目基于 [Fossify Messages](https://github.com/FossifyOrg/Messages) 二次开发，遵循 [GNU General Public License v3.0](LICENSE)。公开分发修改版时必须继续提供相应源代码并遵守 GPL-3.0。

感谢 Fossify 社区及相关开源项目的贡献。
