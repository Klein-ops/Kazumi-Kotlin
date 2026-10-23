package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingSwitchRow
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import kotlinx.coroutines.launch

/**
 * 更新设置页（对应原版 update_settings.dart）
 * 仅保留规则仓库更新检查（应用更新不指向原版，故移除）
 */
@Composable
fun UpdateSettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()

var checkPluginUpdate by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        checkPluginUpdate = settings.getBoolean(SettingsManager.Update.CHECK_PLUGIN_UPDATE_ON_STARTUP, true)
    }

    SettingsSubPageScaffold(
        title = "更新设置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("启动时检查更新") {
                SettingSwitchRow(
                    title = "规则更新",
                    subtitle = "启动时检查规则仓库新版本",
                    leading = Icons.Filled.Extension,
                    checked = checkPluginUpdate,
                    onCheckedChange = { value ->
                        checkPluginUpdate = value
                        scope.launch { settings.setBoolean(SettingsManager.Update.CHECK_PLUGIN_UPDATE_ON_STARTUP, value) }
                    }
                )
            }
        }
    }
}