package com.garan.counterpart.ui.screens

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
import com.garan.counterpart.WearAppInstalledStatus
import com.garan.counterpart.PhoneCounterpartService
import com.garan.counterpart.TAG
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) : ViewModel() {
    private var counterpartService: PhoneCounterpartService? = null

    val serviceState: MutableState<ServiceState> = mutableStateOf(ServiceState.Disconnected)
    var bound = mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as PhoneCounterpartService.LocalBinder
            binder.getService().let {
                counterpartService = it
                serviceState.value = ServiceState.Connected(
                    installedStatus = it.installedStatus,
                    appActive = it.appActiveStatus,
                    hr = it.hr
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

    fun startRemoteApp() = counterpartService?.startRemoteApp()

    private fun createService() {
        Intent(applicationContext, PhoneCounterpartService::class.java).also { intent ->
            applicationContext.startService(intent)
            applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

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
    // used in compose to react to change, e.g. change in HR etc, change in Wear app being active
    // or inactive etc.
    data class Connected(
        val installedStatus: StateFlow<WearAppInstalledStatus>,
        val appActive: StateFlow<Boolean>,
        val hr: State<Int>
    ) : ServiceState()
}