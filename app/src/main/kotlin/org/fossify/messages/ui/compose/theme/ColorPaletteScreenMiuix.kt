// Adapted directly from tiann/KernelSU v3.3.1 ColorPaletteScreenMiuix.kt (GPL-3.0-or-later).
package org.fossify.messages.ui.compose.theme

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ColorPaletteScreenMiuix(
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val uiState = LocalAppearance.current
    val update = LocalSetAppearance.current
    val isDark = uiState.mode == 2 || uiState.mode == 0 && org.fossify.messages.ui.compose.theme.LocalGatewayDark.current
    val backdrop = if (Build.VERSION.SDK_INT >= 33 && uiState.blur) top.yukonga.miuix.kmp.blur.rememberLayerBackdrop() else null
    val barColor = colorScheme.surface

    Scaffold(
        topBar = {
            Box {
                TopAppBar(
                    color = barColor,
                    title = "主题设置",
                    navigationIcon = {
                        IconButton(
                            onClick = onBack
                        ) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = null,
                                tint = colorScheme.primary
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->


        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    ThemePreviewCardMiuix(
                        enableFloatingBottomBar = uiState.floating,
                        enableFloatingBottomBarBlur = uiState.blur,
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    val themeItems = listOf(
                        "跟随系统",
                        "浅色",
                        "深色",
                    )
                    TabRow(
                        tabs = themeItems,
                        selectedTabIndex = (if (uiState.mode >= 3) uiState.mode - 3 else uiState.mode).coerceIn(0, 2),
                        onTabSelected = { index ->
                            update(uiState.copy(mode = index))
                        },
                    )

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = "启用 Monet 颜色",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Wallpaper,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "启用 Monet 颜色",
                                    tint = colorScheme.primary
                                )
                            },
                            checked = uiState.dynamic,
                            onCheckedChange = {
                                update(uiState.copy(dynamic = it))
                            }
                        )

                        if (!uiState.dynamic) {
                            OverlayDropdownPreference(
                                title = "基础配色",
                                items = listOf("青绿", "蓝色", "紫色", "橘红"),
                                selectedIndex = uiState.accent.coerceIn(0, 3),
                                onSelectedIndexChange = { update(uiState.copy(accent = it)) }
                            )
                        }

                        AnimatedVisibility(
                            visible = uiState.dynamic
                        ) {
                            Column {
                                val colorItems = listOf(
                                    "默认",
                                    "红色",
                                    "粉色",
                                    "紫色",
                                    "深紫",
                                    "靛青",
                                    "蓝色",
                                    "青色",
                                    "青绿",
                                    "绿色",
                                    "黄色",
                                    "琥珀",
                                    "橙色",
                                    "棕色",
                                    "灰蓝",
                                    "樱花",
                                )
                                val colorValues = listOf(0) + keyColorOptions
                                OverlayDropdownPreference(
                                    title = "强调色",
                                    items = colorItems,
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.Colorize,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = "强调色",
                                            tint = colorScheme.primary
                                        )
                                    },
                                    selectedIndex = colorValues.indexOf(uiState.keyColor).takeIf { it >= 0 } ?: 0,
                                    onSelectedIndexChange = { index ->
                                        update(uiState.copy(keyColor = colorValues[index]))
                                    }
                                )

                                AnimatedVisibility(
                                    visible = uiState.keyColor != 0
                                ) {
                                    Column {
                                        val styles = PaletteStyle.entries
                                        OverlayDropdownPreference(
                                            title = "色彩风格",
                                            startAction = {
                                                Icon(
                                                    Icons.Rounded.Style,
                                                    modifier = Modifier.padding(end = 6.dp),
                                                    contentDescription = "色彩风格",
                                                    tint = colorScheme.primary
                                                )
                                            },
                                            items = styles.map { it.name },
                                            selectedIndex = styles.indexOfFirst { it.name == uiState.palette }.coerceAtLeast(0),
                                            onSelectedIndexChange = { index ->
                                                update(uiState.copy(palette = styles[index].name))
                                            }
                                        )

                                        val specs = ColorSpec.SpecVersion.entries
                                        OverlayDropdownPreference(
                                            title = "色彩标准",
                                            startAction = {
                                                Icon(
                                                    Icons.Rounded.DesignServices,
                                                    modifier = Modifier.padding(end = 6.dp),
                                                    contentDescription = "色彩标准",
                                                    tint = colorScheme.primary
                                                )
                                            },
                                            items = specs.map { it.name },
                                            selectedIndex = specs.indexOfFirst { it.name == (if (uiState.spec2025) ColorSpec.SpecVersion.SPEC_2025 else ColorSpec.SpecVersion.SPEC_2021).name }.coerceAtLeast(0),
                                            onSelectedIndexChange = { index ->
                                                update(uiState.copy(spec2025 = specs[index] == ColorSpec.SpecVersion.SPEC_2025))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            SwitchPreference(
                                title = "模糊",
                                summary = "启用顶栏和底栏的模糊效果",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.BlurOn,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "模糊",
                                        tint = colorScheme.primary
                                    )
                                },
                                checked = uiState.blur,
                                onCheckedChange = {
                                    update(uiState.copy(blur = it))
                                }
                            )
                        }
                        SwitchPreference(
                            title = "悬浮底栏",
                            summary = "使用 Apple 风格的悬浮底栏",
                            startAction = {
                                Icon(
                                    Icons.Rounded.CallToAction,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "悬浮底栏",
                                    tint = colorScheme.primary
                                )
                            },
                            checked = uiState.floating,
                            onCheckedChange = {
                                update(uiState.copy(floating = it))
                            }
                        )
                        AnimatedVisibility(visible = uiState.floating && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            SwitchPreference(
                                title = "液态玻璃",
                                summary = "启用悬浮底栏的液态玻璃效果",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.WaterDrop,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "液态玻璃",
                                        tint = colorScheme.primary
                                    )
                                },
                                checked = uiState.blur,
                                onCheckedChange = {
                                    update(uiState.copy(blur = it))
                                }
                            )
                        }
                    }
                }
                item {
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                                    12.dp
                        )
                    )
                }
            }
        }
    }
}

