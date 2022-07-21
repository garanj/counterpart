package com.garan.counterpart

import android.content.Intent
import com.garan.counterpart.common.MessagePaths
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Listens for messages to start the Wear app. This message can be sent from the phone app.
 */
class WearCounterpartListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == MessagePaths.ping) {
            Intent(applicationContext, WearCounterpartService::class.java).also { intent ->
                applicationContext.startForegroundService(intent)
            }
        } else if (messageEvent.path == MessagePaths.launchRemoteApp) {
            val intent = Intent(this, WearCounterpartActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
    }
}