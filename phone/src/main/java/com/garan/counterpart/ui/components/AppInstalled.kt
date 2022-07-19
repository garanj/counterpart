package com.garan.counterpart.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.garan.counterpart.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppInstalled(
    appActiveStatus: Boolean,
    hr: Int,
    onStartRemoteAppClick: () -> Unit
) {
    val buttonDebounceMillis = 3000L
    val scope = rememberCoroutineScope()
    var buttonEnabled by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (appActiveStatus && hr > 0) {
                hr.toString()
            } else {
                "--"
            },
            style = MaterialTheme.typography.h1,
            color = if (appActiveStatus) {
                MaterialTheme.colors.primary
            } else {
                MaterialTheme.colors.onBackground
            }
        )
        AppStatusRow(appActiveStatus = appActiveStatus)
        Button(
            onClick = {
                onStartRemoteAppClick()
                // When the button to launch the remote app is clicked, disable temporarily to avoid
                // multiple presses.
                scope.launch {
                    buttonEnabled = false
                    delay(buttonDebounceMillis)
                    buttonEnabled = true
                }
            },
            enabled = buttonEnabled && !appActiveStatus
        ) {
            Text(stringResource(id = R.string.start_app))
        }
    }
}