/** Static sample dashboard; uses the live application theme, never queries SMS or sends data. */
@Composable
private fun ThemePreviewCardMiuix(
    enableFloatingBottomBar: Boolean,
    enableFloatingBottomBarBlur: Boolean,
) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val labels = listOf("信息", "大盘", "通道", "设置")
    val icons = listOf(Icons.Rounded.Message, Icons.Rounded.BarChart, Icons.Rounded.Extension, Icons.Rounded.Settings)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("迷你大盘 · 主题实时预览", fontSize = 12.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth(0.72f).width(300.dp)
                .clip(RoundedCornerShape(22.dp)).background(colors.background)
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(22.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("运行大盘", fontSize = 14.sp, color = colors.onSurface)
                Text("示例数据", fontSize = 9.sp, color = colors.onSurfaceVariant)
            }
            MiniDashboardCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("运行状态", fontSize = 11.sp, color = colors.onSurface)
                    Text("示例正常", fontSize = 9.sp, color = colors.primary)
                }
                Text("短信转发设备", fontSize = 10.sp, color = colors.onSurface)
                Text("电池白名单 · 短信权限 · 通知", fontSize = 8.sp, color = colors.onSurfaceVariant)
            }
            MiniDashboardCard {
                Text("今日短信与转发", fontSize = 11.sp, color = colors.onSurface)
                Row(Modifier.fillMaxWidth()) {
                    val titles = listOf("收件", "成功", "队列", "失败")
                    val values = listOf("28", "26", "2", "0")
                    val marks = listOf("📩", "✅", "📦", "⚠️")
                    val tints = listOf(org.fossify.messages.ui.compose.theme.GatewayBlue,
                        org.fossify.messages.ui.compose.theme.GatewayGreen,
                        org.fossify.messages.ui.compose.theme.GatewayBlue,
                        org.fossify.messages.ui.compose.theme.GatewayRed)
                    titles.indices.forEach { index ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(titles[index], fontSize = 8.sp, color = colors.onSurfaceVariant)
                            Text(values[index], fontSize = 17.sp, color = tints[index])
                            Text(marks[index], fontSize = 10.sp)
                        }
                    }
                }
            }
            MiniDashboardCard {
                Text("最近记录", fontSize = 11.sp, color = colors.onSurface)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("示例短信转发", fontSize = 9.sp, color = colors.onSurface)
                    Text("已受理", fontSize = 9.sp, color = colors.primary)
                }
                Text("仅预览布局，不代表真实运行状态", fontSize = 8.sp, color = colors.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(if (enableFloatingBottomBar) 24.dp else 10.dp))
                    .background(colors.surface.copy(alpha = if (enableFloatingBottomBarBlur) 0.85f else 1f))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                labels.indices.forEach { index ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                        .background(if (index == 1) colors.primary.copy(alpha = 0.15f) else Color.Transparent)
                        .padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icons[index], contentDescription = null, modifier = Modifier.size(14.dp),
                            tint = if (index == 1) colors.primary else colors.onSurface)
                        Text(labels[index], fontSize = 8.sp,
                            color = if (index == 1) colors.primary else colors.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniDashboardCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
}

private val keyColorOptions = listOf(0xFFF44336.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(), 0xFF3F51B5.toInt(), 0xFF2196F3.toInt(), 0xFF00BCD4.toInt(), 0xFF009688.toInt(), 0xFF159447.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt(), 0xFF795548.toInt(), 0xFF607D8B.toInt(), 0xFFFFB7C5.toInt())

