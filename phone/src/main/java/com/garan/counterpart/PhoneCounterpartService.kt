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

    private val HEART_RATE_TTL_MS = 10000L

    // The capability that the phone app declares on the network of nodes.
    private val capabilityUri = Uri.parse("wear://*/${Capabilities.wear}")

    private var started = false

    // Job used for reading data from the [Channel] [InputStream] when HR is being collected.
    private var hrInputJob: Job? = null
    private var hrInputStream: InputStream? = null

    // Job to invalidate the HR value if it is too old.
    private var heartRateTimeOut: Job? = null

    private lateinit var capabilityClient: CapabilityClient
    private lateinit var channelClient: ChannelClient
    private lateinit var messageClient: MessageClient

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
    private val capableDeviceListener = object : CapabilityClient.OnCapabilityChangedListener {
        override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
            connectedHeartRateSensorNodeId = capabilityInfo.nodes.firstOrNull()?.id
            lifecycleScope.launch {
                installedStatus.value = checkNodeForInstall()
            }
        }
    }

    private val runningStatusListener = object : CapabilityClient.OnCapabilityChangedListener {
        override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
            appActiveStatus.value = capabilityInfo.nodes.isNotEmpty()
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
                hrInputJob?.cancel()
                hrInputStream = null
                hr.value = 0
            }
        }
    }

    private fun updateHeartRateTimeOutCheck() {
        heartRateTimeOut?.cancel()
        heartRateTimeOut = lifecycleScope.launch {
            delay(HEART_RATE_TTL_MS)
            hr.value = 0
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
                                // Ensure that the timer is reset to invalidate the heart rate if
                                // the value gets stale.
                                updateHeartRateTimeOutCheck()
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
                capableDeviceListener,
                capabilityUri,
                CapabilityClient.FILTER_REACHABLE
            ).await()
        }

        val runningUri = Uri.parse("wear://*/${Capabilities.wearAppRunning}")
        lifecycleScope.launch {
            capabilityClient.addListener(
                runningStatusListener,
                runningUri,
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
        suspendCoroutine { continuation ->
            Wearable.getCapabilityClient(this)
                .getCapability(Capabilities.wear, CapabilityClient.FILTER_REACHABLE)
                .addOnCompleteListener { task ->
                    continuation.resume(task.result?.nodes?.firstOrNull()?.id)
                }
        }

    private suspend fun checkForPoweredOnNode() = suspendCoroutine { continuation ->
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
            capabilityClient.removeListener(capableDeviceListener)
            capabilityClient.removeListener(runningStatusListener)
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

