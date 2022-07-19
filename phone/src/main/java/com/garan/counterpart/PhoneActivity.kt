package com.garan.counterpart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.garan.counterpart.ui.screens.MainScreen
import com.garan.counterpart.ui.screens.MainViewModel
import com.garan.counterpart.ui.theme.CounterpartTheme
import dagger.hilt.android.AndroidEntryPoint

const val TAG = "Counterpart"

@AndroidEntryPoint
class PhoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CounterpartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    val viewModel by viewModels<MainViewModel>()
                    val serviceState by viewModel.serviceState
                    MainScreen(
                        serviceState = serviceState,
                        onStartRemoteAppClick = {
                            viewModel.startRemoteApp()
                        }
                    )
                }
            }
        }
    }
}