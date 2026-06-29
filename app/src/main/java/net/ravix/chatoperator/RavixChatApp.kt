package net.ravix.chatoperator

import android.app.Application
import net.ravix.chatoperator.data.ChatRepository
import net.ravix.chatoperator.notification.ChatNotificationManager

class RavixChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ChatNotificationManager.createChannels(this)
        ChatRepository.initialize(this)
    }
}
