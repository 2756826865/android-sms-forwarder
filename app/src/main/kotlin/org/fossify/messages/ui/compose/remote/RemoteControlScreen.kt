package org.fossify.messages.ui.compose.remote

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.fossify.messages.R
import org.fossify.messages.forwarding.repository.ChannelRepository
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.permissions.PermissionCapability
import org.fossify.messages.permissions.XXPermissionGateway
import org.fossify.messages.remote.RemoteControlReceiptConfig
import org.fossify.messages.remote.repository.RemoteSourceConnectionState
import org.fossify.messages.remote.repository.RemoteSourceInstance
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType
import org.fossify.messages.ui.compose.components.StatusBadge
import org.fossify.messages.ui.compose.theme.AppBackground
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.DarkBackground
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.DarkSurface
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RemoteControlScreen(
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val remoteRepo = remember { RemoteSourceRepository.getInstance(context) }
    val channelRepo = remember { ChannelRepository.getInstance(context) }
    val sources by remoteRepo.sourcesFlow.collectAsState()
    val channelInstances by channelRepo.instancesFlow.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedNewSourceType by remember { mutableStateOf<RemoteSourceType?>(null) }
    var editingSource by remember { mutableStateOf<RemoteSourceInstance?>(null) }
    var showReceiptDialog by remember { mutableStateOf(false) }

    val bgColor = if (isDark) DarkBackground else AppBackground
    val cardColor = if (isDark) DarkSurface else SurfaceCard
    val outlineColor = if (isDark) DarkOutline else OutlineSoft

    Scaffold(
        containerColor = bgColor,
        topBar = {
            if (onBack != null) Surface(
                color = cardColor,
                border = BorderStroke(1.dp, outlineColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_chevron_left),
                                contentDescription = "Back",
                                tint = if (isDark) Color.White else TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "远程发送",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color.White else TextPrimary
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)
        ) {
            // 3. 远程来源列表头部与添加来源
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "远程来源",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color.White else TextPrimary
                    )

                    Button(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_plus),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加来源", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    text = "${sources.size} 个来源 · ${sources.count { it.enabled }} 个已启用",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            if (sources.isEmpty()) {
                item {
                    EmptySourcesPlaceholder(
                        cardColor = cardColor,
                        outlineColor = outlineColor,
                        isDark = isDark,
                        onAdd = { showAddDialog = true }
                    )
                }
            } else {
                items(sources, key = { it.id }) { source ->
                    RemoteSourceCard(
                        source = source,
                        cardColor = cardColor,
                        outlineColor = outlineColor,
                        isDark = isDark,
                        onToggle = { enabled -> remoteRepo.toggleEnabled(source.id, enabled) },
                        onEdit = { editingSource = source },
                        onDelete = { remoteRepo.deleteSource(source.id) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        RemoteSourceTypePickerDialog(
            onDismiss = { showAddDialog = false },
            onSelect = { selectedType ->
                showAddDialog = false
                selectedNewSourceType = selectedType
            }
        )
    }

    if (selectedNewSourceType != null || editingSource != null) {
        RemoteSourceEditDialog(
            initialSource = editingSource,
            initialType = selectedNewSourceType ?: editingSource?.type ?: RemoteSourceType.DINGTALK,
            onDismiss = {
                selectedNewSourceType = null
                editingSource = null
            },
            onSave = { saved ->
                remoteRepo.saveSource(saved)
                selectedNewSourceType = null
                editingSource = null
            },
            onOpenReceiptSettings = { showReceiptDialog = true }
        )
    }

    if (showReceiptDialog) {
        ReceiptSettingsDialog(
            context = context,
            channelInstances = channelInstances,
            onDismiss = { showReceiptDialog = false }
        )
    }
}

@Composable
private fun PrerequisiteCard(
    context: android.content.Context,
    isDark: Boolean,
    cardColor: Color,
    outlineColor: Color
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasSendSms by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasReceiveSms by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasPhoneState by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        )
    }

    val updatePermissions = {
        hasSendSms = ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        hasReceiveSms = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        hasPhoneState = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updatePermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        updatePermissions()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isAllGranted = hasSendSms && hasReceiveSms && hasPhoneState
    val hasCoreSend = hasSendSms

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardColor,
        border = BorderStroke(1.dp, outlineColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡️", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "系统发信环境检查",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (isDark) Color.White else TextPrimary
                    )
                }
                StatusBadge(
                    text = when {
                        isAllGranted -> "全权就绪"
                        hasCoreSend -> "发信可用·部分受限"
                        else -> "缺少核心发信权限"
                    },
                    color = when {
                        isAllGranted -> GatewayGreen
                        hasCoreSend -> GatewayOrange
                        else -> GatewayRed
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (hasCoreSend) {
                    "系统已具备基础发信能力。收到经过校验的合法远程指令后，将直接调起底层短信发送通道。"
                } else {
                    "核心发送权限 (SEND_SMS) 缺失，任何远程指令到达时均无法分发短信，请立即开启授权。"
                },
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 权限子状态指示器
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PermissionMiniChip(
                    name = "发送短信",
                    isGranted = hasSendSms,
                    isRequired = true,
                    isDark = isDark,
                    modifier = Modifier.weight(1f)
                )
                PermissionMiniChip(
                    name = "短信接收",
                    isGranted = hasReceiveSms,
                    isRequired = false,
                    isDark = isDark,
                    modifier = Modifier.weight(1f)
                )
                PermissionMiniChip(
                    name = "卡槽识别",
                    isGranted = hasPhoneState,
                    isRequired = false,
                    isDark = isDark,
                    modifier = Modifier.weight(1f)
                )
            }

            if (!isAllGranted && context is Activity) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            XXPermissionGateway.openAppPermissionSettings(context)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!hasCoreSend) GatewayRed else GatewayOrange
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (!hasCoreSend) "前往授予发送短信权限" else "完善相关权限设置",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionMiniChip(
    name: String,
    isGranted: Boolean,
    isRequired: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val chipBg = if (isGranted) {
        GatewayGreen.copy(alpha = 0.12f)
    } else if (isRequired) {
        GatewayRed.copy(alpha = 0.12f)
    } else {
        GatewayOrange.copy(alpha = 0.12f)
    }
    val chipBorder = if (isGranted) {
        GatewayGreen.copy(alpha = 0.35f)
    } else if (isRequired) {
        GatewayRed.copy(alpha = 0.35f)
    } else {
        GatewayOrange.copy(alpha = 0.35f)
    }
    val textColor = if (isGranted) {
        GatewayGreen
    } else if (isRequired) {
        GatewayRed
    } else {
        GatewayOrange
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = chipBg,
        border = BorderStroke(1.dp, chipBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isGranted) "✓" else "✕",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isDark) Color.White else TextPrimary
            )
        }
    }
}

