# 短信转发（Fossify Messages + PushPlus）

这是基于 Fossify Messages 的个人定制版本。它作为 Android 默认短信应用接收 `SMS_DELIVER`，同时兼容部分系统使用的 `SMS_RECEIVED` 广播，支持收件箱、发件箱、通讯录或手动号码、独立批量发送，并把新短信通过 PushPlus 微信公众号渠道转发。

## 使用方式

1. 安装 APK，首次打开时将“短信转发”设为默认短信应用并授予短信、电话和通知权限。
2. 主界面右上角菜单进入“PushPlus 短信转发”。
3. 粘贴 PushPlus Token，打开“收到短信后自动推送”，保存配置。
4. 点击“发送测试消息”，在 PushPlus 微信公众号中确认测试消息。
5. 使用另一台手机发送一条普通短信进行完整测试。

## 批量发送

1. 主界面右上角菜单进入“批量发送短信”。
2. 搜索、逐个勾选或全选当前搜索结果；也可以直接输入 `10086`、手机号或带国家区号的号码并点击“添加号码”。
3. 选择发送 SIM 卡并填写正文。
4. App 会显示收件人数和预计计费短信条数，确认后逐个独立发送。

每位收件人收到的是独立 SMS，彼此不会看到号码。为降低误发、系统限流和运营商风控风险，单次限制 30 个号码，任务之间间隔 1 秒。

## 转发行为

- PushPlus 渠道固定为 `wechat`，模板为 `txt`。
- 默认标题为短信发送号码，可选标题前缀。
- 正文可选择包含发送号码、SIM 卡信息和接收时间。
- PushPlus Token 使用 Android Keystore 的 AES/GCM 密钥加密后保存在本机。
- 转发任务要求网络可用；网络中断时 WorkManager 使用指数退避自动重试。
- 以短信接收时间、发送方和正文生成唯一任务名，降低重复推送概率。

## 华为手机设置

在“设置 → 应用和服务 → 默认应用 → 短信”中确认本应用为默认短信应用。再到“应用启动管理”关闭自动管理，允许自启动、关联启动和后台活动。建议关闭省电模式并允许休眠时保持网络连接。

如果历史短信显示不完整，可在主界面右上角菜单选择“重新同步全部短信”。应用会合并系统短信库和本地缓存，并重建缺失的会话列表。

## 构建

需要 JDK 17、Android SDK 36 和可访问 Google Maven/Maven Central 的网络环境。

```bash
./gradlew :app:assembleCoreDebug
```

输出路径：

```text
app/build/outputs/apk/core/debug/
```

仓库也包含 `.github/workflows/build-custom-apk.yml`。推送到自己的 GitHub 仓库后，可以手动运行 `Build custom APK`，并从 Actions 的 `sms-forwarder-apk` 构建产物中下载 APK。

本项目沿用 Fossify Messages 的 GPL-3.0 许可证。公开分发修改版时应同时提供对应源代码。
