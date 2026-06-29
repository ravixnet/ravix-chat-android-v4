package net.ravix.chatoperator.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.ravix.chatoperator.R
import net.ravix.chatoperator.data.ChatRepository
import net.ravix.chatoperator.databinding.ActivityChatBinding
import net.ravix.chatoperator.model.ChatMessage
import net.ravix.chatoperator.model.Conversation
import net.ravix.chatoperator.notification.ChatNotificationManager

class ChatActivity : ComponentActivity(), ChatRepository.Observer {
    private lateinit var binding: ActivityChatBinding
    private val adapter = MessageAdapter()
    private lateinit var layoutManager: LinearLayoutManager
    private val typingHandler = Handler(Looper.getMainLooper())
    private var typingSent = false
    private val stopTyping = Runnable { sendTyping(false) }
    private lateinit var conversationId: String
    private var conversationTitle: String = "گفتگو"
    private var initialHistoryDisplayed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        if (initialId.isBlank()) {
            finish()
            return
        }
        conversationId = initialId
        conversationTitle = intent.getStringExtra(EXTRA_CONVERSATION_TITLE).orEmpty().ifBlank { "گفتگو" }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }

        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        configureHeader()

        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.messageList.layoutManager = layoutManager
        binding.messageList.adapter = adapter
        binding.messageList.itemAnimator = null
        binding.messageList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isNearBottom()) binding.jumpToLatestButton.visibility = View.GONE
            }
        })

        binding.jumpToLatestButton.setOnClickListener { scrollToLatest(smooth = true) }
        binding.sendButton.setOnClickListener { sendMessage() }
        binding.closeConversationButton.setOnClickListener { confirmClose() }
        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrBlank()) {
                    sendTyping(true)
                    typingHandler.removeCallbacks(stopTyping)
                    typingHandler.postDelayed(stopTyping, 900)
                } else {
                    sendTyping(false)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        ChatNotificationManager.dismissConversation(this, conversationId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        if (newId.isBlank()) return
        val newTitle = intent.getStringExtra(EXTRA_CONVERSATION_TITLE).orEmpty()
        if (newId == conversationId) {
            if (newTitle.isNotBlank()) {
                conversationTitle = newTitle
                configureHeader()
            }
            ChatNotificationManager.dismissConversation(this, conversationId)
            ChatRepository.openConversation(conversationId)
            binding.messageList.post { scrollToLatest(smooth = false) }
            return
        }
        typingHandler.removeCallbacks(stopTyping)
        sendTyping(false)
        ChatRepository.leaveConversation(conversationId)
        conversationId = newId
        conversationTitle = newTitle.ifBlank { "گفتگو" }
        initialHistoryDisplayed = false
        adapter.submitList(emptyList())
        binding.messageEmptyState.visibility = View.VISIBLE
        binding.jumpToLatestButton.visibility = View.GONE
        binding.messageInput.setText("")
        configureHeader()
        ChatNotificationManager.dismissConversation(this, conversationId)
        ChatRepository.openConversation(conversationId)
    }

    override fun onStart() {
        super.onStart()
        ChatNotificationManager.dismissConversation(this, conversationId)
        ChatRepository.addObserver(this)
        ChatRepository.openConversation(conversationId)
    }

    override fun onStop() {
        typingHandler.removeCallbacks(stopTyping)
        sendTyping(false)
        ChatRepository.leaveConversation(conversationId)
        ChatRepository.removeObserver(this)
        super.onStop()
    }

    override fun onConnectionChanged(connected: Boolean, message: String) {
        binding.chatStatus.text = if (connected) "●  متصل و آماده پاسخ‌گویی" else "●  $message"
        binding.chatStatus.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.ravix_success else R.color.ravix_warning),
        )
        val closed = ChatRepository.currentConversations().firstOrNull { it.id == conversationId }?.closed == true
        binding.sendButton.isEnabled = connected && !closed
        binding.messageInput.isEnabled = !closed
    }

    override fun onConversationsChanged(items: List<Conversation>) {
        val item = items.firstOrNull { it.id == conversationId } ?: return
        binding.toolbar.subtitle = when {
            item.closed -> "گفتگوی بسته‌شده • تاریخچه پیام‌ها"
            item.guestOnline -> "کاربر آنلاین است"
            else -> "کاربر آفلاین است"
        }
        binding.closeConversationButton.isEnabled = !item.closed && ChatRepository.isConnected
        binding.closeConversationButton.alpha = if (item.closed) 0.45f else 1f
        if (item.closed) {
            binding.chatStatus.text = "این گفتگو بسته شده است"
            binding.chatStatus.setTextColor(ContextCompat.getColor(this, R.color.ravix_muted))
            binding.messageInput.isEnabled = false
            binding.sendButton.isEnabled = false
        }
    }

    override fun onMessagesChanged(conversationId: String, messages: List<ChatMessage>) {
        if (conversationId != this.conversationId) return
        val oldCount = adapter.itemCount
        val nearBottomBeforeUpdate = isNearBottom() || oldCount == 0
        adapter.submitList(messages, Runnable {
            binding.messageEmptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
            if (messages.isNotEmpty()) {
                when {
                    !initialHistoryDisplayed -> {
                        initialHistoryDisplayed = true
                        scrollToLatest(smooth = false)
                    }
                    messages.size > oldCount && nearBottomBeforeUpdate -> scrollToLatest(smooth = true)
                    messages.size > oldCount -> binding.jumpToLatestButton.visibility = View.VISIBLE
                }
            }
        })
    }

    override fun onGuestTyping(conversationId: String, active: Boolean) {
        if (conversationId == this.conversationId) {
            binding.typingStatus.text = if (active) "کاربر در حال نوشتن است…" else ""
        }
    }

    override fun onConversationClosed(conversationId: String) {
        if (conversationId != this.conversationId) return
        binding.chatStatus.text = "این گفتگو بسته شده است"
        binding.chatStatus.setTextColor(ContextCompat.getColor(this, R.color.ravix_muted))
        binding.messageInput.isEnabled = false
        binding.sendButton.isEnabled = false
        binding.closeConversationButton.isEnabled = false
    }

    override fun onAuthenticationRequired() {
        Toast.makeText(this, "نشست ورود منقضی شده است.", Toast.LENGTH_LONG).show()
    }

    private fun sendMessage() {
        val text = binding.messageInput.text?.toString().orEmpty().trim()
        if (text.isBlank() || !binding.sendButton.isEnabled) return
        binding.sendButton.isEnabled = false
        ChatRepository.sendMessage(conversationId, text) { result ->
            binding.sendButton.isEnabled = ChatRepository.isConnected && binding.messageInput.isEnabled
            result.onSuccess {
                binding.messageInput.setText("")
                sendTyping(false)
                scrollToLatest(smooth = true)
            }.onFailure {
                Toast.makeText(this, it.message ?: "ارسال ناموفق بود", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendTyping(active: Boolean) {
        if (typingSent == active) return
        typingSent = active
        ChatRepository.sendTyping(conversationId, active)
    }

    private fun confirmClose() {
        AlertDialog.Builder(this)
            .setTitle("بستن گفتگو")
            .setMessage("گفتگو برای کاربر بسته شود؟ تاریخچه پیام‌ها در بخش گفتگوهای قبلی باقی می‌ماند.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("بستن گفتگو") { _, _ ->
                binding.closeConversationButton.isEnabled = false
                ChatRepository.closeConversation(conversationId) { result ->
                    result.onSuccess {
                        Toast.makeText(this, "گفتگو بسته شد", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        binding.closeConversationButton.isEnabled = true
                        Toast.makeText(this, it.message ?: "عملیات ناموفق بود", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun configureHeader() {
        binding.toolbar.title = conversationTitle
        binding.toolbar.subtitle = "در حال دریافت پیام‌های قبلی…"
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun isNearBottom(): Boolean {
        if (!::layoutManager.isInitialized || adapter.itemCount == 0) return true
        return layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 3
    }

    private fun scrollToLatest(smooth: Boolean) {
        if (adapter.itemCount == 0) return
        if (smooth) binding.messageList.smoothScrollToPosition(adapter.itemCount - 1)
        else binding.messageList.scrollToPosition(adapter.itemCount - 1)
        binding.jumpToLatestButton.visibility = View.GONE
    }

    private fun applyInsets() {
        val toolbarTop = binding.toolbar.paddingTop
        val composerBottom = binding.composerContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.toolbar.updatePadding(top = toolbarTop + bars.top)
            binding.composerContainer.updatePadding(bottom = composerBottom + maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_CONVERSATION_TITLE = "conversation_title"
    }
}
