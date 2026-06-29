package net.ravix.chatoperator.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import net.ravix.chatoperator.R
import net.ravix.chatoperator.data.ChatRepository
import net.ravix.chatoperator.data.SecureSessionStore
import net.ravix.chatoperator.databinding.ActivityMainBinding
import net.ravix.chatoperator.model.Conversation
import net.ravix.chatoperator.notification.ChatNotificationManager
import net.ravix.chatoperator.service.ChatSocketService

class MainActivity : ComponentActivity(), ChatRepository.Observer {
    private enum class ConversationFilter { ALL, UNREAD, HISTORY }

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SecureSessionStore
    private lateinit var adapter: ConversationAdapter
    private var allConversations: List<Conversation> = emptyList()
    private var selectedFilter = ConversationFilter.ALL

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateNotificationWarning()
        startChatService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureSessionStore(this)
        if (!store.isLoggedIn) {
            openLogin()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        adapter = ConversationAdapter { openConversation(it) }.apply {
            stateRestorationPolicy = androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        binding.conversationList.layoutManager = LinearLayoutManager(this)
        binding.conversationList.adapter = adapter
        binding.conversationList.itemAnimator = null

        binding.filterAllChip.isChecked = true
        binding.filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFilter = when (checkedIds.firstOrNull()) {
                binding.filterUnreadChip.id -> ConversationFilter.UNREAD
                binding.filterHistoryChip.id -> ConversationFilter.HISTORY
                else -> ConversationFilter.ALL
            }
            filter(binding.searchInput.text?.toString().orEmpty())
        }

        binding.reconnectButton.setOnClickListener { ChatRepository.reconnect() }
        binding.logoutButton.setOnClickListener { logout() }
        binding.notificationSettingsButton.setOnClickListener { openNotificationSettings() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        requestNotificationAndStart()
    }

    override fun onStart() {
        super.onStart()
        if (::binding.isInitialized) ChatRepository.addObserver(this)
    }

    override fun onResume() {
        super.onResume()
        ChatNotificationManager.createChannels(this)
        if (::binding.isInitialized) updateNotificationWarning()
    }

    override fun onStop() {
        ChatRepository.removeObserver(this)
        super.onStop()
    }

    override fun onConnectionChanged(connected: Boolean, message: String) {
        binding.connectionStatus.text = if (connected) "●  آنلاین و آماده پاسخ‌گویی" else "●  $message"
        binding.connectionStatus.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.ravix_success else R.color.ravix_warning),
        )
        binding.connectionStatus.setBackgroundResource(
            if (connected) R.drawable.bg_status_online else R.drawable.bg_status_offline,
        )
        binding.reconnectButton.isEnabled = !connected
        binding.reconnectButton.alpha = if (connected) 0.45f else 1f
    }

    override fun onConversationsChanged(items: List<Conversation>) {
        allConversations = items
        updateHeaderCounts()
        filter(binding.searchInput.text?.toString().orEmpty())
    }

    override fun onAuthenticationRequired() {
        binding.connectionStatus.text = "نشست ورود منقضی شده؛ دوباره وارد شوید"
        binding.connectionStatus.setTextColor(ContextCompat.getColor(this, R.color.ravix_danger))
    }

    private fun filter(raw: String) {
        val query = raw.trim()
        val filteredByType = when (selectedFilter) {
            ConversationFilter.ALL -> allConversations
            ConversationFilter.UNREAD -> allConversations.filter { it.unreadCount > 0 }
            ConversationFilter.HISTORY -> allConversations.filter { it.closed || !it.guestOnline }
        }
        val result = if (query.isBlank()) {
            filteredByType
        } else {
            filteredByType.filter {
                it.guestName.contains(query, true) ||
                    it.lastMessage.contains(query, true) ||
                    it.siteLabel.contains(query, true)
            }
        }
        adapter.submitList(result)
        binding.emptyState.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyTitle.text = when {
            query.isNotBlank() -> "نتیجه‌ای پیدا نشد"
            selectedFilter == ConversationFilter.UNREAD -> "پیام خوانده‌نشده‌ای ندارید"
            selectedFilter == ConversationFilter.HISTORY -> "هنوز گفتگوی قبلی ذخیره نشده"
            else -> "هنوز گفتگویی وجود ندارد"
        }
        binding.emptySubtitle.text = when {
            query.isNotBlank() -> "عبارت دیگری را برای جست‌وجو امتحان کنید."
            selectedFilter == ConversationFilter.HISTORY -> "گفتگوهای بسته‌شده، آفلاین و پیام‌های قبلی اینجا می‌مانند."
            else -> "پس از دریافت پیام جدید، گفتگو اینجا نمایش داده می‌شود."
        }
    }

    private fun updateHeaderCounts() {
        val unread = allConversations.sumOf { it.unreadCount }
        val active = allConversations.count { !it.closed && it.guestOnline }
        binding.toolbar.subtitle = if (unread > 0) {
            "$active گفتگوی فعال • $unread پیام جدید"
        } else {
            "$active گفتگوی فعال • همه پیام‌ها خوانده شده"
        }
        binding.filterUnreadChip.text = if (unread > 0) "خوانده‌نشده ($unread)" else "خوانده‌نشده"
        val history = allConversations.count { it.closed || !it.guestOnline }
        binding.filterHistoryChip.text = if (history > 0) "گفتگوهای قبلی ($history)" else "گفتگوهای قبلی"
    }

    private fun openConversation(item: Conversation) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, item.id)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_TITLE, item.guestName),
        )
    }

    private fun requestNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startChatService()
        }
    }

    private fun startChatService() {
        store.keepOnline = true
        ChatSocketService.start(this)
        ChatRepository.connect()
        updateNotificationWarning()
    }

    private fun updateNotificationWarning() {
        binding.notificationWarning.visibility =
            if (ChatNotificationManager.canPostNotifications(this)) View.GONE else View.VISIBLE
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, ChatNotificationManager.MESSAGE_CHANNEL)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    private fun logout() {
        store.keepOnline = false
        stopService(Intent(this, ChatSocketService::class.java))
        ChatRepository.disconnect(clearData = true)
        store.clearLogin()
        openLogin()
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun applyInsets() {
        val toolbarTop = binding.toolbar.paddingTop
        val containerBottom = binding.listContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = toolbarTop + bars.top)
            binding.listContainer.updatePadding(bottom = containerBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }
}
