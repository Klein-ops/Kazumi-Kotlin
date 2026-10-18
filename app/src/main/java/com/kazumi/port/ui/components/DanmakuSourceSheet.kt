package com.kazumi.port.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import com.kazumi.port.data.repository.DanmakuApi
import com.kazumi.port.data.repository.DanmakuEpisode
import com.kazumi.port.data.repository.DanmakuSearchAnime
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.player.PlayerDanmakuController
import kotlinx.coroutines.launch

/**
 * 弹幕源切换底部弹窗（对应原版 danmaku_source_sheet.dart）
 *
 * 三步流程：搜索番剧 -> 选择番剧 -> 选择分集（应用到当前播放）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DanmakuSourceSheet(
    bangumiId: Int,
    initialKeyword: String,
    danmakuController: PlayerDanmakuController,
    danmakuApi: DanmakuApi,
    settings: SettingsManager,
    onDismiss: () -> Unit,
    toast: (String) -> Unit
) {
    var step by remember { mutableIntStateOf(0) } // 0 搜索 1 番剧列表 2 分集列表
    var keyword by remember { mutableStateOf(initialKeyword) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var animes by remember { mutableStateOf<List<DanmakuSearchAnime>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<DanmakuEpisode>>(emptyList()) }
    var animeTitle by remember { mutableStateOf<String?>(null) }
    var recentKeywords by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val historyKey = "danmakuSearchHistory_$bangumiId"

    // 读取历史
    androidx.compose.runtime.LaunchedEffect(Unit) {
        recentKeywords = settings.getStringList(historyKey, emptyList())
    }

    fun search() {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            error = "请输入番剧名称"
            return
        }
        scope.launch {
            loading = true
            error = null
            step = 1
            animes = emptyList()
            // 记录历史
            val newHistory = (listOf(kw) + recentKeywords.filter { it != kw }).take(5)
            recentKeywords = newHistory
            settings.setStringList(historyKey, newHistory)
            try {
                val resp = danmakuApi.searchAnimes(kw)
                animes = resp.animes
                if (resp.animes.isEmpty()) error = "未找到番剧"
            } catch (e: Exception) {
                error = "搜索失败"
            }
            loading = false
        }
    }

    fun selectAnime(anime: DanmakuSearchAnime) {
        scope.launch {
            loading = true
            error = null
            animeTitle = anime.animeTitle
            step = 2
            episodes = emptyList()
            try {
                val resp = danmakuApi.getDanDanEpisodesByDanDanBangumiID(anime.animeId)
                episodes = resp.episodes
                if (episodes.isEmpty()) error = "暂无分集"
            } catch (e: Exception) {
                error = "分集加载失败"
            }
            loading = false
        }
    }

    fun selectEpisode(episode: DanmakuEpisode) {
        scope.launch {
            loading = true
            try {
                val has = danmakuController.getDanDanmakuByEpisodeID(episode.episodeId)
                danmakuController.setDanmakuEnabled(has)
                onDismiss()
                toast(if (has) "已切换弹幕源" else "暂无弹幕")
            } catch (e: Exception) {
                loading = false
                toast("弹幕源切换失败")
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // 头部
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    IconButton(onClick = {
                        error = null
                        step = if (step == 2) 1 else 0
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (step) {
                            0 -> "选择弹幕源"
                            1 -> "选择番剧"
                            else -> "选择分集"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (step == 1) Text(keyword, style = MaterialTheme.typography.bodySmall)
                    if (step == 2 && animeTitle != null) Text(animeTitle!!, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when (step) {
                            0, 1 -> "搜索中…"
                            else -> if (episodes.isEmpty()) "加载分集…" else "切换中…"
                        }
                    )
                }
            } else {
                when (step) {
                    0 -> {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it; error = null },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            placeholder = { Text("番剧名称") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = if (keyword.isNotEmpty()) {
                                {
                                    IconButton(onClick = { keyword = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清空")
                                    }
                                }
                            } else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { search() },
                                onDone = { search() }
                            )
                        )
                        if (error != null) {
                            Text(
                                error!!,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        if (recentKeywords.isNotEmpty()) {
                            Text(
                                "最近搜索",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            FlowRow(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                recentKeywords.forEach { kw ->
                                    InputChip(
                                        selected = false,
                                        onClick = { keyword = kw; search() },
                                        label = { Text(kw) },
                                        leadingIcon = {
                                            Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.width(16.dp))
                                        }
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) { Text("取消") }
                            Spacer(modifier = Modifier.width(10.dp))
                            FilledTonalButton(onClick = { search() }) {
                                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.width(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("搜索")
                            }
                        }
                    }
                    1 -> {
                        if (error != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(error!!, color = MaterialTheme.colorScheme.error)
                                FilledTonalButton(
                                    onClick = { step = 0; error = null },
                                    modifier = Modifier.padding(top = 12.dp)
                                ) { Text("返回搜索") }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                                items(animes) { anime ->
                                    ListItem(
                                        headlineContent = {
                                            Text(anime.animeTitle, maxLines = 2)
                                        },
                                        supportingContent = if (anime.typeDescription.isNotEmpty()) {
                                            { Text(anime.typeDescription) }
                                        } else null,
                                        trailingContent = { Text("›") },
                                        modifier = Modifier.clickable { selectAnime(anime) }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    else -> {
                        if (error != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(error!!, color = MaterialTheme.colorScheme.error)
                                FilledTonalButton(
                                    onClick = { step = 1; error = null },
                                    modifier = Modifier.padding(top = 12.dp)
                                ) { Text("返回番剧") }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                                items(episodes) { episode ->
                                    ListItem(
                                        headlineContent = { Text(episode.episodeTitle, maxLines = 1) },
                                        modifier = Modifier.clickable { selectEpisode(episode) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}