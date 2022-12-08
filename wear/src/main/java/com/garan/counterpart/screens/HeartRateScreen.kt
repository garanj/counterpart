package com.garan.counterpart.screens

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.garan.counterpart.R
import com.garan.counterpart.WearCounterpartService
import com.garan.counterpart.ui.theme.WearCounterpartTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.time.ZonedDateTime

/**
 * Composable that either shows a loading screen, if the service is not bound yet, or shows the
 * main sensor screen.
 */
@Composable
fun HeartRateScreen(
    onStartStopClick: () -> Unit,
    serviceState: ServiceState
) {
    when (serviceState) {
        is ServiceState.Connected -> {
            val hr by serviceState.hr
            val isHrSensorOn by serviceState.isHrSensorOn
            val networkState by serviceState.networkState

            PermissionsCheckScreen(
                hr = hr,
                isHrSensorOn = isHrSensorOn,
                onStartStopClick = onStartStopClick,
                networkState = networkState
            )
        }

        else -> LoadingScreen()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsCheckScreen(
    hr: Int,
    isHrSensorOn: Boolean,
    onStartStopClick: () -> Unit,
    networkState: WearCounterpartService.CurrentNetworkState
) {
    val permissions = rememberPermissionState(
        permission = Manifest.permission.BODY_SENSORS,
        onPermissionResult = { }
    )

    LaunchedEffect(permissions) {
        if (!permissions.status.isGranted) {
            permissions.launchPermissionRequest()
        }
    }

    if (permissions.status.isGranted) {
        ServiceConnectedScreen(
            hr = hr,
            isHrSensorOn = isHrSensorOn,
            onStartStopClick = onStartStopClick,
            networkState = networkState
        )
    }
}

@Composable
fun ServiceConnectedScreen(
    hr: Int,
    isHrSensorOn: Boolean,
    onStartStopClick: () -> Unit,
    networkState: WearCounterpartService.CurrentNetworkState
) {
    // Permissions are only requested on the first time of being needed, i.e. when the user first
    // clicks the Start button.
    NetworkStateProgressIndicator(networkState)
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hr > 0 && isHrSensorOn) {
                    hr.toString()
                } else {
                    "--"
                },
                style = MaterialTheme.typography.display1
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    onStartStopClick()
                }
            ) {
                val buttonTextId = if (isHrSensorOn) {
                    R.string.stop
                } else {
                    R.string.start
                }
                Text(stringResource(id = buttonTextId))
            }
        }
    }
}


@Composable
fun NetworkStateProgressIndicator(networkState: WearCounterpartService.CurrentNetworkState) {
    if (networkState != WearCounterpartService.CurrentNetworkState.UNKNOWN) {
        val networkTypeColor = when {
            networkState.isWifi -> Color.Magenta
            networkState.isBluetooth -> Color.Blue
            else -> Color.Gray
        }
        val hasInternetColor = if (networkState.hasInternet) {
            Color.Green
        } else {
            Color.Black
        }
        val isActiveColor = if (networkState.isActive) {
            Color.Yellow

        } else {
            Color.Black
        }
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            indicatorColor = isActiveColor,
            strokeWidth = 10.dp,
            startAngle = 0f,
            progress = 0.333f
        )
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            indicatorColor = hasInternetColor,
            strokeWidth = 10.dp,
            startAngle = 120f,
            progress = 0.333f
        )
        CircularProgressIndicator(
            modifier = Modifier.fillMaxSize(),
            indicatorColor = networkTypeColor,
            strokeWidth = 10.dp,
            startAngle = 240f,
            progress = 0.333f
        )
    }
}

@Preview(
    device = Devices.WEAR_OS_LARGE_ROUND,
    showSystemUi = true,
    backgroundColor = 0xff000000,
    showBackground = true
)
@Composable
fun ServiceConnectedScreenPreview() {
    WearCounterpartTheme {
        ServiceConnectedScreen(
            hr = 65,
            isHrSensorOn = true,
            onStartStopClick = { },
            networkState =
            WearCounterpartService.CurrentNetworkState(
                network = 10,
                updateTime = ZonedDateTime.now(),
                hasInternet = true,
                isWifi = false,
                isBluetooth = true,
                isActive = true
            )
        )
    }
}