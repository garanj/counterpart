package com.garan.counterpart.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

@Composable
fun WearCounterpartTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors,
        content = content
    )
}