@Composable
private fun EmptySourcesPlaceholder(
    cardColor: Color,
    outlineColor: Color,
    isDark: Boolean,
    onAdd: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        shape = RoundedCornerShape(16.dp),
        color = cardColor,
        border = BorderStroke(1.dp, outlineColor)
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "暂无远程来源",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = if (isDark) Color.White else TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "添加钉钉、飞书等来源后，即可远程触发本机发送短信。",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onAdd,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
            ) {
                Text("添加来源", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RemoteSourceTypePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (RemoteSourceType) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("选择来源", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "仅会创建您选中的来源，其他渠道不会生成或启动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                RemoteSourceTypeSection(
                    title = "国内推荐",
                    subtitle = "无需公网回调地址",
                    types = listOf(RemoteSourceType.DINGTALK, RemoteSourceType.FEISHU, RemoteSourceType.WECOM),
                    isDark = isDark,
                    onSelect = onSelect
                )
                RemoteSourceTypeSection(
                    title = "专业接入",
                    subtitle = "适合已有自建服务的用户",
                    types = listOf(RemoteSourceType.WEBSOCKET),
                    isDark = isDark,
                    onSelect = onSelect
                )
                RemoteSourceTypeSection(
                    title = "备用方式",
                    subtitle = "用于兼容或断网应急",
                    types = listOf(RemoteSourceType.EMAIL, RemoteSourceType.SMS),
                    isDark = isDark,
                    onSelect = onSelect
                )
                RemoteSourceTypeSection(
                    title = "其他",
                    subtitle = "可能受所在网络环境影响",
                    types = listOf(RemoteSourceType.TELEGRAM),
                    isDark = isDark,
                    onSelect = onSelect
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RemoteSourceTypeSection(
    title: String,
    subtitle: String,
    types: List<RemoteSourceType>,
    isDark: Boolean,
    onSelect: (RemoteSourceType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(subtitle, fontSize = 11.sp, color = TextSecondary)
        types.forEach { type ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(type) },
                shape = RoundedCornerShape(12.dp),
                color = if (isDark) Color(0xFF252D3A) else Color(0xFFF7F8FA),
                border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(type.emoji, fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(type.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            if (type == RemoteSourceType.DINGTALK || type == RemoteSourceType.FEISHU || type == RemoteSourceType.WECOM) {
                                Spacer(modifier = Modifier.width(6.dp))
                                StatusBadge(text = "Beta", color = GatewayOrange)
                            }
                        }
                        Text(
                            text = when (type) {
                                RemoteSourceType.DINGTALK -> "钉钉企业内部应用 Stream 长连接"
                                RemoteSourceType.FEISHU -> "飞书企业自建应用 WebSocket 长连接"
                                RemoteSourceType.WECOM -> "企业微信智能机器人 WebSocket 长连接"
                                RemoteSourceType.WEBSOCKET -> "连接您的自建 WebSocket 服务端"
                                RemoteSourceType.EMAIL -> "通过 IMAP 轮询接收指令"
                                RemoteSourceType.SMS -> "从白名单号码接收应急短信指令"
                                RemoteSourceType.TELEGRAM -> "Telegram Bot 长轮询"
                                else -> type.label
                            },
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    Text("›", fontSize = 22.sp, color = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun RemoteSourceCard(
    source: RemoteSourceInstance,
    cardColor: Color,
    outlineColor: Color,
    isDark: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardColor,
        border = BorderStroke(1.dp, outlineColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(source.type.emoji, fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = source.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = if (isDark) Color.White else TextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            ConnectionBadge(state = source.connectionState)
                        }
                        Text(
                            text = source.type.label,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = BrandGreen
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val simLabel = when (source.defaultSimMode) {
                SubscriptionResolver.MODE_SIM1 -> "指定 SIM1"
                SubscriptionResolver.MODE_SIM2 -> "指定 SIM2"
                SubscriptionResolver.MODE_DEFAULT -> "系统默认卡"
                else -> "跟随接收卡"
            }
            val prefixLabel = if (source.customCommandPrefix.isNotBlank()) {
                "前缀: ${source.customCommandPrefix}"
            } else {
                "默认前缀 (/发信)"
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallTag(text = simLabel, isDark = isDark)
                SmallTag(text = prefixLabel, isDark = isDark)
                if (source.authorizedUsers.isNotEmpty()) {
                    SmallTag(text = "白名单: ${source.authorizedUsers.size}人", isDark = isDark)
                }
            }

            if (source.lastMessageAt > 0L) {
                Spacer(modifier = Modifier.height(6.dp))
                val timeStr = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(source.lastMessageAt))
                Text(
                    text = "最后收到指令: $timeStr",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDelete) {
                    Text("删除", color = GatewayRed, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = onEdit,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0xFF2C3E50) else Color(0xFFEAECEF)
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        "编辑配置",
                        color = if (isDark) Color.White else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionBadge(state: RemoteSourceConnectionState) {
    val color = when (state) {
        RemoteSourceConnectionState.READY,
        RemoteSourceConnectionState.AUTHENTICATED -> GatewayGreen
        RemoteSourceConnectionState.CONNECTING -> GatewayBlue
        RemoteSourceConnectionState.DISABLED,
        RemoteSourceConnectionState.CONFIG_REQUIRED -> TextSecondary
        RemoteSourceConnectionState.DEGRADED -> GatewayOrange
        RemoteSourceConnectionState.ERROR -> GatewayRed
    }
    StatusBadge(text = state.label, color = color)
}

@Composable
private fun SmallTag(text: String, isDark: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isDark) Color(0xFF252D3A) else Color(0xFFF1F3F5),
        border = BorderStroke(0.5.dp, if (isDark) DarkOutline else OutlineSoft)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun RemoteSourceEditDialog(
    initialSource: RemoteSourceInstance?,
    initialType: RemoteSourceType,
    onDismiss: () -> Unit,
    onSave: (RemoteSourceInstance) -> Unit,
    onOpenReceiptSettings: () -> Unit
) {
    val context = LocalContext.current
    var type by remember(initialSource?.id, initialType) {
        mutableStateOf(initialSource?.type ?: initialType)
    }
    var name by remember { mutableStateOf(initialSource?.name ?: type.label) }
    var prefix by remember { mutableStateOf(initialSource?.customCommandPrefix ?: "") }
    var simMode by remember { mutableStateOf(initialSource?.defaultSimMode ?: SubscriptionResolver.MODE_FOLLOW_RECEIVE) }

    var whitelistEnabled by remember { mutableStateOf(initialSource?.whitelistEnabled ?: false) }
    var authUsersText by remember { mutableStateOf(initialSource?.authorizedUsers?.joinToString("\n") ?: "") }
    var authGroupsText by remember { mutableStateOf(initialSource?.authorizedGroups?.joinToString("\n") ?: "") }
    var requireMention by remember { mutableStateOf(initialSource?.requireMention ?: true) }

    var quietHoursEnabled by remember { mutableStateOf(initialSource?.quietHoursEnabled ?: false) }
    var quietStartText by remember { mutableStateOf(initialSource?.quietHoursStart?.toString() ?: "23") }
    var quietEndText by remember { mutableStateOf(initialSource?.quietHoursEnd?.toString() ?: "7") }

    var hourlyLimitText by remember { mutableStateOf(initialSource?.hourlyLimit?.toString() ?: "10") }
    var dailyLimitText by remember { mutableStateOf(initialSource?.dailyLimit?.toString() ?: "50") }

    var param1 by remember {
        mutableStateOf(
            initialSource?.optString("token").orEmpty()
                .ifBlank { initialSource?.optString("botToken").orEmpty() }
                .ifBlank { initialSource?.optString("clientId").orEmpty() }
                .ifBlank { initialSource?.optString("appId").orEmpty() }
                .ifBlank { initialSource?.optString("botId").orEmpty() }
                .ifBlank { initialSource?.optString("corpId").orEmpty() }
                .ifBlank { initialSource?.optString("host").orEmpty() }
                .ifBlank { initialSource?.optString("url").orEmpty() }
                .ifBlank { initialSource?.optString("wsUrl").orEmpty() }
        )
    }
    var param2 by remember {
        mutableStateOf(
            initialSource?.optString("clientSecret").orEmpty()
                .ifBlank { initialSource?.optString("appSecret").orEmpty() }
                .ifBlank { initialSource?.optString("secret").orEmpty() }
                .ifBlank { initialSource?.optString("user").orEmpty() }
                .ifBlank { initialSource?.optString("token").orEmpty() }
        )
    }
    var param3 by remember {
        mutableStateOf(
            initialSource?.optString("pass").orEmpty()
                .ifBlank { initialSource?.optString("chatId").orEmpty() }
                .ifBlank { initialSource?.optString("agentId").orEmpty() }
        )
    }
    var emailPortText by remember { mutableStateOf(initialSource?.optInt("port", 993)?.toString() ?: "993") }
    var emailSsl by remember { mutableStateOf(initialSource?.optBoolean("ssl", true) ?: true) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialSource != null) "编辑远程来源" else "添加远程来源", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 类型在上一步选择；实例创建后不允许变更类型，避免凭据与运行句柄错配。
                Text("来源类型", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = BrandGreen.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, BrandGreen.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(type.emoji, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(type.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (type == RemoteSourceType.DINGTALK || type == RemoteSourceType.FEISHU || type == RemoteSourceType.WECOM) {
                            Spacer(modifier = Modifier.width(6.dp))
                            StatusBadge(text = "Beta", color = GatewayOrange)
                        }
                    }
                }

                if (type == RemoteSourceType.DINGTALK || type == RemoteSourceType.FEISHU || type == RemoteSourceType.WECOM) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = GatewayOrange.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, GatewayOrange.copy(alpha = 0.25f))
                    ) {
                        Text(
                            text = when (type) {
                                RemoteSourceType.DINGTALK -> "Beta：通过钉钉官方 Stream 长连接直接接收指令，无需公网回调地址。需要企业内部应用并启用机器人 Stream 模式。"
                                RemoteSourceType.FEISHU -> "Beta：通过飞书官方 WebSocket 长连接接收指令，无需公网回调地址。需要企业自建应用、机器人能力和消息事件权限。"
                                RemoteSourceType.WECOM -> "Beta：通过企业微信官方智能机器人 WebSocket 长连接直接接收指令，无需公网回调地址。配置 Bot ID 与 Secret 即可建连。"
                                else -> ""
                            },
                            modifier = Modifier.padding(10.dp),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = TextSecondary
                        )
                    }
                }

                // 2. 来源名称与指令前缀
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("来源名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("自定义指令前缀 (留空默认 /发信)") },
                    placeholder = { Text("例如：/sms 或 #发信") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. 发信卡槽选择
                Text("发信 SIM 卡槽", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                val simOptions = listOf(
                    SubscriptionResolver.MODE_FOLLOW_RECEIVE to "跟随接收卡",
                    SubscriptionResolver.MODE_SIM1 to "SIM 1",
                    SubscriptionResolver.MODE_SIM2 to "SIM 2",
                    SubscriptionResolver.MODE_DEFAULT to "系统默认卡"
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(simOptions) { (mode, label) ->
                        FilterChip(
                            selected = simMode == mode,
                            onClick = { simMode = mode },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GatewayBlue.copy(alpha = 0.15f),
                                selectedLabelColor = GatewayBlue
                            )
                        )
                    }
                }

                // 4. 各平台专有凭证与连接配置
                Text("连接与鉴权凭据", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                when (type) {
                    RemoteSourceType.SMS -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = GatewayBlue.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, GatewayBlue.copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = "短信指令将持续监听本机收到的短信。当内容以指定前缀开头且发送者处于白名单时，自动触发短信外发任务。",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(10.dp),
                                lineHeight = 16.sp
                            )
                        }
                    }
                    RemoteSourceType.TELEGRAM -> {
                        OutlinedTextField(
                            value = param1,
                            onValueChange = { param1 = it },
                            label = { Text("Telegram Bot Token") },
                            placeholder = { Text("例如 123456789:ABCdefGhI...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = param3,
                            onValueChange = { param3 = it },
                            label = { Text("默认 ChatID (选填，原路回复时自动填充)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    RemoteSourceType.DINGTALK -> {
                        OutlinedTextField(
                            value = param1,
                            onValueChange = { param1 = it },
                            label = { Text("Client ID (AppKey)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = param2,
                            onValueChange = { param2 = it },
                            label = { Text("Client Secret (AppSecret)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    RemoteSourceType.FEISHU -> {
                        OutlinedTextField(
                            value = param1,
                            onValueChange = { param1 = it },
                            label = { Text("App ID (飞书机器人凭据)") },
                            placeholder = { Text("cli_a1b2c3d4...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = param2,
                            onValueChange = { param2 = it },
                            label = { Text("App Secret") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    RemoteSourceType.WECOM -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = GatewayBlue.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, GatewayBlue.copy(alpha = 0.2f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "📌 企业微信智能机器人官方长连接配置指南",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GatewayBlue
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "【电脑端后台创建】\n" +
                                           "1. 登录企业微信后台 (work.weixin.qq.com)，进入「安全与管理 → 管理工具 → 智能专区 → 智能机器人」；\n" +
                                           "2. 点击「创建机器人」，选择【API模式】；\n" +
                                           "3. 连接方式必须勾选【使用长连接】（免公网IP/免域名），点击查看/获取 Bot ID 与 Secret 密钥；\n\n" +
                                           "【手机企微App直接创建】\n" +
                                           "打开企业微信手机端 →「通讯录」→「智能机器人」→「创建智能机器人」→ 选择【API模式】并勾选【使用长连接】；\n\n" +
                                           "【使用说明】\n" +
                                           "将获取的 Bot ID 与 Secret 填入下方保存；把机器人拉入群聊或单聊，@机器人 发送 /发信 10086 查询 即可远程代发短信并原路接收回执。",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        OutlinedTextField(
                            value = param1,
                            onValueChange = { param1 = it },
                            label = { Text("Bot ID (企业微信智能机器人 ID)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = param2,
                            onValueChange = { param2 = it },
                            label = { Text("Secret (机器人密钥)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = param3,
                            onValueChange = { param3 = it },
                            label = { Text("默认推送 Chat ID (选填)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    RemoteSourceType.EMAIL -> {
                        OutlinedTextField(
                            value = param1,
                            onValueChange = { param1 = it },
                            label = { Text("IMAP 邮件服务器主机") },
                            placeholder = { Text("例如 imap.qq.com / imap.163.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = param2,
                            onValueChange = { param2 = it },
                            label = { Text("邮箱账号") },
                            placeholder = { Text("例如 example@qq.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = param3,
                            onValueChange = { param3 = it },
                            label = { Text("邮箱授权码 / 密码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = emailPortText,
                                onValueChange = { emailPortText = it },
                                label = { Text("IMAP 端口") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("SSL/TLS（关闭则 STARTTLS）", fontSize = 12.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Switch(checked = emailSsl, onCheckedChange = { emailSsl = it })
                            }
                        }
                    }
                    RemoteSourceType.WEBSOCKET -> {
                        OutlinedTextField(
                            value = param1,
                            onValueChange = { param1 = it },
                            label = { Text("WebSocket URL") },
                            placeholder = { Text("ws:// 或 wss://") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = param2,
                            onValueChange = { param2 = it },
                            label = { Text("鉴权 Token / API Key (选填)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 5. 安全与授权白名单
                Text("安全与群聊过滤", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用用户白名单", fontSize = 13.sp)
                        Text(
                            if (whitelistEnabled) "仅接受名单内用户" else "已关闭：接受所有用户的有效指令",
                            fontSize = 11.sp,
                            color = if (whitelistEnabled) TextSecondary else GatewayOrange
                        )
                    }
                    Switch(checked = whitelistEnabled, onCheckedChange = { whitelistEnabled = it })
                }

                if (whitelistEnabled) {
                    OutlinedTextField(
                        value = authUsersText,
                        onValueChange = { authUsersText = it },
                        label = { Text("用户白名单（必填，每行一个）") },
                        placeholder = { Text("账号、手机号或用户 ID") },
                        supportingText = {
                            if (authUsersText.lines().none { it.isNotBlank() }) {
                                Text("启用白名单后至少填写一个用户")
                            }
                        },
                        isError = authUsersText.lines().none { it.isNotBlank() },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }

                if (type != RemoteSourceType.SMS && type != RemoteSourceType.EMAIL) {
                    if (whitelistEnabled) {
                        OutlinedTextField(
                            value = authGroupsText,
                            onValueChange = { authGroupsText = it },
                            label = { Text("群组白名单（群内使用时必填）") },
                            placeholder = { Text("每行一个群 ID 或会话 ID") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("群消息必须 @ 机器人", fontSize = 13.sp)
                        Switch(checked = requireMention, onCheckedChange = { requireMention = it })
                    }
                }

                // 6. 防刷频次上限与夜间免打扰
                Text("频次限制与免打扰", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = hourlyLimitText,
                        onValueChange = { hourlyLimitText = it },
                        label = { Text("每小时上限(条)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dailyLimitText,
                        onValueChange = { dailyLimitText = it },
                        label = { Text("每天上限(条)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用夜间免打扰", fontSize = 13.sp)
                    Switch(checked = quietHoursEnabled, onCheckedChange = { quietHoursEnabled = it })
                }

                if (quietHoursEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = quietStartText,
                            onValueChange = { quietStartText = it },
                            label = { Text("起始小时(0-23)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Text("至")
                        OutlinedTextField(
                            value = quietEndText,
                            onValueChange = { quietEndText = it },
                            label = { Text("结束小时(0-23)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenReceiptSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("发送回执", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val authSet = authUsersText.lines().map(String::trim).filter(String::isNotBlank).toSet()
                    val groupSet = authGroupsText.lines().map(String::trim).filter(String::isNotBlank).toSet()

                    val json = JSONObject().apply {
                        when (type) {
                            RemoteSourceType.SMS -> {}
                            RemoteSourceType.TELEGRAM -> {
                                put("botToken", param1.trim())
                                put("chatId", param3.trim())
                            }
                            RemoteSourceType.DINGTALK -> {
                                put("clientId", param1.trim())
                                put("clientSecret", param2.trim())
                            }
                            RemoteSourceType.FEISHU -> {
                                put("appId", param1.trim())
                                put("appSecret", param2.trim())
                            }
                            RemoteSourceType.WECOM -> {
                                put("botId", param1.trim())
                                put("secret", param2.trim())
                                put("chatId", param3.trim())
                            }
                            RemoteSourceType.EMAIL -> {
                                put("host", param1.trim())
                                put("user", param2.trim())
                                put("pass", param3.trim())
                                put("port", emailPortText.toIntOrNull() ?: 993)
                                put("ssl", emailSsl)
                            }
                            RemoteSourceType.WEBSOCKET -> {
                                put("url", param1.trim())
                                put("token", param2.trim())
                            }
                        }
                    }

                    val hasValid = when (type) {
                        RemoteSourceType.SMS -> true
                        RemoteSourceType.TELEGRAM -> param1.isNotBlank()
                        RemoteSourceType.DINGTALK -> param1.isNotBlank() && param2.isNotBlank()
                        RemoteSourceType.FEISHU -> param1.isNotBlank() && param2.isNotBlank()
                        RemoteSourceType.WECOM -> param1.isNotBlank() && param2.isNotBlank()
                        RemoteSourceType.EMAIL -> param1.isNotBlank() && param2.isNotBlank() && param3.isNotBlank()
                        RemoteSourceType.WEBSOCKET -> param1.isNotBlank()
                    }

                    // 拒绝虚假就绪：网络来源若有效则标记为 CONNECTING 等待后台服务握手，SMS 在权限满足时标为 READY
                    val calculatedState = if (type == RemoteSourceType.SMS) {
                        val hasSmsPerm = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.SEND_SMS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasSmsPerm) RemoteSourceConnectionState.READY else RemoteSourceConnectionState.CONFIG_REQUIRED
                    } else {
                        if (!hasValid) {
                            RemoteSourceConnectionState.CONFIG_REQUIRED
                        } else {
                            RemoteSourceConnectionState.CONNECTING
                        }
                    }

                    val item = (initialSource ?: RemoteSourceInstance(name = name, type = type)).copy(
                        name = name.ifBlank { type.label },
                        type = type,
                        customCommandPrefix = prefix.trim(),
                        whitelistEnabled = whitelistEnabled,
                        authorizedUsers = authSet,
                        authorizedGroups = groupSet,
                        requireMention = requireMention,
                        defaultSimMode = simMode,
                        quietHoursEnabled = quietHoursEnabled,
                        quietHoursStart = quietStartText.toIntOrNull() ?: 23,
                        quietHoursEnd = quietEndText.toIntOrNull() ?: 7,
                        hourlyLimit = hourlyLimitText.toIntOrNull() ?: 10,
                        dailyLimit = dailyLimitText.toIntOrNull() ?: 50,
                        configJson = json.toString(),
                        connectionState = calculatedState
                    )
                    onSave(item)
                },
                enabled = !whitelistEnabled || authUsersText.lines().any { it.isNotBlank() },
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ReceiptSettingsDialog(
    context: android.content.Context,
    channelInstances: List<org.fossify.messages.forwarding.ForwardingChannelInstance>,
    onDismiss: () -> Unit
) {
    val receiptConfig = remember { RemoteControlReceiptConfig(context) }
    var enabled by remember { mutableStateOf(receiptConfig.enabled) }
    var includeDelivered by remember { mutableStateOf(receiptConfig.includeDelivered) }
    var selectedChannels by remember { mutableStateOf(receiptConfig.channels) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发送回执", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("启用回执派发", fontWeight = FontWeight.SemiBold)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("包含送达报告回执", fontWeight = FontWeight.SemiBold)
                        Text("当运营商返回送达短信时再次派发最终回执", fontSize = 11.sp, color = TextSecondary)
                    }
                    Switch(checked = includeDelivered, onCheckedChange = { includeDelivered = it })
                }

                Text(
                    "同时派发至以下已有普通转发通道：",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                Text(
                    "企业微信、钉钉、飞书、Telegram 和 WebSocket 可原路返回；短信与邮箱来源为避免额外资费或缺少 SMTP 凭据，需选择下方普通通道接收回执。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = TextSecondary
                )

                if (channelInstances.isEmpty()) {
                    Text("尚未配置普通发送通道；短信和邮箱来源将只保留本地状态记录。", fontSize = 11.sp, color = TextSecondary)
                } else {
                    channelInstances.forEach { instance ->
                        val isChecked = selectedChannels.contains(instance.id) || selectedChannels.contains(instance.channelType)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedChannels = if (isChecked) {
                                        selectedChannels - instance.id - instance.channelType
                                    } else {
                                        selectedChannels + instance.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = isChecked,
                                onCheckedChange = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(instance.name, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    receiptConfig.enabled = enabled
                    receiptConfig.includeDelivered = includeDelivered
                    receiptConfig.channels = selectedChannels
                    Toast.makeText(context, "回执设置已更新", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
