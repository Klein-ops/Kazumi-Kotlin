package com.kazumi.port.ui.page

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.ResultItem
import com.kazumi.port.data.repository.TraceApi
import kotlinx.coroutines.launch
import java.io.File

/**
 * 以图搜番页（对应原版 image_search_page.dart）
 * 支持本地图片 / 图片链接两种来源，展示相似度最高与其余片段
 */
@Composable
fun ImageSearchPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var useUrl by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }
    var imageFile by remember { mutableStateOf<File?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var isPicking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ResultItem>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchedOnce by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    val traceApi = remember { TraceApi() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            isPicking = true
            scope.launch {
                try {
                    val file = app.cacheDir.resolve("trace_pick_${System.currentTimeMillis()}.jpg")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (file.exists() && file.length() > 25 * 1024 * 1024) {
                        inputError = "图片超过 25 MB，请换一张较小的截图"
                        file.delete()
                    } else if (file.exists()) {
                        imageFile = file
                        inputError = null
                        searchedOnce = false
                        results = emptyList()
                        searchError = null
                    } else {
                        inputError = "无法读取图片，请重新选择并检查相册权限"
                    }
                } catch (e: Exception) {
                    inputError = "无法读取图片，请重新选择并检查相册权限"
                } finally {
                    isPicking = false
                }
            }
        }
    }

    val hasImage = if (useUrl) {
        urlText.trim().startsWith("http://") || urlText.trim().startsWith("https://")
    } else imageFile != null

    fun doSearch() {
        if (isSearching || isPicking) return
        if (!hasImage) {
            inputError = "请输入有效的 HTTP 或 HTTPS 图片链接"
            return
        }
        inputError = null
        isSearching = true
        searchedOnce = true
        scope.launch {
            try {
                val item = if (useUrl) {
                    traceApi.searchAnimeByImageUrl(urlText.trim())
                } else {
                    traceApi.searchAnimeByImageFile(imageFile!!)
                }
                results = item.result?.sortedByDescending { it.similarity ?: 0.0 } ?: emptyList()
                searchError = item.error
            } catch (e: Exception) {
                searchError = "网络请求失败：${e.message ?: "未知错误"}"
                results = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    fun pasteUrl() {
        val text = clipboard.getText()?.text?.trim().orEmpty()
        if (text.isEmpty()) {
            inputError = "剪贴板里还没有图片链接"
            return
        }
        urlText = text
        if (!(text.startsWith("http://") || text.startsWith("https://"))) {
            inputError = "请输入以 https:// 或 http:// 开头的图片链接"
        } else {
            inputError = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回"
                )
            }
            Text(
                text = "用截图找到番名",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.W700,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showHelp = true }) {
                Icon(Icons.Filled.HelpOutline, contentDescription = "搜图小贴士")
            }
        }
        Text(
            text = "用一张截图，找到番名与出现的集数。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 来源切换（本地图片 / 图片链接）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SourceButton(
                text = "本地图片",
                icon = if (!useUrl) Icons.Filled.Check else Icons.Filled.AddPhotoAlternate,
                selected = !useUrl,
                enabled = !isSearching && !isPicking,
                onClick = { useUrl = false }
            )
            SourceButton(
                text = "图片链接",
                icon = if (useUrl) Icons.Filled.Check else Icons.Filled.Link,
                selected = useUrl,
                enabled = !isSearching && !isPicking,
                onClick = { useUrl = true }
            )
        }
        Spacer(Modifier.height(16.dp))

        if (useUrl) {
            OutlinedTextField(
                value = urlText,
                onValueChange = {
                    urlText = it
                    inputError = null
                    searchedOnce = false
                    results = emptyList()
                    searchError = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("图片链接") },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { doSearch() }
                ),
                trailingIcon = {
                    IconButton(onClick = { pasteUrl() }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴图片链接")
                    }
                }
            )
            Text(
                text = "使用可直接访问的 HTTP 或 HTTPS 图片地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (urlText.trim().startsWith("http")) {
                Spacer(Modifier.height(12.dp))
                AsyncImage(
                    model = urlText.trim(),
                    contentDescription = "图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
                )
            }
        } else {
            if (imageFile != null) {
                val bmp = runCatching {
                    val b = android.graphics.BitmapFactory.decodeFile(imageFile!!.absolutePath)
                    b?.asImageBitmap()
                }.getOrNull()
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "截图预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂时无法预览\n仍可尝试识别，或更换图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = imageFile!!.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { picker.launch("image/*") }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("更换截图", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
                        .clickable(enabled = !isPicking) { picker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "点击选择截图",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            Text(
                text = "JPG、PNG、WebP · 最大 25 MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }

        if (inputError != null) {
            Text(
                text = inputError!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { doSearch() },
            enabled = !isSearching && !isPicking,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("正在识别")
            } else {
                Icon(Icons.Filled.ImageSearch, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(if (imageFile == null && !useUrl) "选择截图" else "开始识别")
            }
        }
        Text(
            text = "由 trace.moe 识别动画截图",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )

        // 结果
        if (searchedOnce) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            when {
                isSearching -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在寻找这一幕",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        Text(
                            text = "正在比对动画画面，匹配番名、集数与时间。\n请稍等片刻。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                results.isEmpty() -> {
                    if (searchError != null && searchError != "未找到匹配结果") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.WifiOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "这次识别未完成",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            Text(
                                text = searchError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            OutlinedButton(onClick = { doSearch() }, modifier = Modifier.padding(top = 12.dp)) {
                                Text("重新识别")
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.ImageSearch,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "没有找到匹配画面",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        text = "找到 ${results.size} 个相似片段",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.W700
                    )
                    Text(
                        text = "按画面相似度排序，先对照截图再确认。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    BestMatchCard(
                        result = results.first(),
                        onSelect = { title ->
                            // 把标题带回搜索页
                            navController.previousBackStackEntry?.savedStateHandle
                                ?.set("image_search_keyword", title)
                            navController.popBackStack()
                        }
                    )
                    if (results.size > 1) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = "其他相似片段",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.W700
                        )
                        Spacer(Modifier.height(8.dp))
                        results.drop(1).forEach { result ->
                            OtherMatchCard(
                                result = result,
                                onSelect = { title ->
                                    navController.previousBackStackEntry?.savedStateHandle
                                        ?.set("image_search_keyword", title)
                                    navController.popBackStack()
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            icon = { Icon(Icons.Filled.ImageSearch, contentDescription = null) },
            title = { Text("让截图更容易被找到") },
            text = {
                Column {
                    Text("使用动画正片截图，保留原始比例。尽量避开黑边、字幕遮挡、水印与拼接画面。")
                    Spacer(Modifier.height(12.dp))
                    Text("插画、漫画和经过大幅裁剪的图片通常无法匹配。相似度仅供参考，建议对照画面或预览片段确认。")
                    Spacer(Modifier.height(12.dp))
                    Text("识别由 trace.moe 提供。开始识别后，所选图片或图片链接会发送至该服务。")
                }
            },
            confirmButton = {
                Button(onClick = { showHelp = false }) { Text("知道了") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showHelp = false
                    runCatching {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://trace.moe")
                        )
                        app.startActivity(intent)
                    }
                }) { Text("访问 trace.moe") }
            }
        )
    }
}

@Composable
private fun RowScope.SourceButton(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerHighest,
        shape = RoundedCornerShape(if (selected) 24.dp else 16.dp),
        modifier = Modifier.weight(1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
            )
        }
    }
}

/** 相似度最高卡片（对应原版 _BestMatch） */
@Composable
private fun BestMatchCard(
    result: ResultItem,
    onSelect: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val title = titleOf(result)
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "相似度最高",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${similarityOf(result)} 相似",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        fontWeight = FontWeight.W700
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                    fontWeight = FontWeight.W700
                )
            }
            AsyncImage(
                model = result.image ?: "",
                contentDescription = "${title}的匹配画面",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(colors.surfaceContainerLow)
            )
            Column(modifier = Modifier.padding(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = episodeLabel(result.episode),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface
                    )
                    Text(
                        text = timeRange(result),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSelect(title) }) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("搜索这部番", modifier = Modifier.padding(start = 6.dp))
                    }
                    if (isHttpUrl(result.video.orEmpty())) {
                        OutlinedButton(onClick = {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(result.video)
                                )
                                com.kazumi.port.KazumiApp.get().startActivity(intent)
                            }
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("预览片段", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 其他相似片段卡片（对应原版 _OtherMatch） */
@Composable
private fun OtherMatchCard(
    result: ResultItem,
    onSelect: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val title = titleOf(result)
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(title) }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = result.image ?: "",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(80.dp)
                    .height(56.dp)
                    .background(colors.surfaceContainerLow, RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W600,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${episodeLabel(result.episode)} · ${similarityOf(result)} 相似",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = timeRange(result),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.Movie,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun titleOf(result: ResultItem): String {
    val t = result.anilist?.title
    listOf(t?.chinese, t?.native, t?.romaji, t?.english, result.filename).forEach {
        if (!it.isNullOrBlank()) return it.trim()
    }
    return "未知番剧"
}

private fun episodeLabel(value: Any?): String {
    fun number(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()
    return when (value) {
        is Double -> "第 ${number(value)} 集"
        is Int -> "第 $value 集"
        is List<*> -> {
            val nums = value.filterIsInstance<Number>().map { number(it.toDouble()) }
            if (nums.isNotEmpty()) "第 ${nums.joinToString(" / ")} 集" else "集数未知"
        }
        else -> "集数未知"
    }
}

private fun similarityOf(result: ResultItem): String {
    val v = result.similarity ?: return "未知"
    if (!v.isFinite()) return "未知"
    return "${(v.coerceIn(0.0, 1.0) * 100).let { String.format("%.1f", it) }}%"
}

private fun timeRange(result: ResultItem): String {
    fun time(value: Double): String {
        val seconds = value.toInt()
        val minutes = seconds / 60
        return String.format("%02d:%02d", minutes, seconds % 60)
    }
    val from = result.from ?: result.at
    if (from == null || !from.isFinite() || from < 0) return "时间未知"
    val to = result.to
    if (to == null || !to.isFinite() || to < from) return time(from)
    return "${time(from)} – ${time(to)}"
}

private fun isHttpUrl(value: String): Boolean =
    value.startsWith("http://") || value.startsWith("https://")