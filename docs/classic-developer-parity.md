# 经典版与开发版功能入口对比（1.2.1）

代码入口核查，不代表所有设备/通道实测通过。两版共用应用数据及业务组件，但界面入口和展示范围并不完全相同。

| 功能 | 经典版 | 开发版 | 当前结论 |
|---|---|---|---|
| 短信会话/发短信 | MainActivity/ThreadActivity | ConversationsScreen → 同一 ThreadActivity | 共用发送界面与 Provider/Room；仍需三键/键盘真机验收 |
| 会话搜索/刷新 | 经典列表 | 联系人/号码 + 已同步正文数据库搜索 | 不加载整张短信表，正文会话结果上限500；未同步的历史需全量同步 |
| 通道配置 | ForwardingChannelsActivity | ChannelHubScreen | 共用配置；不能由入口存在推断所有参数均等价 |
| 转发规则 | 经典规则入口 | RuleManagementScreen/RuleEditorScreen | 共用引擎；开发版草稿模拟实时更新，不发送 |
| 消息模板 | MessageTemplateActivity | RuleStudioScreen | 已有入口，共用格式化器 |
| 自动回复 | AutoReplySettingsActivity | AutoReplyEmbeddedScreen | 已有入口，共用配置和处理链 |
| 远程来源/指令 | RemoteForwardingActivity | RemoteControlScreen | 已有入口，共用来源仓库 |
| 低电量/未接来电/心跳/自动填充 | 对应 SettingsActivity | 对应 EmbeddedScreen | 已有入口；需逐项控件回归，不能称完全同步 |
| 定时/批量短信 | 独立 Activity | 通道页打开同一 Activity | 实现复用 |
| 备份恢复 | 设置页打开 ConfigBackupActivity | 运维页 ConfigBackupCard | 共用同一文件导入/导出/预览页面，兼容剪贴板 |
| 拦截/回收站/密度/灵动岛/关于 | 设置页 | 运维原生卡片 + 完整设置 | 关键词/应用号码名单、回收站会话、密度/灵动岛已有原生入口；关于和系统来电仍复用设置 |
| 全量历史重新同步 | 设置页确认重新同步 | 信息页独立“全量同步”确认入口 | 两版前台同步防并发，补本地历史，不补转发 |
| 厂商兼容设置 | DeviceCompatibilityActivity | 运维向导 + 完整设置入口 | 共用系统设置；不能保证厂商后台限制被解除 |
| 请求记录/队列/Root心跳/连接诊断 | 无同等集中卡片 | 运维诊断 | 开发版新增观察，不是收件设备送达证明 |

## 本轮界面与入口修改

- 开发版运维增加备份卡片：系统文件选择器保存 JSON；从文件读取，先预览、确认后恢复；后台线程处理，限制输入2 MiB。无凭据/正文日志。
- 运维增加完整设置入口，补足经典设置项可达性，保留经典版原入口。
- 把短信同步、配置体检、待发队列、影子记录、通道请求、Root、备用通道、后台连接分为独立圆角卡片，长诊断内容默认限制行数、可展开。
- 底部在现有动态导航避让基础上增加24dp滚动留白，不改变其他页面Insets。

## 保留边界

- v3配置备份包含SIM名称/号码、备用通道、常用全局/界面设置、应用拦截名单、低电量/自动填充/心跳/通知转发设置；不含短信、历史、系统授权或全部BaseConfig字段。备份含明文凭据，应自行保管。
- 导入先校验类型/结构，写入前保存AES-GCM本机快照和中断恢复日志，失败回滚配置；启动时检测日志先回滚再启用后台。回滚不撤销短信、审计日志或系统授权，并非跨存储原子事务。
- 更换两版界面使用同一应用数据库；Debug和正式包是不同应用，不会因切换界面而自动跨包迁移数据。
- 规则即时预览已核实：输入的onValueChange调用updateTestInputs → runLiveTest，用草稿规则计算；并不真实投递。

## 恢复保护与兼容

- v1/v2备份可读，缺失部分保留；v3新增featureSettings/uiSettings。
- 导入前加密快照保存在应用noBackupFilesDir/config-before-restore.enc，仅保留最近一份；待提交日志config-restore-pending.enc成功或回滚后删除。
- 密钥不可用时不创建未保护快照，不继续导入；待回滚日志解密失败时保留文件并暂停下次启动后台初始化。
- UI导出文件为含凭据的明文JSON；与本机自动回滚加密快照不同，不上传云端。
- 灵动岛依赖系统能力/权限；号码名单是应用名单，不替代系统来电黑名单。回收站原生列表显示最近50个会话，详情恢复复用原有ThreadActivity。
