package com.garan.counterpart.screens

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.garan.counterpart.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

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
            ServiceConnectedScreen(
                hr = hr,
                isHrSensorOn = isHrSensorOn,
                onStartStopClick = onStartStopClick
            )
        }
        else -> LoadingScreen()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ServiceConnectedScreen(
    hr: Int,
    isHrSensorOn: Boolean,
    onStartStopClick: () -> Unit
) {
    // Permissions are only requested on the first time of being needed, i.e. when the user first
    // clicks the Start button.
    val permissions = rememberPermissionState(
        permission = Manifest.permission.BODY_SENSORS,
        onPermissionResult = { result ->
            if (result) onStartStopClick()
        }
    )
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
                    if (permissions.status == PermissionStatus.Granted) {
                        onStartStopClick()
                    } else {
                        permissions.launchPermissionRequest()
                    }
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