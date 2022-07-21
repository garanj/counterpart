package com.garan.counterpart.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.garan.counterpart.WearAppInstalledStatus
import com.garan.counterpart.ui.components.AppInstalled
import com.garan.counterpart.ui.components.AppNotInstalled
import com.garan.counterpart.ui.components.NoDevice

@Composable
fun MainScreen(
    serviceState: ServiceState,
    onStartRemoteAppClick: () -> Unit
) {
    when (serviceState) {
        is ServiceState.Connected -> {
            val heartRateSensorStatus by serviceState.installedStatus.collectAsState()
            val appActiveStatus by serviceState.appActive.collectAsState()
            val hr by serviceState.hr
            ServiceConnectedScreen(
                appActiveStatus = appActiveStatus,
                installedStatus = heartRateSensorStatus,
                hr = hr,
                onStartRemoteAppClick = onStartRemoteAppClick
            )
        }
        else -> LoadingScreen()
    }
}

// Depending on whether the there is (1) A device with the app installed (2) A device or (3) no
// device detected, different Composables are shown.
@Composable
fun ServiceConnectedScreen(
    installedStatus: WearAppInstalledStatus,
    appActiveStatus: Boolean,
    hr: Int,
    onStartRemoteAppClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (installedStatus) {
            WearAppInstalledStatus.APP_INSTALLED -> AppInstalled(
                appActiveStatus = appActiveStatus,
                hr = hr,
                onStartRemoteAppClick = onStartRemoteAppClick
            )
            WearAppInstalledStatus.APP_NOT_INSTALLED -> AppNotInstalled()
            WearAppInstalledStatus.NO_DEVICE_FOUND -> NoDevice()
        }
    }
}
