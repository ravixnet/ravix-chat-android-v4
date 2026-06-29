package net.ravix.chatoperator.data

import android.content.Context
import net.ravix.chatoperator.model.ChatMessage
import net.ravix.chatoperator.model.Conversation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Small app-private cache used to keep recent conversations visible between app launches.
 * The server remains the source of truth and fresh server history replaces cached history.
 */
class ChatHistoryStore(
    context: Context,
    accountName: String,
) {
    data class Snapshot(
        val conversations: List<Conversation>,
        val messages: Map<String, List<ChatMessage>>,
    )

    private val cacheFile = File(
        context.filesDir,
        "chat_history_${accountKey(accountName)}.json",
    )

    fun load(): Snapshot {
        if (!cacheFile.exists()) return Snapshot(emptyList(), emptyMap())
        return runCatching {
            val root = JSONObject(cacheFile.readText(Charsets.UTF_8))
            val conversations = Conversation.listFrom(root.optJSONArray("conversations") ?: JSONArray())
            val messageMap = linkedMapOf<String, List<ChatMessage>>()
            val messagesObject = root.optJSONObject("messages") ?: JSONObject()
            val keys = messagesObject.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val list = ChatMessage.listFrom(messagesObject.optJSONArray(id) ?: JSONArray())
                if (id.isNotBlank() && list.isNotEmpty()) messageMap[id] = list
            }
            Snapshot(conversations, messageMap)
        }.getOrDefault(Snapshot(emptyList(), emptyMap()))
    }

    fun save(
        conversations: List<Conversation>,
        messages: Map<String, List<ChatMessage>>,
    ) {
        val limitedConversations = conversations
            .sortedByDescending { it.updatedAt }
            .take(MAX_CONVERSATIONS)

        val root = JSONObject()
        val conversationArray = JSONArray()
        limitedConversations.forEach { item ->
            conversationArray.put(
                JSONObject()
                    .put("id", item.id)
                    .put("guestName", item.guestName)
                    .put("lastMessage", item.lastMessage)
                    .put("updatedAt", item.updatedAt)
                    .put("unreadCount", item.unreadCount)
                    .put("guestOnline", item.guestOnline)
                    .put("closed", item.closed)
                    .put("siteLabel", item.siteLabel)
                    .put("lastSender", item.lastSender)
                    .put("lastMessageId", item.lastMessageId),
            )
        }
        root.put("conversations", conversationArray)

        val messagesObject = JSONObject()
        limitedConversations.forEach { conversation ->
            val list = messages[conversation.id]
                .orEmpty()
                .sortedBy { it.createdAt }
                .takeLast(MAX_MESSAGES_PER_CONVERSATION)
            if (list.isEmpty()) return@forEach
            val array = JSONArray()
            list.forEach { message ->
                array.put(
                    JSONObject()
                        .put("id", message.id)
                        .put("conversationId", message.conversationId)
                        .put("text", message.text)
                        .put("sender", message.sender)
                        .put("createdAt", message.createdAt)
                        .put("seen", message.seen),
                )
            }
            messagesObject.put(conversation.id, array)
        }
        root.put("messages", messagesObject)

        val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        temp.writeText(root.toString(), Charsets.UTF_8)
        if (!temp.renameTo(cacheFile)) {
            cacheFile.writeText(root.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        private const val MAX_CONVERSATIONS = 300
        private const val MAX_MESSAGES_PER_CONVERSATION = 1000

        private fun accountKey(accountName: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(accountName.trim().lowercase().toByteArray(Charsets.UTF_8))
            return bytes.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
