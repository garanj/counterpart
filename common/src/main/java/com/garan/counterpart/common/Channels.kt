package com.garan.counterpart.common

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

const val TAG = "Counterpart"

// Only one channel used in this example - for transmission of HR values from watch to phone.
object Channels {
    const val hrChannel = "/hr"
}

 suspend fun openChannel(channelClient: ChannelClient, nodeId: String, path: String) =
    suspendCoroutine<ChannelClient.Channel> { continuation ->
        channelClient.openChannel(nodeId, path).addOnSuccessListener {
            continuation.resume(it)
        }
    }

 suspend fun closeChannel(channelClient: ChannelClient, channel: ChannelClient.Channel) =
    suspendCoroutine<Void> { continuation ->
        channelClient.close(channel).addOnSuccessListener {
            continuation.resume(it)
        }
    }

 suspend fun getOutputStream(channelClient: ChannelClient, channel: ChannelClient.Channel) =
    suspendCoroutine<OutputStream> { continuation ->
        channelClient.getOutputStream(channel).addOnSuccessListener {
            continuation.resume(it)
        }
    }

suspend fun getInputStream(channelClient: ChannelClient, channel: ChannelClient.Channel) =
    suspendCoroutine<InputStream> { continuation ->
        channelClient.getInputStream(channel).addOnSuccessListener {
            continuation.resume(it)
        }
    }

 suspend fun registerChannelCallback(channelClient: ChannelClient, channelCallback: ChannelClient.ChannelCallback) =
    suspendCoroutine<Void> { continuation ->
        channelClient.registerChannelCallback(channelCallback).addOnSuccessListener {
            continuation.resume(it)
        }.addOnFailureListener {
            Log.w(TAG, "Failed to add channel callback: $it")
            continuation.resumeWithException(it)
        }
    }