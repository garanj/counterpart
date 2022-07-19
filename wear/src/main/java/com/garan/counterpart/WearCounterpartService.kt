package com.garan.counterpart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPoint
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.garan.counterpart.common.Capabilities
import com.garan.counterpart.common.Channels
import com.garan.counterpart.common.KEEP_ALIVE_DELAY_MS
import com.garan.counterpart.common.MessagePaths
import com.garan.counterpart.common.MessageValues
import com.garan.counterpart.common.addCapabilityListener
import com.garan.counterpart.common.closeChannel
import com.garan.counterpart.common.getOutputStream
import com.garan.counterpart.common.openChannel
import com.garan.counterpart.common.registerChannelCallback
import com.garan.counterpart.common.sendMessage
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * This service forms the guts of the mechanism for:
 *
 *     - Identifying if there is a connected phone, using [CapabilityClient]
 *     - Measuring heart rate, using the in-built sensor
 *     - Transmitting heart rate to the phone, using [ChannelClient]
 *     - Using [MessageClient] to send periodic "keepalive" messages to the phone, to show that the
 *       Wear app is still running, even if it's not collecting HR (in reality, this may be just
 *       as well or better done via another [Channel] with [ChannelClient]
 *     - Ensuring that collection and transmission can continue when the app is not being used
 *       interactively: When the user turns the heart rate collection on, this service is put into
 *       [ForegroundService] mode, which means it will keep running when the user browses away from
 *       it and allows an [OngoingActivity] to be shown on the watch face and other surfaces to
 *       indicate it is still running. Stopping HR collection takes the service out of foreground
 *       mode, and browsing away from the app will then result in normal shutdown.
 */
@AndroidEntryPoint
class WearCounterpartService : LifecycleService() {
    private val binder = LocalBinder()

    // ChannelClient does not appear to define this constant for the reason code for a timeout.
    private val CLOSE_REASON_TIMEOUT = 4

    private var started = false
    private var foreground = false

    // The capability that the phone app declares on the network of nodes.
    private val capabilityUri = Uri.parse("wear://*/${Capabilities.phone}")

    private lateinit var capabilityClient: CapabilityClient
    private lateinit var channelClient: ChannelClient
    private lateinit var messageClient: MessageClient

    private var measureCallback: MeasureCallback? = null
    private lateinit var measureClient: MeasureClient

    private var messageSenderJob: Job? = null
    private var hrChannel: ChannelClient.Channel? = null
    private var hrOutputStream: OutputStream? = null

    val capablePhoneNodeId: MutableStateFlow<String?> = MutableStateFlow(null)

    val hr: MutableState<Int> = mutableStateOf(0)
    val isHrSensorOn: MutableState<Boolean> = mutableStateOf(false)

