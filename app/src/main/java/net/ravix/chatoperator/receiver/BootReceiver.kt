package net.ravix.chatoperator.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.ravix.chatoperator.data.SecureSessionStore
import net.ravix.chatoperator.service.ChatSocketService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val store = SecureSessionStore(context)
        if (store.isLoggedIn && store.keepOnline) ChatSocketService.start(context)
    }
}
