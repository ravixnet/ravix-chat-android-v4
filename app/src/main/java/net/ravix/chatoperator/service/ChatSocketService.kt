package net.ravix.chatoperator.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import net.ravix.chatoperator.data.ChatRepository
import net.ravix.chatoperator.data.SecureSessionStore
import net.ravix.chatoperator.notification.ChatNotificationManager

class ChatSocketService : Service(), ChatRepository.Observer {
    private lateinit var store: SecureSessionStore

    override fun onCreate() {
        super.onCreate()
        store = SecureSessionStore(this)
        ChatNotificationManager.createChannels(this)
        startForeground(
            ChatNotificationManager.SERVICE_NOTIFICATION_ID,
            ChatNotificationManager.serviceNotification(this, "در حال اتصال به سرور..."),
        )
        ChatRepository.addObserver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!store.isLoggedIn || !store.keepOnline) {
            stopSelf()
            return START_NOT_STICKY
        }
        ChatRepository.connect()
        return START_STICKY
    }

    override fun onDestroy() {
        ChatRepository.removeObserver(this)
        if (!store.keepOnline) ChatRepository.disconnect(clearData = false)
        super.onDestroy()
    }

    override fun onConnectionChanged(connected: Boolean, message: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(
            ChatNotificationManager.SERVICE_NOTIFICATION_ID,
            ChatNotificationManager.serviceNotification(
                this,
                if (connected) "آنلاین و آماده پاسخ‌گویی" else message,
            ),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ChatSocketService::class.java))
        }
    }
}
