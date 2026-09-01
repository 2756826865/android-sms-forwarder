package org.fossify.messages.ui.compose.conversations

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.R
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.models.Conversation
import org.fossify.messages.ui.compose.theme.AppBackground
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.BrandGreenSoft
import org.fossify.messages.ui.compose.theme.DarkBackground
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.DarkSurface
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.RefreshIconBlue
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary
import org.fossify.messages.ui.compose.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    conversationsViewModel: ConversationsViewModel,
    onRequestDefaultSms: () -> Unit = {},
    onSwitchToClassic: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by conversationsViewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val isDark = isSystemInDarkTheme()

    val pageBgColor = if (isDark) DarkBackground else AppBackground
    val primaryTextColor = if (isDark) Color.White else TextPrimary
    val secondaryTextColor = if (isDark) Color(0xFF9CA3AF) else TextSecondary

    val filteredConversations = remember(uiState.conversations, searchQuery) {
        if (searchQuery.isBlank()) {
            uiState.conversations
        } else {
            uiState.conversations.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.snippet.contains(searchQuery, ignoreCase = true) ||
                it.phoneNumber.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        containerColor = pageBgColor,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    context.startActivity(Intent(context, NewConversationActivity::class.java))
                },
                containerColor = BrandGreen,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .padding(bottom = 80.dp)
                    .size(56.dp)
            ) {
                Icon(
                    painter = painterResource(id = org.fossify.commons.R.drawable.ic_plus_vector),
                    contentDescription = "新建会话",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            // 顶部区域：大标题 + 副标题 + 经典版胶囊按钮 + 独立刷新按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "信息",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "共 ${uiState.conversations.size} 条对话 · 短信收发与托管",
                        fontSize = 12.sp,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 📱 经典版胶囊按钮
                    Surface(
                        onClick = onSwitchToClassic,
                        shape = RoundedCornerShape(22.dp),
                        color = if (isDark) Color(0xFF1B3322) else BrandGreenSoft,
                        modifier = Modifier.height(38.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "📱 经典版",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = BrandGreen
                            )
                        }
                    }

                    // 🔄 独立刷新按钮
                    Surface(
                        onClick = { conversationsViewModel.refresh(isInitial = false) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isDark) DarkSurface else Color.White,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_refresh_modern),
                                contentDescription = "刷新",
                                tint = RefreshIconBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 高保真搜索栏
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (isDark) DarkSurface else Color.White,
                border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft),
                shadowElevation = 0.5.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search_vector),
                        contentDescription = "搜索",
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "搜索短信联系人或正文…",
                                fontSize = 15.5.sp,
                                color = TextTertiary
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 15.5.sp,
                                color = primaryTextColor
                            ),
                            cursorBrush = SolidColor(BrandGreen),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "✕",
                            fontSize = 16.sp,
                            color = TextTertiary,
                            modifier = Modifier
                                .clickable { searchQuery = "" }
                                .padding(4.dp)
                        )
                    }
                }
            }

            // 默认短信应用轻量提醒条 (若非默认应用，置于搜索栏下方)
            if (!uiState.isDefaultSmsApp) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = GatewayOrange.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { onRequestDefaultSms() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "⚠️ 未设为默认短信应用，部分收发特性受限",
                            style = MaterialTheme.typography.bodySmall,
                            color = GatewayOrange,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text("去设置", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GatewayOrange)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 列表内容区域
            if (uiState.isLoading && uiState.conversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = BrandGreen)
                }
            } else if (filteredConversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "💬", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isBlank()) "暂无短信会话" else "未搜到匹配短信",
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryTextColor
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredConversations, key = { it.threadId }) { conv ->
                        ConversationItem(
                            conversation = conv,
                            onClick = {
                                val intent = Intent(context, ThreadActivity::class.java).apply {
                                    putExtra(THREAD_ID, conv.threadId)
                                    putExtra(THREAD_TITLE, conv.title)
                                    putExtra(THREAD_NUMBER, conv.phoneNumber)
                                }
                                context.startActivity(intent)
                            }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(110.dp)) }
                }
            }
        }
    }
}

private val avatarLruCache = object : android.util.LruCache<String, Bitmap>(128) {
    override fun sizeOf(key: String, value: Bitmap): Int = 1
}

@Composable
fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val formattedDate = remember(conversation.date) {
        dateFormat.format(Date(conversation.date.toLong() * 1000L))
    }

    var contactBitmap by remember(conversation.photoUri) {
        mutableStateOf<Bitmap?>(avatarLruCache.get(conversation.photoUri))
    }
    LaunchedEffect(conversation.photoUri) {
        if (conversation.photoUri.isNotBlank()) {
            val cached = avatarLruCache.get(conversation.photoUri)
            if (cached != null) {
                contactBitmap = cached
            } else {
                withContext(Dispatchers.IO) {
                    val bmp = runCatching {
                        Glide.with(context)
                            .asBitmap()
                            .load(conversation.photoUri)
                            .submit(120, 120)
                            .get()
                    }.getOrNull()
                    if (bmp != null) {
                        avatarLruCache.put(conversation.photoUri, bmp)
                    }
                    contactBitmap = bmp
                }
            }
        } else {
            contactBitmap = null
        }
    }

    // 独立白色卡片结构
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 74.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (isDark) DarkSurface else SurfaceCard,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 联系人头像：真实头像或品牌绿色圆形默认头像
            if (contactBitmap != null) {
                Image(
                    bitmap = contactBitmap!!.asImageBitmap(),
                    contentDescription = conversation.title,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF1B3322) else BrandGreenSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_person_vector),
                        contentDescription = null,
                        tint = BrandGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 标题、时间、短信正文摘要与未读圆点
            Column(modifier = Modifier.weight(1f)) {
                // 第一行：联系人/号码 + 右侧时间
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.title,
                        fontSize = 16.sp,
                        fontWeight = if (conversation.read) FontWeight.SemiBold else FontWeight.Bold,
                        color = if (isDark) Color.White else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formattedDate,
                        fontSize = 11.sp,
                        fontWeight = if (conversation.read) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (conversation.read) TextSecondary else BrandGreen,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 第二行：短信摘要 + 未读小绿点
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.snippet.ifBlank { "无正文" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = if (isDark) Color(0xFF9CA3AF) else Color(0xFF4B515B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        modifier = Modifier.weight(1f)
                    )

                    if (!conversation.read) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(BrandGreen)
                        )
                    }
                }
            }
        }
    }
}
