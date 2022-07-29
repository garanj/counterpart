package com.garan.counterpart

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.garan.counterpart.common.Capabilities
import com.garan.counterpart.common.Channels
import com.garan.counterpart.hrm.HeartRateSensor
import com.google.android.gms.wearable.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.OutputStream
import javax.inject.Inject

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
    @Inject
    lateinit var heartRateSensor: HeartRateSensor

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

    private var hrChannel: ChannelClient.Channel? = null
    private var hrOutputStream: OutputStream? = null

    val capablePhoneNodeId: MutableStateFlow<String?> = MutableStateFlow(null)

    val hr: MutableState<Int> = mutableStateOf(0)
    val isHrSensorOn: MutableState<Boolean> = mutableStateOf(false)

    // Use an alarm to wake up the app and flush the sensor, typically at the 5s interval
    private val ALARM_INTERVAL_MS = 5000L
    private val alarmManager by lazy { getSystemService(ALARM_SERVICE) as AlarmManager }
    private val alarmListener = object: AlarmManager.OnAlarmListener {
        override fun onAlarm() {
            Log.i(TAG, "RTC wakeup alarm")
            heartRateSensor.flush()
            Log.i(TAG, "Sending: ${heartRateSensor.latestValue}")
            // Write the value to the [Channel] for transmission to the phone. In this
            // basic example, HR is written to the [Channel] as a simple [Int] value which
            // is read on the other side. It might be better to send some kind of structure
            // like using protobuf.
            hrOutputStream?.write(heartRateSensor.latestValue)
            // Update the state value which is used locally on the watch in the UI.
            hr.value = heartRateSensor.latestValue
            setNextAlarm()
        }
    }
    private val handler = Handler(Looper.getMainLooper())

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

        lifecycleScope.launch {
            // If there is a phone on the node network with the app installed, start the [Channel]
            // which will be used for sending the HR values when sensor is on, but also allows the
            // phone to know when the app is running on the Wear app.
            capablePhoneNodeId.collect { nodeId ->
                if (nodeId != null) {
                    initializeHrChannel()
                } else {
                    tearDownHrChannel()
                }
            }
        }

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
        return START_STICKY
    }

    /**
     * Starts or stops HR collection.
     */
    fun startStopHr() {
        if (isHrSensorOn.value) {
            disableForeground()
            teardownHeartRateSensor()
            cancelAlarm()
        } else {
            enableForegroundService()
            initializeHeartRateSensor()
            setNextAlarm()
        }
    }

    private fun setNextAlarm() {
        val now = System.currentTimeMillis()
        alarmManager.setExact(
            AlarmManager.RTC_WAKEUP,
            now + ALARM_INTERVAL_MS,
            "counterpart_alarm",
            alarmListener,
            handler
        )
    }

    private fun cancelAlarm() {
        alarmManager.cancel(alarmListener)
    }

    private fun initializeHeartRateSensor() {
        heartRateSensor.start()

        // Once collection is enabled, the service is put into Foreground mode.
        isHrSensorOn.value = true
    }

    /**
     * Creates a [Channel] in [OutputStream] mode for transmission of HR data to the phone.
     */
    private fun initializeHrChannel() {
        capablePhoneNodeId.value?.let { nodeId ->
            lifecycleScope.launch(Dispatchers.IO) {
                hrChannel = channelClient.openChannel(nodeId, Channels.hrChannel).await()
                hrChannel?.let { channel ->
                    hrOutputStream = channelClient.getOutputStream(channel).await()
                    Log.i(TAG, "Set up channel to node: $nodeId")
                }
            }
        }
    }

    private fun tearDownHrChannel() {
        hrChannel?.let { channel ->
            lifecycleScope.launch {
                channelClient.close(channel).await()
                hrChannel = null
                hrOutputStream = null
            }
        }
    }

    private fun teardownHeartRateSensor() {
        hrOutputStream?.write(0)
        heartRateSensor.stop()
        isHrSensorOn.value = false
        hr.value = 0
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
        val capabilityInfo = Wearable.getCapabilityClient(this)
            .getCapability(Capabilities.phone, CapabilityClient.FILTER_REACHABLE)
            .await()
        return capabilityInfo.nodes.firstOrNull()?.id
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
            tearDownHrChannel()
            disableForeground()
            lifecycleScope.launch(Dispatchers.IO) {
                channelClient.unregisterChannelCallback(channelCallback)
                capabilityClient.removeListener(capabilityChangedListener)
                stopSelf()
            }
        }
    }

    private fun enableForegroundService() {
        if (!foreground) {
            createNotificationChannel()
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
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