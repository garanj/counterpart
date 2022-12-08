package com.garan.counterpart.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.garan.counterpart.TAG
import com.garan.counterpart.WearCounterpartService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject


@HiltViewModel
class HeartRateViewModel @Inject constructor(
    // I don't think this really does leak a context object as the supplied context is that for the
    // application overall.
    @ApplicationContext private val applicationContext: Context
) : ViewModel() {
    private var counterpartService: WearCounterpartService? = null

    // Represents whether there is a connection to the [WearCounterpartService] or not.
    val serviceState: MutableState<ServiceState> = mutableStateOf(ServiceState.Disconnected)
    var bound = mutableStateOf(false)

    fun startStopHr() {
        counterpartService?.startStopHr()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as WearCounterpartService.LocalBinder
            binder.getService().let {
                counterpartService = it
                serviceState.value = ServiceState.Connected(
                    hr = it.hr,
                    isHrSensorOn = it.isHrSensorOn,
                    networkState = it.networkState
                )
            }
            Log.i(TAG, "onServiceConnected")
            bound.value = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            bound.value = false
            counterpartService = null
            serviceState.value = ServiceState.Disconnected
            Log.i(TAG, "onServiceDisconnected")
        }
    }

    init {
        if (!bound.value) {
            createService()
        }
    }

    private fun createService() {
        Intent(applicationContext, WearCounterpartService::class.java).also { intent ->
            applicationContext.startForegroundService(intent)
            applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    // When the viewmodel is being disposed of, ensure that the service is unbound. This may result
    // in the service shutting itself down, if the sensor is not actively collecting HR, or it will
    // continue as a Foreground service, independently of the UI, if it is actively collecting HR.
    override fun onCleared() {
        super.onCleared()
        if (bound.value) {
            applicationContext.unbindService(connection)
        }
    }
}

sealed class ServiceState {
    object Disconnected : ServiceState()

    // When the service is connected, the [Connected] subclass provides state holders that can be
    // used in compose to react to change, e.g. change in connected device, change in HR etc.
    data class Connected(
        val hr: State<Int>,
        val isHrSensorOn: State<Boolean>,
        val networkState: State<WearCounterpartService.CurrentNetworkState>
    ) : ServiceState()
}