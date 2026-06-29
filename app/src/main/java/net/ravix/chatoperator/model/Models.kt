package net.ravix.chatoperator.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private fun JSONObject.firstString(vararg names: String): String {
    for (name in names) {
        val value = opt(name)
        if (value == null || value == JSONObject.NULL || value is JSONObject || value is JSONArray) continue
        val text = value.toString().trim()
        if (text.isNotEmpty()) return text
    }
    return ""
}

private fun JSONObject.firstLong(vararg names: String): Long {
    for (name in names) {
        val value = opt(name) ?: continue
        when (value) {
            is Number -> return value.toLong().normalizeEpoch()
            is String -> {
                val numeric = value.toLongOrNull()
                if (numeric != null) return numeric.normalizeEpoch()
                val parsed = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                if (parsed != null) return parsed
            }
        }
    }
    return 0L
}

private fun JSONObject.firstBoolean(default: Boolean, vararg names: String): Boolean {
    for (name in names) {
        if (!has(name) || isNull(name)) continue
        return when (val value = opt(name)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", true) || value == "1" || value.equals("yes", true)
            else -> default
        }
    }
    return default
}

private fun Long.normalizeEpoch(): Long = if (this in 1..9_999_999_999L) this * 1000L else this

data class LoginResult(
    val token: String,
    val cookie: String,
    val socketUrl: String,
    val socketPath: String,
)

data class Conversation(
    val id: String,
    val guestName: String,
    val lastMessage: String,
    val updatedAt: Long,
    val unreadCount: Int,
    val guestOnline: Boolean,
    val closed: Boolean,
    val siteLabel: String,
    val lastSender: String = "",
    val lastMessageId: String = "",
) {
    val isLastMessageOperator: Boolean
        get() = lastSender.equals("operator", true) ||
            lastSender.equals("agent", true) ||
            lastSender.equals("admin", true) ||
            lastSender.equals("support", true)

    companion object {
        fun fromJson(json: JSONObject): Conversation {
            val source = json.optJSONObject("conversation")
                ?.takeIf { json.firstString("id", "conversationId", "conversation_id").isBlank() }
                ?: json.optJSONObject("data")?.optJSONObject("conversation")
                ?: json

            val lastRaw = source.opt("lastMessage") ?: source.opt("last_message")
            val lastObject = lastRaw as? JSONObject
            val lastText = when (lastRaw) {
                is JSONObject -> lastRaw.firstString("text", "message", "content", "body")
                null, JSONObject.NULL -> source.firstString("preview", "lastText", "last_text")
                else -> lastRaw.toString().trim()
            }
            val rootUpdatedAt = source.firstLong(
                "updatedAt",
                "updated_at",
                "lastMessageAt",
                "last_message_at",
                "createdAt",
                "created_at",
            )
            val nestedUpdatedAt = lastObject?.firstLong("createdAt", "created_at", "timestamp", "time") ?: 0L
            val online = source.firstBoolean(
                false,
                "guestOnline",
                "guest_online",
                "online",
                "isOnline",
                "is_online",
            )
            val unread = source.optInt(
                "unreadCount",
                source.optInt("unread_count", source.optInt("unread", 0)),
            )
            val status = source.firstString("status", "state")
            val closed = source.firstBoolean(false, "closed", "isClosed", "is_closed") ||
                status.equals("closed", true) ||
                status.equals("ended", true) ||
                status.equals("archived", true)

            return Conversation(
                id = source.firstString("id", "conversationId", "conversation_id"),
                guestName = source.firstString(
                    "guestName",
                    "guest_name",
                    "guestLabel",
                    "guest_label",
                    "visitorName",
                    "visitor_name",
                    "name",
                    "label",
                ).ifBlank { "کاربر سایت" },
                lastMessage = lastText.ifBlank { "گفتگوی جدید" },
                updatedAt = maxOf(rootUpdatedAt, nestedUpdatedAt),
                unreadCount = unread.coerceAtLeast(0),
                guestOnline = online,
                closed = closed,
                siteLabel = source.firstString("siteLabel", "site_label", "siteName", "site_name", "siteId", "site_id"),
                lastSender = lastObject?.firstString("sender", "role", "from", "author")
                    .orEmpty()
                    .ifBlank { source.firstString("lastSender", "last_sender", "lastRole", "last_role") },
                lastMessageId = lastObject?.firstString("id", "messageId", "message_id")
                    .orEmpty()
                    .ifBlank { source.firstString("lastMessageId", "last_message_id") },
            )
        }

        fun listFrom(value: Any?): List<Conversation> {
            val array = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("conversations")
                    ?: value.optJSONArray("items")
                    ?: value.optJSONObject("data")?.optJSONArray("conversations")
                    ?: value.optJSONObject("data")?.optJSONArray("items")
                    ?: JSONArray()
                else -> JSONArray()
            }
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val parsed = fromJson(item)
                    if (parsed.id.isNotBlank()) add(parsed)
                }
            }
        }
    }
}

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val text: String,
    val sender: String,
    val createdAt: Long,
    val seen: Boolean,
) {
    val isOperator: Boolean
        get() = sender.equals("operator", true) ||
            sender.equals("agent", true) ||
            sender.equals("admin", true) ||
            sender.equals("support", true)

    val isSynthetic: Boolean get() = id.startsWith("summary-")

    companion object {
        fun fromJson(json: JSONObject): ChatMessage {
            val source = json.optJSONObject("message") ?: json.optJSONObject("data")?.optJSONObject("message") ?: json
            val conversationObject = source.optJSONObject("conversation") ?: json.optJSONObject("conversation")
            val conversationId = source.firstString("conversationId", "conversation_id")
                .ifBlank { conversationObject?.firstString("id", "conversationId", "conversation_id").orEmpty() }
            val text = source.firstString("text", "message", "content", "body")
            val senderObject = source.optJSONObject("sender")
            val sender = source.firstString("sender", "role", "from", "author")
                .ifBlank { senderObject?.firstString("role", "type", "name").orEmpty() }
                .ifBlank { "guest" }
            val createdAt = source.firstLong("createdAt", "created_at", "timestamp", "time")
                .let { if (it == 0L) System.currentTimeMillis() else it }
            val explicitId = source.firstString("id", "messageId", "message_id")
            val stableId = explicitId.ifBlank {
                "derived-${(conversationId + '|' + sender + '|' + createdAt + '|' + text).hashCode()}"
            }
            return ChatMessage(
                id = stableId,
                conversationId = conversationId,
                text = text,
                sender = sender,
                createdAt = createdAt,
                seen = source.firstBoolean(false, "seen", "read", "isSeen", "is_seen"),
            )
        }

        fun listFrom(value: Any?): List<ChatMessage> {
            val array = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("messages")
                    ?: value.optJSONObject("data")?.optJSONArray("messages")
                    ?: JSONArray()
                else -> JSONArray()
            }
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(fromJson(item))
                }
            }
        }
    }
}
