package com.kazumi.port.data.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 用户头像
 * 对应 Dart UserAvatar
 */
data class UserAvatar(
    val small: String = "",
    val medium: String = "",
    val large: String = ""
) {
    companion object {
        fun fromJson(json: JsonObject): UserAvatar = UserAvatar(
            small = json.get("small")?.takeIf { !it.isJsonNull }?.asString ?: "",
            medium = json.get("medium")?.takeIf { !it.isJsonNull }?.asString ?: "",
            large = json.get("large")?.takeIf { !it.isJsonNull }?.asString ?: ""
        )
    }
}

/**
 * 用户
 * 对应 Dart User
 */
data class User(
    val id: Int = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: UserAvatar = UserAvatar(),
    val sign: String = "",
    val joinedAt: Int = 0
) {
    companion object {
        fun fromJson(json: JsonObject): User = User(
            id = json.get("id")?.asInt ?: 0,
            username = json.get("username")?.takeIf { !it.isJsonNull }?.asString ?: "",
            nickname = json.get("nickname")?.takeIf { !it.isJsonNull }?.asString ?: "",
            avatar = json.getAsJsonObject("avatar")?.let { UserAvatar.fromJson(it) } ?: UserAvatar(),
            sign = json.get("sign")?.takeIf { !it.isJsonNull }?.asString ?: "",
            joinedAt = json.get("joinedAt")?.asInt ?: 0
        )
    }
}

/**
 * 评论内容
 * 对应 Dart Comment
 */
data class Comment(
    val rate: Int = 0,
    val comment: String = "",
    val updatedAt: Int = 0
) {
    companion object {
        fun fromJson(json: JsonObject): Comment = Comment(
            rate = json.get("rate")?.asInt ?: 0,
            comment = json.get("comment")?.takeIf { !it.isJsonNull }?.asString ?: "",
            updatedAt = json.get("updatedAt")?.asInt ?: 0
        )
    }
}

/**
 * 评论条目（用户 + 评论）
 * 对应 Dart CommentItem
 */
data class CommentItem(
    val user: User = User(),
    val comment: Comment = Comment()
) {
    companion object {
        fun fromJson(json: JsonObject): CommentItem = CommentItem(
            user = json.getAsJsonObject("user")?.let { User.fromJson(it) } ?: User(),
            comment = Comment.fromJson(json)
        )
    }
}

/**
 * 评论响应
 * 对应 Dart CommentResponse
 */
data class CommentResponse(
    val commentList: List<CommentItem> = emptyList(),
    val total: Int = 0
) {
    companion object {
        fun fromJson(json: JsonObject): CommentResponse {
            val raw = json.get("list") ?: json.get("data")
            val list = mutableListOf<CommentItem>()
            if (raw != null && raw.isJsonArray) {
                for (e in raw.asJsonArray) {
                    if (e.isJsonObject) {
                        try {
                            list.add(CommentItem.fromJson(e.asJsonObject))
                        } catch (_: Exception) {}
                    }
                }
            }
            return CommentResponse(
                commentList = list,
                total = json.get("total")?.asInt ?: 0
            )
        }
    }
}

/**
 * 剧集评论
 * 对应 Dart EpisodeComment
 */
data class EpisodeComment(
    val user: User = User(),
    val comment: String = "",
    val createdAt: Int = 0
) {
    companion object {
        fun fromJson(json: JsonObject): EpisodeComment = EpisodeComment(
            user = json.getAsJsonObject("user")?.let { User.fromJson(it) } ?: User(),
            comment = json.get("content")?.takeIf { !it.isJsonNull }?.asString ?: "",
            createdAt = json.get("createdAt")?.asInt ?: 0
        )
    }
}

/**
 * 剧集评论条目（评论 + 回复）
 * 对应 Dart EpisodeCommentItem
 */
data class EpisodeCommentItem(
    val comment: EpisodeComment = EpisodeComment(),
    val replies: List<EpisodeComment> = emptyList()
) {
    companion object {
        fun fromJson(json: JsonObject): EpisodeCommentItem {
            val replies = mutableListOf<EpisodeComment>()
            val raw = json.getAsJsonArray("replies")
            if (raw != null) {
                for (e in raw) {
                    if (e.isJsonObject) {
                        try {
                            replies.add(EpisodeComment.fromJson(e.asJsonObject))
                        } catch (_: Exception) {}
                    }
                }
            }
            return EpisodeCommentItem(
                comment = EpisodeComment.fromJson(json),
                replies = replies
            )
        }
    }
}

/**
 * 角色评论
 * 对应 Dart CharacterComment
 */
data class CharacterComment(
    val user: User = User(),
    val comment: String = "",
    val createdAt: Int = 0
) {
    companion object {
        fun fromJson(json: JsonObject): CharacterComment = CharacterComment(
            user = json.getAsJsonObject("user")?.let { User.fromJson(it) } ?: User(),
            comment = json.get("content")?.takeIf { !it.isJsonNull }?.asString ?: "",
            createdAt = json.get("createdAt")?.asInt ?: 0
        )
    }
}

/**
 * 角色评论条目（评论 + 回复）
 * 对应 Dart CharacterCommentItem
 */
data class CharacterCommentItem(
    val comment: CharacterComment = CharacterComment(),
    val replies: List<CharacterComment> = emptyList()
) {
    companion object {
        fun fromJson(json: JsonObject): CharacterCommentItem {
            val replies = mutableListOf<CharacterComment>()
            val raw = json.getAsJsonArray("replies")
            if (raw != null) {
                for (e in raw) {
                    if (e.isJsonObject) {
                        try {
                            replies.add(CharacterComment.fromJson(e.asJsonObject))
                        } catch (_: Exception) {}
                    }
                }
            }
            return CharacterCommentItem(
                comment = CharacterComment.fromJson(json),
                replies = replies
            )
        }
    }
}

/**
 * 角色评论响应
 * 对应 Dart CharacterCommentResponse
 */
data class CharacterCommentResponse(
    val commentList: List<CharacterCommentItem> = emptyList()
) {
    companion object {
        fun fromJson(json: JsonElement?): CharacterCommentResponse {
            val list = mutableListOf<CharacterCommentItem>()
            if (json != null && json.isJsonArray) {
                for (e in json.asJsonArray) {
                    if (e.isJsonObject) {
                        try {
                            list.add(CharacterCommentItem.fromJson(e.asJsonObject))
                        } catch (_: Exception) {}
                    }
                }
            }
            return CharacterCommentResponse(commentList = list)
        }
    }
}