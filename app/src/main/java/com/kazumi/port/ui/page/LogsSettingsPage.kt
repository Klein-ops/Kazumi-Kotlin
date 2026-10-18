package com.kazumi.port.ui.page

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import java.io.File

/**
 * 日志页（对应原版 logs_page.dart）
 * 读取应用日志文件，支持清空与复制
 */
@Composable
fun LogsSettingsPage(navController: NavHostController) {
    val context = LocalContext.current
    val app = remember { KazumiApp.get() }
    val appContext = remember { app.applicationContext }

    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    fun logFile(): File = File(appContext.filesDir, "logs/kazumi_logs.log")

    fun loadLogs() {
        isLoading = true
        hasError = false
        try {
            val file = logFile()
            logLines = if (file.exists()) file.readLines() else emptyList()
        } catch (e: Exception) {
            hasError = true
            logLines = emptyList()
        }
        isLoading = false
    }

    fun clearLogs() {
        try {
            logFile().writeText("")
            logLines = emptyList()
        } catch (e: Exception) {
            // 忽略清空失败
        }
    }

    fun copyLogs() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("kazumi_logs", logLines.joinToString("\n"))
        )
    }

    LaunchedEffect(Unit) { loadLogs() }

    SettingsSubPageScaffold(
        title = "日志",
        onBack = { navController.popBackStack() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                when {
                    isLoading -> Text(
                        "正在加载日志…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    hasError -> Text(
                        "无法读取日志，请稍后重新加载。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    logLines.isEmpty() -> Text(
                        "还没有日志记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> logLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { clearLogs() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "清空日志")
                }
                IconButton(onClick = { copyLogs() }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制日志")
                }
            }
        }
    }
}