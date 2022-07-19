package com.garan.counterpart.common

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

// The two capabilites which allow phones and wear devices with the app installed to be identified
// on the network
object Capabilities {
    const val phone = "counterpart_phone_capability"
    const val wear = "counterpart_wear_capability"
}

suspend fun addCapabilityListener(
    capabilityClient: CapabilityClient,
    listener: CapabilityClient.OnCapabilityChangedListener,
    capabilityUri: Uri, filterType: Int
) =
    suspendCoroutine<Void> { continuation ->
        capabilityClient.addListener(listener, capabilityUri, filterType).addOnSuccessListener {
            continuation.resume(it)
        }.addOnFailureListener {
            Log.w(TAG, "Failed to add capability listener: $it")
            continuation.resumeWithException(it)
        }
    }
