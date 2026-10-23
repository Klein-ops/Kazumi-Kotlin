package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kazumi.port.KazumiApp
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.components.rememberToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 规则编辑页（对应原版 plugin_editor_page）
 *
 * 两种模式：
 * - **表单模式**：改动最常用字段（名称/版本/站点地址/搜索地址/UA/Referer、内置播放器/WebView/多源/
 *   POST/旧解析器/广告过滤、以及 5 个 XPath 选择器）。保存时在**原始 JSON 上覆盖这些键**，
 *   其余字段（api 配置、反爬配置等）原样保留，不会被表单抹掉。
 * - **JSON 模式**：直接编辑完整 JSON，保存前解析校验，失败不覆盖原文件。
 */
@Composable
fun RuleEditPage(navController: NavHostController, pluginName: String) {
    val app = remember { KazumiApp.get() }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    var jsonMode by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var baseJson by remember { mutableStateOf<JsonObject?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    // 表单字段
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var searchUrl by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    var referer by remember { mutableStateOf("") }
    var useNativePlayer by remember { mutableStateOf(true) }
    var useWebview by remember { mutableStateOf(false) }
    var muliSources by remember { mutableStateOf(false) }
    var usePost by remember { mutableStateOf(false) }
    var useLegacyParser by remember { mutableStateOf(false) }
    var adBlocker by remember { mutableStateOf(false) }
    var searchList by remember { mutableStateOf("") }
    var searchName by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf("") }
    var chapterRoads by remember { mutableStateOf("") }
    var chapterResult by remember { mutableStateOf("") }

    fun fillForm(obj: JsonObject) {
        fun str(key: String, def: String = ""): String =
            obj.get(key)?.takeIf { !it.isJsonNull }?.asString ?: def
        fun bool(key: String, def: Boolean): Boolean =
            runCatching { obj.get(key)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull() ?: def
        name = str("name", pluginName)
        version = str("version")
        baseUrl = str("baseURL")
        searchUrl = str("searchURL")
        userAgent = str("userAgent")
        referer = str("referer")
        useNativePlayer = bool("useNativePlayer", true)
        useWebview = bool("useWebview", false)
        muliSources = bool("muliSources", false)
        usePost = bool("usePost", false)
        useLegacyParser = bool("useLegacyParser", false)
        adBlocker = bool("adBlocker", false)
        searchList = str("searchList")
        searchName = str("searchName")
        searchResult = str("searchResult")
        chapterRoads = str("chapterRoads")
        chapterResult = str("chapterResult")
    }

    LaunchedEffect(Unit) {
        val raw = withContext(Dispatchers.IO) {
            app.pluginRepository.readPluginJson(pluginName) ?: ""
        }
        text = raw
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        baseJson = obj
        if (obj != null) fillForm(obj)
        loaded = true
    }

    /** 表单 → JSON：在原始 JSON 上覆盖表单字段，保留其余配置 */
    fun buildJsonFromForm(): String? {
        val obj = baseJson?.let {
            runCatching { JsonParser.parseString(it.toString()).asJsonObject }.getOrNull()
        } ?: JsonObject()
        obj.addProperty("name", name.trim())
        obj.addProperty("version", version.trim())
        obj.addProperty("baseURL", baseUrl.trim())
        obj.addProperty("searchURL", searchUrl.trim())
        obj.addProperty("userAgent", userAgent)
        obj.addProperty("referer", referer)
        obj.addProperty("useNativePlayer", useNativePlayer)
        obj.addProperty("useWebview", useWebview)
        obj.addProperty("muliSources", muliSources)
        obj.addProperty("usePost", usePost)
        obj.addProperty("useLegacyParser", useLegacyParser)
        obj.addProperty("adBlocker", adBlocker)
        obj.addProperty("searchList", searchList.trim())
        obj.addProperty("searchName", searchName.trim())
        obj.addProperty("searchResult", searchResult.trim())
        obj.addProperty("chapterRoads", chapterRoads.trim())
        obj.addProperty("chapterResult", chapterResult.trim())
        return obj.toString()
    }

    fun save() {
        if (saving) return
        val payload = if (jsonMode) text else buildJsonFromForm() ?: text
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                app.pluginRepository.savePluginJsonText(pluginName, payload)
            }
            saving = false
            if (ok) {
                text = payload
                val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
                if (obj != null) {
                    baseJson = obj
                    fillForm(obj)
                }
                dirty = false
                toast("规则已保存")
            } else {
                toast("保存失败：JSON 解析不通过，请检查语法与必填字段")
            }
        }
    }

    SettingsSubPageScaffold(
        title = "编辑规则 · $pluginName",
        onBack = { navController.popBackStack() },
        actions = {
            TextButton(onClick = { save() }, enabled = loaded && !saving && dirty) {
                Text(if (saving) "保存中…" else "保存")
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !jsonMode,
                    onClick = {
                        jsonMode = false
                        dirty = false
                    },
                    label = { Text("表单") }
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                FilterChip(
                    selected = jsonMode,
                    onClick = {
                        jsonMode = true
                        text = buildJsonFromForm() ?: text
                        dirty = false
                    },
                    label = { Text("JSON") }
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = "表单只覆盖常用字段，其余配置原样保留",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()

            if (jsonMode) {
                Text(
                    text = "直接编辑完整 JSON，保存前会解析校验，失败不会覆盖原文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        dirty = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    label = { Text("规则 JSON") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    minLines = 20
                )
            } else {
                SectionTitle("基本信息")
                FormField("规则名称", name) { name = it; dirty = true }
                FormField("版本", version) { version = it; dirty = true }
                FormField("站点地址（baseURL）", baseUrl) { baseUrl = it; dirty = true }
                FormField("搜索地址（searchURL）", searchUrl) { searchUrl = it; dirty = true }
                FormField("User-Agent（可留空）", userAgent) { userAgent = it; dirty = true }
                FormField("Referer（可留空）", referer) { referer = it; dirty = true }

                SectionTitle("行为开关")
                FormSwitch("使用内置播放器（useNativePlayer）", useNativePlayer) {
                    useNativePlayer = it; dirty = true
                }
                FormSwitch("使用 WebView（useWebview）", useWebview) {
                    useWebview = it; dirty = true
                }
                FormSwitch("多源模式（muliSources）", muliSources) {
                    muliSources = it; dirty = true
                }
                FormSwitch("使用 POST（usePost）", usePost) {
                    usePost = it; dirty = true
                }
                FormSwitch("旧版解析器（useLegacyParser）", useLegacyParser) {
                    useLegacyParser = it; dirty = true
                }
                FormSwitch("广告过滤（adBlocker）", adBlocker) {
                    adBlocker = it; dirty = true
                }

                SectionTitle("XPath 选择器")
                FormField("搜索结果列表（searchList）", searchList, 2) {
                    searchList = it; dirty = true
                }
                FormField("番剧名称（searchName）", searchName, 2) {
                    searchName = it; dirty = true
                }
                FormField("番剧链接（searchResult）", searchResult, 2) {
                    searchResult = it; dirty = true
                }
                FormField("剧集列表（chapterRoads）", chapterRoads, 2) {
                    chapterRoads = it; dirty = true
                }
                FormField("剧集链接（chapterResult）", chapterResult, 2) {
                    chapterResult = it; dirty = true
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.W700,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun FormField(
    label: String,
    value: String,
    minLines: Int = 1,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textStyle = if (minLines > 1) {
            TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        } else {
            TextStyle(fontSize = 14.sp)
        }
    )
}

@Composable
private fun FormSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}