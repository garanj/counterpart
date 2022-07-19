package com.garan.counterpart

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.garan.counterpart.screens.HeartRateScreen
import com.garan.counterpart.screens.HeartRateViewModel
import com.garan.counterpart.screens.Screen
import com.garan.counterpart.ui.theme.WearCounterpartTheme
import dagger.hilt.android.AndroidEntryPoint

const val TAG = "Counterpart"

@AndroidEntryPoint
class WearCounterpartActivity : FragmentActivity(), AmbientModeSupport.AmbientCallbackProvider {
    @Composable
    fun WearCounterpartScreen() {
        WearCounterpartTheme {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                timeText = { TimeText() },
            ) {
                WearCounterpartNavigation()
            }
        }
    }

    @Composable
    fun WearCounterpartNavigation() {
        val navHostController = rememberSwipeDismissableNavController()
        SwipeDismissableNavHost(
            navController = navHostController,
            startDestination = Screen.START.route
        ) {
            // Very simple nav graph - only one screen specified.
            composable(Screen.START.route) {
                val viewModel = hiltViewModel<HeartRateViewModel>()
                val serviceState by viewModel.serviceState
                HeartRateScreen(
                    onStartStopClick = { viewModel.startStopHr() },
                    serviceState = serviceState
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Essential to attach this activity, to ensure that always-on/ambient works as expected.
        // Without this, then when the screen goes off, after a while the activity could be killed
        // and would not be recreated when the screen comes back on again, which does not work well
        // for this HR use-case.
        AmbientModeSupport.attach(this)

        setContent {
            WearCounterpartScreen()
        }
    }

    override fun getAmbientCallback() = object : AmbientModeSupport.AmbientCallback() {}
}
