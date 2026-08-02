# Codex 接手与打包说明

本文件是后续 Codex 会话接手本项目时的第一入口。请先完整阅读，再修改、提交或打包。

## 一、项目身份

- 项目：Fossify Messages 短信转发定制版
- 正式版本：`1.0.1`
- `versionCode`：`1008`
- 正式包名：`com.helyu.smsforwarder`
- 最低 Android：API 26（Android 8.0）
- 编译 SDK：API 36
- Java：JDK 17
- 主仓库：`https://github.com/2756826865/sms-forwarder-huawei`
- 工作分支：`main`
- 上游仓库：`https://github.com/FossifyOrg/Messages.git`
- 正式 APK 名：`SMS-Forwarder-1.0.1-release.apk`

不要更改正式包名，不要生成新的签名证书，也不要把 JKS、密码、Token 或 GitHub 凭据提交到 Git。

## 二、Codex 接手后的第一轮检查

```bash
git status --short
git remote -v
git log -5 --oneline
git diff --check
```

必须保留用户已有改动。工作树不干净时，只处理本任务相关文件，不要执行 `git reset --hard`、递归删除或覆盖用户修改。

## 三、当前已经完成的主要功能

- 作为 Android 默认短信应用收发短信、读取系统短信库和通讯录。
- 支持手动输入号码、批量发送、1–5 秒批量间隔和定时发送。
- 支持 PushPlus、钉钉群机器人、飞书群机器人、企业微信应用消息和电子邮箱转发。
- SIM1/SIM2 支持自定义名称和号码，转发模板支持简洁、标准、详细模式。
- 短信转发首次使用必须同意免责声明。
- 支持黑名单、白名单、关键词拦截和最近删除。
- 支持华为/Honor及小米/Redmi/POCO后台设置引导。
- 后台接收链路包含短信广播、前台保活服务、WorkManager 补偿同步、开机/解锁/亮屏恢复和新短信通知。
- 主界面、会话页、设置页、多选页统一为当前约定的浅灰、白色和绿色视觉。
- Fossify 假版本检测入口、缓存和提示链路已处理；Release 构建会扫描 DEX，发现英文警告就直接失败。

## 四、本地构建

本地环境需要 JDK 17、Android SDK 36、可访问 Google Maven/Maven Central 的网络。

Debug：

```bash
./gradlew :app:assembleCoreDebug
```

输出目录：

```text
app/build/outputs/apk/core/debug/
```

本地 Release 需要在仓库根目录创建未纳入 Git 的 `keystore.properties`，格式参考 `keystore.properties_sample`。不要把正式签名文件或该配置提交到仓库。

```bash
./gradlew :app:assembleCoreRelease
```

## 五、GitHub Actions 正式打包

仓库工作流：`.github/workflows/build-custom-apk.yml`

仓库已经配置以下加密 Secrets，禁止在日志中输出它们：

- `ANDROID_KEYSTORE_BASE64`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_STORE_PASSWORD`

推送 `main` 会自动构建，也可以手动触发：

```bash
gh workflow run "Build custom APK" --repo 2756826865/sms-forwarder-huawei
gh run list --repo 2756826865/sms-forwarder-huawei --workflow "Build custom APK" --limit 5
gh run watch RUN_ID --repo 2756826865/sms-forwarder-huawei --exit-status
```

下载成功产物：

```bash
mkdir -p deliverables/1.0.1
gh run download RUN_ID \
  --repo 2756826865/sms-forwarder-huawei \
  --name SMS-Forwarder-1.0.1-release \
  --dir deliverables/1.0.1
```

如果环境没有 `gh`，不要修改认证配置。可以使用已经登录的 Git 凭据推送，再从 GitHub Actions 页面下载；或让用户向 Codex 工作区提供可信的 `gh` 可执行文件。

## 六、Release 强制验收

每次正式交付至少完成以下检查：

```bash
unzip -tq SMS-Forwarder-1.0.1-release.apk
unzip -p SMS-Forwarder-1.0.1-release.apk 'classes*.dex' \
  | strings \
  | grep -F 'You are using a fake version'
sha256sum SMS-Forwarder-1.0.1-release.apk
```

第二条命令必须无输出。工作流也会执行同类扫描，检测到 Fossify 假版本警告就拒绝上传 APK。

使用 Android SDK 的 `apksigner` 验证：

```bash
apksigner verify --verbose --print-certs SMS-Forwarder-1.0.1-release.apk
```

正式证书 SHA-256 必须是：

```text
227d5ed7b74462e37d6e88c424eb0aad58a33acb1b6e193f2e2d08a650107c96
```

2026-08-02 已通过的基准构建：

- GitHub Actions Run：`30735678948`
- APK 大小：`6,266,609` 字节
- APK SHA-256：`3126ac3f7c4b56c8b2c842e267e3fbe9ce2514479f86cffef86744b85cc1a61d`
- 签名：APK Signature Scheme v2 验证通过
- DEX：不存在 Fossify 假版本英文警告

大小约 6 MB 是启用 R8 和资源裁剪后的正式 Release，不是功能不完整的测试包。

## 七、真机回归清单

编译通过不等于真机全部通过。交付前优先在华为 EMUI/HarmonyOS 4.x 和小米 MIUI/HyperOS 双卡设备测试：

1. 全新安装、清除数据、首次启动、主页、会话页、设置页和批量发送页均不出现 Fossify 英文警告。
2. 设置为默认短信应用，授予短信、联系人、电话状态和通知权限。
3. SIM1/SIM2 分别发送、接收、显示正确，并正确使用自定义卡名。
4. 前台、后台、息屏、锁屏、清理最近任务和重启后均能收信并显示任务栏通知。
5. 断网收信后，恢复网络能够补发转发且不重复。
6. PushPlus、钉钉、飞书、企业微信和邮箱分别执行测试消息与真实短信转发。
7. 新建短信、联系人选择、手动输入服务号码、批量发送间隔和定时发送正常。
8. 单条短信、整条会话删除后进入最近删除，并能恢复或彻底删除。
9. 长按会话、多选、标记已读、置顶和删除的布局与交互正常。

华为和小米的后台策略会随系统版本变化；若出现息屏漏收，先查看“后台运行与系统兼容”页以及系统自启动、电池优化、通知渠道和默认短信角色，不要用无限循环、秒级轮询或永久唤醒锁代替系统短信广播。

## 八、安装与升级

- Debug 包带 `.debug` 后缀且签名不同，迁移到正式版时必须先卸载 Debug 包。
- 从本项目正式版升级时，只要包名和上述证书一致即可覆盖安装。
- 更换签名后 Android 不允许覆盖升级，因此正式签名必须永久统一。

## 九、提交与交付原则

- 修改后先执行 `git diff --check`，再提交本任务相关文件。
- 代码推送到 `personal/main`，不要向 Fossify 上游仓库推送定制修改。
- 不把未经过真实 Gradle 编译的 APK 交给用户。
- GitHub Actions 失败时读取具体 Kotlin、资源、Manifest、R8 或签名日志，修复后继续运行到通过。
- 最终交付 APK、文件大小、SHA-256、签名证书摘要、Actions Run 链接和仍需真机验证的项目。

