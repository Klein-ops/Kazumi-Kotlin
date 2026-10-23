package com.kazumi.port.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingSwitchRow
import com.kazumi.port.ui.navigation.Routes
import kotlinx.coroutines.launch

private val stepLabels = listOf("使用约定", "访问加速", "添加规则")

/**
 * 首次引导页（对应原版 onboarding_page.dart）
 * 3 步：使用约定（免责声明）→ 访问加速（网络镜像）→ 添加规则
 * 完成时写入 onboardingCompleted=true（与原版键名一致）并跳转默认启动页
 */
@Composable
fun OnboardingPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val pagerState = rememberPagerState(pageCount = { 3 })
    var agreed by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }

    var bangumiAcceleration by remember { mutableStateOf("mirror") }
    var imageAcceleration by remember { mutableStateOf("ech") }
    var enableGitProxy by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        bangumiAcceleration = settings.bangumiAcceleration()
        imageAcceleration = settings.getString(SettingsManager.Proxy.IMAGE_ACCELERATION, "ech")
        enableGitProxy = settings.getBoolean(SettingsManager.Proxy.ENABLE_GIT_PROXY, true)
    }

    suspend fun finishOnboarding() {
        settings.setBoolean(SettingsManager.Interface.ONBOARDING_COMPLETED, true)
        val defaultPage = settings.getString(
            SettingsManager.Interface.DEFAULT_STARTUP_PAGE,
            SettingsManager.DEFAULT_STARTUP_PAGE
        )
        navController.navigate(Routes.fromStartupPage(defaultPage)) {
            popUpTo(Routes.ONBOARDING) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun goNext() {
        if (preparing) return
        when {
            !agreed && pagerState.currentPage == 0 -> {
                preparing = true
                scope.launch {
                    app.pluginRepository.ensureBundledPluginsInstalled()
                    agreed = true
                    preparing = false
                    pagerState.animateScrollToPage(1)
                }
            }
            pagerState.currentPage == 2 -> {
                // 最后一步：检查是否已安装规则（原版：没有规则时提示“暂不添加规则？”）
                if (app.pluginRepository.allPlugins().isEmpty()) {
                    showSkipDialog = true
                } else {
                    scope.launch { finishOnboarding() }
                }
            }
            else -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            OnboardingProgress(
                labels = stepLabels,
                currentIndex = pagerState.currentPage,
                modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = agreed
            ) { page ->
                when (page) {
                    0 -> DisclaimerStepContent()
                    1 -> MirrorSettingsStepContent(
                        bangumiAcceleration = bangumiAcceleration,
                        imageAcceleration = imageAcceleration,
                        enableGitProxy = enableGitProxy,
                        onBangumiChanged = {
                            bangumiAcceleration = it
                            scope.launch { settings.setString(SettingsManager.Proxy.BANGUMI_ACCELERATION, it) }
                        },
                        onImageChanged = {
                            imageAcceleration = it
                            scope.launch {
                                settings.setString(SettingsManager.Proxy.IMAGE_ACCELERATION, it)
                                app.updateImageAcceleration(com.kazumi.port.network.ImageAcceleration.fromSetting(it))
                            }
                        },
                        onGitProxyChanged = {
                            enableGitProxy = it
                            scope.launch {
                                settings.setBoolean(SettingsManager.Proxy.ENABLE_GIT_PROXY, it)
                                app.pluginRepository.setGitProxyEnabled(it)
                            }
                        }
                    )
                    else -> OnboardingCatalogStep(onOpenShop = {
                        navController.navigate(Routes.SETTINGS_PLUGIN_SHOP)
                    })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    enabled = agreed && pagerState.currentPage > 0 && !preparing,
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }
                ) {
                    Text(if (pagerState.currentPage == 0) "退出" else "上一步")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { goNext() }, enabled = !preparing) {
                    if (preparing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在准备")
                    } else {
                        Text(
                            when {
                                !agreed -> "同意并继续"
                                pagerState.currentPage == 2 -> "开始使用"
                                else -> "继续"
                            }
                        )
                        Icon(
                            imageVector = if (pagerState.currentPage == 2) Icons.Filled.Check
                            else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        }
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { Icon(Icons.Filled.WarningAmber, contentDescription = null) },
            title = { Text("暂不添加规则？") },
            text = { Text("没有规则时无法搜索和播放番剧。之后也可以在规则管理中添加。") },
            confirmButton = {
                TextButton(onClick = {
                    showSkipDialog = false
                    scope.launch { finishOnboarding() }
                }) { Text("仍然继续") }
            },
            dismissButton = {
                // 原版：返回添加为主按钮
                Button(onClick = { showSkipDialog = false }) { Text("返回添加") }
            }
        )
    }
}

/** 顶部步骤进度条（对应原版 _OnboardingProgress） */
@Composable
private fun OnboardingProgress(
    labels: List<String>,
    currentIndex: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = labels[currentIndex],
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.W700,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${currentIndex + 1} / ${labels.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(
                            color = if (index <= currentIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(3.dp)
                        )
                )
            }
        }
    }
}

/** 步骤标题区（对应原版 OnboardingStepLayout 顶部引导） */
@Composable
private fun OnboardingStepIntro(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    iconBackground: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String? = null
) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(iconBackground, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(text = title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.W700)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** 提示行（对应原版 OnboardingHint） */
@Composable
private fun OnboardingHint(text: String) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 步骤 1：免责声明（对应原版 DisclaimerStep / statements.txt） */
@Composable
private fun DisclaimerStepContent() {
    val context = LocalContext.current
    var statements by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        statements = try {
            context.assets.open("statements/statements.txt")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "免责声明加载失败，请退出后重试。"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        OnboardingStepIntro(
            icon = Icons.Filled.VerifiedUser,
            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
            iconBackground = MaterialTheme.colorScheme.primaryContainer,
            title = "欢迎来到 Kazumi"
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "免责声明",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.W600
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (statements == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    statements!!.trim().split("\n").forEach { p ->
                        Text(
                            text = p.trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.4,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 步骤 2：访问加速（对应原版 MirrorSettingsStep / NetworkMirrorSettings 精简版） */
@Composable
private fun MirrorSettingsStepContent(
    bangumiAcceleration: String,
    imageAcceleration: String,
    enableGitProxy: Boolean,
    onBangumiChanged: (String) -> Unit,
    onImageChanged: (String) -> Unit,
    onGitProxyChanged: (Boolean) -> Unit
) {
    val bangumiLabels = mapOf("direct" to "直连", "ech" to "加密握手", "mirror" to "镜像")
    val bangumiOrder = listOf("direct", "ech", "mirror")
    val imageLabels = mapOf("direct" to "直连", "ech" to "ECH", "mirror" to "镜像")
    val imageOrder = listOf("direct", "ech", "mirror")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        OnboardingStepIntro(
            icon = Icons.Filled.Public,
            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
            iconBackground = MaterialTheme.colorScheme.tertiaryContainer,
            title = "让连接更顺畅",
            subtitle = "番剧信息与评论默认使用镜像，也可选择 ECH 或直连。图片默认使用 ECH 加速。"
        )
        SettingGroup("访问加速") {
            SettingClickRow(
                title = "番剧条目加速",
                subtitle = "番剧信息与评论",
                leading = Icons.Filled.Info,
                trailingValue = bangumiLabels[bangumiAcceleration] ?: "镜像",
                onClick = {
                    val idx = bangumiOrder.indexOf(bangumiAcceleration)
                    onBangumiChanged(bangumiOrder[(idx + 1) % bangumiOrder.size])
                }
            )
            SettingSwitchRow(
                title = "规则仓库镜像",
                subtitle = "加速规则的下载与更新",
                leading = Icons.Filled.Extension,
                checked = enableGitProxy,
                onCheckedChange = { onGitProxyChanged(it) }
            )
            SettingClickRow(
                title = "图片加速",
                subtitle = "封面与头像",
                leading = Icons.Filled.Info,
                trailingValue = imageLabels[imageAcceleration] ?: "ECH",
                onClick = {
                    val idx = imageOrder.indexOf(imageAcceleration)
                    onImageChanged(imageOrder[(idx + 1) % imageOrder.size])
                }
            )
        }
        OnboardingHint(text = "可在「设置 → 网络设置」中调整。")
    }
}

/** 步骤 4：添加规则（对应原版 PluginShopStep，精简为引导去规则仓库） */
@Composable
private fun PluginShopStepContent(onOpenShop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        OnboardingStepIntro(
            icon = Icons.Filled.Extension,
            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
            iconBackground = MaterialTheme.colorScheme.primaryContainer,
            title = "发现更多番剧来源",
            subtitle = "添加喜欢的规则，拓展搜索来源。也可以先开始使用，稍后在规则管理中添加。"
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "规则仓库",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.W700,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "浏览社区规则，为你的番剧搜索添加更多选择。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenShop) {
                    Icon(Icons.Filled.Extension, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("打开规则仓库")
                }
            }
        }
        OnboardingHint(text = "也可以先开始使用，稍后在「设置 → 规则管理」中添加。")
    }
}