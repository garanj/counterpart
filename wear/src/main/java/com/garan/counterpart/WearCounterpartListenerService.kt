package com.garan.counterpart

import android.content.Intent
import android.util.Log
import com.garan.counterpart.common.MessagePaths
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Listens for messages to start the Wear app. This message can be sent from the phone app.
 */
class WearCounterpartListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == MessagePaths.launchRemoteApp) {
            Log.d(TAG, "Message received to launch Counterpart Wear app")
            val intent = Intent(this, WearCounterpartActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
    }
}