package com.garan.counterpart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.garan.counterpart.common.Capabilities.wearAppRunning
import com.garan.counterpart.hrm.HeartRateSensor
import com.garan.counterpart.network.HrSenderClient
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * This service forms the guts of the mechanism for:
 *
 *     - Identifying if there is a connected phone, using [CapabilityClient]
 *     - Measuring heart rate, using the in-built sensor
 *     - Transmitting heart rate to the phone, using [ChannelClient]
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

    @Inject
    lateinit var hrSenderClient: HrSenderClient

    private val binder = LocalBinder()

    private var started = false
    private var foreground = false

    val networkState = mutableStateOf(CurrentNetworkState.UNKNOWN)

    val hr: MutableState<Int> = mutableStateOf(0)
    val isHrSensorOn: MutableState<Boolean> = mutableStateOf(false)

    private val connectivityManager by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }

    private val onSendBlock = { latestValue: Int ->
        Log.i(TAG, "Sending: $latestValue")
        // Write the value to the [Channel] for transmission to the phone. In this
        // basic example, HR is written to the [Channel] as a simple [Int] value which
        // is read on the other side. It might be better to send some kind of structure
        // like using protobuf.
        hrSenderClient.sendValue(latestValue)
        // Update the state value which is used locally on the watch in the UI.
        hr.value = latestValue
    }


    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.let {
                updateNetworkState(network, it)
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            updateNetworkState(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            updateNetworkState(network, isActive = false)
        }

        override fun onUnavailable() {
            super.onUnavailable()
            updateNetworkState(isActive = false)
        }
    }

    fun updateNetworkState(
        network: Network? = null,
        capabilities: NetworkCapabilities? = null,
        isActive: Boolean = true
    ) {
        val isBluetooth =
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ?: false
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        val hasInternet =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false

        networkState.value = if (network != null) {
            CurrentNetworkState(
                updateTime = ZonedDateTime.now(),
                network = network.networkHandle,
                isActive = isActive,
                isBluetooth = isBluetooth,
                isWifi = isWifi,
                hasInternet = hasInternet
            )
        } else CurrentNetworkState.UNKNOWN
    }

    data class CurrentNetworkState(
        val network: Long,
        val updateTime: ZonedDateTime,
        val hasInternet: Boolean,
        val isWifi: Boolean,
        val isBluetooth: Boolean,
        val isActive: Boolean
    ) {
        companion object {
            val UNKNOWN = CurrentNetworkState(
                network = 0,
                updateTime = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
                hasInternet = false,
                isWifi = false,
                isActive = false,
                isBluetooth = false
            )
        }
    }


    override fun onCreate() {
        super.onCreate()
        capabilityClient.addLocalCapability(wearAppRunning)

        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        Log.i(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!started) {
            started = true
            enableForegroundService()
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
        } else {
            enableForegroundService()
            initializeHeartRateSensor()
        }
    }

    private fun initializeHeartRateSensor() {
        hrSenderClient.connect()
        heartRateSensor.start(onSendBlock)

        // Once collection is enabled, the service is put into Foreground mode.
        isHrSensorOn.value = true
    }


    private fun tearDownHrChannel() = hrSenderClient.disconnect()

    private fun teardownHeartRateSensor() {
        hrSenderClient.sendValue(0)
        heartRateSensor.stop()
        isHrSensorOn.value = false
        hr.value = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        capabilityClient.removeLocalCapability(wearAppRunning)
        connectivityManager.unregisterNetworkCallback(networkCallback)
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
            Log.i(TAG, "Stopping everything")
            tearDownHrChannel()
            disableForeground()
            stopSelf()
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