package net.ravix.chatoperator.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import net.ravix.chatoperator.R
import net.ravix.chatoperator.ui.ChatActivity
import net.ravix.chatoperator.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject

object ChatNotificationManager {
    const val SERVICE_CHANNEL = "ravix_chat_connection"

    // A new channel ID intentionally migrates installs that previously had a broken/silent channel.
    const val MESSAGE_CHANNEL = "ravix_chat_messages_v3"
    const val SERVICE_NOTIFICATION_ID = 21001

    private const val MESSAGE_GROUP = "ravix-chat-messages-v3"
    private const val GROUP_SUMMARY_ID = 21002
    private const val MESSAGE_NOTIFICATION_BASE = 30_000
    private const val PREFS_NAME = "ravix_notification_state_v3"
    private const val PREFS_STATE = "state"
    private const val MAX_NOTIFICATION_MESSAGES = 6

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL,
                context.getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "اتصال دائمی و امن اپراتور به سرور راویکس"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )

        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL,
                context.getString(R.string.message_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.message_channel_description)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 220, 100, 220)
                enableLights(true)
                setShowBadge(true)
                setSound(sound, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    fun serviceNotification(context: Context, status: String) = NotificationCompat.Builder(context, SERVICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_ravix_chat)
        .setContentTitle("اپراتور چت راویکس")
        .setContentText(status)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(mainPendingIntent(context))
        .build()

    @Synchronized
    fun showMessage(
        context: Context,
        conversationId: String,
        title: String,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        unreadCount: Int = 1,
    ) {
        if (conversationId.isBlank()) return
        createChannels(context)
        if (!canPostNotifications(context)) return

        val safeTitle = title.trim().ifBlank { "کاربر سایت" }
        val safeText = text.trim().ifBlank { "پیام جدید" }
        val state = loadState(context)
        val conversationState = state.optJSONObject(conversationId) ?: JSONObject()
        val oldMessages = conversationState.optJSONArray("messages") ?: JSONArray()
        val messages = JSONArray()
        val start = (oldMessages.length() - (MAX_NOTIFICATION_MESSAGES - 1)).coerceAtLeast(0)
        for (index in start until oldMessages.length()) {
            oldMessages.optJSONObject(index)?.let { messages.put(it) }
        }
        messages.put(
            JSONObject()
                .put("text", safeText)
                .put("time", timestamp),
        )
        conversationState
            .put("title", safeTitle)
            .put("count", maxOf(unreadCount, conversationState.optInt("count", 0) + 1))
            .put("messages", messages)
            .put("updatedAt", timestamp)
        state.put(conversationId, conversationState)
        saveState(context, state)

        val operator = Person.Builder().setName("اپراتور راویکس").build()
        val guest = Person.Builder().setName(safeTitle).build()
        val style = NotificationCompat.MessagingStyle(operator)
            .setConversationTitle(safeTitle)
            .setGroupConversation(false)
        for (index in 0 until messages.length()) {
            val item = messages.optJSONObject(index) ?: continue
            style.addMessage(
                item.optString("text", "پیام جدید"),
                item.optLong("time", timestamp),
                guest,
            )
        }

        val builder = NotificationCompat.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_ravix_chat)
            .setContentTitle(safeTitle)
            .setContentText(safeText)
            .setStyle(style)
            .setContentIntent(conversationPendingIntent(context, conversationId, safeTitle))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(MESSAGE_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setNumber(conversationState.optInt("count", 1))
            .setWhen(timestamp)
            .setShowWhen(true)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(24 * 60 * 60 * 1000L)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(conversationId), builder.build())
            updateGroupSummary(context, state)
        }
    }

    fun playIncomingSound(context: Context) {
        if (!canPostNotifications(context)) return
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
        }
    }

    @Synchronized
    fun dismissConversation(context: Context, conversationId: String) {
        if (conversationId.isBlank()) return
        val state = loadState(context)
        state.remove(conversationId)
        saveState(context, state)
        NotificationManagerCompat.from(context).cancel(notificationId(conversationId))
        updateGroupSummary(context, state)
    }

    @Synchronized
    fun clearMessageNotifications(context: Context) {
        val state = loadState(context)
        val ids = state.keys()
        while (ids.hasNext()) {
            NotificationManagerCompat.from(context).cancel(notificationId(ids.next()))
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        NotificationManagerCompat.from(context).cancel(GROUP_SUMMARY_ID)
    }

    fun canPostNotifications(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(MESSAGE_CHANNEL)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun conversationPendingIntent(context: Context, conversationId: String, title: String): PendingIntent {
        val intent = Intent(context, ChatActivity::class.java)
            .setData(Uri.parse("ravix://chat/${Uri.encode(conversationId)}"))
            .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(ChatActivity.EXTRA_CONVERSATION_TITLE, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return requireNotNull(
            TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(intent)
                .getPendingIntent(
                    notificationId(conversationId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
        )
    }

    private fun mainPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        SERVICE_NOTIFICATION_ID,
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("ravix://conversations"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun updateGroupSummary(context: Context, state: JSONObject) {
        if (!canPostNotifications(context)) return
        var totalUnread = 0
        var conversationCount = 0
        val keys = state.keys()
        while (keys.hasNext()) {
            val item = state.optJSONObject(keys.next()) ?: continue
            conversationCount += 1
            totalUnread += item.optInt("count", 1).coerceAtLeast(1)
        }
        val manager = NotificationManagerCompat.from(context)
        if (conversationCount == 0) {
            manager.cancel(GROUP_SUMMARY_ID)
            return
        }
        val summaryText = if (conversationCount == 1) {
            "$totalUnread پیام خوانده‌نشده"
        } else {
            "$totalUnread پیام از $conversationCount گفتگو"
        }
        val summary = NotificationCompat.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_ravix_chat)
            .setContentTitle("پیام‌های جدید راویکس")
            .setContentText(summaryText)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(summaryText))
            .setContentIntent(mainPendingIntent(context))
            .setGroup(MESSAGE_GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setNumber(totalUnread)
            .build()
        manager.notify(GROUP_SUMMARY_ID, summary)
    }

    private fun notificationId(conversationId: String): Int =
        MESSAGE_NOTIFICATION_BASE + (conversationId.hashCode() and 0x0fffffff)

    private fun loadState(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_STATE, null)
            .orEmpty()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun saveState(context: Context, state: JSONObject) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_STATE, state.toString())
            .apply()
    }
}
