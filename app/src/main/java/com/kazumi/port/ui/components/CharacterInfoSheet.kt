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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.CharacterCommentItem
import com.kazumi.port.data.model.CharacterFullItem
import com.kazumi.port.data.model.CommentItem
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.network.BangumiClient
import com.kazumi.port.util.dateFormat
import kotlinx.coroutines.launch

/**
 * 角色详情底部弹窗
 * 对齐原版 character_page.dart + character_info_view.dart
 * 包含「资料」与「吐槽」两个 Tab
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterInfoSheet(
    characterID: Int,
    characterName: String,
    characterRelation: String,
    actorNames: List<String> = emptyList(),
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var tabIndex by remember { mutableIntStateOf(0) }
    var character by remember { mutableStateOf<CharacterFullItem?>(null) }
    var comments by remember { mutableStateOf<List<CharacterCommentItem>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(true) }
    var commentsError by remember { mutableStateOf(false) }

    val bangumiApi = remember { BangumiApi(BangumiClient(KazumiApp.get().settings)) }

    fun loadCharacter() {
        character = null
        scope.launch {
            character = bangumiApi.getCharacterByCharacterID(characterID)
        }
    }

    fun loadComments() {
        commentsLoading = true
        commentsError = false
        scope.launch {
            try {
                val response = bangumiApi.getCharacterCommentsByCharacterID(characterID)
                comments = response.commentList
            } catch (_: Exception) {
                commentsError = true
            } finally {
                commentsLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadCharacter()
        loadComments()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val name = characterName.trim()
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = name.ifEmpty { "人物" },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("资料") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("吐槽") })
            }
            when (tabIndex) {
                0 -> CharacterInfoBody(
                    character = character,
                    characterName = name,
                    characterRelation = characterRelation,
                    actorNames = actorNames,
                    onRetry = { loadCharacter() }
                )
                1 -> CharacterCommentsBody(
                    comments = comments,
                    loading = commentsLoading,
                    error = commentsError,
                    onRetry = { loadComments() }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CharacterInfoBody(
    character: CharacterFullItem?,
    characterName: String,
    characterRelation: String,
    actorNames: List<String>,
    onRetry: () -> Unit
) {
    when {
        character != null && character.id == 0 -> {
            GeneralErrorWidget(
                title = "人物资料加载失败",
                errMsg = "请检查网络连接后重试。",
                onRetry = onRetry,
                modifier = Modifier.padding(vertical = 48.dp)
            )
        }
        character == null -> {
            Column(modifier = Modifier.padding(16.dp)) {
                SkeletonBar(width = 0, height = 220)
                Spacer(Modifier.height(20.dp))
                SkeletonBar(width = 0, height = 14)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(width = 0, height = 14)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(width = 0, height = 14)
            }
        }
        else -> CharacterInfoContent(
            character = character,
            characterName = characterName,
            characterRelation = characterRelation,
            actorNames = actorNames
        )
    }
}

@Composable
private fun CharacterInfoContent(
    character: CharacterFullItem,
    characterName: String,
    characterRelation: String,
    actorNames: List<String>
) {
    val quickKeys = setOf("性别", "生日", "年龄", "身高", "体重", "血型")
    val fields = character.infobox.filter { it.key != "引用来源" }
    val quickFields = fields
        .filter { it.key in quickKeys }
        .take(4)
    val quickKeysUsed = quickFields.map { it.key }.toSet()
    val moreFields = fields.filter { it.key != "简体中文名" && it.key !in quickKeysUsed }

    val tappedName = characterName.trim()
    val originalName = character.name.ifEmpty { tappedName }
    val name = character.nameCn.ifEmpty { tappedName.ifEmpty { originalName } }
    val relation = characterRelation.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // 人物立绘
        if (character.image.isNotEmpty()) {
            AsyncImage(
                model = character.image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            )
            Spacer(Modifier.height(16.dp))
        }

        // 资料卡
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(20.dp)
        ) {
            if (relation.isNotEmpty() && relation != "未知") {
                Text(
                    text = relation,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                text = name.ifEmpty { "人物" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (originalName.isNotEmpty() && originalName != name) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (actorNames.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                InfoField(label = "配音", value = actorNames.joinToString(" / "))
            }
        }

        // 基本资料
        if (quickFields.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("基本资料", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                quickFields.forEach { field ->
                    InfoField(label = field.key, value = field.value)
                }
            }
        }

        // 人物简介
        if (character.summary.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("人物简介", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                text = character.summary,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.65f
            )
        }

        // 更多资料
        if (moreFields.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("更多资料", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                moreFields.forEach { field ->
                    InfoField(label = field.key, value = field.value)
                }
            }
        }

        // 人物资料（非结构化）
        if (character.unstructuredInfo.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("人物资料", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                text = character.unstructuredInfo,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.6f
            )
        }

        // 空态
        if (fields.isEmpty() &&
            character.unstructuredInfo.isEmpty() &&
            character.summary.isEmpty()
        ) {
            Spacer(Modifier.height(24.dp))
            GeneralEmptyState(
                icon = Icons.Filled.PersonOutline,
                title = "暂无人物资料",
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.5f
        )
    }
}

@Composable
private fun CharacterCommentsBody(
    comments: List<CharacterCommentItem>,
    loading: Boolean,
    error: Boolean,
    onRetry: () -> Unit
) {
    when {
        loading -> {
            Column(modifier = Modifier.padding(16.dp)) {
                CommentCardBone()
                CommentCardBone()
                CommentCardBone()
            }
        }
        error -> {
            GeneralErrorWidget(
                title = "人物吐槽加载失败",
                errMsg = "请检查网络连接后重试。",
                onRetry = onRetry,
                modifier = Modifier.padding(vertical = 48.dp)
            )
        }
        comments.isEmpty() -> {
            GeneralEmptyState(
                icon = Icons.Filled.PersonOutline,
                title = "还没有评论",
                modifier = Modifier.padding(vertical = 48.dp)
            )
        }
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                comments.forEach { item ->
                    UserCommentRow(
                        avatar = item.comment.user.avatar.large,
                        nickname = item.comment.user.nickname.ifEmpty { item.comment.user.username },
                        time = dateFormat(item.comment.createdAt),
                        content = item.comment.comment
                    )
                    if (item.replies.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 44.dp)
                        ) {
                            item.replies.take(4).forEach { reply ->
                                UserCommentRow(
                                    avatar = reply.user.avatar.large,
                                    nickname = reply.user.nickname.ifEmpty { reply.user.username },
                                    time = dateFormat(reply.createdAt),
                                    content = reply.comment
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun UserCommentRow(
    avatar: String,
    nickname: String,
    time: String,
    content: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        UserAvatar(imageUrl = avatar, size = 32)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nickname,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}