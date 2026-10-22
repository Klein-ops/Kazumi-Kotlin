package com.kazumi.port.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.BangumiItem
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.network.BangumiClient
import kotlinx.coroutines.launch

private val SCORE_LABELS = listOf(
    "未评分", "不忍直视", "很差", "差", "较差", "不过不失",
    "还行", "推荐", "力荐", "神作", "超神作"
)

private const val MAX_TAGS = 10
private const val MAX_TAG_LENGTH = 10
private const val MAX_COMMENT_LENGTH = 380

/**
 * 评分吐槽弹窗
 * 对齐原版 rating_review_dialog.dart（评分 1-10 + 热门标签 + 自定义标签 + 吐槽）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingReviewSheet(
    bangumiItem: BangumiItem,
    onSubmit: suspend (score: Int, tags: List<String>, comment: String) -> Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    val interest = bangumiItem.interest
    val savedScore = (interest?.rate ?: 0).coerceIn(0, 10)
    val initialComment = interest?.comment ?: ""
    val initialTags = (interest?.tags ?: emptyList()).toSet()

    var score by remember { mutableIntStateOf(if (savedScore > 0) savedScore else 5) }
    var comment by remember { mutableStateOf(initialComment) }
    var selectedTags by remember { mutableStateOf(initialTags.toMutableList()) }
    var tagError by remember { mutableStateOf<String?>(null) }
    var showCustomTag by remember { mutableStateOf(false) }
    var customTag by remember { mutableStateOf("") }

    val popularTags = remember(bangumiItem.id) {
        bangumiItem.tags
            .map { it.name.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_TAG_LENGTH }
            .distinct()
            .take(20)
    }

    val editing = savedScore > 0 || initialComment.trim().isNotEmpty()

    fun toggleTag(tag: String) {
        if (submitting) return
        if (selectedTags.remove(tag)) {
            tagError = null
        } else if (selectedTags.size >= MAX_TAGS) {
            tagError = "最多 $MAX_TAGS 个标签"
        } else {
            selectedTags.add(tag)
            tagError = null
        }
    }

    fun addCustomTag(): Boolean {
        val tag = customTag.trim()
        val error: String? = when {
            tag.isEmpty() -> "请输入标签"
            tag.length > MAX_TAG_LENGTH -> "标签最多 $MAX_TAG_LENGTH 字"
            selectedTags.contains(tag) -> "标签已添加"
            selectedTags.size >= MAX_TAGS -> "最多 $MAX_TAGS 个标签"
            else -> null
        }
        tagError = error
        if (error == null) {
            selectedTags.add(tag)
            customTag = ""
        }
        return error == null
    }

    fun submit() {
        if (submitting) return
        if (customTag.trim().isNotEmpty() && !addCustomTag()) return
        if (comment.length > MAX_COMMENT_LENGTH || selectedTags.size > MAX_TAGS) {
            submitError = "吐槽最多 $MAX_COMMENT_LENGTH 字，标签最多 $MAX_TAGS 个"
            return
        }
        submitting = true
        submitError = null
        scope.launch {
            try {
                val ok = onSubmit(score, selectedTags.toList(), comment.trim())
                if (ok) {
                    onDismiss()
                } else {
                    submitError = "发表失败，请检查网络或 Bangumi 授权"
                    submitting = false
                }
            } catch (e: Exception) {
                submitError = "发表失败，请检查网络或 Bangumi 授权"
                submitting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 700.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (editing) "编辑吐槽" else "发表吐槽",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // 番剧名
            Text(
                text = bangumiItem.displayName(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )

            // 吐槽输入
            OutlinedTextField(
                value = comment,
                onValueChange = { if (it.length <= MAX_COMMENT_LENGTH) comment = it },
                enabled = !submitting,
                minLines = 4,
                maxLines = 8,
                label = { Text("吐槽") },
                placeholder = { Text("下面我简单喵两句…") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            // 评分
            ScoreSelector(
                score = score,
                enabled = !submitting,
                onScoreChange = { score = it }
            )

            Spacer(Modifier.height(24.dp))

            // 标签
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "标签",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${selectedTags.size} / $MAX_TAGS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))

            if (selectedTags.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selectedTags) { tag ->
                        FilterChip(
                            selected = true,
                            onClick = { toggleTag(tag) },
                            label = { Text(tag) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (popularTags.isNotEmpty()) {
                Text(
                    text = "热门标签",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(popularTags) { tag ->
                    AssistChip(
                        onClick = { toggleTag(tag) },
                        label = { Text(tag) }
                    )
                }
            }
            if (!showCustomTag) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showCustomTag = true }) {
                    Text("+ 自定义标签")
                }
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customTag,
                    onValueChange = { customTag = it },
                    enabled = !submitting,
                    label = { Text("自定义标签") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showCustomTag = false }) { Text("取消") }
                    TextButton(
                        onClick = { addCustomTag() },
                        enabled = customTag.trim().isNotEmpty()
                    ) { Text("添加") }
                }
            }

            if (tagError != null) {
                Text(
                    text = tagError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (submitError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = submitError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "发布至 Bangumi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { submit() },
                    enabled = !submitting
                ) {
                    Text(if (submitting) "正在发表…" else if (submitError != null) "重试" else if (editing) "保存修改" else "发表")
                }
            }
        }
    }
}

@Composable
private fun ScoreSelector(
    score: Int,
    enabled: Boolean,
    onScoreChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(24.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (score == 0) "—" else "$score",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.width(64.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "评分",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = SCORE_LABELS[score],
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // 5 列 × 2 行
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowStart in listOf(1, 6)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (value in rowStart until rowStart + 5) {
                        val selected = score == value
                        androidx.compose.material3.FilledTonalButton(
                            onClick = { onScoreChange(value) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "$value",
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
        // 清除评分
        if (score != 0) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onScoreChange(0) }, enabled = enabled) {
                Text("清除评分")
            }
        }
    }
}