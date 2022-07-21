package com.garan.counterpart

import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.garan.counterpart.common.Capabilities
import com.garan.counterpart.common.Channels
import com.garan.counterpart.common.MessagePaths
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.ChannelIOException
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * This service controls the communication with the Wear device, detection of changes on the
 * connected node network, and provision of HR data that the UI can then display.
 */
@AndroidEntryPoint
class PhoneCounterpartService : LifecycleService() {
    private val binder = LocalBinder()

    // The capability that the phone app declares on the network of nodes.
    private val capabilityUri = Uri.parse("wear://*/${Capabilities.wear}")

    private var started = false

    // Job used for reading data from the [Channel] [InputStream] when HR is being collected.
    private var hrInputJob: Job? = null
    private var hrInputStream: InputStream? = null

    private lateinit var capabilityClient: CapabilityClient
    private lateinit var channelClient: ChannelClient
    private lateinit var messageClient: MessageClient

    // Job for pinging the watch when it is on. At the moment, on Samsung devices, it appears that
    // even ForegroundServices can be paused in some way by some battery-saving logic, which should
    // not really happen when the sensor is tracking. To work around this at the moment, a message
    // is delivered periodically from the phone which stops the ForegroundService from doing this.
    // This is not the ideal situation and a better remedy to this situation is required.
    private var pingJob: Job? = null

    val installedStatus: MutableStateFlow<WearAppInstalledStatus> =
        MutableStateFlow(WearAppInstalledStatus.NO_DEVICE_FOUND)
    val appActiveStatus: MutableStateFlow<Boolean> =
        MutableStateFlow(false)
    val hr: MutableState<Int> = mutableStateOf(0)

    // The ID of the connected Wear device, if there is one, or null
    var connectedHeartRateSensorNodeId: String? = null


    // Listens for changes on the node network for any devices running the Wear app. In this simple
    // example, this phone app simply takes the first Wear app device on the network that is running
    // the wear app, and does not deal with the scenario where there may be more than one.
    private val capabilityChangedListener = object : CapabilityClient.OnCapabilityChangedListener {
        override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
            connectedHeartRateSensorNodeId = capabilityInfo.nodes.firstOrNull()?.id
            lifecycleScope.launch {
                installedStatus.value = checkNodeForInstall()
            }
        }
    }

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        // The Wear app opens a channel when the apps is started, and sends HR readings as plain
        // Int values. The phone app reacts to the opening of the channel by setting up a loop to
        // read from the InputStream.
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            Log.i(TAG, "Channel opened! ${channel.path}")
            if (channel.path == Channels.hrChannel) {
                appActiveStatus.value = true
                startChannelInputStream(channel)
            }
        }

        override fun onChannelClosed(channel: ChannelClient.Channel, p1: Int, p2: Int) {
            if (channel.path == Channels.hrChannel) {
                appActiveStatus.value = false
                hrInputJob?.cancel()
                hrInputStream = null
                hr.value = 0
            }
        }
    }

    private fun startChannelInputStream(channel: ChannelClient.Channel) {
        lifecycleScope.launch(Dispatchers.IO) {
            hrInputStream = channelClient.getInputStream(channel).await()
            var isEnded = false
            while (hrInputStream != null && !isEnded) {
                if ((hrInputStream?.available() ?: 0) > 0) {
                    try {
                        val readInt = hrInputStream?.read()
                        readInt?.let {
                            if (it == -1) {
                                isEnded = true
                            } else {
                                hr.value = it
                            }
                        }
                    } catch (e: ChannelIOException) {
                        Log.w(TAG, "Tried to read as channel may have been closing")
                        isEnded = true
                    }
                }
                delay(10)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        capabilityClient = Wearable.getCapabilityClient(this)
        channelClient = Wearable.getChannelClient(this)
        messageClient = Wearable.getMessageClient(this)
        lifecycleScope.launch {
            capabilityClient.addListener(
                capabilityChangedListener,
                capabilityUri,
                CapabilityClient.FILTER_REACHABLE
            ).await()
        }

        lifecycleScope.launch {
            channelClient.registerChannelCallback(channelCallback).await()
        }

        // Look for any supported Wear nodes on initial start up.
        lifecycleScope.launch {
            queryForCapability()
        }

        lifecycleScope.launch {
            Log.i(TAG, "Setting up ping")
            appActiveStatus.collect { status ->
                when (status) {
                    false -> {
                        pingJob?.cancel()
                        pingJob = null
                    }
                    true -> startPingJob()
                }
            }
        }
        Log.i(TAG, "Service onCreate")
    }

    fun startRemoteApp() {
        connectedHeartRateSensorNodeId?.let { nodeId ->
            lifecycleScope.launch(Dispatchers.IO) {
                messageClient.sendMessage(nodeId, MessagePaths.launchRemoteApp, "".toByteArray())
                    .await()
            }
        }
    }

    private fun startPingJob() {
        pingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                Log.i(TAG, "Sending ping")
                connectedHeartRateSensorNodeId?.let { nodeId ->
                    messageClient.sendMessage(
                        nodeId,
                        MessagePaths.ping,
                        "".toByteArray()
                    ).await()
                }
                delay(5000)
            }
        }
    }

    private suspend fun queryForCapability() {
        connectedHeartRateSensorNodeId = checkForPoweredOnInstalledNode()
        installedStatus.value = checkNodeForInstall()
    }

    private suspend fun checkNodeForInstall() = if (connectedHeartRateSensorNodeId == null) {
        // Check for whether a device exists without the app
        val firstNode = checkForPoweredOnNode()
        if (firstNode != null) {
            connectedHeartRateSensorNodeId = firstNode
            WearAppInstalledStatus.APP_NOT_INSTALLED
        } else {
            WearAppInstalledStatus.NO_DEVICE_FOUND
        }
    } else {
        WearAppInstalledStatus.APP_INSTALLED
    }

    private suspend fun checkForPoweredOnInstalledNode() =
        suspendCoroutine<String?> { continuation ->
            Wearable.getCapabilityClient(this)
                .getCapability(Capabilities.wear, CapabilityClient.FILTER_REACHABLE)
                .addOnCompleteListener { task ->
                    continuation.resume(task.result?.nodes?.firstOrNull()?.id)
                }
        }

    private suspend fun checkForPoweredOnNode() = suspendCoroutine<String?> { continuation ->
        Wearable.getNodeClient(this)
            .connectedNodes
            .addOnCompleteListener { task ->
                continuation.resume(task.result?.firstOrNull()?.id)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!started) {
            started = true
        }
        Log.i(TAG, "service onStartCommand")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Log.i(TAG, "service onBind")
        return binder
    }

    override fun onRebind(intent: Intent?) {
        Log.i(TAG, "service onRebind")
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "service onUnbind")
        maybeStopService()
        return true
    }

    private fun maybeStopService() {
        lifecycleScope.launch {
            capabilityClient.removeListener(capabilityChangedListener)
            channelClient.unregisterChannelCallback(channelCallback)
            stopSelf()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PhoneCounterpartService = this@PhoneCounterpartService
    }
}

enum class WearAppInstalledStatus {
    NO_DEVICE_FOUND,
    APP_NOT_INSTALLED,
    APP_INSTALLED
}