    // Callback for when channels are opened or closed.
    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelClosed(channel: ChannelClient.Channel, reason: Int, appCode: Int) {
            if (channel.path == Channels.hrChannel) {
                hrOutputStream = null
                hrChannel = null

                if (isHrSensorOn.value && reason == CLOSE_REASON_DISCONNECTED) {
                    // In this basic example, if the channel gets disconnected and it's not for
                    // normal reasons, then simply try to re-establish it. In reality, perhaps you'd
                    // want better logic
                    initializeHrChannel()
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

    override fun onCreate() {
        super.onCreate()
        capabilityClient = Wearable.getCapabilityClient(this)
        channelClient = Wearable.getChannelClient(this)
        messageClient = Wearable.getMessageClient(this)
        measureClient = HealthServices.getClient(this).measureClient

        lifecycleScope.launch {
            // If there is a phone on the node network with the app installed, start the periodic
            // transmission to show the Wear app is awake. This could be done with a [Channel],
            // which may be better. Also, this makes no attempt at the moment to determine whether
            // the app on the phone is running or not.
            capablePhoneNodeId.collect { nodeId ->
                if (nodeId != null) {
                    Log.i(TAG, "Starting message sender")
                    enableMessageSender()
                } else {
                    Log.i(TAG, "Stopping message sender")
                    disableSender()
                }
            }
        }

        lifecycleScope.launch {
            addCapabilityListener(
                capabilityClient,
                capabilityChangedListener,
                capabilityUri,
                CapabilityClient.FILTER_REACHABLE
            )
        }

        lifecycleScope.launch {
            registerChannelCallback(
                channelClient = channelClient,
                channelCallback = channelCallback
            )
        }
        // This service implements [OnCapabilityChangedListener], so any change on the node network
        // e.g. a phone being turned on with the app installed, or a phone installing the app will
        // result in an event. However, on initial start up, it is necessary to query the current
        // capabilities of the node network to see if there is a phone already present with the app
        // installed.
        lifecycleScope.launch {
            queryForCapability()
        }

        Log.i(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!started) {
            started = true
        }
        Log.i(TAG, "service onStartCommand")
        return START_NOT_STICKY
    }

    /**
     * Starts or stops HR collection. This requires both the turning on or off of the sensor and the
     * initializing or tearing down of the [Channel] used to send data to the phone.
     */
    suspend fun startStopHr() {
        if (isHrSensorOn.value) {
            teardownHeartRateSensor()
            tearDownHrChannel()
        } else {
            initializeHeartRateSensor()
            initializeHrChannel()
        }
    }

    private suspend fun initializeHeartRateSensor() {
        measureCallback = object : MeasureCallback {
            override fun onData(data: List<DataPoint>) {
                val heartRate = data.last().value.asDouble().toInt()
                if (heartRate > 0) {
                    // Update the state value which is used locally on the watch in the UI.
                    hr.value = heartRate
                    // Write the value to the [Channel] for transmission to the phone. In this
                    // basic example, HR is written to the [Channel] as a simple [Int] value which
                    // is read on the other side. It might be better to send some kind of structure
                    // like using protobuf.
                    hrOutputStream?.write(heartRate)
                }
            }

            override fun onAvailabilityChanged(dataType: DataType, availability: Availability) {
                when (availability) {
                    DataTypeAvailability.UNKNOWN,
                    DataTypeAvailability.ACQUIRING,
                    DataTypeAvailability.UNAVAILABLE -> {
                        hr.value = 0
                    }
                }
            }
        }

        measureCallback?.let { callback ->
            Log.i(TAG, "Enabling HR sensor")
            measureClient.registerCallback(DataType.HEART_RATE_BPM, callback).await()
        }
        // Once collection is enabled, the service is put into Foreground mode.
        isHrSensorOn.value = true
        enableForegroundService()
    }

    /**
     * Creates a [Channel] in [OutputStream] mode for transmission of HR data to the phone.
     */
    private fun initializeHrChannel() {
        capablePhoneNodeId.value?.let { nodeId ->
            lifecycleScope.launch(Dispatchers.IO) {
                hrChannel = openChannel(channelClient, nodeId, Channels.hrChannel)
                hrChannel?.let { channel ->
                    hrOutputStream = getOutputStream(channelClient, channel)
                    Log.i(TAG, "Set up channel to node: $nodeId")
                }
            }
        }
    }

    private fun tearDownHrChannel() {
        hrChannel?.let { channel ->
            lifecycleScope.launch {
                closeChannel(channelClient, channel)
                hrChannel = null
                hrOutputStream = null
            }
        }
    }

    private suspend fun teardownHeartRateSensor() {
        measureCallback?.let { callback ->
            measureClient.unregisterCallback(DataType.HEART_RATE_BPM, callback).await()
        }
        measureCallback = null
        isHrSensorOn.value = false
        hr.value = 0
        // When the HR sensor is disabled, the service is taken out of foreground mode as the app no
        // longer needs to keep running when not being used interactively.
        disableForeground()
    }

    /**
     * Sends a periodic message to the phone to let it know the app is still running - even if the
     * HR sensor isn't started. The phone app can use this to know whether to offer the option to
     * launch the Wear app remotely or not.
     *
     * This could be achieved using a [Channel] instead that is established when the app is launched
     * which may be a better approach.
     */
    private fun enableMessageSender() {
        if (messageSenderJob == null) {
            messageSenderJob = lifecycleScope.launch(Dispatchers.IO) {
                while (true) {
                    capablePhoneNodeId.value?.let { nodeId ->
                        Log.i(TAG, "Sending alive message")
                        sendMessage(
                            messageClient,
                            nodeId,
                            MessagePaths.wearStatus,
                            MessageValues.alive.toByteArray()
                        )
                    }
                    delay(KEEP_ALIVE_DELAY_MS)
                }
            }
        }
    }

    /**
     * Lets the phone app know that the Wear app is shutting down.
     */
    private suspend fun sendInactiveMessage() {
        capablePhoneNodeId.value?.let { nodeId ->
            sendMessage(
                messageClient,
                nodeId,
                MessagePaths.wearStatus,
                MessageValues.inactive.toByteArray()
            )
        }
    }

    /**
     * Stops the periodic alive messages being sent.
     */
    private fun disableSender() {
        messageSenderJob?.let {
            it.cancel()
            messageSenderJob = null
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
    private suspend fun checkForPoweredOnInstalledNode() =
        suspendCoroutine<String?> { continuation ->
            Wearable.getCapabilityClient(this)
                .getCapability(Capabilities.phone, CapabilityClient.FILTER_REACHABLE)
                .addOnCompleteListener { task ->
                    continuation.resume(task.result?.nodes?.firstOrNull()?.id)
                }
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
        // When the Activity unbinds, need to consider whether the service should shutdown, if the
        // app is or isn't measuring HR.
        maybeStopService()
        return true
    }

    private fun maybeStopService() {
        if (!isHrSensorOn.value) {
            // Stop sending alive messages
            disableSender()
            // Send message to phone to signal Wear app stopping
            lifecycleScope.launch(Dispatchers.IO) {
                sendInactiveMessage()
                channelClient.unregisterChannelCallback(channelCallback)
                capabilityClient.removeListener(capabilityChangedListener)
                stopSelf()
            }
        }
    }

    private fun enableForegroundService() {
        if (!foreground) {
            createNotificationChannel()
            startForeground(1, buildNotification())
            foreground = true
        }
    }

    private fun disableForeground() {
        if (foreground) {
            stopForeground(true)
            foreground = false
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL, "com.garan.counterpart.ONGOING",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager!!.createNotificationChannel(serviceChannel)
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, WearCounterpartActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Build the notification.
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val ongoingActivityStatus = Status.Builder()
            .addTemplate(STATUS_TEMPLATE)
            // Could change the text here depending on whether the HR sensor is collecting or not
            // which would then be reflected on supported surfaces.
            .build()
        val ongoingActivity =
            OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, notificationBuilder)
                .setAnimatedIcon(R.drawable.ic_logo)
                .setStaticIcon(R.drawable.ic_logo)
                .setTouchIntent(pendingIntent)
                .setStatus(ongoingActivityStatus)
                .build()
        ongoingActivity.apply(applicationContext)

        return notificationBuilder.build()
    }

    inner class LocalBinder : Binder() {
        fun getService(): WearCounterpartService = this@WearCounterpartService
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL = "com.garan.counterpart.WearCounterpartService"
        const val NOTIFICATION_TITLE = "Counterpart"
        const val NOTIFICATION_TEXT = "Counterpart"
        const val STATUS_TEMPLATE = "Counterpart"
    }
}