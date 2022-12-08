package com.garan.counterpart.network

import android.content.Context
import android.net.Uri
import android.util.Log
import com.garan.counterpart.TAG
import com.garan.counterpart.common.Capabilities
import com.garan.counterpart.common.Channels
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.OutputStream

class DataLayerHrSenderClient(
    val context: Context,
    private val scope: CoroutineScope
) : HrSenderClient() {
    private val CLOSE_REASON_TIMEOUT = 4

    // The capability that the phone app declares on the network of nodes.
    private val capabilityUri = Uri.parse("wear://*/${Capabilities.phone}")

    private var hrChannel: ChannelClient.Channel? = null
    private var hrOutputStream: OutputStream? = null

    private var shouldBeConnected: Boolean = false

    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val channelClient = Wearable.getChannelClient(context)

    private val capablePhoneNodeId: MutableStateFlow<String?> = MutableStateFlow(null)

    private var nodeIdJob: Job? = null
    private var capabilityListenerJob: Job? = null

    // Callback for when channels are opened or closed.
    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelClosed(channel: ChannelClient.Channel, reason: Int, appCode: Int) {
            if (channel.path == Channels.hrChannel) {
                hrOutputStream = null
                hrChannel = null

                if (shouldBeConnected && reason == CLOSE_REASON_DISCONNECTED) {
                    // In this basic example, if the channel gets disconnected and it's not for
                    // normal reasons, then simply try to re-establish it. In reality, perhaps you'd
                    // want better logic
                    connect()
                } else if (reason == CLOSE_REASON_TIMEOUT) {
                    // Connection timeout - probably the app isn't running on the phone-side - which
                    // is something that could be established in advance by some other means, e.g.
                    // via MessageClient or another Channel.
                    // Ideally, it might be worth indicating this timeout to the user and giving
                    // options for what to do.
                }
            }
        }
    }

    private val capabilityChangedListener = object : CapabilityClient.OnCapabilityChangedListener {
        /**
         * Called when the capability changes on the node network, e.g. a new device added that has the
         * app installed, or an existing device has the app installed etc.
         */
        override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
            capablePhoneNodeId.value = capabilityInfo.nodes.firstOrNull()?.id
        }
    }

    override fun connect(): Boolean {
        nodeIdJob = scope.launch {
            // If there is a phone on the node network with the app installed, start the [Channel]
            // which will be used for sending the HR values when sensor is on, but also allows the
            // phone to know when the app is running on the Wear app.
            capablePhoneNodeId.collect { nodeId ->
                if (nodeId != null) {
                    initializeHrChannel()
                }
            }
        }

        capabilityListenerJob = scope.launch {
            capabilityClient.addListener(
                capabilityChangedListener,
                capabilityUri,
                CapabilityClient.FILTER_REACHABLE
            ).await()
        }

        // This service implements [OnCapabilityChangedListener], so any change on the node network
        // e.g. a phone being turned on with the app installed, or a phone installing the app will
        // result in an event. However, on initial start up, it is necessary to query the current
        // capabilities of the node network to see if there is a phone already present with the app
        // installed.
        scope.launch {
            queryForCapability()
        }

        return true
    }

    override fun disconnect() {
        nodeIdJob?.cancel()
        capabilityListenerJob?.cancel()
        scope.launch {
            hrChannel?.let { channel ->
                shouldBeConnected = false
                channelClient.close(channel).await()
                hrChannel = null
                hrOutputStream = null
            }
            capabilityClient.removeListener(capabilityChangedListener)
            channelClient.unregisterChannelCallback(channelCallback)
        }
    }

    override fun sendValue(value: Int) {
        hrOutputStream?.write(value)
    }

    private fun initializeHrChannel() {
        Log.i(TAG, "Initialising channel")
        capablePhoneNodeId.value?.let { nodeId ->
            scope.launch {
                shouldBeConnected = true
                hrChannel = channelClient.openChannel(nodeId, Channels.hrChannel).await()
                hrChannel?.let { channel ->
                    hrOutputStream = channelClient.getOutputStream(channel).await()
                }
            }
        }
    }

    /**
     * Checks for whether there is a phone on the node network that has the app installed (though
     * not necessarily running), and assigns it to the [capablePhoneNodeId] variable.
     */
    private suspend fun queryForCapability() {
        capablePhoneNodeId.value = checkForPoweredOnInstalledNode()
    }


    /**
     * Checks for a phone with the app installed. If one is found, the ID is returned as a string
     * otherwise null.
     */
    private suspend fun checkForPoweredOnInstalledNode(): String? {
        val capabilityInfo = Wearable.getCapabilityClient(context)
            .getCapability(Capabilities.phone, CapabilityClient.FILTER_REACHABLE)
            .await()
        return capabilityInfo.nodes.firstOrNull()?.id
    }
}