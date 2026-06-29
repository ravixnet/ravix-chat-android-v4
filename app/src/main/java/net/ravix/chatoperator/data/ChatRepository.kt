package net.ravix.chatoperator.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.Polling
import io.socket.engineio.client.transports.WebSocket
import net.ravix.chatoperator.model.ChatMessage
import net.ravix.chatoperator.model.Conversation
import net.ravix.chatoperator.notification.ChatNotificationManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

object ChatRepository {
    interface Observer {
        fun onConnectionChanged(connected: Boolean, message: String) {}
        fun onConversationsChanged(items: List<Conversation>) {}
        fun onMessagesChanged(conversationId: String, messages: List<ChatMessage>) {}
        fun onGuestTyping(conversationId: String, active: Boolean) {}
        fun onConversationClosed(conversationId: String) {}
        fun onAuthenticationRequired() {}
    }

    private data class MessageUpsertResult(
        val added: Boolean,
        val replacedSynthetic: Boolean,
    )

    private data class PendingNotification(
        val conversationId: String,
        val title: String,
        val text: String,
        val timestamp: Long,
        val unreadCount: Int,
    )

    private lateinit var appContext: Context
    private lateinit var store: SecureSessionStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<Observer>()
    private val conversations = linkedMapOf<String, Conversation>()
    private val messages = mutableMapOf<String, MutableList<ChatMessage>>()
    private val cacheExecutor = Executors.newSingleThreadExecutor()
    private val cacheGeneration = AtomicLong(0)
    private var historyStore: ChatHistoryStore? = null
    private var loadedAccount = ""
    private var socket: Socket? = null
    private var activeConversationId: String? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = SecureSessionStore(appContext)
        ensureHistoryLoaded()
    }

    fun addObserver(observer: Observer) {
        ensureHistoryLoaded()
        observers.add(observer)
        mainHandler.post {
            observer.onConnectionChanged(isConnected, if (isConnected) "متصل" else "قطع")
            observer.onConversationsChanged(currentConversations())
            activeConversationId?.let { id -> observer.onMessagesChanged(id, currentMessages(id)) }
        }
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    @Synchronized
    fun connect(force: Boolean = false) {
        ensureHistoryLoaded()
        if (!::appContext.isInitialized || !store.isLoggedIn) {
            notifyAuthRequired()
            return
        }
        if (!force && (socket?.connected() == true || socket != null)) return
        disconnect(clearData = false)

        val options = IO.Options().apply {
            path = store.socketPath
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1_000
            reconnectionDelayMax = 15_000
            timeout = 15_000
            transports = arrayOf(Polling.NAME, WebSocket.NAME)
            val authMap = mutableMapOf<String, String>("role" to "operator")
            if (store.authToken.isNotBlank()) {
                authMap["token"] = store.authToken
                authMap["operatorToken"] = store.authToken
                authMap["operator_token"] = store.authToken
                val encoded = URLEncoder.encode(store.authToken, "UTF-8")
                query = "role=operator&token=$encoded&operator_token=$encoded"
            } else {
                query = "role=operator"
            }
            auth = authMap

            val socketOrigin = runCatching {
                URI.create(store.socketUrl).let { uri -> "${uri.scheme}://${uri.authority}" }
            }.getOrDefault(store.baseUrl)

            val headers = linkedMapOf<String, List<String>>()
            headers["Origin"] = listOf(socketOrigin)
            if (store.authCookie.isNotBlank()) headers["Cookie"] = listOf(store.authCookie)
            extraHeaders = headers
        }

        val createdResult = runCatching { IO.socket(URI.create(store.socketUrl), options) }
        if (createdResult.isFailure) {
            updateConnection(false, "آدرس اتصال نامعتبر است")
            return
        }
        val created = createdResult.getOrThrow()
        socket = created
        bind(created)
        updateConnection(false, "در حال اتصال...")
        created.connect()
    }

    @Synchronized
    fun disconnect(clearData: Boolean = false) {
        socket?.off()
        socket?.disconnect()
        socket?.close()
        socket = null
        isConnected = false
        if (clearData) {
            synchronized(conversations) { conversations.clear() }
            synchronized(messages) { messages.clear() }
            activeConversationId = null
            loadedAccount = ""
            historyStore = null
            if (::appContext.isInitialized) ChatNotificationManager.clearMessageNotifications(appContext)
        }
        updateConnection(false, "قطع")
    }

    fun reconnect() = connect(force = true)

    fun currentConversations(): List<Conversation> = synchronized(conversations) {
        conversations.values.sortedWith(
            compareByDescending<Conversation> { it.unreadCount > 0 }
                .thenBy { it.closed }
                .thenByDescending { it.updatedAt },
        )
    }

    fun currentMessages(conversationId: String): List<ChatMessage> = synchronized(messages) {
        messages[conversationId]?.sortedBy { it.createdAt }?.toList().orEmpty()
    }

    fun openConversation(conversationId: String) {
        if (conversationId.isBlank()) return
        activeConversationId = conversationId
        ChatNotificationManager.dismissConversation(appContext, conversationId)
        synchronized(conversations) {
            conversations[conversationId]?.let {
                conversations[conversationId] = it.copy(unreadCount = 0)
            }
        }
        persistHistory()
        notifyConversations()
        notifyMessages(conversationId)

        val payload = JSONObject().put("conversationId", conversationId)
        socket?.emit("operator:open", payload, Ack { args ->
            val first = args.firstOrNull()
            if (first is JSONObject) {
                if (first.optBoolean("ok", true).not()) {
                    updateConnection(isConnected, first.optString("message", "باز کردن گفتگو انجام نشد"))
                } else if (first.has("messages") || first.optJSONObject("data")?.has("messages") == true) {
                    handleHistoryPayload(first.optJSONObject("data") ?: first, conversationId)
                }
            }
        })
    }

    fun leaveConversation(conversationId: String) {
        if (activeConversationId == conversationId) activeConversationId = null
        sendTyping(conversationId, false)
    }

    fun sendMessage(conversationId: String, text: String, callback: (Result<Unit>) -> Unit) {
        val clean = text.trim()
        if (clean.isBlank()) {
            callback(Result.failure(IllegalArgumentException("پیام خالی است.")))
            return
        }
        if (!isConnected) {
            callback(Result.failure(IllegalStateException("اتصال به سرور برقرار نیست.")))
            return
        }
        val payload = JSONObject().put("conversationId", conversationId).put("text", clean)
        socket?.emit("operator:message", payload, Ack { args ->
            val response = args.firstOrNull() as? JSONObject
            val ok = response?.optBoolean("ok", true) ?: true
            mainHandler.post {
                if (ok) callback(Result.success(Unit))
                else callback(Result.failure(IllegalStateException(response?.optString("message", "ارسال پیام ناموفق بود."))))
            }
        }) ?: callback(Result.failure(IllegalStateException("سوکت فعال نیست.")))
    }

    fun sendTyping(conversationId: String, active: Boolean) {
        if (!isConnected) return
        socket?.emit("operator:typing", JSONObject().put("conversationId", conversationId).put("active", active))
    }

    fun closeConversation(conversationId: String, callback: (Result<Unit>) -> Unit) {
        if (!isConnected) {
            callback(Result.failure(IllegalStateException("اتصال به سرور برقرار نیست.")))
            return
        }
        val activeSocket = socket
        if (activeSocket == null) {
            callback(Result.failure(IllegalStateException("سوکت فعال نیست.")))
            return
        }
        activeSocket.emit("operator:close", JSONObject().put("conversationId", conversationId), Ack { args ->
            val response = args.firstOrNull() as? JSONObject
            val ok = response?.optBoolean("ok", true) ?: true
            if (ok) {
                synchronized(conversations) {
                    conversations[conversationId]?.let {
                        conversations[conversationId] = it.copy(closed = true, guestOnline = false, unreadCount = 0)
                    }
                }
                ChatNotificationManager.dismissConversation(appContext, conversationId)
                persistHistory()
                notifyConversations()
            }
            mainHandler.post {
                if (ok) callback(Result.success(Unit))
                else callback(Result.failure(IllegalStateException(response?.optString("message", "بستن گفتگو ناموفق بود."))))
            }
        })
    }

    private fun bind(s: Socket) {
        s.on(Socket.EVENT_CONNECT) { updateConnection(true, "متصل") }
        s.on(Socket.EVENT_DISCONNECT) {
            updateConnection(false, "اتصال قطع شد؛ تلاش مجدد...")
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args.firstOrNull()?.toString().orEmpty()
            val authError = error.contains("auth", true) ||
                error.contains("token", true) ||
                error.contains("unauthorized", true)
            val message = when {
                authError -> "نشست ورود معتبر نیست"
                error.contains("origin", true) -> "Origin اتصال توسط سرور پذیرفته نشد"
                error.isNotBlank() -> "خطای اتصال: $error"
                else -> "خطای اتصال؛ تلاش مجدد..."
            }
            updateConnection(false, message)
            if (authError) notifyAuthRequired()
        }
        s.on("conversation:list") { args ->
            handleConversationList(args.firstOrNull())
        }
        s.on("conversation:update") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            handleConversationUpdate(json)
        }
        s.on("conversation:removed") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            val id = json.optString("conversationId", json.optString("conversation_id"))
            if (id.isBlank()) return@on
            synchronized(conversations) {
                conversations[id]?.let {
                    conversations[id] = it.copy(
                        guestOnline = false,
                        unreadCount = if (activeConversationId == id) 0 else it.unreadCount,
                        closed = true,
                    )
                }
            }
            persistHistory()
            notifyConversations()
        }
        s.on("conversation:history") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            handleHistoryPayload(json.optJSONObject("data") ?: json, activeConversationId.orEmpty())
        }
        s.on("message:new") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            handleNewMessage(json)
        }
        s.on("guest:typing") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            val id = json.optString("conversationId", json.optString("conversation_id"))
            val active = json.optBoolean("active", false)
            if (id.isNotBlank()) mainHandler.post { observers.forEach { it.onGuestTyping(id, active) } }
        }
        s.on("messages:seen") { args ->
            val json = args.firstOrNull() as? JSONObject
            val id = json?.optString("conversationId", activeConversationId.orEmpty()).orEmpty()
            if (id.isNotBlank()) {
                synchronized(messages) {
                    messages[id]?.let { list ->
                        for (index in list.indices) {
                            val item = list[index]
                            if (item.isOperator && !item.seen) list[index] = item.copy(seen = true)
                        }
                    }
                }
                persistHistory()
                notifyMessages(id)
            }
        }
        s.on("session:closed") { args ->
            val json = args.firstOrNull() as? JSONObject
            val id = json?.optString("conversationId", activeConversationId.orEmpty()).orEmpty()
            if (id.isNotBlank()) {
                synchronized(conversations) {
                    conversations[id]?.let {
                        conversations[id] = it.copy(
                            closed = true,
                            guestOnline = false,
                            unreadCount = if (activeConversationId == id) 0 else it.unreadCount,
                        )
                    }
                }
                if (activeConversationId == id) ChatNotificationManager.dismissConversation(appContext, id)
                persistHistory()
                mainHandler.post { observers.forEach { it.onConversationClosed(id) } }
                notifyConversations()
            }
        }
    }

    private fun handleConversationList(value: Any?) {
        val incoming = Conversation.listFrom(value)
        val previous = synchronized(conversations) { conversations.toMap() }
        val incomingIds = incoming.mapTo(hashSetOf()) { it.id }
        val next = linkedMapOf<String, Conversation>()
        val changedMessageIds = linkedSetOf<String>()
        val notifications = mutableListOf<PendingNotification>()

        incoming.forEach { rawItem ->
            val old = previous[rawItem.id]
            val item = normalizeConversationTime(rawItem, old, useCurrentTime = false)
            val fresh = old != null && isSummaryFresh(old, item)
            val guestMessage = fresh && !item.isLastMessageOperator && isRealPreview(item.lastMessage)
            val upsert = if (guestMessage) upsertMessage(summaryMessage(item)) else MessageUpsertResult(false, false)
            if (upsert.added) changedMessageIds += item.id
            val unread = when {
                activeConversationId == item.id -> 0
                upsert.added -> maxOf(item.unreadCount, (old?.unreadCount ?: 0) + 1)
                else -> maxOf(item.unreadCount, old?.unreadCount ?: 0)
            }
            val merged = (mergeConversation(old, item) ?: item).copy(unreadCount = unread)
            next[item.id] = merged
            if (upsert.added) {
                notifications += PendingNotification(
                    conversationId = item.id,
                    title = merged.guestName,
                    text = merged.lastMessage,
                    timestamp = merged.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    unreadCount = unread.coerceAtLeast(1),
                )
            }
        }

        previous.values
            .asSequence()
            .filter { it.id !in incomingIds }
            .sortedByDescending { it.updatedAt }
            .forEach {
                next[it.id] = it.copy(
                    guestOnline = false,
                    closed = true,
                    unreadCount = if (activeConversationId == it.id) 0 else it.unreadCount,
                )
            }

        synchronized(conversations) {
            conversations.clear()
            conversations.putAll(next)
        }
        persistHistory()
        changedMessageIds.forEach(::notifyMessages)
        notifyConversations()
        notifications.forEach(::postNotification)
    }

    private fun handleConversationUpdate(json: JSONObject) {
        val oldCandidate = Conversation.fromJson(json)
        if (oldCandidate.id.isBlank()) return
        val old = synchronized(conversations) { conversations[oldCandidate.id] }
        val item = normalizeConversationTime(oldCandidate, old, useCurrentTime = true)
        val fresh = isSummaryFresh(old, item)
        val guestMessage = fresh && !item.isLastMessageOperator && isRealPreview(item.lastMessage)
        val upsert = if (guestMessage) upsertMessage(summaryMessage(item)) else MessageUpsertResult(false, false)
        val unread = when {
            activeConversationId == item.id -> 0
            upsert.added -> maxOf(item.unreadCount, (old?.unreadCount ?: 0) + 1)
            else -> maxOf(item.unreadCount, old?.unreadCount ?: 0)
        }
        val merged = (mergeConversation(old, item) ?: item).copy(unreadCount = unread)
        synchronized(conversations) { conversations[item.id] = merged }
        persistHistory()
        if (upsert.added) notifyMessages(item.id)
        notifyConversations()
        if (upsert.added) {
            postNotification(
                PendingNotification(
                    conversationId = item.id,
                    title = merged.guestName,
                    text = merged.lastMessage,
                    timestamp = merged.updatedAt,
                    unreadCount = unread.coerceAtLeast(1),
                ),
            )
        }
    }

    private fun handleNewMessage(json: JSONObject) {
        val parsed = ChatMessage.fromJson(json)
        var conversationId = parsed.conversationId
        if (conversationId.isBlank()) conversationId = activeConversationId.orEmpty()
        if (conversationId.isBlank()) return
        val normalized = parsed.copy(conversationId = conversationId)
        val upsert = upsertMessage(normalized)
        val old = synchronized(conversations) { conversations[conversationId] }
        val nested = json.optJSONObject("conversation")?.let { Conversation.fromJson(it) }
            ?: json.optJSONObject("data")?.optJSONObject("conversation")?.let { Conversation.fromJson(it) }
        val base = mergeConversation(old, nested).let { merged ->
            if (merged != null) merged else Conversation(
                id = conversationId,
                guestName = "کاربر سایت",
                lastMessage = normalized.text.ifBlank { "پیام جدید" },
                updatedAt = normalized.createdAt,
                unreadCount = 0,
                guestOnline = true,
                closed = false,
                siteLabel = "",
                lastSender = normalized.sender,
                lastMessageId = normalized.id,
            )
        }
        val unread = when {
            activeConversationId == conversationId -> 0
            !normalized.isOperator && upsert.added -> (old?.unreadCount ?: base.unreadCount) + 1
            else -> maxOf(old?.unreadCount ?: 0, base.unreadCount)
        }
        val updated = base.copy(
            lastMessage = normalized.text.ifBlank { base.lastMessage },
            updatedAt = maxOf(base.updatedAt, normalized.createdAt),
            unreadCount = unread,
            guestOnline = if (normalized.isOperator) base.guestOnline else true,
            closed = false,
            lastSender = normalized.sender,
            lastMessageId = normalized.id,
        )
        synchronized(conversations) { conversations[conversationId] = updated }
        persistHistory()
        notifyMessages(conversationId)
        notifyConversations()

        // If the summary event already produced a synthetic message, replacing it here must not alert twice.
        if (!normalized.isOperator && upsert.added) {
            postNotification(
                PendingNotification(
                    conversationId = conversationId,
                    title = updated.guestName,
                    text = normalized.text,
                    timestamp = normalized.createdAt,
                    unreadCount = unread.coerceAtLeast(1),
                ),
            )
        }
    }

    private fun handleHistoryPayload(json: JSONObject, fallbackId: String) {
        val conversationJson = json.optJSONObject("conversation")
        val conversation = conversationJson?.let { Conversation.fromJson(it) }
        val id = conversation?.id?.ifBlank { fallbackId }
            ?: json.optString("conversationId", json.optString("conversation_id", fallbackId))
        if (id.isBlank()) return

        val old = synchronized(conversations) { conversations[id] }
        conversation?.takeIf { it.id.isNotBlank() }?.let {
            synchronized(conversations) {
                conversations[id] = (mergeConversation(old, normalizeConversationTime(it, old, false)) ?: it)
                    .copy(unreadCount = 0)
            }
        } ?: synchronized(conversations) {
            conversations[id]?.let { conversations[id] = it.copy(unreadCount = 0) }
        }

        val history = ChatMessage.listFrom(json.optJSONArray("messages") ?: JSONArray())
            .map { if (it.conversationId.isBlank()) it.copy(conversationId = id) else it }
        synchronized(messages) {
            val list = messages.getOrPut(id) { mutableListOf() }
            history.sortedBy { it.createdAt }.forEach { upsertInto(list, it) }
            list.sortBy { it.createdAt }
        }
        ChatNotificationManager.dismissConversation(appContext, id)
        persistHistory()
        notifyMessages(id)
        notifyConversations()
    }

    private fun normalizeConversationTime(
        item: Conversation,
        old: Conversation?,
        useCurrentTime: Boolean,
    ): Conversation {
        val time = when {
            item.updatedAt > 0 -> item.updatedAt
            old != null && old.updatedAt > 0 -> old.updatedAt
            useCurrentTime -> System.currentTimeMillis()
            else -> 0L
        }
        return item.copy(updatedAt = time)
    }

    private fun mergeConversation(old: Conversation?, incoming: Conversation?): Conversation? {
        if (incoming == null) return old
        if (old == null) return incoming
        val incomingHasPreview = isRealPreview(incoming.lastMessage)
        val changedPreview = incomingHasPreview &&
            (incoming.lastMessage != old.lastMessage ||
                incoming.updatedAt > old.updatedAt ||
                (incoming.lastMessageId.isNotBlank() && incoming.lastMessageId != old.lastMessageId))
        return incoming.copy(
            guestName = incoming.guestName.takeUnless { it == "کاربر سایت" && old.guestName.isNotBlank() } ?: old.guestName,
            lastMessage = if (incomingHasPreview) incoming.lastMessage else old.lastMessage,
            updatedAt = maxOf(incoming.updatedAt, old.updatedAt),
            unreadCount = maxOf(incoming.unreadCount, old.unreadCount),
            siteLabel = incoming.siteLabel.ifBlank { old.siteLabel },
            lastSender = incoming.lastSender.ifBlank { if (changedPreview) "" else old.lastSender },
            lastMessageId = incoming.lastMessageId.ifBlank { if (changedPreview) "" else old.lastMessageId },
            closed = if (incoming.guestOnline || changedPreview) incoming.closed else incoming.closed || old.closed,
        )
    }

    private fun isSummaryFresh(old: Conversation?, incoming: Conversation): Boolean {
        if (old == null) return true
        if (incoming.lastMessageId.isNotBlank() && incoming.lastMessageId != old.lastMessageId) return true
        if (incoming.updatedAt > old.updatedAt) return true
        return isRealPreview(incoming.lastMessage) && incoming.lastMessage != old.lastMessage
    }

    private fun isRealPreview(text: String): Boolean =
        text.isNotBlank() && !text.equals("گفتگوی جدید", true)

    private fun summaryMessage(item: Conversation): ChatMessage {
        val timestamp = item.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val stablePart = item.lastMessageId.ifBlank {
            "${timestamp}-${item.lastMessage.trim().hashCode()}"
        }
        return ChatMessage(
            id = "summary-$stablePart",
            conversationId = item.id,
            text = item.lastMessage,
            sender = item.lastSender.ifBlank { "guest" },
            createdAt = timestamp,
            seen = false,
        )
    }

    private fun upsertMessage(incoming: ChatMessage): MessageUpsertResult = synchronized(messages) {
        val list = messages.getOrPut(incoming.conversationId) { mutableListOf() }
        upsertInto(list, incoming)
    }

    private fun upsertInto(list: MutableList<ChatMessage>, incoming: ChatMessage): MessageUpsertResult {
        val exactIndex = list.indexOfFirst { it.id == incoming.id }
        if (exactIndex >= 0) {
            list[exactIndex] = incoming
            return MessageUpsertResult(added = false, replacedSynthetic = false)
        }
        val semanticIndex = list.indexOfFirst { messagesEquivalent(it, incoming) }
        if (semanticIndex >= 0) {
            val existing = list[semanticIndex]
            return when {
                existing.isSynthetic && !incoming.isSynthetic -> {
                    list[semanticIndex] = incoming
                    MessageUpsertResult(added = false, replacedSynthetic = true)
                }
                !existing.isSynthetic && incoming.isSynthetic -> {
                    MessageUpsertResult(added = false, replacedSynthetic = false)
                }
                else -> {
                    list[semanticIndex] = incoming
                    MessageUpsertResult(added = false, replacedSynthetic = false)
                }
            }
        }
        list.add(incoming)
        list.sortBy { it.createdAt }
        return MessageUpsertResult(added = true, replacedSynthetic = false)
    }

    private fun messagesEquivalent(first: ChatMessage, second: ChatMessage): Boolean {
        if (first.conversationId.isNotBlank() && second.conversationId.isNotBlank() &&
            first.conversationId != second.conversationId
        ) return false
        if (first.isOperator != second.isOperator) return false
        if (first.text.trim() != second.text.trim()) return false
        return abs(first.createdAt - second.createdAt) <= 2_500L
    }

    private fun postNotification(item: PendingNotification) {
        ChatNotificationManager.showMessage(
            context = appContext,
            conversationId = item.conversationId,
            title = item.title,
            text = item.text,
            timestamp = item.timestamp,
            unreadCount = item.unreadCount,
        )
    }

    @Synchronized
    private fun ensureHistoryLoaded() {
        if (!::appContext.isInitialized || !::store.isInitialized) return
        val account = store.username.trim().lowercase()
        if (account.isBlank() || account == loadedAccount) return
        loadedAccount = account
        historyStore = ChatHistoryStore(appContext, account)
        val snapshot = historyStore?.load() ?: return
        synchronized(conversations) {
            conversations.clear()
            snapshot.conversations.forEach { conversations[it.id] = it.copy(guestOnline = false) }
        }
        synchronized(messages) {
            messages.clear()
            snapshot.messages.forEach { (id, list) -> messages[id] = list.toMutableList() }
        }
    }

    private fun persistHistory() {
        val target = historyStore ?: return
        val conversationSnapshot = currentConversations()
        val messageSnapshot = synchronized(messages) {
            messages.mapValues { (_, list) -> list.toList() }
        }
        val generation = cacheGeneration.incrementAndGet()
        cacheExecutor.execute {
            if (generation != cacheGeneration.get()) return@execute
            runCatching { target.save(conversationSnapshot, messageSnapshot) }
        }
    }

    private fun updateConnection(connected: Boolean, message: String) {
        isConnected = connected
        mainHandler.post { observers.forEach { it.onConnectionChanged(connected, message) } }
    }

    private fun notifyConversations() {
        val snapshot = currentConversations()
        mainHandler.post { observers.forEach { it.onConversationsChanged(snapshot) } }
    }

    private fun notifyMessages(conversationId: String) {
        val snapshot = currentMessages(conversationId)
        mainHandler.post { observers.forEach { it.onMessagesChanged(conversationId, snapshot) } }
    }

    private fun notifyAuthRequired() {
        mainHandler.post { observers.forEach { it.onAuthenticationRequired() } }
    }
}
