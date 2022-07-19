package com.garan.counterpart.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.garan.counterpart.R

@Composable
fun AppNotInstalled() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // In this scenario, where a connected Wear device is found, but without the required
        // Capability (therefore indicating the app isn't installed), the app could offer to launch
        // a remote intent to deep link into the Play Store on the Wear device and install the app.
        // Example of this can be seen in the GitHub Wear OS Samples.
        Text(stringResource(id = R.string.wear_app_not_installed))
    }
}
