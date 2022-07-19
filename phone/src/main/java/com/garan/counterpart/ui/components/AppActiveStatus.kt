package com.garan.counterpart.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.garan.counterpart.R

/**
 * Shows whether the app is running on the remote device or not.
 */
@Composable
fun AppStatusRow(appActiveStatus: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val labelId = if (appActiveStatus) {
            R.string.running
        } else {
            R.string.not_running
        }
        Text(
            text = stringResource(id = labelId),
            style = MaterialTheme.typography.h4
        )
    }
}

@Preview
@Composable
fun AppStatusRowPreview() {
    AppStatusRow(appActiveStatus = true)
}
