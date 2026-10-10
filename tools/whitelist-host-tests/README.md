# 白名单 ID 申请针对性测试

仅编译测试所需的远程来源类，不执行 Gradle、不构建或打包完整 APK，不连接真实机器人，不发送真实短信。

```sh
python tools/whitelist-host-tests/fetch-deps.py
ANDROID_JAR=/absolute/path/to/android.jar python tools/whitelist-host-tests/run.py
```

Java 17、Python 3 和 Android API 37 stub。测试依赖默认放在临时目录 sms-whitelist-test-deps，可用 WHITELIST_TEST_DEPS 指定。Maven Central 下载固定版本的 Kotlin 2.2.0、OkHttp 4.12.0、飞书 SDK 2.8.5、JSON、JUnit 及依赖，仅供这个隔离的 JVM 测试；未更改项目 Gradle 的版本。

生产源码：四个机器人来源、运行时管理器、白名单请求格式/限流、飞书身份和前导提及处理。三个新增 app/src/test 测试类加 host 集成类，共18项测试。

stubs 下的存储、来源仓库、短信处理器、服务、邮箱和自定义 WebSocket 是测试替身，**不是应用代码**。Android Context 用宿主对象提供内存偏好接口。Telegram 回复仅发往临时 localhost HTTP 端点，用于断言请求目标和内容；没有真实 Token 或平台发送。钉钉/飞书/企微使用本地消息载荷回放，不启动连接。

检查范围：精确申请匹配、拒绝正文中间命中、ID原样保留、首选/备用用户ID、群主别名、私聊群ID提示、群聊@条件、重复与频率限制、Stream先ACK、申请不进入短信处理器、企微/飞书元数据映射、Telegram未授权请求的原会话回复，以及禁用/变更/失活来源不排队回复。

不涵盖 Compose 页面编译、完整 APK 依赖集成、真实 Android 持久存储、平台权限/群隐私模式、手机后台运行或运营商短信。真实会话回复仍需真机验收。
