package com.garan.counterpart.common

import android.util.Log
import com.google.android.gms.wearable.MessageClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object MessagePaths {
    const val wearStatus = "/wear_status"
    const val launchRemoteApp = "/counterpart_app_launch"
}

object MessageValues {
    const val alive = "alive"
    const val inactive = "inactive"
}

suspend fun addMessageListener(messageClient: MessageClient, listener: MessageClient.OnMessageReceivedListener) =
    suspendCoroutine<Void> { continuation ->
        messageClient.addListener(listener).addOnSuccessListener {
            continuation.resume(it)
        }.addOnFailureListener {
            Log.w(TAG, "Failed to add message listener: $it")
            continuation.resumeWithException(it)
        }
    }

suspend fun sendMessage(messageClient: MessageClient, nodeId: String, path: String, data: ByteArray) = suspendCoroutine<Int> { continuation ->
    messageClient.sendMessage(nodeId, path, data).addOnSuccessListener {
        continuation.resume(it)
    }
}

const val KEEP_ALIVE_DELAY_MS = 15000L
