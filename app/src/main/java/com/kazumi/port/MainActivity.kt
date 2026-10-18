package com.kazumi.port

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.ui.navigation.KazumiNavHost
import com.kazumi.port.ui.navigation.Routes
import com.kazumi.port.ui.theme.KazumiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 主界面入口（对应原版 main.dart / init_page.dart）
 * 装配 Compose 导航 + 主题 + 下载控制器初始化
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化下载控制器（启动时重置未完成任务为 paused）
        val app = KazumiApp.get()
        scope.launch {
            app.downloadController.init()
        }

        // 对应原版 init_page._initializeApp：
        // 1) WebDAV 初始化 + 历史同步（webDavEnable / webDavEnableHistory）
        // 2) Bangumi 连通性检测（bangumiSyncEnable）
        // 3) 插件启动更新检查（checkPluginUpdateOnStartup）
        scope.launch {
            try {
                if (app.settings.getBoolean(SettingsManager.WebDav.ENABLE, false)) {
                    app.webDavSyncService.init()
                    if (app.settings.getBoolean(SettingsManager.WebDav.ENABLE_HISTORY, false)) {
                        app.webDavSyncService.syncHistory { remote ->
                            // 本地无历史快照时以远端为准；类型要求返回非空字符串
                            remote.orEmpty()
                        }
                    }
                }
            } catch (e: Exception) {
                // WebDAV 初始化失败不阻塞启动
            }
        }
        scope.launch {
            try {
                if (app.settings.getBoolean(SettingsManager.Bangumi.SYNC_ENABLE, false)) {
                    app.bangumiSyncService.connect()
                }
            } catch (e: Exception) {
                // Bangumi 暂未连接时保留设置以便下次重试
            }
        }
        scope.launch {
            try {
                val enabled = app.settings.getBoolean(SettingsManager.Update.CHECK_PLUGIN_UPDATE_ON_STARTUP, false)
                val count = app.pluginRepository.checkPluginUpdatesOnStartup(enabled)
                if (count != 0) {
                    // 检测到可更新规则，可由规则管理页提示（此处静默）
                }
            } catch (e: Exception) {
                // 启动更新检查失败不阻塞
            }
        }

        setContent {
            val app = remember { KazumiApp.get() }
            val settings = app.settings
            val themeMode by settings.stringFlow(SettingsManager.Theme.MODE, "system")
                .collectAsState(initial = "system")
            val themeColor by settings.stringFlow(SettingsManager.Theme.COLOR, "default")
                .collectAsState(initial = "default")
            val oledEnhance by settings.booleanFlow(SettingsManager.Theme.OLED_ENHANCE, false)
                .collectAsState(initial = false)
            val useDynamicColor by settings.booleanFlow(SettingsManager.Theme.USE_DYNAMIC_COLOR, false)
                .collectAsState(initial = false)

            // 启动分流：首次使用进入引导页（对应原版 init_page 中 pluginList 为空时跳 /onboarding）
            var startReady by remember { mutableStateOf(false) }
            var startDest by remember { mutableStateOf(Routes.POPULAR) }
            LaunchedEffect(Unit) {
                val done = settings.getBoolean(SettingsManager.Interface.ONBOARDING_DONE, false)
                startDest = if (done) Routes.POPULAR else Routes.ONBOARDING
                startReady = true
            }

            KazumiTheme(
                themeMode = themeMode,
                themeColor = themeColor,
                oledEnhance = oledEnhance,
                useDynamicColor = useDynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (startReady) {
                        KazumiNavHost(startDestination = startDest)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}